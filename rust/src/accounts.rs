//! The total balance taken apart: one entry per exchange account, its wallets and what they hold.
//!
//! Several cores often trade on one exchange account, and each of them reports the whole
//! account's wallets. A plain list per core would show that money once per core. The cores are
//! grouped here the way the total folds them (`moon_core::session::balances`): by the account's
//! identity the exchange gave each core in `AuthCheck`, and the wallet where spot and futures keep
//! apart. Each wallet is shown once, with the coins it holds — the exchange's own wallet list,
//! which one core of the wallet asks for — so nothing appears twice. A coin in an open order is
//! counted where the order holds it: a buy holds the quote coin, a sell the coin sold; the order
//! itself is not listed. A wallet is worth what its coins are, USDT and USDC at one dollar.
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
use moon_core::venue::{AccountMergeKey, Brand, MarketKind, WalletScope};
use serde::Serialize;

/// A holding worth less than this, in USDT, is counted among the small ones, not listed.
const DUST_USDT: f64 = 1.0;

/// A core's wallet list is asked for again no sooner than this.
const REFRESH_EVERY: Duration = Duration::from_secs(30);

/// When each core's wallet list was last asked for.
static ASKED: Mutex<Option<HashMap<CoreId, Instant>>> = Mutex::new(None);

#[derive(Serialize)]
pub struct AccountsDto {
    /// What all the accounts below hold together, USDT and USDC at one dollar.
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
    /// The coins it holds, largest first.
    pub assets: Vec<AssetDto>,
    /// Holdings under [`DUST_USDT`] or with no price: how many, and what the priced ones are worth.
    pub small_count: u32,
    pub small_usdt: f64,
}


#[derive(Serialize)]
pub struct AssetDto {
    /// `coin` (a held coin) or `cash` (the account's own currency).
    pub kind: &'static str,
    pub coin: String,
    /// The whole amount, what stands in orders included.
    pub amount: f64,
    /// Worth in USDT; `None` with no price.
    pub value: Option<f64>,
}

struct Member {
    id: CoreId,
    name: String,
    figures: BalanceFigures,
    merge: Option<AccountMergeKey>,
    /// Where the total does not fold the exchange (Gate, HTX: their cores state only a deposit
    /// address): the address and the market, so cores of one address and market show as one
    /// wallet here.
    by_address: Option<(Brand, String, MarketKind)>,
    mode: TotalMode,
}

/// The main screen's «Общий баланс»: what all the accounts hold together, the same figure the
/// balances window shows. A core's own figure is its account currency only — a spot core leaves
/// out the coins it bought — so the sum goes by the exchange's wallet lists instead. A futures
/// wallet's list does not come by itself, so each wallet's list is asked for again, at most every
/// 30 s per core, while the app runs.
pub fn total(session: &SessionManager, config: &AppConfig) -> Option<f64> {
    accounts(session, config, true).total
}

/// The accounts behind the total, each once. With `refresh`, a live core of each wallet is asked
/// for the wallet list again (coins some exchanges list only there), at most every 30 s per core.
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
        let merge = session.account_merge_key(server.id);
        let by_address = merge
            .is_none()
            .then(|| session.core_venues().get(&server.id).and_then(|v| v.resolved()))
            .flatten()
            .filter(|venue| venue.brand.wallet_scope() == WalletScope::NeverFold)
            .and_then(|venue| account_of(session, server.id).map(|(brand, id)| (brand, id, venue.kind)));
        members.push(Member {
            id: server.id,
            name: server.name.clone(),
            figures: reading,
            merge,
            by_address,
            mode: core_total_mode(&config.servers, server.id),
        });
    }
    // The cores' own figures summed, for when no wallet has a figure yet.
    let sum = aggregate_account_figures(session, &config.servers, &figures).sum;

    // Groups in first-seen order, as the total folds them: `Exclude` stays out, `Separate` and an
    // unknown account stand alone, `Auto` cores of one account form one group. Cores of Gate and
    // HTX, which the total never folds, group here by their deposit address and market: one
    // address is one account, and its cores on one market read the same wallet.
    let mut excluded = Vec::new();
    let mut groups: Vec<Vec<&Member>> = Vec::new();
    for member in &members {
        match (member.mode, &member.merge) {
            (TotalMode::Exclude, _) => excluded.push(member.name.clone()),
            (TotalMode::Auto, None) if member.by_address.is_some() => {
                let existing = groups.iter_mut().find(|g| {
                    g[0].mode == TotalMode::Auto && g[0].merge.is_none() && g[0].by_address == member.by_address
                });
                match existing {
                    Some(group) => group.push(member),
                    None => groups.push(vec![member]),
                }
            }
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
        // A wallet belongs to a known account when the total folds it by account, or, on Gate and
        // HTX, by the deposit address its cores state.
        let owner = if kept.merge.is_some() || kept.by_address.is_some() { account_of(session, kept.id) } else { None };
        wallets.push((owner, kept.id, wallet(session, &ordered, refresh)));
    }

    let accounts = gather(session, wallets);
    // What the accounts here hold together; the main screen's total is this same figure.
    let totals: Vec<f64> = accounts.iter().filter_map(|a| a.total).collect();
    let total = if totals.is_empty() { sum.total } else { Some(totals.iter().sum()) };
    AccountsDto { total, accounts, excluded }
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
        // The wallet lists: what the exchange holds in each wallet, coins in orders included.
        // One live core of the wallet is enough; all of them would answer the same.
        if let Some(member) = members.iter().find(|m| m.figures.state == BalanceState::Live) {
            ask_wallets(session, member.id);
        }
    }

    let assets = wallet_assets(session, members, kind);
    // The wallet is worth what its coins are; while none has a price, what the core says.
    let priced: Vec<f64> = assets.iter().filter_map(|a| a.value).collect();
    let total = if priced.is_empty() { usable.then_some(kept.figures.total) } else { Some(priced.iter().sum()) };
    let mut assets = assets;
    // The account's own currency always shows; a coin worth under a dollar, or with no price, is
    // counted among the small ones.
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
        kind,
        currency,
        cores: members.iter().map(|m| m.name.clone()).collect(),
        state,
        total,
        assets,
        small_count,
        small_usdt,
    }
}

