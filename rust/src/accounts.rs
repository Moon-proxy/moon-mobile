//! The total balance taken apart: one entry per exchange account, with what it holds.
//!
//! Several cores often trade on one exchange account, and each of them reports the whole
//! account — its balance, its coins and its positions. A plain list per core would show that
//! money once per core. The cores are grouped here the way the total folds them
//! (`moon_core::session::balances`): by the account's identity the exchange gave each core in
//! `AuthCheck`, and the wallet where spot and futures keep apart. Each account is shown once, with
//! the balance of the core the total counts (the largest reading of the group), so the entries
//! add up to the total on the Cores screen. Its coins and positions come from that core first;
//! another live core of the account only adds what the first did not list, so nothing appears
//! twice.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use moon_core::config::{AppConfig, TotalMode};
use moon_core::session::balances::{aggregate_account_figures, core_total_mode, BalanceFigures};
use moon_core::session::{BalanceState, CoreId, SessionManager};
use moon_core::venue::{AccountMergeKey, MarketKind};
use serde::Serialize;

/// A holding worth less than this, in USDT, is counted among the small ones, not listed.
const DUST_USDT: f64 = 1.0;

/// A core's wallet list is asked for again no sooner than this.
const REFRESH_EVERY: Duration = Duration::from_secs(30);

/// When each core's wallet list was last asked for.
static ASKED: Mutex<Option<HashMap<CoreId, Instant>>> = Mutex::new(None);

#[derive(Serialize)]
pub struct AccountsDto {
    /// The same sum as the Cores screen's total.
    pub total: Option<f64>,
    pub free: Option<f64>,
    pub accounts: Vec<AccountDto>,
    /// Cores set to stay out of the total.
    pub excluded: Vec<String>,
}

#[derive(Serialize)]
pub struct AccountDto {
    /// The exchange as people name it, `Binance`.
    pub exchange: String,
    /// The exchange as one lowercase word, for the badge.
    pub brand: String,
    /// `spot`, `futures`, `quarterly`; `unified` where cores of several kinds share the account
    /// (an exchange keeping spot and futures in one wallet); empty while unknown.
    pub kind: &'static str,
    /// The account currency, `USDT`.
    pub currency: String,
    /// Every core trading this account, the counted one first.
    pub cores: Vec<String>,
    /// `live`, `stale` (from before a lost connection), `awaiting` or `unpriced`.
    pub state: &'static str,
    pub total: Option<f64>,
    pub free: Option<f64>,
    /// Unrealized profit, USDT.
    pub pnl: Option<f64>,
    /// Coins and positions, largest first.
    pub assets: Vec<AssetDto>,
    /// Holdings under [`DUST_USDT`] or with no price: how many, and what the priced ones are worth.
    pub small_count: u32,
    pub small_usdt: f64,
}

#[derive(Serialize)]
pub struct AssetDto {
    /// `coin` (a held coin), `cash` (the account's own currency) or `position`.
    pub kind: &'static str,
    pub coin: String,
    /// The market, for a position.
    #[serde(skip_serializing_if = "String::is_empty")]
    pub market: String,
    /// Coins held, or the position's size in coins.
    pub amount: f64,
    /// Worth in USDT: the holding, or the position's notional. `None` with no price.
    pub value: Option<f64>,
    /// `long` or `short`, for a position.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub side: Option<&'static str>,
    /// Unrealized profit in USDT, for a position whose figure is live.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pnl: Option<f64>,
}

struct Member {
    id: CoreId,
    name: String,
    figures: BalanceFigures,
    merge: Option<AccountMergeKey>,
    mode: TotalMode,
}

