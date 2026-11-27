//! The state a client draws from: cores, their open orders, recent detects and strategies,
//! projected out of the session into plain rows.
//!
//! Built on the engine's owner thread and published as one JSON string, so a client never
//! reads the session itself and never waits on it.

use moon_core::config::AppConfig;
use moon_core::feed::{ConnStatus, DetectRow, OrderRow, StrategyRow};
use moon_core::session::balances::{aggregate_account_figures, BalanceFigures};
use moon_core::session::{CoreId, SessionManager};
use serde::Serialize;
use std::collections::HashMap;

use crate::api::Uid;
use crate::cards::CardDto;

/// Newest detects kept per snapshot, across all cores.
const MAX_DETECTS: usize = 200;

#[derive(Serialize)]
pub struct Snapshot {
    /// `none`, `scoped` or `full`, as the engine was opened.
    pub market_data: &'static str,
    pub total: TotalDto,
    pub cores: Vec<CoreDto>,
    pub orders: Vec<OrderDto>,
    pub detects: Vec<DetectDto>,
    /// The detects that are still a card in the detect feed, newest first.
    pub cards: Vec<CardDto>,
    pub strategies: Vec<StrategyDto>,
    /// What the cores reported lately that the app may notify about, oldest first.
    pub events: Vec<EventDto>,
    /// The core being added by its key, while it is checked and after.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub adding: Option<AddingDto>,
    /// The newest closed trade of the cores, from the report.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_deal: Option<crate::reports::LastDeal>,
}

/// Adding a core by its key: `checking`, `saved`, `not_saved` (connected, the file not written)
/// or `failed` (did not answer; taken out again).
#[derive(Serialize, Clone)]
pub struct AddingDto {
    pub core: CoreId,
    pub name: String,
    pub state: &'static str,
    pub text: String,
}

/// One reported happening: today only the exchange refusing requests over its limits.
#[derive(Serialize, Clone)]
pub struct EventDto {
    /// Grows by one per event for the engine's whole life.
    pub seq: u64,
    pub core: CoreId,
    /// `api_limit`.
    pub kind: &'static str,
    pub text: String,
    pub time_ms: i64,
}

/// How a core's machine and its connections are doing, as the core reports it.
#[derive(Serialize, Default)]
pub struct HealthDto {
    /// Ping between this phone and the core, one way, as Moonbot prints it.
    pub ping_ms: Option<u32>,
    /// How long the core's order requests take to the exchange.
    pub exchange_ms: Option<u32>,
    /// Load of the core's whole machine, and of the Moonbot process, percent.
    pub cpu_pct: Option<u8>,
    pub cpu_process_pct: Option<u8>,
    /// Memory of the Moonbot process and free on its machine, MB.
    pub mem_mb: Option<u32>,
    pub free_mem_mb: Option<u32>,
    /// Requests the exchange still allows (HyperLiquid only).
    pub api_quota: Option<u64>,
}

/// Every connected account in USDT. The total is what the balances window shows — the exchange's
/// wallets, each once ([`crate::accounts::total`]); the free amount and the counts below are the
/// cores' own figures, summed the way the terminal's Assets footer sums them: cores that report
/// one exchange account count once.
#[derive(Serialize)]
pub struct TotalDto {
    /// `null` until at least one wallet or core has a balance that can be priced.
    pub total: Option<f64>,
    pub free: Option<f64>,
    /// Cores whose figures are in the sum.
    pub counted: u32,
    /// Of those, cores whose figure is from before a lost connection.
    pub stale: u32,
    /// Cores left out: no report yet, or no USDT rate for their currency.
    pub excluded: u32,
}

#[derive(Serialize)]
pub struct CoreDto {
    pub id: CoreId,
    pub name: String,
    pub group: String,
    /// The core's colour, `#rrggbb`.
    pub color: String,
    /// The core's own switch, apart from its group's.
    pub enabled: bool,
    /// The address of the core's machine, without the port: cores sharing it share a server.
    /// Empty for a core without one (the demo).
    pub host: String,
    /// The server and its group are enabled, so the engine connects to it.
    pub active: bool,
    /// `connecting`, `stage`, `ready`, `failed`, `disconnected` or `inactive`.
    pub status: &'static str,
    /// Stage caption or failure text, empty otherwise.
    pub status_text: String,
    /// Exchange caption once the core named it.
    pub exchange: String,
    /// The exchange as one lowercase word (`binance`, `bybit`), for the app's badge.
    pub brand: String,
    /// What the core trades: `spot`, `futures` or `quarterly`; empty while unknown.
    pub kind: &'static str,
    /// Account currency once known (`USDT`, `USDC`, ...).
    pub base: String,
    pub run: RunDto,
    pub orders: usize,
    pub positions: usize,
    pub strategies_total: usize,
    pub strategies_checked: usize,
    pub balance: Option<BalanceDto>,
    pub server_version: Option<u32>,
    pub health: HealthDto,
    /// The watcher ("Следить за ордерами этого бота"): `on` or `off` when a watcher address is
    /// set, `none` when it is not, empty while the core has not told.
    pub control: &'static str,
    /// The core's trade history still coming into the phone's report: while a catch-up runs,
    /// how many trades arrived and how far along it is.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub report_sync: Option<ReportSyncDto>,
}

