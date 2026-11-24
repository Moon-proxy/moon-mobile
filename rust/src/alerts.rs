//! Push notifications and detect sounds, decided from each published snapshot: a core that lost
//! its connection, a trade, a server that runs hot or short of memory, a slow ping to the
//! exchange and the exchange's limits. The app only shows what [`Alerts::take`] hands it, so
//! every platform notifies alike — and a server relay can send the very same notices.
//!
//! Nothing is decided before the app has sent its settings ([`Alerts::configure`]). The first
//! pass after that only records where things stand: nothing that was already so when the app
//! connected is news.

use std::collections::{HashMap, HashSet};

use moon_core::session::CoreId;
use serde::Deserialize;
use serde_json::{json, Value};

use crate::snapshot::{CoreDto, DetectDto, EventDto, OrderDto};

/// The core's ping to its exchange is slow from here on, and has recovered under four fifths
/// of it. HyperLiquid answers slowly by nature: its threshold is higher. The phone's own ping to
/// a core is not watched: everyone's internet differs.
const PING_SLOW_MS: u32 = 250;
const PING_SLOW_HYPERLIQUID_MS: u32 = 1000;

/// The ping counted as slow: the alert's threshold, and the chart shows a slower one in red.
pub const PING_SLOW: u32 = PING_SLOW_MS;

/// The exchange latency counted as slow for an exchange (`brand`): Hyperliquid answers slower.
pub fn exchange_slow_ms(brand: &str) -> u32 {
    if brand == "hyperliquid" { PING_SLOW_HYPERLIQUID_MS } else { PING_SLOW_MS }
}
const PING_HOLD_MS: i64 = 10_000;

const CPU_HIGH: u8 = 90;
const CPU_OK: u8 = 80;
const CPU_HOLD_MS: i64 = 60_000;

/// Free memory on a server this low is reported, and has recovered over [`MEM_OK_MB`].
const MEM_LOW_MB: u32 = 150;
const MEM_OK_MB: u32 = 250;
const MEM_HOLD_MS: i64 = 30_000;

/// A connection that stays lost this long is reported; a blink is not.
const LOST_HOLD_MS: i64 = 15_000;

/// HyperLiquid's request quota is low at 5% of the most it stood at over the last day, and has
/// recovered over 10%. The exchange gives no total, so the day's peak stands for it.
const QUOTA_LOW_SHARE: f64 = 0.05;
const QUOTA_OK_SHARE: f64 = 0.10;
const HOUR_MS: i64 = 3_600_000;

/// More notices than this in one pass (a dozen fills at once) become one notice listing the
/// first [`BURST_LINES`] of them.
const BURST: usize = 4;
const BURST_LINES: usize = 6;

/// What waits for the app at most; a long-silent app gets the newest.
const MAX_PENDING: usize = 50;
const MAX_SOUNDS: usize = 10;

/// The app's notification switches (Settings → Уведомления).
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct AlertSettings {
    /// A core that was connected lost its connection.
    pub core_lost: bool,
    /// Trades: an entry filled.
    pub trades: bool,
    /// Only an entry that filled; off also notifies about an entry order being placed.
    pub entries_only: bool,
    /// Trades of every strategy, or only of [`Self::strategies`].
    pub all_strategies: bool,
    /// `core:strategyId` of the strategies whose trades are notified when not all are.
    pub strategies: HashSet<String>,
    /// A slow ping from the core to its exchange.
    pub ping: bool,
    /// The core's machine at 90% CPU for over a minute.
    pub cpu: bool,
    /// The core's machine running out of memory.
    pub memory: bool,
    /// The exchange refusing requests over its limits; on HyperLiquid, its quota down to 5%.
    pub api_limits: bool,
    /// Play the sound a detect's strategy chose, as MoonTerminal does.
    pub sounds: bool,
}

