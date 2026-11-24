//! Markets ranked by how far their price moved or by how much they traded, for the chart's
//! market picker.
//!
//! The terminal's Screener reads each market's movement from its retained history, which a
//! phone keeps only for the markets it charts. So a worker samples every market's price from
//! the market list as well, and a market with no retained history is ranked from those samples:
//! the high and the low over the interval, as the Screener measures it.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use moon_core::market::{MarketDataSource, MarketStats, ScreenerRow};
use moon_core::session::CoreId;
use serde::{Deserialize, Serialize};

use crate::chart;

/// Samples are folded into buckets this long.
const BUCKET_MS: i64 = 10_000;
/// The longest interval ranked from samples; a day comes from the market list.
const KEEP_MS: i64 = 3_600_000 + BUCKET_MS;
/// How often the worker samples while the picker is in use, and otherwise.
pub const SAMPLE_ACTIVE: Duration = Duration::from_secs(5);
pub const SAMPLE_IDLE: Duration = Duration::from_secs(20);
/// The picker counts as in use this long after its last ranking.
const ACTIVE_FOR: Duration = Duration::from_secs(180);
/// How often each server's markets table is asked for while the picker is in use, and
/// otherwise: the volumes and deltas a core calculates for every market it lists.
const STATS_ACTIVE: Duration = Duration::from_secs(4);
const STATS_IDLE: Duration = Duration::from_secs(60);
/// A ranking asked for longer than this after the last sample samples again first, so a tap on
/// an interval answers with the present rather than with the worker's last pass.
const RESAMPLE_AFTER: Duration = Duration::from_secs(3);

/// What a ranking sorts by.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RankBy {
    /// The price's range over the interval, as a percentage of its low.
    Volatility,
    /// Traded over the interval, in the quote currency.
    Volume,
}

/// The interval movement is measured over.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
pub enum RankInterval {
    #[serde(rename = "1m")]
    M1,
    #[serde(rename = "5m")]
    M5,
    #[serde(rename = "15m")]
    M15,
    #[serde(rename = "1h")]
    H1,
    #[serde(rename = "1d")]
    D1,
}

impl RankInterval {
    fn millis(self) -> i64 {
        match self {
            Self::M1 => 60_000,
            Self::M5 => 300_000,
            Self::M15 => 900_000,
            Self::H1 => 3_600_000,
            Self::D1 => 86_400_000,
        }
    }

    /// The Screener's own figure for this interval, where the market has retained history.
    fn screener(self, row: &ScreenerRow) -> f64 {
        match self {
            Self::M1 => row.d_1m,
            Self::M5 => 0.0,
            Self::M15 => row.d_15m,
            Self::H1 => row.d_1h,
            Self::D1 => row.d_24h,
        }
    }

    /// The core's own delta for this interval from its markets table, unsigned.
    fn stats_delta(self, stats: &MarketStats) -> Option<f64> {
        let delta = match self {
            Self::M1 => stats.delta_1m,
            Self::M5 => return None,
            Self::M15 => stats.delta_15m,
            Self::H1 => stats.delta_1h,
            Self::D1 => stats.delta_24h,
        };
        delta.is_finite().then(|| delta.abs())
    }
}

/// What a market traded over `interval`, in the quote currency: the core's markets table first,
/// the market's retained history next. Fifteen minutes, which neither states, adds up the
/// five-minute volumes sampled five and ten minutes ago, or is a quarter of the hour's.
fn interval_volume(
    interval: RankInterval,
    row: &ScreenerRow,
    stats: Option<&MarketStats>,
    series: Option<&Series>,
    now_ms: i64,
) -> Option<f64> {
    let known = |value: f64| (value.is_finite() && value > 0.0).then_some(value);
    let figure = match (interval, stats) {
        (RankInterval::M1, Some(s)) => Some(f64::from(s.volume_1m)),
        (RankInterval::M1, None) => known(row.vol_1m),
        (RankInterval::M5, Some(s)) => Some(f64::from(s.volume_5m)),
        (RankInterval::M5, None) => known(row.vol_5m),
        (RankInterval::M15, _) => series.and_then(|s| s.volume_15m(now_ms)).or_else(|| {
            stats
                .map(|s| s.volume_1h)
                .or_else(|| known(row.vol_1h))
                .map(|hour| hour / 4.0)
        }),
        (RankInterval::H1, Some(s)) => Some(s.volume_1h),
        (RankInterval::H1, None) => known(row.vol_1h),
        (RankInterval::D1, Some(s)) => Some(s.volume_24h),
        (RankInterval::D1, None) => Some(row.vol_24h),
    };
    figure.filter(|v| v.is_finite() && *v >= 0.0)
}