/// A running catch-up of a core's report.
#[derive(Serialize)]
pub struct ReportSyncDto {
    pub rows: u64,
    pub percent: Option<u8>,
}

/// A core's run switches; every half is `null` while unknown or offline.
#[derive(Serialize)]
pub struct RunDto {
    pub online: bool,
    /// Market runtime started.
    pub started: Option<bool>,
    /// The strategy engine runs (Moonbot's Start/Stop).
    pub trading: Option<bool>,
    /// `trading` was reported on the CURRENT connection, not remembered from an earlier one.
    pub trading_confirmed: bool,
    pub auto_detect: Option<bool>,
}

#[derive(Serialize)]
pub struct BalanceDto {
    pub currency: String,
    pub total: f64,
    pub free: f64,
    pub pnl: f64,
    /// What is really free, in the core's own currency (`currency`): not held by orders or
    /// positions.
    pub available: f64,
    /// Each coin's own free balance, on a coin-margined (quarterly) account only.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub coins: Vec<CoinBalance>,
}

#[derive(Serialize)]
pub struct CoinBalance {
    pub coin: String,
    pub free: f64,
}

#[derive(Serialize)]
pub struct OrderDto {
    pub core: CoreId,
    pub uid: Uid,
    pub market: String,
    pub coin: String,
    pub quote: String,
    pub short: bool,
    pub size: f64,
    pub remaining_size: f64,
    pub buy_price: f64,
    pub sell_price: f64,
    /// The market's current price as the core sees it; 0 when unknown.
    pub price: f32,
    /// Unrealised result of a filled position in percent of its entry; `null` otherwise.
    pub pnl_pct: Option<f64>,
    pub fill_pct: f32,
    /// The core's own status word (`None`, `BuySet`, `SellSet`, ...).
    pub status: String,
    /// Waiting on its entry condition, not on the exchange yet.
    pub pending: bool,
    /// The entry filled: this is a position.
    pub filled: bool,
    pub panic_sell: bool,
    pub emulator: bool,
    pub strategy: String,
    pub strategy_id: Uid,
    pub stop_loss: Option<f64>,
    pub take_profit: Option<f64>,
    pub trailing: Option<f64>,
    pub created_ms: f64,
    /// Placed from this phone.
    pub mine: bool,
    /// Placed by hand from a terminal (this app, MoonTerminal): no strategy of its own, or the
    /// core's Manual strategy that governs hand-placed orders.
    pub manual: bool,
    /// A futures position's liquidation price and the market's mark price; `null` on spot,
    /// while flat, or before the core reports them.
    pub liq_price: Option<f64>,
    pub mark_price: Option<f64>,
}

#[derive(Serialize)]
pub struct DetectDto {
    pub core: CoreId,
    pub seq: u64,
    pub market: String,
    pub time_ms: f64,
    pub short: bool,
    pub alert: bool,
    pub kind: u8,
    pub msg: String,
    pub strategy: String,
    /// The sound the detect asks for, as the terminal plays it: the strategy's sound when its
    /// "sound alert" is on, `default` for an alert without one; absent when it asks for none.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sound: Option<String>,
}

#[derive(Serialize)]
pub struct StrategyDto {
    pub core: CoreId,
    pub id: Uid,
    pub name: String,
    pub kind: String,
    pub folder: String,
    pub checked: bool,
    pub short: bool,
}