impl Default for AlertSettings {
    fn default() -> Self {
        Self {
            core_lost: true,
            trades: true,
            entries_only: true,
            all_strategies: true,
            strategies: HashSet::new(),
            ping: true,
            cpu: true,
            memory: true,
            api_limits: true,
            sounds: true,
        }
    }
}

/// One condition of one core or server: since when it holds and whether it was reported.
#[derive(Default)]
struct Watch {
    since: i64,
    told: bool,
}

impl Watch {
    /// Advances the condition: `bad` held for `hold_ms` reports once (returns true); `good`
    /// clears it for the next time. A reading of `None` changes nothing.
    fn hold(&mut self, bad: Option<bool>, good: Option<bool>, hold_ms: i64, now: i64) -> bool {
        if bad == Some(true) {
            if self.since == 0 {
                self.since = now;
            }
            if !self.told && now - self.since >= hold_ms {
                self.told = true;
                return true;
            }
        } else if good == Some(true) {
            self.since = 0;
            self.told = false;
        } else if bad == Some(false) {
            self.since = 0;
        }
        false
    }
}

#[derive(Default)]
pub struct Alerts {
    settings: AlertSettings,
    /// A server's address → the name the user gave it.
    server_names: HashMap<String, String>,
    configured: bool,
    primed: bool,
    last_event: u64,
    online: HashMap<CoreId, bool>,
    lost: HashMap<CoreId, Watch>,
    ping: HashMap<CoreId, Watch>,
    /// By server: the cores on one machine report its load and memory once.
    cpu: HashMap<String, Watch>,
    memory: HashMap<String, Watch>,
    quota: HashMap<CoreId, Watch>,
    /// Per core, the highest quota seen in each of the last 24 hours.
    quota_peaks: HashMap<CoreId, HashMap<i64, u64>>,
    /// Per core, the newest detect already heard.
    sound_seq: HashMap<CoreId, u64>,
    sounds_primed: bool,
    /// Every order seen, with whether it was filled.
    orders: HashMap<(CoreId, u64), bool>,
    /// Waiting for the app: notices as `(title, text)`, and sounds by name.
    pending: Vec<(String, String)>,
    sounds: Vec<String>,
}

impl Alerts {
    /// The app's switches and server names. Until the first call nothing is decided.
    pub fn configure(&mut self, settings: AlertSettings, server_names: HashMap<String, String>) {
        self.settings = settings;
        self.server_names = server_names;
        self.configured = true;
    }

    /// What came up since the last call:
    /// `{"notices":[{"kind","title","text"}],"sounds":["name"]}`. The kind (`core_lost`,
    /// `trade`, `server`) is what the app gathers its notices under.
    pub fn take(&mut self) -> Value {
        let notices: Vec<Value> = self
            .pending
            .drain(..)
            .map(|(title, text)| json!({ "kind": kind_of(&title), "title": title, "text": text }))
            .collect();
        json!({ "notices": notices, "sounds": std::mem::take(&mut self.sounds) })
    }

