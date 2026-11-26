//! The total balance taken apart: one entry per exchange account, its wallets and what they hold.
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
//!
//! The wallets are then gathered into the exchange account they belong to — Binance by the
//! account's uid, other exchanges by their account id or address, as each core stated it — so one
//! account shows once with its spot, futures and coin-margined wallets inside. The account's
//! number is never shown: the app gets a stable hash of it to keep the name the user gives it.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use moon_core::config::{AppConfig, TotalMode};
use moon_core::session::balances::{aggregate_account_figures, core_total_mode, BalanceFigures};
use moon_core::session::{BalanceState, CoreId, SessionManager};
use moon_core::venue::{AccountMergeKey, Brand, MarketKind};
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
    /// Exchange accounts: by exchange name, then by the account's number on that exchange.
    pub accounts: Vec<ExchangeAccountDto>,
    /// Cores set to stay out of the total.
    pub excluded: Vec<String>,
}

/// One exchange account with its wallets.
#[derive(Serialize)]
pub struct ExchangeAccountDto {
    /// Stable across launches, for the name the user gives the account: a hash of the exchange
    /// and the account's number, never the number itself; `core:<id>` for an unknown account.
    pub key: String,
    pub exchange: String,
    pub brand: String,
    /// The account's place among this exchange's accounts, from 1; 0 when it is the only one, or
    /// unknown.
    pub number: u32,
    /// The cores stated which account they trade; false for a core that did not.
    pub known: bool,
    /// The sum of its wallets' balances; `None` while none has one.
    pub total: Option<f64>,
    pub wallets: Vec<WalletDto>,
}

/// One wallet of an account: spot, futures, coin-margined, or the one wallet of an exchange that
/// keeps everything together.
#[derive(Serialize)]
pub struct WalletDto {
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
    /// Coins and positions, largest first.
    pub assets: Vec<AssetDto>,
    /// Holdings under [`DUST_USDT`] or with no price: how many, and what the priced ones are worth.
    pub small_count: u32,
    pub small_usdt: f64,
    /// The cores' orders standing on the exchange's book, largest first. Their funds are already
    /// in the wallet's total: an order only holds part of it.
    pub orders: Vec<BookOrderDto>,
}

/// An order standing on the book: an entry not filled yet, or the take of a position.
#[derive(Serialize)]
pub struct BookOrderDto {
    pub coin: String,
    pub market: String,
    /// `buy` or `sell`, as the order stands on the book.
    pub side: &'static str,
    /// `entry` (opens a position) or `take` (closes one).
    pub leg: &'static str,
    /// Coins still to trade.
    pub amount: f64,
    pub price: f64,
    /// `amount × price`, in [`Self::quote`].
    pub value: f64,
    pub quote: String,
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

    let mut wallets: Vec<(Option<(Brand, String)>, CoreId, WalletDto)> = Vec::with_capacity(groups.len());
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
        // Only a wallet the total folds by account belongs to a known account.
        let owner = kept.merge.as_ref().and_then(|_| account_of(session, kept.id));
        wallets.push((owner, kept.id, wallet(session, &ordered, refresh)));
    }

    AccountsDto { total: sum.total, accounts: gather(session, wallets), excluded }
}

/// The exchange account a core trades, by the same field the total folds it by: the uid on
/// Binance, else the account id, else the address. `None` when the core stated none.
fn account_of(session: &SessionManager, core: CoreId) -> Option<(Brand, String)> {
    let brand = session.core_venues().get(&core).and_then(|v| v.resolved())?.brand;
    let identity = session.account_identity(core)?;
    let id = match brand {
        Brand::Binance => identity.binance_uid?.to_string(),
        _ => identity.account_id.or(identity.address)?,
    };
    Some((brand, id))
}

