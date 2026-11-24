//! The cores' trade report on the phone: the same replica MoonTerminal keeps (`reports.sqlite`
//! in the data root, every core's whole history), read the way the terminal's own automation
//! tools read it, with times on true UTC and profits in USDT.

use std::collections::HashMap;
use std::sync::OnceLock;
use std::sync::atomic::Ordering;

use moon_core::automation::reports::{self as auto, Scope};
use moon_core::db;
use serde::Serialize;
use serde_json::{Value, json};

/// The writer of the replica and the worker that values non-USDT trades in USDT. Started once
/// per process: the replica's lease is the process's own, and a second start is refused.
struct Replica {
    handle: db::ReportsHandle,
    _valuation: Option<db::valuation::ValuationHandle>,
}

static REPLICA: OnceLock<Option<Replica>> = OnceLock::new();

/// The sink the core feeds write report rows into, starting the writer on first use. `None`
/// when the replica could not be opened (the reason is in the log); the app then runs without
/// a report rather than without its cores.
pub(crate) fn sink() -> Option<&'static db::ReportTx> {
    REPLICA
        .get_or_init(|| {
            let permit = db::report_recovery::prepare()?;
            let handle = db::spawn_writer(permit)?;
            let valuation = db::valuation::spawn_worker(handle.tx.clone());
            log::info!("отчёты: реплика открыта на телефоне");
            Some(Replica { handle, _valuation: valuation })
        })
        .as_ref()
        .map(|replica| &replica.handle.tx)
}

/// Bumped with every batch the writer commits: what a cached read compares against.
pub(crate) fn generation() -> u64 {
    REPLICA
        .get()
        .and_then(Option::as_ref)
        .map_or(0, |replica| replica.handle.generation.load(Ordering::Acquire))
}

/// The longest period one read covers, as the automation tools allow.
const MAX_WINDOW_MS: i64 = 366 * 86_400_000;

/// Columns of a report row, in the order the reply lists them.
const ROW_FIELDS: &[&str] = &[
    "core",
    "coin",
    "strategy_name",
    "side",
    "lev",
    "open_utc_ms",
    "close_utc_ms",
    "buyprice",
    "sellprice",
    "profit_pct",
    "profit_usdt",
    "profit_native",
    "currency",
    "volume_usdt",
    "reason",
    "sellreason",
];

/// Figures over the whole period, however many rows are listed.
const TOTAL_METRICS: &[&str] = &[
    "trades",
    "wins",
    "losses",
    "winrate_pct",
    "profit_usdt_sum",
    "profit_pct_avg",
    "gross_win_usdt",
    "gross_loss_usdt",
    "volume_usdt",
    "max_win_usdt",
    "max_loss_usdt",
    "funding_usdt",
    "unvalued",
];

fn scope(cores: &[u64], names: &HashMap<(u64, u64), String>) -> Scope {
    Scope {
        cores: cores.to_vec(),
        live_names: names.clone(),
    }
}

fn window(from_ms: i64, to_ms: i64) -> (i64, i64) {
    let to = to_ms.max(1);
    let from = from_ms.clamp(to - MAX_WINDOW_MS, to - 1).max(1);
    (from, to)
}