/// The accounts behind the total, each once. With `refresh`, the counted spot cores are asked for
/// their wallet list again (coins some exchanges list only there), at most every 30 s per core.
pub fn accounts(session: &SessionManager, config: &AppConfig, refresh: bool) -> AccountsDto {
    let store = session.store();
    let mut members: Vec<Member> = Vec::new();
    let mut figures: Vec<(CoreId, String, BalanceFigures)> = Vec::new();
    for server in &config.servers {
        if !(server.active && config.group(&server.group).active) {
            continue;
        }
        let Some(data) = store.core(server.id) else { continue };
        let reading = BalanceFigures {
            state: data.balance_state(),
            free: data.assets.global.free_usdt,
            total: data.assets.global.total_usdt,
        };
        figures.push((server.id, server.name.clone(), reading));
        members.push(Member {
            id: server.id,
            name: server.name.clone(),
            figures: reading,
            merge: session.account_merge_key(server.id),
            mode: core_total_mode(&config.servers, server.id),
        });
    }
    // The total exactly as the Cores screen sums it.
    let sum = aggregate_account_figures(session, &config.servers, &figures).sum;

    // Groups in first-seen order, as the total folds them: `Exclude` stays out, `Separate` and an
    // unknown account stand alone, `Auto` cores of one account form one group.
    let mut excluded = Vec::new();
    let mut groups: Vec<Vec<&Member>> = Vec::new();
    for member in &members {
        match (member.mode, &member.merge) {
            (TotalMode::Exclude, _) => excluded.push(member.name.clone()),
            (TotalMode::Separate, _) | (TotalMode::Auto, None) => groups.push(vec![member]),
            (TotalMode::Auto, Some(key)) => {
                let existing = groups
                    .iter_mut()
                    .find(|g| g[0].mode == TotalMode::Auto && g[0].merge.as_ref() == Some(key));
                match existing {
                    Some(group) => group.push(member),
                    None => groups.push(vec![member]),
                }
            }
        }
    }

    let mut accounts = Vec::with_capacity(groups.len());
    for group in groups {
        // The reading the total counts: the usable one with the largest total, the earliest on a
        // tie; with none usable, the first.
        let kept = group
            .iter()
            .rev()
            .filter(|m| m.figures.usable())
            .max_by(|a, b| a.figures.total.total_cmp(&b.figures.total))
            .copied()
            .unwrap_or(group[0]);
        let mut ordered: Vec<&Member> = vec![kept];
        ordered.extend(group.iter().copied().filter(|m| m.id != kept.id));
        accounts.push(account(session, &ordered, refresh));
    }
    // The biggest accounts first; ones without a figure last.
    accounts.sort_by(|a, b| b.total.unwrap_or(f64::MIN).total_cmp(&a.total.unwrap_or(f64::MIN)));

    AccountsDto { total: sum.total, free: sum.free, accounts, excluded }
}