/// What a market traded over the last hour in dollars: a stablecoin quote counts as dollars,
/// another quote (BTC, ETH, BNB…) at its own USDT market's price in the same list; `None` while
/// the hour or the quote's price is unknown.
fn hour_volume_usdt(rows: &[ScreenerRow], row: &ScreenerRow, stats: Option<&MarketStats>) -> Option<f64> {
    let hour = stats
        .map(|s| s.volume_1h)
        .or_else(|| (row.vol_1h.is_finite() && row.vol_1h > 0.0).then_some(row.vol_1h))
        .filter(|v| v.is_finite() && *v >= 0.0)?;
    let quote = row
        .market
        .strip_prefix(row.coin.as_str())
        .unwrap_or("")
        .trim_start_matches(['/', '-', '_'])
        .to_ascii_uppercase();
    let rate = match quote.as_str() {
        "" | "USDT" | "USDC" | "FDUSD" | "USD" | "BUSD" | "TUSD" | "USDE" | "DAI" | "USD1" | "USDS" => 1.0,
        other => {
            let pair = format!("{other}USDT");
            rows.iter().find(|r| r.market == pair).map(|r| r.ask).filter(|p| p.is_finite() && *p > 0.0)?
        }
    };
    Some(hour * rate)
}

/// One ranked market.
#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct RankRow {
    /// The core a chart opened from this row belongs to: the first of the asked cores that
    /// reads this exchange.
    pub core: CoreId,
    pub market: String,
    pub coin: String,
    /// High over low over the interval, percent; `None` while nothing is known yet.
    pub range_pct: Option<f64>,
    /// Signed change over the interval, percent, from the samples.
    pub change_pct: Option<f64>,
    /// Traded over the last day, in the quote currency.
    pub vol_24h: f64,
    /// Traded over the interval, in the quote currency; `None` while nothing is known yet.
    pub vol: Option<f64>,
    /// Traded over the last hour in dollars (the picker's «Ликвидность»); `None` while unknown.
    pub vol_1h_usdt: Option<f64>,
}

#[derive(Clone, Copy)]
struct Bucket {
    t_ms: i64,
    low: f32,
    high: f32,
    first: f32,
    last: f32,
    /// The market's five-minute volume as last read in this bucket; negative when unknown.
    vol5: f32,
}

/// The sampled prices of one market.
#[derive(Default)]
struct Series {
    buckets: VecDeque<Bucket>,
}

impl Series {
    fn push(&mut self, t_ms: i64, price: f32, vol5: Option<f32>) {
        let start = t_ms - t_ms.rem_euclid(BUCKET_MS);
        let vol5 = vol5.filter(|v| v.is_finite() && *v >= 0.0).unwrap_or(-1.0);
        match self.buckets.back_mut() {
            Some(bucket) if bucket.t_ms == start => {
                bucket.low = bucket.low.min(price);
                bucket.high = bucket.high.max(price);
                bucket.last = price;
                if vol5 >= 0.0 {
                    bucket.vol5 = vol5;
                }
            }
            _ => self.buckets.push_back(Bucket {
                t_ms: start,
                low: price,
                high: price,
                first: price,
                last: price,
                vol5,
            }),
        }
        while self.buckets.front().is_some_and(|b| b.t_ms < t_ms - KEEP_MS) {
            self.buckets.pop_front();
        }
    }

    /// Range and change over the last `span_ms`, when the samples reach back at least half
    /// of it (or a minute's worth for the shortest).
    fn measure(&self, now_ms: i64, span_ms: i64) -> Option<(f64, f64)> {
        let from = now_ms - span_ms;
        let mut low = f32::MAX;
        let mut high = f32::MIN;
        let mut first = None;
        let mut oldest = i64::MAX;
        let mut last = None;
        for bucket in self.buckets.iter().filter(|b| b.t_ms + BUCKET_MS > from) {
            low = low.min(bucket.low);
            high = high.max(bucket.high);
            first.get_or_insert(bucket.first);
            oldest = oldest.min(bucket.t_ms);
            last = Some(bucket.last);
        }
        let (first, last) = (first?, last?);
        if now_ms - oldest < (span_ms / 2).min(60_000).max(BUCKET_MS) || !(low > 0.0) {
            return None;
        }
        let range = f64::from((high - low) / low) * 100.0;
        let change = f64::from((last - first) / first) * 100.0;
        Some((range, change))
    }