/// Project the session into a snapshot body. The revision and the clock are added by the
/// envelope (`engine::envelope`), so two builds of an unchanged state serialise identically.
///
/// Args:
///     market_data: The market-data mode the engine was opened in, as its wire name.
///     cards: The detect cards alive now, built by the caller, which also knows their thumbnails.
pub fn build(
    session: &SessionManager,
    config: &AppConfig,
    market_data: &'static str,
    cards: Vec<CardDto>,
    events: Vec<EventDto>,
    adding: Option<AddingDto>,
    mine: &std::collections::HashSet<(CoreId, u64)>,
) -> Snapshot {
    let store = session.store();
    let venues = session.core_venues();
    let source = session.market_source();
    let mut cores = Vec::with_capacity(config.servers.len());
    let mut orders = Vec::new();
    let mut detects: Vec<DetectDto> = Vec::new();
    let mut strategies = Vec::new();
    let mut figures: Vec<(CoreId, String, BalanceFigures)> = Vec::new();

    for server in &config.servers {
        let id = server.id;
        let active = server.active && config.group(&server.group).active;
        let data = store.core(id);
        let (status, status_text) = match (active, data.map(|d| &d.status)) {
            (false, _) => ("inactive", String::new()),
            (true, None) => ("connecting", String::new()),
            (true, Some(ConnStatus::Connecting)) => ("connecting", String::new()),
            (true, Some(ConnStatus::Stage(text))) => ("stage", text.clone()),
            (true, Some(ConnStatus::Ready)) => ("ready", String::new()),
            (true, Some(ConnStatus::Failed(text))) => ("failed", text.clone()),
            (true, Some(ConnStatus::Disconnected)) => ("disconnected", String::new()),
        };
        let run = session.core_run_state(id);
        let exchange = venues
            .get(&id)
            .map(|venue| match venue.resolved() {
                Some(resolved) => resolved.brand.display().to_string(),
                None => venue.reported.clone(),
            })
            .unwrap_or_default();
        let resolved = venues.get(&id).and_then(|venue| venue.resolved());
        let brand = resolved
            .map(|resolved| resolved.brand.slug().to_string())
            .unwrap_or_default();
        let kind = match resolved.map(|resolved| resolved.kind) {
            Some(moon_core::venue::MarketKind::Spot) => "spot",
            Some(moon_core::venue::MarketKind::Futures) => "futures",
            Some(moon_core::venue::MarketKind::Quarterly) => "quarterly",
            None if server.synthetic => "futures",
            None => "",
        };
        let open: Vec<&OrderRow> = data
            .map(|d| d.orders.iter().filter(|o| !o.job_is_done).collect())
            .unwrap_or_default();
        let balance = data.and_then(|d| {
            let global = &d.assets.global;
            // An account that has not reported yet reads all zeros; show nothing rather than 0.
            (global.total_usdt != 0.0 || global.free_usdt != 0.0 || global.btc_total != 0.0).then(|| BalanceDto {
                currency: d.assets.base_currency.clone(),
                total: global.total_usdt,
                free: global.free_usdt,
                pnl: global.pnl_usdt,
                available: global.btc_total,
                coins: if kind == "quarterly" {
                    d.assets
                        .rows
                        .iter()
                        .filter(|row| !row.coin.is_empty())
                        .map(|row| CoinBalance { coin: row.coin.clone(), free: row.qty })
                        .collect()
                } else {
                    Vec::new()
                },
            })
        });
        let control = match data {
            Some(d) if d.watchdog_fresh => match d.watchdog.as_deref() {
                Some(state) if !state.available => "",
                Some(state) => {
                    let ip = state.server_ip.trim();
                    if ip.is_empty() || ip == "0.0.0.0" {
                        "none"
                    } else if state.sign_orders {
                        "on"
                    } else {
                        "off"
                    }
                }
                None => "",
            },
            _ => "",
        };
        cores.push(CoreDto {
            id,
            name: server.name.clone(),
            group: server.group.clone(),
            color: format!("#{:02x}{:02x}{:02x}", server.color[0], server.color[1], server.color[2]),
            enabled: server.active,
            host: host_of(server),
            active,
            status,
            status_text,
            exchange,
            brand,
            kind,
            base: session.core_base(id).unwrap_or_default().to_string(),
            run: RunDto {
                online: run.online,
                started: run.started,
                trading: run.trading,
                trading_confirmed: run.trading_confirmed,
                auto_detect: run.auto_detect,
            },
            orders: open.len(),
            positions: open.iter().filter(|o| o.filled).count(),
            strategies_total: data.map_or(0, |d| d.strategies.len()),
            strategies_checked: data.map_or(0, |d| d.strategies.iter().filter(|s| s.checked).count()),
            balance,
            server_version: data.and_then(|d| d.server_version),
            health: data.map_or_else(HealthDto::default, |d| HealthDto {
                ping_ms: d.sys.round_trip_ms.map(|rtt| rtt.div_ceil(2)),
                exchange_ms: d.sys.order_api_latency_ms.map(u32::from),
                cpu_pct: d.sys.system_cpu_percent,
                cpu_process_pct: d.sys.process_cpu_percent,
                mem_mb: d.sys.used_memory_mb.map(u32::from),
                free_mem_mb: d.sys.free_physical_memory_mb.map(u32::from),
                api_quota: d.api_quota,
            }),
            control,
            report_sync: data
                .filter(|d| d.status == ConnStatus::Ready)
                .and_then(|d| d.report_sync)
                .map(|p| ReportSyncDto { rows: p.rows, percent: p.percent() }),
        });

        // A position's liquidation and mark price, read once per market.
        let mut figures_of: HashMap<String, Option<(Option<f64>, Option<f64>)>> = HashMap::new();
        orders.extend(open.into_iter().map(|order| {
            let mut dto = order_dto(id, order);
            dto.mine = mine.contains(&(id, order.uid));
            if order.filled {
                let read = figures_of.entry(order.market.clone()).or_insert_with(|| {
                    source.market_figures(id, &order.market, false).map(|f| (f.liq_price, f.mark))
                });
                if let Some((liq, mark)) = read {
                    dto.liq_price = *liq;
                    dto.mark_price = *mark;
                }
            }
            dto
        }));
        if let (true, Some(data)) = (active, data) {
            figures.push((
                id,
                server.name.clone(),
                BalanceFigures {
                    state: data.balance_state(),
                    free: data.assets.global.free_usdt,
                    total: data.assets.global.total_usdt,
                },
            ));
        }
        if let Some(data) = data {
            detects.extend(data.detects.iter().map(|row| detect_dto(id, row)));
            strategies.extend(data.strategies.iter().map(|row| strategy_dto(id, row)));
        }
    }

    // Positions first, then waiting orders; newest first inside each.
    orders.sort_by(|a, b| {
        b.filled
            .cmp(&a.filled)
            .then(b.created_ms.total_cmp(&a.created_ms))
    });
    detects.sort_by(|a, b| b.time_ms.total_cmp(&a.time_ms).then(b.seq.cmp(&a.seq)));
    detects.truncate(MAX_DETECTS);

    let sum = aggregate_account_figures(session, &config.servers, &figures).sum;

    Snapshot {
        market_data,
        total: TotalDto {
            total: crate::accounts::total(session, config),
            free: sum.free,
            counted: sum.counted,
            stale: sum.stale,
            excluded: sum.excluded,
        },
        cores,
        orders,
        detects,
        cards,
        strategies,
        events,
        adding,
        last_deal: None,
    }
}