fn resolved_kind(session: &SessionManager, core: CoreId) -> Option<MarketKind> {
    session.core_venues().get(&core).and_then(|v| v.resolved()).map(|r| r.kind)
}

/// Coins valued at one dollar whatever the market says.
fn stable(coin: &str) -> bool {
    coin.eq_ignore_ascii_case("USDT") || coin.eq_ignore_ascii_case("USDC")
}

/// What a wallet holds, one line per coin with its full amount — what stands in orders included,
/// in the coin the order holds (a buy holds the quote coin, a sell the coin sold) — and its worth
/// in USDT, USDT and USDC at one dollar.
///
/// The exchange's own wallet list comes first: the core asks the exchange, and the list has every
/// coin of the wallet with its total. Until a core of the wallet has one, the core's figures stand
/// in: on spot its coins and its own currency (free and in orders), on futures the margin currency
/// without the open positions' result, on coin-margined futures the coins.
fn wallet_assets(session: &SessionManager, members: &[&Member], kind: &str) -> Vec<AssetDto> {
    let store = session.store();
    let line = |coin: &str, base: &str, amount: f64, value_usdt: f64| AssetDto {
        kind: if coin.eq_ignore_ascii_case(base) { "cash" } else { "coin" },
        coin: coin.to_string(),
        amount,
        value: if stable(coin) {
            Some(amount)
        } else {
            (value_usdt.is_finite() && value_usdt > 0.0).then_some(value_usdt)
        },
    };

    // The exchange's list, from the first core of the wallet that has one.
    for member in members {
        let Some(data) = store.core(member.id) else { continue };
        let lists = &data.transfer_assets;
        let rows: Vec<&moon_core::feed::TransferAssetRow> = match kind {
            "spot" => lists.spot.iter().collect(),
            "futures" => lists.futures.iter().collect(),
            "quarterly" => lists.quarterly.iter().collect(),
            _ => lists.spot.iter().chain(&lists.futures).chain(&lists.quarterly).collect(),
        };
        if rows.iter().all(|r| !(r.total.abs() > 0.0)) {
            continue;
        }
        let base = data.assets.base_currency.trim().to_ascii_uppercase();
        // One line per coin; a wallet shared by spot and futures may list a coin in both.
        let mut by_coin: Vec<AssetDto> = Vec::new();
        for row in rows {
            let amount = row.total.abs();
            let coin = row.currency.trim().to_ascii_uppercase();
            if coin.is_empty() || !(amount > 0.0) {
                continue;
            }
            match by_coin.iter_mut().find(|a| a.coin == coin) {
                Some(seen) if seen.amount >= amount => {}
                Some(seen) => *seen = line(&coin, &base, amount, row.value_usdt),
                None => by_coin.push(line(&coin, &base, amount, row.value_usdt)),
            }
        }
        return by_coin;
    }

    // No list yet: the counted core's own figures.
    let Some(data) = store.core(members[0].id) else { return Vec::new() };
    let base = data.assets.base_currency.trim().to_ascii_uppercase();
    let global = &data.assets.global;
    let mut assets: Vec<AssetDto> = Vec::new();
    if kind == "quarterly" {
        for row in &data.assets.rows {
            let amount = row.qty_full.abs().max(row.qty.abs());
            let coin = row.coin.trim().to_ascii_uppercase();
            if coin.is_empty() || !(amount > 0.0) || assets.iter().any(|a| a.coin == coin) {
                continue;
            }
            assets.push(line(&coin, &base, amount, row.value_usdt));
        }
        return assets;
    }
    if kind != "futures" {
        for row in &data.assets.rows {
            let amount = row.qty_full.abs().max(row.qty.abs());
            let coin = row.coin.trim().to_ascii_uppercase();
            if coin.is_empty() || coin == base || row.is_quote_asset || !(amount > 0.0) || assets.iter().any(|a| a.coin == coin) {
                continue;
            }
            assets.push(line(&coin, &base, amount, row.value_usdt));
        }
    }
    // The account's own currency: free and held in orders, without the open positions' result.
    let cash = global.btc_total.max(0.0) + global.btc_locked.max(0.0);
    if !base.is_empty() && cash > 0.0 && cash.is_finite() {
        let rate = if global.btc_full > 0.0 && global.total_usdt > 0.0 { global.total_usdt / global.btc_full } else { 0.0 };
        assets.push(line(&base, &base, cash, cash * rate));
    }
    assets
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