    /// One pass over the latest state, at `now` (Unix ms).
    pub fn step(
        &mut self,
        cores: &[CoreDto],
        orders: &[OrderDto],
        detects: &[DetectDto],
        events: &[EventDto],
        now: i64,
    ) {
        if !self.configured {
            return;
        }
        let settings = self.settings.clone();
        let primed = self.primed;
        let names: HashMap<CoreId, &str> = cores.iter().map(|c| (c.id, c.name.as_str())).collect();
        let mut out: Vec<(String, String)> = Vec::new();
        // HyperLiquid's low quota, as (core, left, peak).
        let mut low_quota: Vec<(&str, u64, u64)> = Vec::new();

        for core in cores.iter().filter(|c| c.active) {
            let was = self.online.insert(core.id, core.run.online);
            // Lost: it was connected, and has not come back for a while.
            let lost = self.lost.entry(core.id).or_default();
            if core.run.online {
                lost.since = 0;
                lost.told = false;
            } else if was == Some(true) || lost.since > 0 {
                if lost.since == 0 {
                    lost.since = now;
                }
                if !lost.told && now - lost.since >= LOST_HOLD_MS {
                    lost.told = true;
                    if settings.core_lost && primed {
                        out.push((format!("Ядро {} потеряло связь", core.name), status_text(core)));
                    }
                }
            }
            if !core.run.online {
                continue;
            }

            let exchange = core.health.exchange_ms;
            let slow = exchange_slow_ms(&core.brand);
            let watch = self.ping.entry(core.id).or_default();
            if watch.hold(exchange.map(|ms| ms >= slow), exchange.map(|ms| ms < slow * 4 / 5), PING_HOLD_MS, now)
                && settings.ping
                && primed
            {
                out.push((
                    format!("Высокий пинг к бирже: {}", core.name),
                    format!("От ядра до биржи {} мс (порог {slow} мс)", exchange.unwrap_or_default()),
                ));
            }

            if let Some(left) = core.health.api_quota {
                let hours = self.quota_peaks.entry(core.id).or_default();
                let hour = now / HOUR_MS;
                let top = hours.entry(hour).or_insert(0);
                *top = (*top).max(left);
                hours.retain(|h, _| *h > hour - 24);
                let peak = hours.values().copied().max().unwrap_or(left);
                let low = peak > 0 && left as f64 <= peak as f64 * QUOTA_LOW_SHARE;
                let recovered = left as f64 > peak as f64 * QUOTA_OK_SHARE;
                let watch = self.quota.entry(core.id).or_default();
                if watch.hold(Some(low), Some(recovered), 0, now) && settings.api_limits && primed {
                    low_quota.push((core.name.as_str(), left, peak));
                }
            }
        }

        // One notice per account: the cores on one HyperLiquid address report the same number.
        for (left, group) in group_by(&low_quota, |item| item.1) {
            let peak = group.iter().map(|item| item.2).max().unwrap_or(left);
            let who: Vec<&str> = group.iter().map(|item| item.0).collect();
            out.push((
                format!("Лимит запросов HyperLiquid: {}", who.join(", ")),
                format!("Осталось {left} запросов — 5% от максимума за сутки ({peak})"),
            ));
        }

        // The machine's load and memory, once per server however many cores run on it.
        let running: Vec<&CoreDto> = cores.iter().filter(|c| c.active && c.run.online).collect();
        for (key, group) in group_by(&running, |core| server_key(core)) {
            let title = server_title(group[0], &self.server_names);
            let who: Vec<&str> = group.iter().map(|core| core.name.as_str()).collect();
            let who = who.join(", ");
            let load = group.iter().filter_map(|core| core.health.cpu_pct).max();
            let watch = self.cpu.entry(key.clone()).or_default();
            if watch.hold(load.map(|l| l >= CPU_HIGH), load.map(|l| l < CPU_OK), CPU_HOLD_MS, now)
                && settings.cpu
                && primed
            {
                out.push((
                    format!("Процессор {}%: {title}", load.unwrap_or_default()),
                    format!("Нагрузка выше {CPU_HIGH}% дольше минуты. Ядра: {who}"),
                ));
            }
            let free = group.iter().filter_map(|core| core.health.free_mem_mb).min();
            let watch = self.memory.entry(key).or_default();
            if watch.hold(free.map(|f| f < MEM_LOW_MB), free.map(|f| f > MEM_OK_MB), MEM_HOLD_MS, now)
                && settings.memory
                && primed
            {
                out.push((
                    format!("Мало памяти: {title}"),
                    format!("Свободно {} МБ оперативной памяти. Ядра: {who}", free.unwrap_or_default()),
                ));
            }
        }

        // The exchange refusing requests, as the cores' logs say. HyperLiquid is watched by its
        // request quota above instead.
        let brands: HashMap<CoreId, &str> = cores.iter().map(|c| (c.id, c.brand.as_str())).collect();
        for event in events.iter().filter(|e| e.seq > self.last_event) {
            if primed
                && settings.api_limits
                && event.kind == "api_limit"
                && brands.get(&event.core).copied() != Some("hyperliquid")
            {
                let who = names.get(&event.core).map(|n| n.to_string()).unwrap_or_else(|| event.core.to_string());
                out.push((format!("Лимит API: {who}"), event.text.clone()));
            }
        }
        if let Some(newest) = events.iter().map(|e| e.seq).max() {
            self.last_event = self.last_event.max(newest);
        }

        // Detect sounds, as MoonTerminal plays them: at most one per core per pass, the newest
        // detect that asked for one. What was there before the app looked stays silent; a core
        // with no detect then hears its very first one.
        let by_core = group_by(detects, |d| d.core);
        if !self.sounds_primed {
            self.sounds_primed = true;
            for core in cores {
                let newest = by_core
                    .iter()
                    .find(|(id, _)| *id == core.id)
                    .and_then(|(_, list)| list.iter().map(|d| d.seq).max())
                    .unwrap_or(0);
                self.sound_seq.insert(core.id, newest);
            }
        } else {
            for (core, list) in &by_core {
                let seen = self.sound_seq.get(core).copied().unwrap_or(0);
                let fresh: Vec<&&DetectDto> = list.iter().filter(|d| d.seq > seen).collect();
                let Some(newest) = fresh.iter().map(|d| d.seq).max() else {
                    continue;
                };
                self.sound_seq.insert(*core, newest);
                let heard = fresh.iter().filter(|d| d.sound.is_some()).max_by_key(|d| d.seq);
                if let (true, Some(sound)) = (settings.sounds, heard.and_then(|d| d.sound.clone())) {
                    if self.sounds.len() < MAX_SOUNDS {
                        self.sounds.push(sound);
                    }
                }
            }
        }

        // Trades.
        let mut current: HashSet<(CoreId, u64)> = HashSet::new();
        for order in orders {
            let key = (order.core, order.uid.0);
            current.insert(key);
            let before = self.orders.insert(key, order.filled);
            if !primed || !settings.trades || !wanted(&settings, order) {
                continue;
            }
            let side = if order.short { "Short" } else { "Long" };
            let strategy = if order.strategy.is_empty() { "ручной" } else { order.strategy.as_str() };
            let who: Vec<&str> = [names.get(&order.core).copied().unwrap_or_default(), strategy]
                .into_iter()
                .filter(|part| !part.is_empty())
                .collect();
            let who = who.join(" · ");
            let at = if order.buy_price > 0.0 { format!(" по {}", price(order.buy_price)) } else { String::new() };
            if order.filled && before != Some(true) {
                out.push((format!("Вход в позицию: {} {side}{at}", order.coin), who));
            } else if before.is_none() && !order.filled && !settings.entries_only {
                out.push((format!("Ордер выставлен: {} {side}{at}", order.coin), who));
            }
        }
        self.orders.retain(|key, _| current.contains(key));

        self.primed = true;
        if out.is_empty() {
            return;
        }
        for (title, text) in &out {
            crate::logs::app_line(&format!("Уведомление: {title} — {text}"));
        }
        if out.len() > BURST {
            let lines: Vec<&str> = out.iter().take(BURST_LINES).map(|(title, _)| title.as_str()).collect();
            self.pending.push((format!("Событий: {}", out.len()), lines.join("\n")));
        } else {
            self.pending.extend(out);
        }
        if self.pending.len() > MAX_PENDING {
            let extra = self.pending.len() - MAX_PENDING;
            self.pending.drain(..extra);
        }
    }
}