/// The trades closed in [`from_ms`, `to_ms`] for the home-screen widget: how many, how many in
/// profit and at a loss, and the same for each kind of strategy (Drops, Spread, MoonShot…) by
/// `kinds` (core, strategy id → kind); a hand-placed trade (strategy 0) counts as Manual.
pub(crate) fn day_stats(
    cores: &[u64],
    names: &HashMap<(u64, u64), String>,
    kinds: &HashMap<(u64, u64), String>,
    from_ms: i64,
    to_ms: i64,
) -> Result<Value, String> {
    let empty = json!({ "trades": 0, "wins": 0, "losses": 0, "kinds": [] });
    if sink().is_none() || cores.is_empty() {
        return Ok(empty);
    }
    let (from, to) = window(from_ms, to_ms);
    let answer = auto::run(
        "reports_aggregate",
        &scope(cores, names),
        json!({
            "filter": {
                "cores": cores.iter().map(u64::to_string).collect::<Vec<_>>(),
                "from_ms": from,
                "to_ms": to,
            },
            "group_by": ["strategy"],
            "metrics": ["trades", "wins", "losses"],
            "limit": 1000,
        }),
    )
    .map_err(|e| e.to_string())?;
    let count = |v: Option<&Value>| v.and_then(Value::as_f64).unwrap_or(0.0).max(0.0) as u64;
    // kind → (trades, wins, losses), in the order first met.
    let mut by_kind: Vec<(String, [u64; 3])> = Vec::new();
    for row in answer.get("rows").and_then(Value::as_array).into_iter().flatten() {
        let Some(cells) = row.as_array() else { continue };
        let key = cells.first().and_then(Value::as_str).unwrap_or_default();
        let (core, sid) = key.split_once(':').unwrap_or(("", ""));
        let (core, sid) = (core.parse::<u64>().unwrap_or(0), sid.parse::<u64>().unwrap_or(0));
        let kind = match kinds.get(&(core, sid)) {
            Some(kind) => kind.clone(),
            None if sid == 0 => "Manual".to_owned(),
            None => "?".to_owned(),
        };
        let figures = [count(cells.get(1)), count(cells.get(2)), count(cells.get(3))];
        match by_kind.iter_mut().find(|(k, _)| *k == kind) {
            Some((_, sum)) => (0..3).for_each(|i| sum[i] += figures[i]),
            None => by_kind.push((kind, figures)),
        }
    }
    by_kind.sort_by(|a, b| b.1[0].cmp(&a.1[0]).then_with(|| a.0.cmp(&b.0)));
    let total = by_kind.iter().fold([0u64; 3], |mut t, (_, f)| {
        (0..3).for_each(|i| t[i] += f[i]);
        t
    });
    Ok(json!({
        "trades": total[0],
        "wins": total[1],
        "losses": total[2],
        "kinds": by_kind
            .iter()
            .map(|(kind, f)| json!({ "kind": kind, "trades": f[0], "wins": f[1], "losses": f[2] }))
            .collect::<Vec<_>>(),
    }))
}

/// What one market's trades on one core closed in [`from_ms`, `to_ms`] made, in USDT: a chart's
/// session result. `None` when the report could not be read.
///
/// Args:
///     core: The chart's core.
///     coins: The spellings the report may file the market under: the market and its coin.
pub(crate) fn session_profit(core: u64, coins: &[String], from_ms: i64, to_ms: i64) -> Option<f64> {
    if sink().is_none() || coins.is_empty() {
        return None;
    }
    // The whole of it, however far back: the report keeps every trade.
    let (from, to) = (from_ms.max(1), to_ms.max(2));
    let answer = auto::run(
        "reports_aggregate",
        &scope(&[core], &HashMap::new()),
        json!({
            "filter": {
                "cores": [core.to_string()],
                "from_ms": from,
                "to_ms": to,
                "coins": coins,
            },
            "metrics": ["profit_usdt_sum"],
        }),
    );
    match answer {
        Ok(answer) => Some(answer["totals"]["profit_usdt_sum"].as_f64().unwrap_or(0.0)),
        Err(e) => {
            log::debug!("сессия графика: {e}");
            None
        }
    }
}

/// One page of the report and the totals of its whole period.
///
/// Args:
///     cores: The cores to read; at most 64, as the automation tools take.
///     names: Current strategy names by core and strategy id.
///     from_ms, to_ms: The period on closing time, true UTC.
///     offset, limit: The page.
///
/// Returns:
///     `{columns, rows, total_rows, has_more, next_offset, totals, coverage}`.
/// What the report is narrowed to beyond the period and the cores, as MoonTerminal's report
/// filters it: a coin (part of its name) and a strategy (its name).
#[derive(Debug, Clone, Default, serde::Deserialize)]
pub struct Narrow {
    #[serde(default)]
    pub coin: String,
    #[serde(default)]
    pub strategy: String,
    /// Count the emulator's trades too, which a report leaves out by default.
    #[serde(default)]
    pub emulator: bool,
}