fn account(session: &SessionManager, members: &[&Member], refresh: bool) -> AccountDto {
    let store = session.store();
    let kept = members[0];
    let venue = session.core_venues().get(&kept.id);
    let resolved = venue.and_then(|v| v.resolved());
    let exchange = match resolved {
        Some(resolved) => resolved.brand.display().to_string(),
        None => venue.map(|v| v.reported.clone()).unwrap_or_default(),
    };
    let brand = resolved.map(|r| r.brand.slug().to_string()).unwrap_or_default();
    let kind_name = |kind: Option<MarketKind>| match kind {
        Some(MarketKind::Spot) => "spot",
        Some(MarketKind::Futures) => "futures",
        Some(MarketKind::Quarterly) => "quarterly",
        None => "",
    };
    let kind = kind_name(resolved.map(|r| r.kind));
    let mixed = members
        .iter()
        .map(|m| kind_name(resolved_kind(session, m.id)))
        .filter(|k| !k.is_empty())
        .any(|k| k != kind);
    let kind = if mixed { "unified" } else { kind };
    let data = store.core(kept.id);
    let currency = data.map(|d| d.assets.base_currency.clone()).unwrap_or_default();
    let state = match kept.figures.state {
        BalanceState::Live => "live",
        BalanceState::Stale => "stale",
        BalanceState::Awaiting => "awaiting",
        BalanceState::Unpriced => "unpriced",
    };
    let usable = kept.figures.usable();
    let pnl = data
        .filter(|d| usable && d.assets.global.usd_rate_known)
        .map(|d| d.assets.global.pnl_usdt)
        .filter(|p| p.is_finite());

    if refresh {
        // The spot cores' wallet lists: some exchanges list bought coins only there.
        for member in members.iter().filter(|m| m.figures.state == BalanceState::Live) {
            if matches!(resolved_kind(session, member.id), Some(MarketKind::Spot)) {
                ask_wallets(session, member.id);
                break;
            }
        }
    }

    // The counted core's holdings first; another live core of the account adds only what is
    // missing. A coin is one entry however many markets list it; a position one per market and
    // side.
    let mut assets: Vec<AssetDto> = Vec::new();
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    for (at, member) in members.iter().enumerate() {
        if at > 0 && member.figures.state != BalanceState::Live {
            continue;
        }
        let Some(data) = store.core(member.id) else { continue };
        let base = data.assets.base_currency.trim().to_ascii_uppercase();
        let futures = data.assets.futures_account;
        let coin_margined = futures && matches!(resolved_kind(session, member.id), Some(MarketKind::Quarterly));
        for row in &data.assets.rows {
            if row.pos_size != 0.0 && row.pos_size.is_finite() {
                let side = if row.pos_size < 0.0 { "short" } else { "long" };
                if !seen.insert(format!("pos:{}:{side}", row.market)) {
                    continue;
                }
                let price = if row.mark_price > 0.0 { row.mark_price } else { row.price };
                let notional = row.pos_size.abs() * price;
                assets.push(AssetDto {
                    kind: "position",
                    coin: row.coin.clone(),
                    market: row.market.clone(),
                    amount: row.pos_size.abs(),
                    value: (notional.is_finite() && notional > 0.0).then_some(notional),
                    side: Some(side),
                    pnl: (row.pnl_live && row.pnl_usdt.is_finite()).then_some(row.pnl_usdt),
                });
                continue;
            }
            // A futures account's balances are its margin, not coins bought; a coin-margined one
            // holds its margin in coins, which are its assets.
            if futures && !coin_margined {
                continue;
            }
            let amount = row.qty_full.abs().max(row.qty.abs());
            if row.coin.is_empty() || !(amount > 0.0) || !seen.insert(format!("coin:{}", row.coin.to_ascii_uppercase())) {
                continue;
            }
            let cash = row.is_quote_asset || row.coin.eq_ignore_ascii_case(&base);
            assets.push(AssetDto {
                kind: if cash { "cash" } else { "coin" },
                coin: row.coin.clone(),
                market: String::new(),
                amount,
                value: (row.value_usdt.is_finite() && row.value_usdt > 0.0).then_some(row.value_usdt),
                side: None,
                pnl: None,
            });
        }
        if !futures {
            // Coins some exchanges list only in the wallet, not per market.
            for wallet in &data.transfer_assets.spot {
                let coin = wallet.currency.to_ascii_uppercase();
                if coin.is_empty() || !(wallet.total.abs() > 0.0) || !seen.insert(format!("coin:{coin}")) {
                    continue;
                }
                assets.push(AssetDto {
                    kind: if coin == base { "cash" } else { "coin" },
                    coin: wallet.currency.clone(),
                    market: String::new(),
                    amount: wallet.total.abs(),
                    value: (wallet.value_usdt.is_finite() && wallet.value_usdt > 0.0).then_some(wallet.value_usdt),
                    side: None,
                    pnl: None,
                });
            }
        }
    }
    // Positions and the account's own currency always show; a coin worth under a dollar, or with
    // no price, is counted among the small ones.
    let mut small_count = 0u32;
    let mut small_usdt = 0.0;
    assets.retain(|a| {
        let small = a.kind == "coin" && a.value.map_or(true, |v| v < DUST_USDT);
        if small {
            small_count += 1;
            small_usdt += a.value.unwrap_or(0.0);
        }
        !small
    });
    assets.sort_by(|a, b| b.value.unwrap_or(-1.0).total_cmp(&a.value.unwrap_or(-1.0)));

    AccountDto {
        exchange,
        brand,
        kind,
        currency,
        cores: members.iter().map(|m| m.name.clone()).collect(),
        state,
        total: usable.then_some(kept.figures.total),
        free: usable.then_some(kept.figures.free),
        pnl,
        assets,
        small_count,
        small_usdt,
    }
}

fn resolved_kind(session: &SessionManager, core: CoreId) -> Option<MarketKind> {
    session.core_venues().get(&core).and_then(|v| v.resolved()).map(|r| r.kind)
}

/// Ask a core for its wallet list, at most every [`REFRESH_EVERY`].
fn ask_wallets(session: &SessionManager, core: CoreId) {
    let mut asked = ASKED.lock().unwrap_or_else(|e| e.into_inner());
    let asked = asked.get_or_insert_with(HashMap::new);
    if asked.get(&core).is_some_and(|at| at.elapsed() < REFRESH_EVERY) {
        return;
    }
    asked.insert(core, Instant::now());
    if let Err(e) = session.refresh_transfer_assets(core) {
        log::debug!("wallet list of core {core} not asked: {e}");
    }
}