/// FNV-1a, 64 bits: a hash that stays the same across builds, for keys kept on the phone.
fn stable_hash(text: &str) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in text.bytes() {
        hash ^= u64::from(byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

/// The wallets gathered into their exchange accounts. Accounts go by exchange name; one
/// exchange's known accounts are numbered by their account number (shorter first, so numeric
/// uids count up), and a wallet whose account is unknown stands alone after them.
fn gather(
    session: &SessionManager,
    wallets: Vec<(Option<(Brand, String)>, CoreId, WalletDto)>,
) -> Vec<ExchangeAccountDto> {
    struct Gathered {
        exchange: String,
        brand: String,
        id: Option<String>,
        key: String,
        wallets: Vec<WalletDto>,
    }
    let mut gathered: Vec<Gathered> = Vec::new();
    for (owner, core, wallet) in wallets {
        let venue = session.core_venues().get(&core);
        let resolved = venue.and_then(|v| v.resolved());
        let exchange = match resolved {
            Some(r) => r.brand.display().to_string(),
            None => venue.map(|v| v.reported.clone()).unwrap_or_default(),
        };
        let brand = resolved.map(|r| r.brand.slug().to_string()).unwrap_or_default();
        match owner {
            Some((owner_brand, id)) => {
                let key = format!("{}:{:016x}", owner_brand.slug(), stable_hash(&format!("{}:{id}", owner_brand.slug())));
                match gathered.iter_mut().find(|g| g.key == key) {
                    Some(account) => account.wallets.push(wallet),
                    None => gathered.push(Gathered { exchange, brand, id: Some(id), key, wallets: vec![wallet] }),
                }
            }
            None => gathered.push(Gathered {
                exchange,
                brand,
                id: None,
                key: format!("core:{core}"),
                wallets: vec![wallet],
            }),
        }
    }
    // Known accounts in account-number order, unknown ones after, exchanges by name.
    gathered.sort_by(|a, b| {
        a.exchange.to_lowercase().cmp(&b.exchange.to_lowercase()).then_with(|| match (&a.id, &b.id) {
            (Some(x), Some(y)) => x.len().cmp(&y.len()).then_with(|| x.cmp(y)),
            (Some(_), None) => std::cmp::Ordering::Less,
            (None, Some(_)) => std::cmp::Ordering::Greater,
            (None, None) => std::cmp::Ordering::Equal,
        })
    });
    let kind_rank = |kind: &str| match kind {
        "spot" => 0,
        "futures" => 1,
        "quarterly" => 2,
        _ => 3,
    };
    let mut out: Vec<ExchangeAccountDto> = Vec::with_capacity(gathered.len());
    for account in gathered {
        let known_here = |e: &str| out.iter().filter(|a| a.exchange == e && a.known).count();
        let number = if account.id.is_some() { known_here(&account.exchange) as u32 + 1 } else { 0 };
        let mut wallets = account.wallets;
        wallets.sort_by_key(|w| kind_rank(w.kind));
        let totals: Vec<f64> = wallets.iter().filter_map(|w| w.total).collect();
        out.push(ExchangeAccountDto {
            key: account.key,
            exchange: account.exchange,
            brand: account.brand,
            number,
            known: account.id.is_some(),
            total: (!totals.is_empty()).then(|| totals.iter().sum()),
            wallets,
        });
    }
    // An exchange with a single known account gets no number.
    let mut counts: HashMap<String, u32> = HashMap::new();
    for account in out.iter().filter(|a| a.known) {
        *counts.entry(account.exchange.clone()).or_default() += 1;
    }
    for account in &mut out {
        if account.known && counts.get(&account.exchange) == Some(&1) {
            account.number = 0;
        }
    }
    out
}

fn wallet(session: &SessionManager, members: &[&Member], refresh: bool) -> WalletDto {
    let store = session.store();
    let kept = members[0];
    let resolved = session.core_venues().get(&kept.id).and_then(|v| v.resolved());
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

    WalletDto {
        orders: book_orders(session, members),
        kind,
        currency,
        cores: members.iter().map(|m| m.name.clone()).collect(),
        state,
        total: usable.then_some(kept.figures.total),
        assets,
        small_count,
        small_usdt,
    }
}

fn resolved_kind(session: &SessionManager, core: CoreId) -> Option<MarketKind> {
    session.core_venues().get(&core).and_then(|v| v.resolved()).map(|r| r.kind)
}

/// The orders every core of the wallet has standing on the book. Each core reports only its own
/// orders, so they add up without repeats. An entry stands while the core says `BuySet`, for the
/// part not filled yet; a take while it says `SellSet`, for what remains to sell. An emulated
/// order, one waiting on its condition and one already done are not on the book.
fn book_orders(session: &SessionManager, members: &[&Member]) -> Vec<BookOrderDto> {
    let store = session.store();
    let mut orders = Vec::new();
    for member in members {
        let Some(data) = store.core(member.id) else { continue };
        for order in &data.orders {
            if order.job_is_done || order.emulator || order.pending {
                continue;
            }
            let (leg, amount, price) = match order.status.as_str() {
                "BuySet" => {
                    let left = order.size * (1.0 - f64::from(order.fill_pct).clamp(0.0, 100.0) / 100.0);
                    ("entry", left, order.buy_price)
                }
                "SellSet" => {
                    let left = if order.remaining_size > 0.0 { order.remaining_size } else { order.size };
                    ("take", left, order.sell_price)
                }
                _ => continue,
            };
            if !(amount > 0.0 && price > 0.0 && amount.is_finite() && price.is_finite()) {
                continue;
            }
            // A long buys to enter and sells to take; a short the other way round.
            let buys = (leg == "entry") != order.is_short;
            orders.push(BookOrderDto {
                coin: order.coin.clone(),
                market: order.market.clone(),
                side: if buys { "buy" } else { "sell" },
                leg,
                amount,
                price,
                value: amount * price,
                quote: order.quote.clone(),
            });
        }
    }
    orders.sort_by(|a, b| b.value.total_cmp(&a.value));
    orders
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

#[cfg(test)]
mod tests {
    use super::stable_hash;

    #[test]
    fn stable_hash_is_fnv1a() {
        // Published FNV-1a 64 values: a name kept on the phone must find its account again.
        assert_eq!(stable_hash(""), 0xcbf2_9ce4_8422_2325);
        assert_eq!(stable_hash("a"), 0xaf63_dc4c_8601_ec8c);
        assert_eq!(stable_hash("foobar"), 0x8594_4171_f739_67e8);
    }
}