impl Narrow {
    /// Adds the narrowing to an automation filter.
    fn apply(&self, filter: &mut Value) {
        if self.emulator {
            filter["include_emulator"] = json!(true);
        }
        let coin = self.coin.trim().replace(['%', '_'], "");
        if !coin.is_empty() {
            filter["coin_like"] = json!(format!("%{coin}%"));
        }
        let strategy = self.strategy.trim();
        if !strategy.is_empty() {
            filter["strategy_name"] = json!(strategy);
        }
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn report(
    cores: &[u64],
    names: &HashMap<(u64, u64), String>,
    from_ms: i64,
    to_ms: i64,
    offset: usize,
    limit: usize,
    narrow: &Narrow,
) -> Result<Value, String> {
    if sink().is_none() {
        return Err("отчёт недоступен: база отчётов не открылась (подробности в журнале)".into());
    }
    let cores: Vec<u64> = cores.iter().copied().take(64).collect();
    if cores.is_empty() {
        return Ok(json!({ "columns": ROW_FIELDS, "rows": [], "total_rows": 0, "has_more": false }));
    }
    let scope = scope(&cores, names);
    let (from, to) = window(from_ms, to_ms);
    let mut filter = json!({
        "cores": cores.iter().map(u64::to_string).collect::<Vec<_>>(),
        "from_ms": from,
        "to_ms": to,
    });
    // The strategies of the period, for the filter's list: before the narrowing, so the list
    // keeps every choice while one is picked.
    let strategies = if offset > 0 {
        Ok(Value::Null)
    } else {
        auto::run(
        "reports_aggregate",
        &scope,
        json!({
            "filter": filter,
            "group_by": ["strategy_name"],
            "metrics": ["trades", "profit_usdt_sum"],
            "limit": 200,
        }),
    )
    }
    .map(|answer| answer.get("rows").cloned().unwrap_or(Value::Null))
    .unwrap_or(Value::Null);
    narrow.apply(&mut filter);
    let mut page = auto::run(
        "reports_query",
        &scope,
        json!({
            "filter": filter,
            "fields": ROW_FIELDS,
            "limit": limit.clamp(1, 500),
            "offset": offset,
        }),
    )
    .map_err(|e| e.to_string())?;
    let totals = auto::run(
        "reports_aggregate",
        &scope,
        json!({ "filter": filter, "metrics": TOTAL_METRICS }),
    )
    .map_err(|e| e.to_string())?;
    page["totals"] = totals.get("totals").cloned().unwrap_or(Value::Null);
    page["strategies"] = strategies;
    let empty = page.get("total_rows").and_then(Value::as_u64) == Some(0)
        || page.get("rows").and_then(Value::as_array).is_some_and(Vec::is_empty);
    if offset == 0 && empty {
        page["empty_hint"] = empty_hint(&scope, &cores, from, to, narrow);
    }
    if let Some(map) = page.as_object_mut() {
        map.remove("text_is_data");
    }
    Ok(page)
}

/// Why a period came out empty, as far as the replica can tell: the newest trade it holds for
/// these cores (whatever the period), how many it holds over the last 30 days, and how many
/// emulator trades the period has, which a report leaves out unless asked.
fn empty_hint(scope: &Scope, cores: &[u64], from: i64, to: i64, narrow: &Narrow) -> Value {
    let core_ids: Vec<String> = cores.iter().map(u64::to_string).collect();
    let newest = auto::run(
        "reports_query",
        scope,
        json!({
            "filter": {
                "cores": core_ids,
                "from_ms": (to - MAX_WINDOW_MS + 1).max(1),
                "to_ms": to,
                "include_emulator": narrow.emulator,
            },
            "fields": ["core", "coin", "close_utc_ms"],
            "limit": 1,
        }),
    )
    .ok()
    .and_then(|answer| answer.get("rows")?.as_array()?.first()?.as_array().cloned());
    let count = |filter: Value| -> Option<u64> {
        auto::run("reports_aggregate", scope, json!({ "filter": filter, "metrics": ["trades"] }))
            .ok()?
            .get("totals")?
            .get("trades")?
            .as_u64()
    };
    let month = count(json!({
        "cores": core_ids,
        "from_ms": (to - 30 * 86_400_000).max(1),
        "to_ms": to,
        "include_emulator": narrow.emulator,
    }));
    let emulator = if narrow.emulator {
        None
    } else {
        let mut with = json!({ "cores": core_ids, "from_ms": from, "to_ms": to, "include_emulator": true });
        Narrow { emulator: false, ..narrow.clone() }.apply(&mut with);
        count(with).filter(|n| *n > 0)
    };
    json!({
        "newest_core": newest.as_ref().and_then(|row| row.first()?.as_str()?.parse::<u64>().ok()),
        "newest_coin": newest.as_ref().and_then(|row| row.get(1)?.as_str().map(str::to_string)),
        "newest_close_ms": newest.as_ref().and_then(|row| row.get(2)?.as_i64()),
        "month_trades": month,
        "emulator_trades": emulator,
    })
}

/// The newest closed trade of the cores, for the chart's corner.
#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct LastDeal {
    pub core: u64,
    pub coin: String,
    /// The chart market the trade belongs to, when the core's catalog names it.
    pub market: String,
    pub short: bool,
    pub close_ms: i64,
    pub profit_usdt: Option<f64>,
    pub profit_native: Option<f64>,
    pub currency: String,
    pub profit_pct: Option<f64>,
}

/// How far back the newest trade is looked for.
const LAST_DEAL_BACK_MS: i64 = 3 * 86_400_000;

/// The newest closed trade over the last few days, or `None` when there was none.
pub(crate) fn last_deal(
    cores: &[u64],
    names: &HashMap<(u64, u64), String>,
    now_ms: i64,
) -> Option<LastDeal> {
    sink()?;
    let cores: Vec<u64> = cores.iter().copied().take(64).collect();
    if cores.is_empty() {
        return None;
    }
    let fields = [
        "core",
        "coin",
        "side",
        "close_utc_ms",
        "profit_usdt",
        "profit_native",
        "currency",
        "profit_pct",
    ];
    let answer = auto::run(
        "reports_query",
        &scope(&cores, names),
        json!({
            "filter": {
                "cores": cores.iter().map(u64::to_string).collect::<Vec<_>>(),
                "from_ms": now_ms - LAST_DEAL_BACK_MS,
                "to_ms": now_ms + 60_000,
            },
            "fields": fields,
            "limit": 1,
        }),
    )
    .map_err(|e| log::debug!("последняя сделка: {e}"))
    .ok()?;
    let row = answer.get("rows")?.as_array()?.first()?.as_array()?;
    let at = |i: usize| row.get(i).cloned().unwrap_or(Value::Null);
    Some(LastDeal {
        core: at(0).as_str()?.parse().ok()?,
        coin: at(1).as_str().unwrap_or_default().to_string(),
        market: String::new(),
        short: at(2).as_str() == Some("short"),
        close_ms: at(3).as_i64().unwrap_or_default(),
        profit_usdt: at(4).as_f64(),
        profit_native: at(5).as_f64(),
        currency: at(6).as_str().unwrap_or_default().to_string(),
        profit_pct: at(7).as_f64(),
    })
}

/// A trade of the chart's market drawn on the chart: where it entered and, once closed, where
/// it left.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TradeMark {
    pub open_ms: i64,
    pub open_price: f64,
    /// Closing time and price; `None` while the position is open.
    pub close: Option<(i64, f64)>,
    pub short: bool,
    pub profit_pct: Option<f64>,
}