    /// Fifteen minutes' volume as three five-minute readings: the newest, and those taken five
    /// and ten minutes before it, each within a bucket of its moment.
    fn volume_15m(&self, now_ms: i64) -> Option<f64> {
        let reading = |at: i64| -> Option<f64> {
            self.buckets
                .iter()
                .rev()
                .filter(|b| b.vol5 >= 0.0 && (b.t_ms - at).abs() <= 2 * BUCKET_MS)
                .min_by_key(|b| (b.t_ms - at).abs())
                .map(|b| f64::from(b.vol5))
        };
        let newest = self.buckets.iter().rev().find(|b| b.vol5 >= 0.0)?;
        if now_ms - newest.t_ms > 3 * BUCKET_MS {
            return None;
        }
        let at = newest.t_ms;
        Some(f64::from(newest.vol5) + reading(at - 300_000)? + reading(at - 600_000)?)
    }
}

/// One exchange's market list as last read, its markets' samples, and its server's markets
/// table.
#[derive(Default)]
struct Provider {
    rows: Vec<ScreenerRow>,
    series: HashMap<String, Series>,
    stats: HashMap<String, MarketStats>,
    stats_asked: Option<Instant>,
    sampled: Option<Instant>,
}

/// The samples of every exchange the configured cores read.
#[derive(Default)]
pub struct Ranker {
    providers: Mutex<HashMap<CoreId, Provider>>,
    asked: Mutex<Option<Instant>>,
}

impl Ranker {
    /// Whether the picker was used lately.
    fn active(&self) -> bool {
        let asked = *self.asked.lock().unwrap_or_else(|e| e.into_inner());
        asked.is_some_and(|at| at.elapsed() < ACTIVE_FOR)
    }

    /// How long the worker should wait before its next sample.
    pub fn pause(&self) -> Duration {
        if self.active() {
            SAMPLE_ACTIVE
        } else {
            SAMPLE_IDLE
        }
    }

    /// Read the market list of each provider behind `cores` and fold its prices in.
    pub fn sample(&self, source: &MarketDataSource, cores: &[CoreId]) {
        let now_ms = moon_core::util::now_unix_ms_i64();
        let mut seen: HashSet<CoreId> = HashSet::new();
        for core in cores {
            let Some(provider) = source.provider_of(*core) else { continue };
            if !seen.insert(provider) {
                continue;
            }
            // Read outside the lock: it walks the whole market list.
            let rows = source.screener_market_rows(provider);
            if rows.is_empty() {
                continue;
            }
            let stats: HashMap<String, MarketStats> = source.market_stats(provider).into_iter().collect();
            let stats_every = if self.active() { STATS_ACTIVE } else { STATS_IDLE };
            let mut providers = self.providers.lock().unwrap_or_else(|e| e.into_inner());
            let book = providers.entry(provider).or_default();
            let ask_stats = book.stats_asked.is_none_or(|at| at.elapsed() >= stats_every);
            for row in &rows {
                let price = row.ask as f32;
                if price.is_finite() && price > 0.0 {
                    let vol5 = match stats.get(&row.market) {
                        Some(s) => Some(s.volume_5m),
                        None => (row.vol_5m > 0.0).then_some(row.vol_5m as f32),
                    };
                    book.series.entry(row.market.clone()).or_default().push(now_ms, price, vol5);
                }
            }
            let listed: HashSet<&str> = rows.iter().map(|r| r.market.as_str()).collect();
            book.series.retain(|market, _| listed.contains(market.as_str()));
            book.rows = rows;
            if !stats.is_empty() {
                book.stats = stats;
            }
            book.sampled = Some(Instant::now());
            if ask_stats {
                book.stats_asked = Some(Instant::now());
            }
            drop(providers);
            if ask_stats {
                source.refresh_market_stats(provider);
            }
        }
        let mut providers = self.providers.lock().unwrap_or_else(|e| e.into_inner());
        providers.retain(|provider, _| seen.contains(provider));
    }