fn order_dto(core: CoreId, order: &OrderRow) -> OrderDto {
    let pnl_pct = (order.filled && order.buy_price > 0.0 && order.price > 0.0).then(|| {
        let change = (f64::from(order.price) - order.buy_price) / order.buy_price * 100.0;
        if order.is_short {
            -change
        } else {
            change
        }
    });
    OrderDto {
        core,
        uid: Uid(order.uid),
        market: order.market.clone(),
        coin: order.coin.clone(),
        quote: order.quote.clone(),
        short: order.is_short,
        size: order.size,
        remaining_size: order.remaining_size,
        buy_price: order.buy_price,
        sell_price: order.sell_price,
        price: order.price,
        pnl_pct,
        fill_pct: order.fill_pct,
        status: order.status.clone(),
        pending: order.pending,
        filled: order.filled,
        panic_sell: order.panic_sell,
        emulator: order.emulator,
        strategy: order.strat_name.clone(),
        strategy_id: Uid(order.strat_id),
        stop_loss: order.stop_loss,
        take_profit: order.take_profit,
        trailing: order.trailing,
        created_ms: order.create_time_ms,
        mine: false,
        manual: order.strat_id == 0 || order.strat == "Manual",
        liq_price: None,
        mark_price: None,
    }
}

fn detect_dto(core: CoreId, row: &DetectRow) -> DetectDto {
    DetectDto {
        core,
        seq: row.seq,
        market: row.market.clone(),
        time_ms: row.time_ms,
        short: row.is_short,
        alert: row.is_alert,
        kind: row.kind,
        msg: row.msg.clone(),
        strategy: row.strat_name.clone(),
        sound: if !row.is_alert && !row.sound_alert {
            None
        } else {
            match &row.sound_name {
                Some(name) if !name.trim().is_empty() => Some(name.trim().to_string()),
                _ if row.is_alert => Some("default".to_string()),
                _ => None,
            }
        },
    }
}

fn strategy_dto(core: CoreId, row: &StrategyRow) -> StrategyDto {
    StrategyDto {
        core,
        id: Uid(row.id),
        name: row.name.clone(),
        kind: row.kind.clone(),
        folder: row.folder_path.clone(),
        checked: row.checked,
        short: row.is_short,
    }
}

/// The machine a core runs on: its address as the feed dials it, without the port.
fn host_of(server: &moon_core::config::ServerConfig) -> String {
    if server.synthetic {
        return String::new();
    }
    let Some(address) = moon_core::station_api::core_address(server.key.expose(), &server.endpoint_override)
    else {
        return String::new();
    };
    if let Some(rest) = address.strip_prefix('[') {
        // [v6]:port
        return rest.split(']').next().unwrap_or_default().to_string();
    }
    match address.rsplit_once(':') {
        Some((host, port)) if port.chars().all(|c| c.is_ascii_digit()) => host.to_string(),
        _ => address,
    }
}