/// The group a notice goes under in the phone's notification shade, by its title.
fn kind_of(title: &str) -> &'static str {
    if title.starts_with("Ядро ") && title.ends_with(" потеряло связь") {
        "core_lost"
    } else if title.starts_with("Вход в позицию") || title.starts_with("Ордер выставлен") {
        "trade"
    } else {
        "server"
    }
}

/// Whether a trade is notified: a strategy's, not one placed by hand or from this phone, and of
/// a strategy the user picked when not all are.
fn wanted(settings: &AlertSettings, order: &OrderDto) -> bool {
    if order.strategy_id.0 == 0 || order.strategy.is_empty() || order.mine {
        return false;
    }
    settings.all_strategies || settings.strategies.contains(&format!("{}:{}", order.core, order.strategy_id.0))
}

fn status_text(core: &CoreDto) -> String {
    match core.status {
        "failed" => format!("Ошибка: {}", core.status_text),
        "stage" | "connecting" => "Переподключается…".to_string(),
        _ => "Нет связи".to_string(),
    }
}

/// A price with as many decimals as its size needs, trailing zeros dropped.
fn price(value: f64) -> String {
    let decimals = if value < 1.0 {
        6
    } else if value < 100.0 {
        4
    } else {
        2
    };
    let text = format!("{value:.decimals$}");
    text.trim_end_matches('0').trim_end_matches('.').to_string()
}