    /// The markets of `cores`' exchanges ranked by `by` over `interval`, the strongest first.
    pub fn rank(
        &self,
        source: Option<&MarketDataSource>,
        cores: &[CoreId],
        synthetic: &HashSet<CoreId>,
        by: RankBy,
        interval: RankInterval,
        limit: usize,
    ) -> Vec<RankRow> {
        *self.asked.lock().unwrap_or_else(|e| e.into_inner()) = Some(Instant::now());
        let now_ms = moon_core::util::now_unix_ms_i64();
        let mut rows: Vec<RankRow> = Vec::new();
        let mut seen: HashSet<CoreId> = HashSet::new();
        let mut demo_done = false;
        for core in cores {
            if synthetic.contains(core) {
                if !demo_done {
                    demo_done = true;
                    rows.extend(demo_rows(*core, interval, now_ms));
                }
                continue;
            }
            let Some(source) = source else { continue };
            let Some(provider) = source.provider_of(*core) else { continue };
            if !seen.insert(provider) {
                continue;
            }
            let fresh = {
                let providers = self.providers.lock().unwrap_or_else(|e| e.into_inner());
                providers.get(&provider).is_some_and(|p| {
                    !p.rows.is_empty() && p.sampled.is_some_and(|at| at.elapsed() < RESAMPLE_AFTER)
                })
            };
            if !fresh {
                // A provider the worker has not read yet, or read a while ago.
                self.sample(source, &[*core]);
            }
            let providers = self.providers.lock().unwrap_or_else(|e| e.into_inner());
            let Some(book) = providers.get(&provider) else { continue };
            // A market nobody traded today is noise in either ranking; it comes back on its own
            // once it trades.
            for row in book.rows.iter().filter(|row| row.vol_24h > 0.0) {
                let series = book.series.get(&row.market);
                let stats = book.stats.get(&row.market);
                let sampled = series.and_then(|series| series.measure(now_ms, interval.millis()));
                rows.push(RankRow {
                    core: *core,
                    market: row.market.clone(),
                    coin: row.coin.clone(),
                    range_pct: interval_range(interval, row, stats, sampled),
                    change_pct: sampled.map(|(_, change)| change),
                    vol_24h: stats.map_or(row.vol_24h, |s| s.volume_24h),
                    vol: interval_volume(interval, row, stats, series, now_ms),
                    vol_1h_usdt: hour_volume_usdt(&book.rows, row, stats),
                });
            }
            drop(providers);
            // An hour or a day the samples do not reach yet: the sign from the market list.
            if matches!(interval, RankInterval::H1 | RankInterval::D1) {
                for row in rows.iter_mut().filter(|r| r.core == *core && r.change_pct.is_none()) {
                    row.change_pct = ticker_change(source, *core, &row.market, interval);
                }
            }
        }
        let key = |row: &RankRow| match by {
            RankBy::Volatility => row.range_pct.unwrap_or(-1.0),
            RankBy::Volume => row.vol.unwrap_or(-1.0),
        };
        rows.sort_by(|a, b| key(b).total_cmp(&key(a)));
        rows.truncate(limit);
        rows
    }

    /// What is known of one market over `interval`: for the picker's search results and recent
    /// markets, refreshed while they are shown.
    pub fn figures(
        &self,
        source: Option<&MarketDataSource>,
        synthetic: bool,
        core: CoreId,
        market: &str,
        interval: RankInterval,
    ) -> Figures {
        let now_ms = moon_core::util::now_unix_ms_i64();
        if synthetic {
            return demo_figures(market, interval, now_ms);
        }
        let Some(source) = source else { return Figures::default() };
        let Some(provider) = source.provider_of(core) else { return Figures::default() };
        let providers = self.providers.lock().unwrap_or_else(|e| e.into_inner());
        let Some(book) = providers.get(&provider) else { return Figures::default() };
        let Some(row) = book.rows.iter().find(|r| r.market == market) else {
            return Figures::default();
        };
        let series = book.series.get(market);
        let stats = book.stats.get(market);
        let sampled = series.and_then(|s| s.measure(now_ms, interval.millis()));
        let range_pct = interval_range(interval, row, stats, sampled);
        let vol = interval_volume(interval, row, stats, series, now_ms);
        let vol_24h = stats.map_or(row.vol_24h, |s| s.volume_24h);
        let vol_1h_usdt = hour_volume_usdt(&book.rows, row, stats);
        // The ticker read below takes the source's own locks: not under this one.
        drop(providers);
        Figures {
            range_pct,
            change_pct: sampled
                .map(|(_, change)| change)
                .or_else(|| ticker_change(source, core, market, interval)),
            vol_24h: Some(vol_24h),
            vol,
            vol_1h_usdt,
        }
    }