/// How far back a chart looks for its market's closed trades.
pub(crate) const CHART_TRADES_BACK_MS: i64 = 2 * 86_400_000;

/// The closed trades of one market on one core over the last two days, newest first.
///
/// Args:
///     core: The chart's core.
///     coins: The spellings the report may file the market under: the market and its coin.
pub(crate) fn chart_trades(core: u64, coins: &[String], now_ms: i64) -> Vec<TradeMark> {
    if sink().is_none() || coins.is_empty() {
        return Vec::new();
    }
    let fields = ["open_utc_ms", "close_utc_ms", "buyprice", "sellprice", "side", "profit_pct"];
    let answer = auto::run(
        "reports_query",
        &scope(&[core], &HashMap::new()),
        json!({
            "filter": {
                "cores": [core.to_string()],
                "from_ms": now_ms - CHART_TRADES_BACK_MS,
                "to_ms": now_ms + 60_000,
                "coins": coins,
            },
            "fields": fields,
            "limit": 300,
        }),
    );
    let answer = match answer {
        Ok(answer) => answer,
        Err(e) => {
            log::debug!("сделки графика: {e}");
            return Vec::new();
        }
    };
    let Some(rows) = answer.get("rows").and_then(Value::as_array) else {
        return Vec::new();
    };
    rows.iter()
        .filter_map(|row| {
            let row = row.as_array()?;
            let open_ms = row.first()?.as_i64()?;
            let close_ms = row.get(1)?.as_i64()?;
            let buy = row.get(2)?.as_f64()?;
            let sell = row.get(3)?.as_f64()?;
            (buy > 0.0 && sell > 0.0).then_some(TradeMark {
                open_ms,
                open_price: buy,
                close: Some((close_ms, sell)),
                short: row.get(4).and_then(Value::as_str) == Some("short"),
                profit_pct: row.get(5).and_then(Value::as_f64),
            })
        })
        .collect()
}