/// Which server a core runs on: its machine's address, or the core alone when unknown.
fn server_key(core: &CoreDto) -> String {
    if core.host.is_empty() {
        format!("core:{}", core.id)
    } else {
        core.host.clone()
    }
}

/// A server as the user named it, else its address, else its one core's name.
fn server_title(core: &CoreDto, names: &HashMap<String, String>) -> String {
    names.get(&core.host).cloned().unwrap_or_else(|| {
        if core.host.is_empty() {
            core.name.clone()
        } else {
            core.host.clone()
        }
    })
}

/// `items` grouped by `key`, the groups in the order their first item comes.
fn group_by<'a, T, K: PartialEq>(items: &'a [T], key: impl Fn(&T) -> K) -> Vec<(K, Vec<&'a T>)> {
    let mut groups: Vec<(K, Vec<&'a T>)> = Vec::new();
    for item in items {
        let k = key(item);
        match groups.iter_mut().find(|(g, _)| *g == k) {
            Some((_, list)) => list.push(item),
            None => groups.push((k, vec![item])),
        }
    }
    groups
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::Uid;
    use crate::snapshot::{HealthDto, RunDto};

    fn core(id: CoreId, name: &str, online: bool) -> CoreDto {
        CoreDto {
            id,
            name: name.to_string(),
            group: String::new(),
            color: String::new(),
            enabled: true,
            host: format!("10.0.0.{id}"),
            active: true,
            status: if online { "ready" } else { "disconnected" },
            status_text: String::new(),
            exchange: "Binance".to_string(),
            brand: "binance".to_string(),
            kind: "futures",
            base: "USDT".to_string(),
            run: RunDto { online, started: None, trading: None, trading_confirmed: false, auto_detect: None },
            orders: 0,
            positions: 0,
            strategies_total: 0,
            strategies_checked: 0,
            balance: None,
            server_version: None,
            health: HealthDto::default(),
            control: "",
            report_sync: None,
        }
    }

    fn order(core: CoreId, uid: u64, filled: bool, strategy_id: u64) -> OrderDto {
        OrderDto {
            core,
            uid: Uid(uid),
            market: "BTCUSDT".to_string(),
            coin: "BTC".to_string(),
            quote: "USDT".to_string(),
            short: false,
            size: 1.0,
            remaining_size: 1.0,
            buy_price: 64250.5,
            sell_price: 0.0,
            price: 0.0,
            pnl_pct: None,
            fill_pct: 0.0,
            status: String::new(),
            pending: !filled,
            filled,
            panic_sell: false,
            emulator: false,
            strategy: if strategy_id == 0 { String::new() } else { "Drop_5%".to_string() },
            strategy_id: Uid(strategy_id),
            stop_loss: None,
            take_profit: None,
            trailing: None,
            created_ms: 0.0,
            mine: false,
            manual: strategy_id == 0,
            liq_price: None,
            mark_price: None,
        }
    }

    fn detect(core: CoreId, seq: u64, sound: Option<&str>) -> DetectDto {
        DetectDto {
            core,
            seq,
            market: "BTCUSDT".to_string(),
            time_ms: 0.0,
            short: false,
            alert: false,
            kind: 0,
            msg: String::new(),
            strategy: "Drop".to_string(),
            sound: sound.map(str::to_string),
        }
    }

    fn ready() -> Alerts {
        let mut alerts = Alerts::default();
        alerts.configure(AlertSettings::default(), HashMap::new());
        alerts
    }

    fn titles(alerts: &mut Alerts) -> Vec<String> {
        alerts.take()["notices"]
            .as_array()
            .unwrap()
            .iter()
            .map(|n| n["title"].as_str().unwrap().to_string())
            .collect()
    }

    #[test]
    fn nothing_before_settings_and_nothing_old_is_news() {
        let mut alerts = Alerts::default();
        let orders = [order(1, 7, true, 5)];
        alerts.step(&[core(1, "A", true)], &orders, &[], &[], 1_000);
        assert!(!alerts.primed);
        alerts.configure(AlertSettings::default(), HashMap::new());
        // The first pass only records: the filled order was there when the app connected.
        alerts.step(&[core(1, "A", true)], &orders, &[], &[], 2_000);
        alerts.step(&[core(1, "A", true)], &orders, &[], &[], 3_000);
        assert!(titles(&mut alerts).is_empty());
    }

    #[test]
    fn a_lost_core_is_reported_after_the_hold_and_once() {
        let mut alerts = ready();
        alerts.step(&[core(1, "A", true)], &[], &[], &[], 0);
        alerts.step(&[core(1, "A", false)], &[], &[], &[], 1_000);
        alerts.step(&[core(1, "A", false)], &[], &[], &[], 10_000);
        assert!(titles(&mut alerts).is_empty());
        alerts.step(&[core(1, "A", false)], &[], &[], &[], 16_000);
        alerts.step(&[core(1, "A", false)], &[], &[], &[], 30_000);
        assert_eq!(titles(&mut alerts), ["Ядро A потеряло связь"]);
    }

    #[test]
    fn a_fill_of_a_strategy_notifies_and_a_hand_order_does_not() {
        let mut alerts = ready();
        let cores = [core(1, "A", true)];
        alerts.step(&cores, &[], &[], &[], 0);
        alerts.step(&cores, &[order(1, 7, false, 5), order(1, 8, false, 0)], &[], &[], 1_000);
        // Entries only: placing is not news.
        assert!(titles(&mut alerts).is_empty());
        alerts.step(&cores, &[order(1, 7, true, 5), order(1, 8, true, 0)], &[], &[], 2_000);
        let notices = alerts.take();
        assert_eq!(notices["notices"][0]["kind"], "trade");
        assert_eq!(notices["notices"][0]["title"], "Вход в позицию: BTC Long по 64250.5");
        assert_eq!(notices["notices"][0]["text"], "A · Drop_5%");
        assert_eq!(notices["notices"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn picked_strategies_only() {
        let mut alerts = Alerts::default();
        let settings = AlertSettings {
            all_strategies: false,
            entries_only: false,
            strategies: ["1:6".to_string()].into_iter().collect(),
            ..AlertSettings::default()
        };
        alerts.configure(settings, HashMap::new());
        let cores = [core(1, "A", true)];
        alerts.step(&cores, &[], &[], &[], 0);
        alerts.step(&cores, &[order(1, 7, false, 5), order(1, 9, false, 6)], &[], &[], 1_000);
        assert_eq!(titles(&mut alerts), ["Ордер выставлен: BTC Long по 64250.5"]);
    }

    #[test]
    fn a_burst_is_one_notice() {
        let mut alerts = ready();
        let cores = [core(1, "A", true)];
        alerts.step(&cores, &[], &[], &[], 0);
        let fills: Vec<OrderDto> = (10..16).map(|uid| order(1, uid, true, 5)).collect();
        alerts.step(&cores, &fills, &[], &[], 1_000);
        let notices = alerts.take();
        assert_eq!(notices["notices"].as_array().unwrap().len(), 1);
        assert_eq!(notices["notices"][0]["title"], "Событий: 6");
        assert_eq!(notices["notices"][0]["text"].as_str().unwrap().lines().count(), 6);
    }

    #[test]
    fn hot_server_after_a_minute_once_per_server() {
        let mut alerts = ready();
        let mut a = core(1, "A", true);
        let mut b = core(2, "B", true);
        b.host = a.host.clone();
        a.health.cpu_pct = Some(95);
        b.health.cpu_pct = Some(93);
        let names: HashMap<String, String> = [(a.host.clone(), "Vultr".to_string())].into_iter().collect();
        alerts.configure(AlertSettings::default(), names);
        let cores = [a, b];
        alerts.step(&cores, &[], &[], &[], 1);
        alerts.step(&cores, &[], &[], &[], 30_000);
        assert!(titles(&mut alerts).is_empty());
        alerts.step(&cores, &[], &[], &[], 61_000);
        alerts.step(&cores, &[], &[], &[], 90_000);
        assert_eq!(titles(&mut alerts), ["Процессор 95%: Vultr"]);
    }

    #[test]
    fn slow_ping_and_exchange_limits() {
        let mut alerts = ready();
        let mut a = core(1, "A", true);
        let event = EventDto { seq: 4, core: 1, kind: "api_limit", text: "429".to_string(), time_ms: 0 };
        alerts.step(std::slice::from_ref(&a), &[], &[], std::slice::from_ref(&event), 1);
        // The event was there before: not news.
        a.health.exchange_ms = Some(300);
        alerts.step(std::slice::from_ref(&a), &[], &[], std::slice::from_ref(&event), 2_000);
        alerts.step(std::slice::from_ref(&a), &[], &[], std::slice::from_ref(&event), 12_500);
        let fresh = EventDto { seq: 5, ..event };
        alerts.step(std::slice::from_ref(&a), &[], &[], &[fresh], 13_000);
        assert_eq!(titles(&mut alerts), ["Высокий пинг к бирже: A", "Лимит API: A"]);
    }

    #[test]
    fn hyperliquid_quota_low_against_the_days_peak() {
        let mut alerts = ready();
        let mut a = core(1, "A", true);
        a.brand = "hyperliquid".to_string();
        a.health.api_quota = Some(10_000);
        alerts.step(std::slice::from_ref(&a), &[], &[], &[], 1);
        a.health.api_quota = Some(400);
        alerts.step(std::slice::from_ref(&a), &[], &[], &[], 1_000);
        assert_eq!(titles(&mut alerts), ["Лимит запросов HyperLiquid: A"]);
    }

    #[test]
    fn sounds_play_for_new_detects_only() {
        let mut alerts = ready();
        let cores = [core(1, "A", true), core(2, "B", true)];
        alerts.step(&cores, &[], &[detect(1, 3, Some("old"))], &[], 0);
        let detects = [detect(1, 3, Some("old")), detect(1, 4, Some("a")), detect(1, 5, None), detect(2, 1, Some("b"))];
        alerts.step(&cores, &[], &detects, &[], 1_000);
        assert_eq!(alerts.take()["sounds"], json!(["a", "b"]));
        alerts.step(&cores, &[], &detects, &[], 2_000);
        assert_eq!(alerts.take()["sounds"], json!([]));
    }

    #[test]
    fn prices_read_naturally() {
        assert_eq!(price(64250.5), "64250.5");
        assert_eq!(price(100.0), "100");
        assert_eq!(price(1.23456789), "1.2346");
        assert_eq!(price(0.000123), "0.000123");
    }
}