    /// How far one market's price ranged over three hours, an hour and fifteen minutes, percent:
    /// for the header of the chart's detect panel. The market's retained history first, as the
    /// Screener measures it; the core's markets table next; the samples last.
    pub fn volatility(
        &self,
        source: Option<&MarketDataSource>,
        synthetic: bool,
        core: CoreId,
        market: &str,
    ) -> [Option<f64>; 3] {
        let now_ms = moon_core::util::now_unix_ms_i64();
        if synthetic {
            return [10_800_000, 3_600_000, 900_000].map(|span| demo_range(market, span, now_ms));
        }
        let Some(source) = source else { return [None; 3] };
        let Some(provider) = source.provider_of(core) else { return [None; 3] };
        let providers = self.providers.lock().unwrap_or_else(|e| e.into_inner());
        let Some(book) = providers.get(&provider) else { return [None; 3] };
        let row = book.rows.iter().find(|r| r.market == market);
        let stats = book.stats.get(market);
        let series = book.series.get(market);
        let pick = |screener: Option<f64>, delta: Option<f64>, span_ms: Option<i64>| -> Option<f64> {
            screener
                .filter(|v| v.is_finite() && *v > 0.0)
                .or(delta.filter(|v| v.is_finite()).map(f64::abs))
                .or_else(|| span_ms.and_then(|span| series?.measure(now_ms, span)).map(|(range, _)| range))
        };
        [
            pick(row.map(|r| r.d_3h), stats.map(|s| s.delta_3h), None),
            pick(row.map(|r| r.d_1h), stats.map(|s| s.delta_1h), Some(3_600_000)),
            pick(row.map(|r| r.d_15m), stats.map(|s| s.delta_15m), Some(900_000)),
        ]
    }
}

/// How far a market's price ranged over `interval`, percent: the core's markets table where it
/// states this interval, so every market of a ranking is measured alike; the Screener's figure
/// from retained history next; the samples last.
fn interval_range(
    interval: RankInterval,
    row: &ScreenerRow,
    stats: Option<&MarketStats>,
    sampled: Option<(f64, f64)>,
) -> Option<f64> {
    if let Some(delta) = stats.and_then(|s| interval.stats_delta(s)) {
        return Some(delta);
    }
    let screener = interval.screener(row);
    if screener > 0.0 && screener.is_finite() {
        Some(screener)
    } else {
        sampled.map(|(range, _)| range)
    }
}

/// The demo market's range over `span_ms`, percent.
fn demo_range(market: &str, span_ms: i64, now_ms: i64) -> Option<f64> {
    let prices: Vec<f32> = (0..=60).map(|i| chart::demo_price(market, now_ms - span_ms + span_ms * i / 60)).collect();
    let low = prices.iter().copied().fold(f32::MAX, f32::min);
    let high = prices.iter().copied().fold(f32::MIN, f32::max);
    (low > 0.0).then(|| f64::from((high - low) / low) * 100.0)
}

/// One market's figures over an interval.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Figures {
    pub range_pct: Option<f64>,
    pub change_pct: Option<f64>,
    pub vol_24h: Option<f64>,
    /// Traded over the interval.
    pub vol: Option<f64>,
    /// Traded over the last hour, in dollars.
    pub vol_1h_usdt: Option<f64>,
}

/// The signed change from the market list, where the samples do not reach: an hour and a day.
fn ticker_change(source: &MarketDataSource, core: CoreId, market: &str, interval: RankInterval) -> Option<f64> {
    let ticker = source.market_ticker(core, market)?;
    let change = match interval {
        RankInterval::H1 => ticker.delta_1h_pct,
        RankInterval::D1 => ticker.delta_24h_pct,
        _ => return None,
    };
    change.is_finite().then_some(change)
}

/// The demo market's figures, measured on its invented prices.
fn demo_figures(market: &str, interval: RankInterval, now_ms: i64) -> Figures {
    let span = interval.millis();
    let steps = 60;
    let prices: Vec<f32> = (0..=steps).map(|i| chart::demo_price(market, now_ms - span + span * i / steps)).collect();
    let low = prices.iter().copied().fold(f32::MAX, f32::min);
    let high = prices.iter().copied().fold(f32::MIN, f32::max);
    let (first, last) = (prices[0], prices[prices.len() - 1]);
    Figures {
        range_pct: (low > 0.0).then(|| f64::from((high - low) / low) * 100.0),
        change_pct: (first > 0.0).then(|| f64::from((last - first) / first) * 100.0),
        vol_24h: Some(2.0e8),
        vol: Some(2.0e8 * span as f64 / 86_400_000.0),
        vol_1h_usdt: Some(2.0e8 / 24.0),
    }
}

/// The demo core's markets, measured on its invented prices.
fn demo_rows(core: CoreId, interval: RankInterval, now_ms: i64) -> Vec<RankRow> {
    chart::DEMO_MARKETS
        .iter()
        .enumerate()
        .map(|(at, market)| {
            let span = interval.millis();
            let steps = 60;
            let prices: Vec<f32> = (0..=steps)
                .map(|i| chart::demo_price(market, now_ms - span + span * i / steps))
                .collect();
            let low = prices.iter().copied().fold(f32::MAX, f32::min);
            let high = prices.iter().copied().fold(f32::MIN, f32::max);
            let (first, last) = (prices[0], prices[prices.len() - 1]);
            RankRow {
                core,
                market: market.to_string(),
                coin: market.trim_end_matches("USDT").to_string(),
                range_pct: Some(f64::from((high - low) / low) * 100.0),
                change_pct: Some(f64::from((last - first) / first) * 100.0),
                vol_24h: 4.0e8 / (at as f64 + 1.0),
                vol: Some(4.0e8 / (at as f64 + 1.0) * span as f64 / 86_400_000.0),
                vol_1h_usdt: Some(4.0e8 / (at as f64 + 1.0) / 24.0),
            }
        })
        .collect()
}

/// The sampling worker: runs until `stop` says so.
pub fn run(
    ranker: Arc<Ranker>,
    source: impl Fn() -> Option<MarketDataSource>,
    cores: impl Fn() -> Vec<CoreId>,
    stop: impl Fn() -> bool,
) {
    while !stop() {
        if let Some(source) = source() {
            let cores = cores();
            if !cores.is_empty() {
                ranker.sample(&source, &cores);
            }
        }
        let until = Instant::now() + ranker.pause();
        while Instant::now() < until && !stop() {
            std::thread::sleep(Duration::from_millis(250));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn samples_measure_the_range_and_the_change() {
        let mut series = Series::default();
        let start = 1_000_000_000;
        for i in 0..=60 {
            let price = if i == 30 { 110.0 } else { 100.0 + i as f32 * 0.1 };
            series.push(start + i * 5_000, price, None);
        }
        let now = start + 300_000;
        let (range, change) = series.measure(now, 300_000).unwrap();
        assert!((range - 10.0).abs() < 0.01, "{range}");
        assert!((change - 6.0).abs() < 0.05, "{change}");
        // Ten seconds of samples say nothing about an hour.
        let mut young = Series::default();
        young.push(start, 100.0, None);
        young.push(start + 5_000, 101.0, None);
        assert!(young.measure(start + 5_000, 3_600_000).is_none());
    }

    #[test]
    fn old_samples_are_dropped() {
        let mut series = Series::default();
        series.push(0, 1.0, None);
        series.push(KEEP_MS + BUCKET_MS * 2, 2.0, None);
        assert_eq!(series.buckets.len(), 1);
    }

    #[test]
    fn the_demo_core_ranks_its_markets() {
        let ranker = Ranker::default();
        let demo: HashSet<CoreId> = [7].into_iter().collect();
        let rows = ranker.rank(None, &[7], &demo, RankBy::Volume, RankInterval::M5, 10);
        assert_eq!(rows.len(), chart::DEMO_MARKETS.len());
        assert!(rows.windows(2).all(|w| w[0].vol >= w[1].vol));
        let rows = ranker.rank(None, &[7], &demo, RankBy::Volatility, RankInterval::H1, 2);
        assert_eq!(rows.len(), 2);
        assert!(rows[0].range_pct >= rows[1].range_pct);
    }

    #[test]
    fn fifteen_minutes_adds_up_three_five_minute_readings() {
        let mut series = Series::default();
        let start = 1_000_000_000_000;
        for i in 0..=66 {
            series.push(start + i * BUCKET_MS, 1.0, Some(10.0 + i as f32));
        }
        let now = start + 66 * BUCKET_MS;
        // Readings 66, 36 and 6 buckets in.
        let sum = series.volume_15m(now).unwrap();
        assert!((sum - (76.0 + 46.0 + 16.0)).abs() < 1e-6, "{sum}");
        // Ten minutes of samples do not reach back far enough.
        let mut young = Series::default();
        young.push(start, 1.0, Some(5.0));
        assert!(young.volume_15m(start).is_none());
    }
}
