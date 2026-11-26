//! The engine: one owner thread holding the terminal's `SessionManager`.
//!
//! Everything the session offers is single-owner — the desktop drives it from its UI thread, the
//! station from its main loop. Here a dedicated thread plays that owner: it drains the feeds,
//! executes commands sent to it, and publishes the state as a JSON snapshot. Clients never touch
//! the session; they read the latest snapshot and queue commands.

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender, SyncSender};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use moon_core::config::{AppConfig, BadgesConfig, GroupExitSettings};
use moon_core::market::{
    CandleReadParams, ChartHistoryBuffers, ChartHistoryCursor, MarketDataMode, MarketDataSource,
    MarketQuantityUnit, VolumeAt, VolumeSpan,
};
use moon_core::session::order_lines::LineKind;
use moon_core::session::{CoreId, RunSwitch, SessionManager};
use moon_core::venue::MarketKind;
use serde_json::{json, Value};

use crate::api::{ok, ApiError, ApiResult, Call, ChartRequest, MarketData, RunSwitchName};
use crate::cards::{self, CardDto, CardKey, Spark};
use crate::chart::{
    self, BookLevel, Candle, Captions, ChartData, LineRole, OrderLine, PriceWindow, Scale, TickPoint,
    View,
};
use crate::rank::{self, RankBy, RankInterval, RankRow, Ranker};
use moon_core::config::{MoveKind, MoveSide};
use moon_core::feed::OrderLinePriceKind;
use crate::reports::TradeMark;
use crate::snapshot;
use crate::strategy;

/// Longest the owner sleeps with nothing to do. Feed wakes and commands cut it short.
const IDLE_PARK: Duration = Duration::from_millis(250);
/// Snapshots are rebuilt at most this often, however fast the feeds talk.
const PUBLISH_EVERY: Duration = Duration::from_millis(250);
/// A snapshot is rebuilt at least this often while anything is connected, so a state that
/// changes by the clock alone (a timed-out stage, a stale flag) still reaches the client.
const PUBLISH_IDLE: Duration = Duration::from_secs(2);
/// Provider election and its wall-clock linger are re-run this often while market data is on,
/// as the desktop does.
const OPEN_SYNC_EVERY: Duration = Duration::from_secs(1);
/// The owner's sleep while a chart is open: the order book is pulled once per pass.
const CHART_PARK: Duration = Duration::from_millis(100);
/// A chart nobody asked a frame of for this long is closed. The session then lingers a few
/// seconds of its own before it drops the market, so a chart reopened at once is still warm.
const CHART_KEEP: Duration = Duration::from_secs(3);
/// The most background tabs kept streaming.
const MAX_KEPT: usize = 12;
/// Newest trades copied for one frame.
const MAX_TICKS: usize = 60_000;
/// The trades a chart holds apart from the ring: how far back, how many, for how many charts,
/// and how long a chart not drawn keeps them.
const HELD_TICKS_BACK_MS: i64 = 24 * 3_600_000;
const HELD_TICKS_MAX: usize = 250_000;
const HELD_TICKS_CHARTS: usize = 8;
const HELD_TICKS_IDLE: Duration = Duration::from_secs(600);
/// The narrowest and the widest window a frame may show.
const MIN_SPAN_MS: i64 = 5_000;
const MAX_SPAN_MS: i64 = 12 * 3_600_000;
/// A detect is printed over its market's chart for this long after it fired.
const DETECT_NOTE_MS: f64 = 600_000.0;
/// Five-minute bars a detect card's thumbnail is asked with; the line itself is the last day.
const SPARK_BARS: usize = 24;
/// A detect card whose market had no history yet is read again this often, this many times.
const SPARK_RETRY: Duration = Duration::from_secs(3);
const SPARK_TRIES: u8 = 6;
/// The candles a chart shows before its first trade: one minute each, as far back as the
/// widest window reaches.
const CANDLE_TF_MS: i64 = 60_000;
const CANDLE_BACK_MS: i64 = MAX_SPAN_MS + 3_600_000;
/// How often a chart's candles are read again, and while it has none yet.
const CANDLE_READ_EVERY: Duration = Duration::from_millis(1000);
const CANDLE_READ_EMPTY: Duration = Duration::from_millis(300);
/// The demo market's trades start this long before the present, as if the app had connected
/// then; candles stand in before.
const DEMO_TRADES_MS: i64 = 15 * 60_000;

/// One chart: a market as one core sees it.
type ChartKey = (CoreId, String);
/// How long a caller waits for the owner to answer a command.
const CALL_TIMEOUT: Duration = Duration::from_secs(5);

/// A command on its way to the owner, with where to answer.
struct Job {
    call: Call,
    reply: SyncSender<ApiResult<Value>>,
}

/// What the owner publishes and the callers read.
struct Shared {
    /// Notifications and detect sounds, decided with every published snapshot.
    alerts: Mutex<crate::alerts::Alerts>,
    /// Each core's name, from the latest snapshot: the report file names its cores.
    core_names: Mutex<Arc<HashMap<CoreId, String>>>,
    /// The latest snapshot body: a JSON object without the envelope fields.
    body: Mutex<Arc<str>>,
    /// Bumped whenever `body` changes. 0 means nothing was published yet.
    rev: AtomicU64,
    stop: AtomicBool,
    mode: MarketData,
    /// Every configured core.
    cores: HashSet<CoreId>,
    /// Cores with no connection behind them: their chart is the bridge's own invented market.
    synthetic: HashSet<CoreId>,
    /// The session's market reader, set once the owner has started the session. It is the one
    /// part of the session other threads may read.
    market: OnceLock<MarketDataSource>,
    /// Charts drawn lately and when each was last asked for. The owner keeps exactly these
    /// markets open.
    wanted: Mutex<HashMap<ChartKey, Instant>>,
    /// Charts kept streaming without being drawn: the app's background tabs.
    kept: Mutex<Vec<ChartKey>>,
    /// The cores' keys as a station takes them (Settings → Станция), refreshed when a core is
    /// added; they never leave the bridge but for the station.
    station_cores: Mutex<Vec<crate::station::StationCore>>,
    /// Own orders as chart lines; replaced with every published snapshot.
    lines: Mutex<Arc<HashMap<ChartKey, Vec<OrderLine>>>>,
    /// What kind of market each core trades (`spot`, `futures`, `quarterly`), as its exchange
    /// says; replaced with every snapshot.
    core_kinds: Mutex<Arc<HashMap<CoreId, &'static str>>>,
    /// Each core's ping and its exchange's, for the chart's corner; replaced with every snapshot.
    /// Each core's ping, its exchange's latency, and the latency its exchange counts as slow.
    pings: Mutex<Arc<HashMap<CoreId, (Option<u32>, Option<u32>, u32)>>>,
    /// When `pings` last changed: they are shown every [`PING_EVERY`], not with every snapshot.
    pings_at: Mutex<Option<Instant>>,
    /// The strategy name of the newest detect on each market; replaced with every snapshot.
    detect_notes: Mutex<Arc<HashMap<ChartKey, String>>>,
    /// The price window each chart's last frame used and when: the scale eases over time.
    windows: Mutex<HashMap<ChartKey, (PriceWindow, Instant)>>,
    /// Leverage figures of each chart's market, re-read every few seconds.
    figures: Mutex<HashMap<ChartKey, (Instant, Value)>>,
    /// Thumbnails and deltas of the detect cards.
    sparks: Mutex<HashMap<CardKey, Spark>>,
    /// Set by the thumbnail worker: the cards have something new to publish.
    cards_dirty: AtomicBool,
    /// Every market's sampled prices, for the picker's ranking.
    ranker: Arc<Ranker>,
    /// The cores connected right now, from the latest snapshot.
    online: Mutex<Arc<Vec<CoreId>>>,
    /// Each chart's candle history and the cursor that keeps it current.
    history: Mutex<HashMap<ChartKey, CandleState>>,
    /// Each chart's mark price line and liquidations, drained from the core's history rings.
    extras: Mutex<HashMap<ChartKey, ExtraState>>,
    /// The time the chart history reads count from.
    epoch_ms: f64,
    /// Orders the app remembers placing in earlier sessions; the owner takes them at start.
    mine_seed: Mutex<HashSet<(CoreId, u64)>>,
    /// Current strategy names by core and strategy id, for the report; replaced with every
    /// snapshot.
    names: Mutex<Arc<HashMap<(u64, u64), String>>>,
    /// Each strategy's kind (Drops, Spread, MoonShot…) by core and strategy id, for the widget.
    kinds: Mutex<Arc<HashMap<(u64, u64), String>>>,
    /// Each core's quote currency as configured, for naming a report coin's market.
    quotes: HashMap<CoreId, String>,
    /// The open positions' entries as chart marks; replaced with every snapshot.
    entries: Mutex<Arc<HashMap<ChartKey, Vec<TradeMark>>>>,
    /// Each chart's closed trades from the report, with the report generation they were read
    /// at and when.
    trades: Mutex<HashMap<ChartKey, (u64, Instant, Arc<Vec<TradeMark>>)>>,
    /// The newest closed trade, the generation it was read at and when.
    last_deal: Mutex<(u64, Option<Instant>, Option<crate::reports::LastDeal>)>,
    /// Each core's learnt clock lead over this device and when it was last updated.
    leads: Mutex<HashMap<u64, (f64, Instant)>>,
    /// Charts whose closed trades are being re-read in the background right now.
    trades_reading: Mutex<HashSet<ChartKey>>,
    /// Each chart's session result — the profit of its trades closed since the session began,
    /// in USDT — by the session's start, with the report generation it was read at and when.
    sessions: Mutex<HashMap<(ChartKey, i64), (u64, Instant, Option<f64>)>>,
    /// Sessions being re-read in the background right now.
    sessions_reading: Mutex<HashSet<(ChartKey, i64)>>,
    /// The trades each chart has drawn, kept apart from the live ring: a ring that starts over
    /// (a resubscribe, a reconnect) or answers empty for a moment does not take them off.
    held_ticks: Mutex<HashMap<ChartKey, (Vec<TickPoint>, Instant)>>,
    /// The price each detect card's market had when the card appeared.
    detect_prices: Mutex<HashMap<CardKey, f64>>,
}

/// How often the chart's ping figures change.
const PING_EVERY: Duration = Duration::from_secs(5);

/// How fast the learnt clock lead eases back, ms per second: slow enough that the chart's right
/// edge never visibly slows, fast enough to follow a clock that was corrected.
const LEAD_EASE_PER_S: f64 = 15.0;

/// A chart's closed trades are re-read at least this often, and never more often than the gap.
const TRADES_REREAD: Duration = Duration::from_secs(60);
const TRADES_MIN_GAP: Duration = Duration::from_secs(3);

/// How far back the mark price line and the liquidations are kept.
const EXTRA_BACK_MS: i64 = 6 * 3_600_000;
/// How often they are drained.
const EXTRA_READ_EVERY: Duration = Duration::from_millis(500);

/// One chart's mark price points and liquidations, kept current by draining what is new.
#[derive(Default)]
struct ExtraState {
    cursor: ChartHistoryCursor,
    buffers: ChartHistoryBuffers,
    /// The mark price as the core streams it — the line Moonbot draws, a point every couple of
    /// seconds — from the moment the chart's market was subscribed.
    marks: Vec<(i64, f32)>,
    /// The same contract's mark price as the app reads it from the exchange: minute closes
    /// before the core's first point, and a point every couple of seconds while the core sends
    /// none.
    exchange: Vec<(i64, f32)>,
    liquidations: Vec<TickPoint>,
    read_at: Option<Instant>,
    started: bool,
}

impl ExtraState {
    /// The yellow line to draw: the futures market's mark price. The core's own points where it
    /// has them, as Moonbot draws them; the exchange's before the first of them, and after the
    /// last once the core has been quiet a while.
    fn mark_line(&self) -> Vec<(i64, f32)> {
        let (Some(first), Some(last)) = (self.marks.first(), self.marks.last()) else {
            return self.exchange.clone();
        };
        let (first, last) = (first.0, last.0);
        let mut line: Vec<(i64, f32)> = self.exchange.iter().copied().filter(|(t, _)| *t < first).collect();
        line.extend(self.marks.iter().copied());
        line.extend(self.exchange.iter().copied().filter(|(t, _)| *t > last + MARK_QUIET_MS));
        line
    }
}

/// The most exchange points one chart keeps: six hours of one every two seconds.
const SPOT_POINTS_MAX: usize = 3 * 3600 + 600;
/// The core counts as sending the mark price while its newest point is younger than this; the
/// app reads the exchange itself otherwise.
const MARK_QUIET_MS: i64 = 8_000;

/// One chart's candles as last read, with the cursor that reads only what changed.
#[derive(Default)]
struct CandleState {
    cursor: ChartHistoryCursor,
    buffers: ChartHistoryBuffers,
    candles: Vec<Candle>,
    /// The revision of `candles`; `u64::MAX` asks for the whole list.
    shipped: u64,
    read_at: Option<Instant>,
    started: bool,
}

/// A running engine. Dropping it stops the owner thread and every core connection.
pub struct Engine {
    shared: Arc<Shared>,
    jobs: Sender<Job>,
    owner: Option<JoinHandle<()>>,
}

impl Engine {
    /// Start the owner thread and the sessions of every active core in `config`.
    pub fn start(config: AppConfig, market_data: MarketData, mine: HashSet<(CoreId, u64)>) -> ApiResult<Self> {
        let shared = Arc::new(Shared {
            alerts: Mutex::new(crate::alerts::Alerts::default()),
            core_names: Mutex::new(Arc::new(HashMap::new())),
            body: Mutex::new(Arc::from("{}")),
            rev: AtomicU64::new(0),
            stop: AtomicBool::new(false),
            mode: market_data,
            cores: config.servers.iter().map(|s| s.id).collect(),
            synthetic: config
                .servers
                .iter()
                .filter(|s| s.synthetic)
                .map(|s| s.id)
                .collect(),
            market: OnceLock::new(),
            wanted: Mutex::new(HashMap::new()),
            kept: Mutex::new(Vec::new()),
            lines: Mutex::new(Arc::new(HashMap::new())),
            core_kinds: Mutex::new(Arc::new(HashMap::new())),
            pings: Mutex::new(Arc::new(HashMap::new())),
            pings_at: Mutex::new(None),
            detect_notes: Mutex::new(Arc::new(HashMap::new())),
            windows: Mutex::new(HashMap::new()),
            figures: Mutex::new(HashMap::new()),
            sparks: Mutex::new(HashMap::new()),
            cards_dirty: AtomicBool::new(false),
            ranker: Arc::new(Ranker::default()),
            online: Mutex::new(Arc::new(Vec::new())),
            mine_seed: Mutex::new(mine),
            history: Mutex::new(HashMap::new()),
            extras: Mutex::new(HashMap::new()),
            epoch_ms: moon_core::util::now_unix_ms_i64() as f64,
            names: Mutex::new(Arc::new(HashMap::new())),
            kinds: Mutex::new(Arc::new(HashMap::new())),
            quotes: config.servers.iter().map(|s| (s.id, s.market.clone())).collect(),
            entries: Mutex::new(Arc::new(HashMap::new())),
            trades: Mutex::new(HashMap::new()),
            last_deal: Mutex::new((u64::MAX, None, None)),
            leads: Mutex::new(HashMap::new()),
            trades_reading: Mutex::new(HashSet::new()),
            sessions: Mutex::new(HashMap::new()),
            sessions_reading: Mutex::new(HashSet::new()),
            held_ticks: Mutex::new(HashMap::new()),
            detect_prices: Mutex::new(HashMap::new()),
            station_cores: Mutex::new(station_cores_of(&config)),
        });
        let (jobs, job_rx) = mpsc::channel::<Job>();
        let owner_shared = Arc::clone(&shared);
        let owner = thread::Builder::new()
            .name("mm-engine".into())
            .spawn(move || run(config, market_data, job_rx, owner_shared))
            .map_err(|e| ApiError::new("internal", format!("engine thread not started: {e}")))?;
        if market_data != MarketData::None {
            let sampler = Arc::clone(&shared);
            let spawned = thread::Builder::new().name("mm-rank".into()).spawn(move || {
                rank::run(
                    Arc::clone(&sampler.ranker),
                    || sampler.market.get().cloned(),
                    || sampler.online.lock().unwrap_or_else(|e| e.into_inner()).to_vec(),
                    || sampler.stop.load(Ordering::Acquire),
                )
            });
            if let Err(e) = spawned {
                log::warn!("market ranking not started: {e}");
            }
        }
        Ok(Self {
            shared,
            jobs,
            owner: Some(owner),
        })
    }

    /// The latest snapshot in its envelope, or the short "unchanged" reply when the caller
    /// already holds revision `since`.
    pub fn snapshot(&self, since: Option<u64>) -> String {
        // Revision first, then the body: a publish between the two reads hands the caller a
        // body newer than the revision it is labelled with, which only costs one extra fetch.
        // The other order could label an old body with a new revision and hide a change.
        let rev = self.shared.rev.load(Ordering::Acquire);
        if since == Some(rev) {
            return format!(r#"{{"ok":true,"changed":false,"rev":{rev}}}"#);
        }
        let body = Arc::clone(&self.shared.body.lock().unwrap_or_else(|e| e.into_inner()));
        envelope(&body, rev)
    }

    /// The report of the period written to `path` (see [`Call::ReportFile`]). On the caller's
    /// thread: read page by page as the screen reads it, then built and written in one go.
    #[allow(clippy::too_many_arguments)]
    pub fn report_file(
        &self,
        from_ms: i64,
        to_ms: i64,
        cores: &[u64],
        narrow: &crate::reports::Narrow,
        format: crate::export::ExportFormat,
        path: &str,
        tz: &str,
    ) -> ApiResult<Value> {
        let mut trades = Vec::new();
        while trades.len() < crate::export::MAX_ROWS {
            let page = self.report(from_ms, to_ms, cores, trades.len(), crate::export::PAGE, narrow)?;
            let rows = crate::export::trades(&page);
            let more = page.get("has_more").and_then(Value::as_bool).unwrap_or(false);
            let empty = rows.is_empty();
            trades.extend(rows);
            if empty || !more {
                break;
            }
        }
        if trades.is_empty() {
            return Ok(ok(json!({ "rows": 0 })));
        }
        let names = Arc::clone(&self.shared.core_names.lock().unwrap_or_else(|e| e.into_inner()));
        let zone = crate::export::zone(tz);
        let bytes = match format {
            crate::export::ExportFormat::Csv => crate::export::csv(&trades, &names, zone),
            crate::export::ExportFormat::Xlsx => {
                crate::export::xlsx(&trades, &names, zone).map_err(|e| ApiError::new("internal", e))?
            }
        };
        std::fs::write(path, bytes).map_err(|e| ApiError::new("io", format!("{path}: {e}")))?;
        Ok(ok(json!({ "rows": trades.len() })))
    }

    /// The app's notification switches and server names. On the caller's thread.
    pub fn alerts_config(
        &self,
        settings: crate::alerts::AlertSettings,
        server_names: HashMap<String, String>,
    ) -> Value {
        self.shared.alerts.lock().unwrap_or_else(|e| e.into_inner()).configure(settings, server_names);
        ok(json!({}))
    }

    /// The notices and detect sounds decided since the last call. On the caller's thread.
    pub fn alerts(&self) -> Value {
        ok(self.shared.alerts.lock().unwrap_or_else(|e| e.into_inner()).take())
    }

    /// Run one command on the owner thread and wait for its answer.
    pub fn call(&self, call: Call) -> ApiResult<Value> {
        let (reply, answer) = mpsc::sync_channel(1);
        self.jobs
            .send(Job { call, reply })
            .map_err(|_| ApiError::new("closed", "the engine has stopped"))?;
        if let Some(owner) = &self.owner {
            owner.thread().unpark();
        }
        answer
            .recv_timeout(CALL_TIMEOUT)
            .map_err(|_| ApiError::new("timeout", "the engine did not answer in time"))?
    }

    /// The markets of `cores`' exchanges ranked for the picker. Read-only, on the caller's thread.
    pub fn rank(&self, cores: &[u64], by: RankBy, interval: RankInterval, limit: usize) -> Value {
        // A core with no connection has no market list; its provider would answer with the
        // library's sample rows.
        let online = Arc::clone(&self.shared.online.lock().unwrap_or_else(|e| e.into_inner()));
        let cores: Vec<CoreId> = cores
            .iter()
            .copied()
            .filter(|c| self.shared.synthetic.contains(c) || online.contains(c))
            .collect();
        let rows: Vec<RankRow> = self.shared.ranker.rank(
            self.shared.market.get(),
            &cores,
            &self.shared.synthetic,
            by,
            interval,
            limit.clamp(1, 300),
        );
        ok(json!({ "rows": rows }))
    }

    /// The cores a station is to take, by their ids in the order given; none named takes every
    /// core switched on.
    pub fn station_cores(&self, ids: &[u64]) -> Vec<crate::station::StationCore> {
        let all = self.shared.station_cores.lock().unwrap_or_else(|e| e.into_inner());
        if ids.is_empty() {
            return all.iter().filter(|c| c.active).cloned().collect();
        }
        ids.iter().filter_map(|id| all.iter().find(|c| c.id == *id).cloned()).collect()
    }

    /// Keep the given charts' markets streaming in the background. Read-only, on the caller's
    /// thread; the owner picks the list up on its next pass.
    pub fn keep(&self, charts: &[crate::api::ChartRef]) -> Value {
        let keys: Vec<ChartKey> = charts
            .iter()
            .filter(|c| self.shared.cores.contains(&c.core) && !c.market.trim().is_empty())
            .take(MAX_KEPT)
            .map(|c| (c.core, c.market.trim().to_string()))
            .collect();
        let count = keys.len();
        *self.shared.kept.lock().unwrap_or_else(|e| e.into_inner()) = keys;
        if let Some(owner) = &self.owner {
            owner.thread().unpark();
        }
        ok(json!({ "kept": count }))
    }

    /// The movement and volume of the given markets over `interval`, for the picker's lists.
    pub fn figures(&self, items: &[crate::api::ChartRef], interval: RankInterval) -> Value {
        let source = self.shared.market.get();
        let rows: Vec<Value> = items
            .iter()
            .take(200)
            .map(|item| {
                let synthetic = self.shared.synthetic.contains(&item.core);
                let figures = self.shared.ranker.figures(source, synthetic, item.core, &item.market, interval);
                json!({
                    "core": item.core,
                    "market": item.market,
                    "range_pct": figures.range_pct,
                    "change_pct": figures.change_pct,
                    "vol_24h": figures.vol_24h,
                    "vol": figures.vol,
                    "vol_1h_usdt": figures.vol_1h_usdt,
                })
            })
            .collect();
        ok(json!({ "rows": rows }))
    }

    /// Draw one chart frame into `pixels` on the caller's thread.
    ///
    /// Asking for a frame is also what keeps the chart's market open: the owner subscribes the
    /// markets that were drawn within [`CHART_KEEP`] and drops the rest.
    pub fn chart(&self, request: &ChartRequest, pixels: &mut [u8]) -> ApiResult<Value> {
        let (width, height) = (request.width, request.height);
        let bytes = u64::from(width) * u64::from(height) * 4;
        if !(64..=8192).contains(&width) || !(64..=8192).contains(&height) {
            return Err(ApiError::new("bad_request", "width and height must be 64..=8192"));
        }
        if pixels.len() as u64 != bytes {
            return Err(ApiError::new(
                "bad_request",
                format!("the buffer holds {} bytes, the frame needs {bytes}", pixels.len()),
            ));
        }
        let market = request.market.trim();
        if market.is_empty() {
            return Err(ApiError::new("bad_request", "no market"));
        }
        let core = request.core;
        if !self.shared.cores.contains(&core) {
            return Err(ApiError::new("not_found", format!("no core {core}")));
        }

        let now = moon_core::util::now_unix_ms_i64();
        let span = request.span_ms.clamp(MIN_SPAN_MS, MAX_SPAN_MS);
        let live = request.end_ms.is_none();
        let synthetic = self.shared.synthetic.contains(&core);
        let source = self.shared.market.get();
        // "The present" is this device's clock moved by how far the exchange's clock runs ahead
        // of it, so the newest trades do not fall off the right edge. The lead is learnt from the
        // trades and kept, easing back slowly: taking the newest trade's time itself made the
        // chart stand still between trades and leap when one came.
        let mut present = now;
        if let (true, false, Some(source)) = (live, synthetic, source) {
            let observed = source
                .latest_trade_ms(core, market)
                .map(|newest| newest - now)
                .filter(|lead| *lead > 0 && *lead < 600_000);
            present = now + self.clock_lead(core, observed);
        }
        // Following the present leaves a tenth of the window empty on the right, as the
        // terminal does, so the last trade is not drawn against the order book's edge.
        let to_ms = request.end_ms.unwrap_or(present + span / 10);
        let from_ms = to_ms - span;
        // The rolling volume at the window's left edge sums trades from just before it.
        let lead = chart::volume_interval_ms(span);
        let key: ChartKey = (core, market.to_string());
        let mut data = ChartData::default();
        let mut demo_book = Vec::new();

        if synthetic {
            let trades_from = (from_ms - lead).max(self.shared.epoch_ms as i64 - DEMO_TRADES_MS);
            let (ticks, book) = chart::demo_market(market, trades_from, to_ms, now);
            data.candles = chart::demo_candles(market, from_ms - CANDLE_TF_MS, trades_from, CANDLE_TF_MS);
            // The demo's mark price: a point every two seconds on the price curve, as a core
            // streams it, coarser over a wide window.
            let step = ((to_ms - from_ms) / 3_000).max(2_000);
            let first = (from_ms.max(now - EXTRA_BACK_MS) / step) * step;
            data.marks = (0..)
                .map(|i| first + i * step)
                .take_while(|t| *t <= now.min(to_ms))
                .map(|t| (t, chart::demo_price(market, t)))
                .filter(|_| request.mark_line)
                .collect();
            let price = chart::demo_price(market, now);
            let change = |ago_ms: i64| {
                let then = f64::from(chart::demo_price(market, now - ago_ms));
                (f64::from(price) - then) / then * 100.0
            };
            let minute = |buy: bool| -> f64 {
                ticks
                    .iter()
                    .filter(|t| t.buy == buy && t.t_ms > now - 60_000)
                    .map(|t| f64::from(t.price) * f64::from(t.qty))
                    .sum()
            };
            data.captions = Captions {
                delta_1h: Some(change(3_600_000)),
                delta_24h: Some(change(86_400_000)),
                buy_1m: (from_ms - lead <= now - 60_000).then(|| minute(true)),
                sell_1m: (from_ms - lead <= now - 60_000).then(|| minute(false)),
                price_tick: Some(0.01),
                ..Captions::default()
            };
            data.ticks = ticks;
            data.last_price = Some(price);
            demo_book = book;
        } else {
            if self.shared.mode == MarketData::None {
                return Err(ApiError::new(
                    "no_market_data",
                    "the engine was opened without market data",
                ));
            }
            let fresh = self
                .shared
                .wanted
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .insert(key.clone(), Instant::now())
                .is_none();
            if let (true, Some(owner)) = (fresh, &self.owner) {
                // A chart just opened: subscribe now rather than at the owner's next pass.
                owner.thread().unpark();
            }
            match source {
                None => data.note = Some("Запускаюсь…".to_string()),
                Some(source) => {
                    let found = source.live_core_ticks(core, market, from_ms - lead, to_ms, MAX_TICKS);
                    let fresh: Option<Vec<TickPoint>> = found.as_ref().map(|found| {
                        found
                            .ticks
                            .iter()
                            .map(|tick| TickPoint {
                                t_ms: tick.time_ms as i64,
                                price: tick.price,
                                qty: tick.qty,
                                buy: matches!(tick.side, moon_core::feed::Side::Buy),
                            })
                            .collect()
                    });
                    data.ticks = self.held_ticks(&key, fresh, from_ms - lead, to_ms, now);
                    if found.is_none() && data.ticks.is_empty() {
                        data.note = Some("Подключаю поток сделок…".to_string());
                    }
                    data.last_price = source.latest_price(core, market).ok();
                    data.candles = self.candles(&key, present, source);
                    let (marks, liquidations) = self.extras(&key, present, source);
                    // Settings → График: either one can be switched off.
                    if request.mark_line {
                        data.marks = marks;
                    }
                    if request.liquidations {
                        data.liquidations = liquidations;
                    }
                    let ticker = source.market_ticker(core, market);
                    let context = source.market_context(core, market);
                    let minute = source.market_volume_span(
                        core,
                        market,
                        VolumeSpan::Millis(60_000),
                        VolumeAt::Now,
                    );
                    data.captions = Captions {
                        delta_1h: ticker.map(|t| t.delta_1h_pct),
                        delta_24h: ticker.map(|t| t.delta_24h_pct),
                        funding_pct: context.and_then(|c| c.funding_pct),
                        funding_at_ms: context.and_then(|c| c.funding_at_ms),
                        buy_1m: minute.map(|v| v.buy_quote),
                        sell_1m: minute.map(|v| v.sell_quote),
                        price_tick: source.price_tick(core, market),
                        ..Captions::default()
                    };
                }
            }
        }
        if let Some(&(ping, exchange, slow)) = self.shared.pings.lock().unwrap_or_else(|e| e.into_inner()).get(&key.0) {
            data.captions.ping_ms = ping;
            data.captions.exchange_ms = exchange;
            data.captions.exchange_slow_ms = slow;
        }
        data.captions.detect = self
            .shared
            .detect_notes
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&key)
            .cloned();
        data.lines = self
            .shared
            .lines
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&key)
            .cloned()
            .unwrap_or_default();
        // The open position's liquidation price: a red line with its distance from the price.
        if !synthetic {
            let figures = self.leverage_figures(&key, synthetic, source);
            if let Some(liq) = figures.get("liq").and_then(Value::as_f64).filter(|p| p.is_finite() && *p > 0.0) {
                let label = match data.last_price.map(f64::from).filter(|p| *p > 0.0) {
                    Some(last) => format!("Ликв. {:+.2}%", (liq - last) / last * 100.0),
                    None => "Ликв.".to_string(),
                };
                data.lines.push(OrderLine {
                    price: liq,
                    color: LINE_LIQUIDATION,
                    light: LIGHT_LIQUIDATION,
                    dashed: false,
                    label,
                    label_color: LINE_LIQUIDATION,
                    label_light: LIGHT_LIQUIDATION,
                    uid: 0,
                    role: LineRole::Liquidation,
                    ..OrderLine::default()
                });
            }
        }
        // A line held by the finger is drawn where the finger is, captioned for that price.
        if let Some(drag) = &request.drag {
            let tick = data.captions.price_tick;
            if let Some(line) = data
                .lines
                .iter_mut()
                .find(|line| line.uid == drag.uid.0 && line.role == drag.role)
            {
                if drag.price.is_finite() && drag.price > 0.0 {
                    line.moved_to(drag.price, tick);
                }
            }
        }
        // The session's result beside the funding, when the app keeps a session.
        if !synthetic {
            if let Some(from) = request.session_from_ms.filter(|from| *from > 0 && *from < now) {
                data.captions.session_asked = true;
                data.captions.session_usdt = self.chart_session(&key, source, from, now);
            }
        }
        // The market's own trades: the closed ones from the report, the open positions' entries.
        if !synthetic {
            let mut trades = self.chart_trades(&key, source, now);
            if let Some(open) = self.shared.entries.lock().unwrap_or_else(|e| e.into_inner()).get(&key) {
                trades.extend(open.iter().copied());
            }
            data.trades = trades;
        }

        if synthetic {
            data.book = demo_book;
        } else if let Some(source) = source {
            // Every level, so the depth from the spread is right even where the spread itself is
            // off screen.
            data.book = source.with_orderbook_view(core, market, |view| {
                let mut points = Vec::new();
                if let Some((book, _)) = view {
                    book.collect_visible_depth(0.0, f32::MAX, &mut points);
                }
                points
                    .iter()
                    .map(|point| BookLevel {
                        price: point.price,
                        notional: point.notional,
                        ask: point.is_ask,
                    })
                    .collect()
            });
        }

        // The prices the plot spans: fitted, or the chosen share of the price, eased from the
        // window this chart's last frame used over the time since that frame. Following the
        // present it is centred on the middle of the spread.
        let scale = match request.scale_pct {
            Some(percent) if percent > 0.0 => Scale::Percent(percent.clamp(0.01, 10_000.0)),
            _ => Scale::Auto,
        };
        let visible = chart::visible_span(&data.ticks, &data.candles, &data.lines, from_ms, to_ms);
        let centre = chart::spread_middle(&data.book, data.last_price).or(data.last_price);
        let window = {
            let mut windows = self.shared.windows.lock().unwrap_or_else(|e| e.into_inner());
            if windows.len() > 64 {
                windows.clear();
            }
            let before = windows.get(&key).copied();
            let elapsed = before.map_or(1.0, |(_, at)| at.elapsed().as_secs_f64());
            let fitted = chart::fit_price(
                before.map(|(window, _)| window),
                visible,
                centre.map(f64::from),
                scale,
                live,
                elapsed,
                request.center,
            );
            match fitted {
                Some(window) => windows.insert(key.clone(), (window, Instant::now())),
                None => windows.remove(&key),
            };
            fitted
        };
        let (lo, hi) = window.map_or((0.0, 0.0), |window| (window.lo(), window.hi()));

        let view = View {
            width,
            height,
            scale: request.scale,
            from_ms,
            to_ms,
            now_ms: now,
            tz_offset_min: request.tz_offset_min.clamp(-900, 900),
            price_lo: lo,
            price_hi: hi,
            scale_badge: window
                .filter(|w| w.center > 0.0)
                .map(|w| percent_text(w.range / w.center * 100.0)),
            cursor: request.cursor.map(|[x, y]| (x, y)),
            book_marks: request.book_marks,
            take_flash_pct: request.take_flash_pct.filter(|p| p.is_finite() && *p > 0.0),
            light: request.light,
            left_inset: request.left_inset.clamp(0.0, width as f32 / 2.0),
        };
        let mut meta = chart::render(&data, &view, pixels)
            .ok_or_else(|| ApiError::new("internal", "the frame was not drawn"))?;
        meta["live"] = json!(live);
        meta["span_ms"] = json!(span);
        meta["now_ms"] = json!(now);
        meta["book"] = json!(data.book.len());
        meta["scale_pct"] = json!(window.filter(|w| w.center > 0.0).map(|w| w.range / w.center * 100.0));
        meta["price_tick"] = json!(data.captions.price_tick);
        let kind = if synthetic {
            "futures"
        } else {
            self.shared
                .core_kinds
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .get(&core)
                .copied()
                .unwrap_or("")
        };
        meta["kind"] = json!(kind);
        meta["leverage"] = self.leverage_figures(&key, synthetic, source);
        // How far the price ranged over 3 h, 1 h and 15 min, for the detect panel's header.
        meta["vola"] = json!(self.shared.ranker.volatility(source, synthetic, core, market));
        Ok(ok(meta))
    }
}

impl Engine {
    /// Fold a fresh read of the live ring into the trades this chart already holds and hand back
    /// those inside `[from_ms, to_ms]`. The ring is contiguous, so the fresh read replaces what
    /// is held over its own stretch and leaves the rest.
    fn held_ticks(
        &self,
        key: &ChartKey,
        fresh: Option<Vec<TickPoint>>,
        from_ms: i64,
        to_ms: i64,
        now_ms: i64,
    ) -> Vec<TickPoint> {
        let mut all = self.shared.held_ticks.lock().unwrap_or_else(|e| e.into_inner());
        all.retain(|_, (_, used)| used.elapsed() < HELD_TICKS_IDLE);
        if all.len() >= HELD_TICKS_CHARTS && !all.contains_key(key) {
            if let Some(oldest) = all.iter().min_by_key(|(_, (_, used))| *used).map(|(k, _)| k.clone()) {
                all.remove(&oldest);
            }
        }
        let (held, used) = all.entry(key.clone()).or_insert_with(|| (Vec::new(), Instant::now()));
        *used = Instant::now();
        if let Some(fresh) = fresh {
            merge_ticks(held, fresh, now_ms - HELD_TICKS_BACK_MS, HELD_TICKS_MAX);
        }
        let first = held.partition_point(|t| t.t_ms < from_ms);
        let last = held.partition_point(|t| t.t_ms <= to_ms);
        held[first..last].to_vec()
    }

    /// The chart's candle history, read again when due. The read is incremental: the cursor
    /// hands over only the candles that changed since the revision already held.
    fn candles(&self, key: &ChartKey, present_ms: i64, source: &MarketDataSource) -> Vec<Candle> {
        let mut all = self.shared.history.lock().unwrap_or_else(|e| e.into_inner());
        if all.len() > 12 && !all.contains_key(key) {
            all.clear();
        }
        let state = all.entry(key.clone()).or_insert_with(|| CandleState {
            shipped: u64::MAX,
            ..CandleState::default()
        });
        let every = if state.candles.is_empty() { CANDLE_READ_EMPTY } else { CANDLE_READ_EVERY };
        if state.read_at.is_some_and(|at| at.elapsed() < every) {
            return state.candles.clone();
        }
        state.read_at = Some(Instant::now());
        let epoch = self.shared.epoch_ms;
        let from_rel = ((present_ms - CANDLE_BACK_MS) as f64 - epoch) as f32;
        let to_rel = ((present_ms + 60_000) as f64 - epoch) as f32;
        let params = CandleReadParams {
            tf_ms: CANDLE_TF_MS,
            // Trades come from the live ticks; this read is for the candles alone.
            trades_from_rel_ms: f32::INFINITY,
            trades_limit: 1,
            shipped_revision: state.shipped,
            series_reset: !state.started,
        };
        state.started = true;
        let read = source.read_chart_history_into(
            key.0,
            &key.1,
            epoch,
            from_rel,
            to_rel,
            false,
            None,
            Some(&params),
            &mut state.cursor,
            &mut state.buffers,
        );
        if let Some(read) = read.filter(|read| read.candles_changed) {
            let buffers = &state.buffers;
            // Kept one for one, even a bar the picture will skip: a later patch counts from
            // the index the source holds.
            let fresh = buffers.candles.iter().enumerate().map(|(at, c)| {
                let tf = buffers.candle_tf_ms.get(at).map_or(CANDLE_TF_MS, |tf| *tf as i64);
                Candle {
                    t_ms: if c.t_open_ms.is_finite() { c.t_open_ms as i64 } else { 0 },
                    tf_ms: tf.max(1),
                    open: c.open,
                    high: c.high,
                    low: c.low,
                    close: c.close,
                    volume: c.quote_volume,
                }
            });
            match read.candles_patch_from {
                None => {
                    state.candles = fresh.collect();
                    state.shipped = read.candles_revision;
                }
                Some(from) if from <= state.candles.len() => {
                    state.candles.truncate(from);
                    state.candles.extend(fresh);
                    state.shipped = read.candles_revision;
                }
                // A patch against a list this side does not hold: ask for the whole list.
                Some(_) => {
                    state.shipped = u64::MAX;
                    state.read_at = None;
                }
            }
        }
        state.candles.clone()
    }

    /// How far the core's exchange clock runs ahead of this device's, in milliseconds: raised at
    /// once to what a trade shows, then eased back by [`LEAD_EASE_PER_S`] a second, so the
    /// chart's present moves at the device clock's own even pace.
    fn clock_lead(&self, core: u64, observed: Option<i64>) -> i64 {
        let mut leads = self.shared.leads.lock().unwrap_or_else(|e| e.into_inner());
        let (lead, at) = leads.entry(core).or_insert((0.0, Instant::now()));
        let eased = (*lead - at.elapsed().as_secs_f64() * LEAD_EASE_PER_S).max(0.0);
        *lead = match observed {
            Some(o) if (o as f64) > eased => o as f64,
            _ => eased,
        };
        *at = Instant::now();
        *lead as i64
    }

    /// The closed trades of a chart's market from the report, re-read when the report changed
    /// (at most every few seconds) or every minute regardless.
    ///
    /// The read itself runs on a thread of its own and the frame draws what is held meanwhile:
    /// a database read on the drawing thread would stall the chart's motion.
    fn chart_trades(&self, key: &ChartKey, source: Option<&MarketDataSource>, now_ms: i64) -> Vec<TradeMark> {
        let generation = crate::reports::generation();
        let held_now = {
            let held = self.shared.trades.lock().unwrap_or_else(|e| e.into_inner());
            match held.get(key) {
                Some((at_generation, at, trades)) => {
                    let age = at.elapsed();
                    let fresh = (*at_generation == generation && age < TRADES_REREAD) || age < TRADES_MIN_GAP;
                    if fresh {
                        return trades.as_ref().clone();
                    }
                    trades.as_ref().clone()
                }
                None => Vec::new(),
            }
        };
        if !self.shared.trades_reading.lock().unwrap_or_else(|e| e.into_inner()).insert(key.clone()) {
            return held_now;
        }
        let coins = report_coins(key, source);
        let shared = Arc::clone(&self.shared);
        let key = key.clone();
        let spawned = std::thread::Builder::new().name("mm-chart-trades".into()).spawn({
            let key = key.clone();
            let shared = Arc::clone(&shared);
            move || {
                let trades = Arc::new(crate::reports::chart_trades(key.0, &coins, now_ms));
                {
                    let mut held = shared.trades.lock().unwrap_or_else(|e| e.into_inner());
                    if held.len() > 16 && !held.contains_key(&key) {
                        held.clear();
                    }
                    held.insert(key.clone(), (generation, Instant::now(), trades));
                }
                shared.trades_reading.lock().unwrap_or_else(|e| e.into_inner()).remove(&key);
            }
        });
        if spawned.is_err() {
            shared.trades_reading.lock().unwrap_or_else(|e| e.into_inner()).remove(&key);
        }
        held_now
    }

    /// The chart's session result: what its market's trades closed since `from_ms` made, in
    /// USDT, read from the report as [`Self::chart_trades`] reads the trades — in the background,
    /// again when the report changed (at most every few seconds) or every minute regardless.
    /// `None` until the first read is in.
    fn chart_session(&self, key: &ChartKey, source: Option<&MarketDataSource>, from_ms: i64, now_ms: i64) -> Option<f64> {
        let generation = crate::reports::generation();
        let slot = (key.clone(), from_ms);
        let held_now = {
            let held = self.shared.sessions.lock().unwrap_or_else(|e| e.into_inner());
            match held.get(&slot) {
                Some((at_generation, at, value)) => {
                    let age = at.elapsed();
                    if (*at_generation == generation && age < TRADES_REREAD) || age < TRADES_MIN_GAP {
                        return *value;
                    }
                    *value
                }
                None => None,
            }
        };
        if !self.shared.sessions_reading.lock().unwrap_or_else(|e| e.into_inner()).insert(slot.clone()) {
            return held_now;
        }
        let coins = report_coins(key, source);
        let shared = Arc::clone(&self.shared);
        let spawned = std::thread::Builder::new().name("mm-chart-session".into()).spawn({
            let slot = slot.clone();
            move || {
                let value = crate::reports::session_profit(slot.0 .0, &coins, slot.1, now_ms + 60_000);
                {
                    let mut held = shared.sessions.lock().unwrap_or_else(|e| e.into_inner());
                    if held.len() > 16 && !held.contains_key(&slot) {
                        held.clear();
                    }
                    held.insert(slot.clone(), (generation, Instant::now(), value));
                }
                shared.sessions_reading.lock().unwrap_or_else(|e| e.into_inner()).remove(&slot);
            }
        });
        if spawned.is_err() {
            self.shared.sessions_reading.lock().unwrap_or_else(|e| e.into_inner()).remove(&slot);
        }
        held_now
    }

    /// Add the exchange's mark price points to a futures chart's yellow line: its history when
    /// the chart opens, then the newest ones while the core sends none. Points at a time already
    /// held replace it. Read-only for the owner; on the caller's thread.
    pub fn mark_history(&self, core: u64, market: &str, points: &[(i64, f64)]) -> Value {
        let key: ChartKey = (core, market.trim().to_string());
        let fresh: Vec<(i64, f32)> = points
            .iter()
            .filter(|(t, p)| *t > 0 && p.is_finite() && *p > 0.0)
            .map(|(t, p)| (*t, *p as f32))
            .collect();
        let mut all = self.shared.extras.lock().unwrap_or_else(|e| e.into_inner());
        let line = &mut all.entry(key).or_default().exchange;
        let mut merged: std::collections::BTreeMap<i64, f32> = line.iter().copied().collect();
        merged.extend(fresh);
        let mut kept: Vec<(i64, f32)> = merged.into_iter().collect();
        if kept.len() > SPOT_POINTS_MAX {
            kept.drain(..kept.len() - SPOT_POINTS_MAX);
        }
        *line = kept;
        ok(json!({ "points": line.len() }))
    }



    /// One page of the report with the totals of its period. Read on the caller's thread.
    /// The trades closed since `from_ms` on every real core, for the widget (reports::day_stats).
    pub fn day_stats(&self, from_ms: i64, to_ms: i64) -> ApiResult<Value> {
        let names = Arc::clone(&self.shared.names.lock().unwrap_or_else(|e| e.into_inner()));
        let kinds = Arc::clone(&self.shared.kinds.lock().unwrap_or_else(|e| e.into_inner()));
        let mut cores: Vec<u64> =
            self.shared.cores.iter().copied().filter(|c| !self.shared.synthetic.contains(c)).collect();
        cores.sort_unstable();
        crate::reports::day_stats(&cores, &names, &kinds, from_ms, to_ms)
            .map(crate::api::ok)
            .map_err(|e| ApiError::new("report", e))
    }

    pub fn report(
        &self,
        from_ms: i64,
        to_ms: i64,
        cores: &[u64],
        offset: usize,
        limit: usize,
        narrow: &crate::reports::Narrow,
    ) -> ApiResult<Value> {
        let names = Arc::clone(&self.shared.names.lock().unwrap_or_else(|e| e.into_inner()));
        let mut cores: Vec<u64> = if cores.is_empty() {
            self.shared.cores.iter().copied().filter(|c| !self.shared.synthetic.contains(c)).collect()
        } else {
            cores.iter().copied().filter(|c| self.shared.cores.contains(c)).collect()
        };
        cores.sort_unstable();
        let mut page = crate::reports::report(&cores, &names, from_ms, to_ms, offset, limit, narrow)
            .map_err(|e| ApiError::new("report", e))?;
        // Each row's market as the chart names it, so a tap opens it.
        if let (Some(source), Some(rows)) = (self.shared.market.get(), page.get_mut("rows").and_then(Value::as_array_mut)) {
            for row in rows.iter_mut() {
                let Some(cells) = row.as_array_mut() else { continue };
                let core = cells.first().and_then(Value::as_str).and_then(|c| c.parse::<u64>().ok());
                let coin = cells.get(1).and_then(Value::as_str).map(str::to_string);
                let market = match (core, coin) {
                    (Some(core), Some(coin)) => {
                        let quote = self.shared.quotes.get(&core).map(String::as_str).unwrap_or_default();
                        source.resolve_market(core, quote, &coin).unwrap_or_default()
                    }
                    _ => String::new(),
                };
                cells.push(json!(market));
            }
            if let Some(columns) = page.get_mut("columns").and_then(Value::as_array_mut) {
                columns.push(json!("market"));
            }
        }
        page["ok"] = json!(true);
        Ok(page)
    }

    /// The mark price line and the liquidations of a chart's market, as the core's history rings
    /// hold them; drained at most every [`EXTRA_READ_EVERY`].
    fn extras(&self, key: &ChartKey, present_ms: i64, source: &MarketDataSource) -> (Vec<(i64, f32)>, Vec<TickPoint>) {
        let mut all = self.shared.extras.lock().unwrap_or_else(|e| e.into_inner());
        if all.len() > 12 && !all.contains_key(key) {
            all.clear();
        }
        let state = all.entry(key.clone()).or_default();
        if state.read_at.is_some_and(|at| at.elapsed() < EXTRA_READ_EVERY) {
            return (state.mark_line(), state.liquidations.clone());
        }
        state.read_at = Some(Instant::now());
        let epoch = self.shared.epoch_ms;
        let from_rel = ((present_ms - EXTRA_BACK_MS) as f64 - epoch) as f32;
        let to_rel = ((present_ms + 60_000) as f64 - epoch) as f32;
        let first = !state.started;
        state.started = true;
        let read = source.read_chart_history_into(
            key.0,
            &key.1,
            epoch,
            from_rel,
            to_rel,
            first,
            None,
            None,
            &mut state.cursor,
            &mut state.buffers,
        );
        if let Some(read) = read {
            let buffers = &state.buffers;
            let points = buffers.mark_points.iter().map(|p| (p.time_ms as i64, p.price));
            match read.mark_line {
                moon_core::market::PriceLineUpdate::Replace => state.marks = points.collect(),
                moon_core::market::PriceLineUpdate::Append => state.marks.extend(points),
                moon_core::market::PriceLineUpdate::None => {}
            }
            let liquidations = buffers.liquidations.iter().map(|tick| TickPoint {
                t_ms: tick.time_ms as i64,
                price: tick.price,
                qty: tick.qty,
                buy: matches!(tick.side, moon_core::feed::Side::Buy),
            });
            if first || read.combo_reset {
                state.liquidations = liquidations.collect();
            } else {
                state.liquidations.extend(liquidations);
                // A clipped drain re-reads the range: the same rows must not count twice.
                state.liquidations.sort_by_key(|tick| tick.t_ms);
                state.liquidations.dedup_by(|a, b| {
                    a.t_ms == b.t_ms && a.price == b.price && a.qty == b.qty && a.buy == b.buy
                });
            }
            let oldest = present_ms - EXTRA_BACK_MS;
            state.marks.retain(|(t, _)| *t >= oldest);
            state.exchange.retain(|(t, _)| *t >= oldest);
            state.liquidations.retain(|tick| tick.t_ms >= oldest);
        }
        (state.mark_line(), state.liquidations.clone())
    }

    /// The market's leverage as the exchange reports it for this core: the maximum, the one in
    /// force and whether margin is isolated. Re-read at most every few seconds.
    fn leverage_figures(
        &self,
        key: &ChartKey,
        synthetic: bool,
        source: Option<&MarketDataSource>,
    ) -> Value {
        if synthetic {
            return json!({ "max": 50, "current": 10, "isolated": true });
        }
        let mut figures = self.shared.figures.lock().unwrap_or_else(|e| e.into_inner());
        if let Some((at, value)) = figures.get(key) {
            if at.elapsed() < Duration::from_secs(3) {
                return value.clone();
            }
        }
        let read = source.and_then(|source| source.market_figures(key.0, &key.1, false));
        let value = json!({
            "max": read.as_ref().and_then(|f| f.max_leverage),
            "current": read.as_ref().and_then(|f| f.leverage_x),
            "isolated": read.as_ref().and_then(|f| f.isolated),
            // The open position's liquidation price; withheld while flat.
            "liq": read.as_ref().and_then(|f| f.liq_price),
        });
        if figures.len() > 64 {
            figures.clear();
        }
        figures.insert(key.clone(), (Instant::now(), value.clone()));
        value
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        self.shared.stop.store(true, Ordering::Release);
        if let Some(owner) = self.owner.take() {
            owner.thread().unpark();
            // The owner drops the session, which joins nothing itself but closes every feed's
            // command channel; a hung join here would hang the app's own shutdown.
            let _ = owner.join();
        }
    }
}

/// A scale the way the terminal's badge prints it: whole percents, finer under ten.
fn percent_text(percent: f64) -> String {
    if percent >= 10.0 {
        format!("{percent:.0}%")
    } else if percent >= 1.0 {
        format!("{percent:.1}%")
    } else {
        format!("{percent:.2}%")
    }
}

/// Wrap a snapshot body with the envelope every reply carries.
fn envelope(body: &str, rev: u64) -> String {
    let now = moon_core::util::now_unix_ms_i64();
    let inner = body.trim_start_matches('{');
    let sep = if inner.trim_start().starts_with('}') { "" } else { "," };
    format!(r#"{{"ok":true,"changed":true,"rev":{rev},"now_ms":{now}{sep}{inner}"#)
}

/// The owner thread's whole life.
fn run(mut config: AppConfig, market_data: MarketData, jobs: Receiver<Job>, shared: Arc<Shared>) {
    let charts = market_data != MarketData::None;
    let epoch = moon_core::util::now_unix_ms_i64() as f64;
    // Process-wide, and read by a core's client when its role is applied: set before any starts.
    moon_core::feed::set_scoped_trades(market_data == MarketData::Scoped);
    // The wake pair unparks THIS thread: a feed message ends the park below at once.
    let (wake_tx, wake_rx) = moon_core::feed::feed_wake_pair_for_thread(thread::current());
    let mut session = SessionManager::start(&config, epoch, crate::reports::sink(), Some(wake_tx));
    match market_data {
        // Each core reads its own catalog and no core is given a trade stream.
        MarketData::None => session.map_cores_to_themselves(),
        // One provider per exchange and currency, whatever the desktop's setting says: a second
        // provider would be a second copy of the same trade stream on a phone.
        MarketData::Scoped => {
            session.set_market_mode(MarketDataMode::Dedup);
            session.set_open(&[], &[]);
        }
        MarketData::Full => {
            session.set_market_mode(config.market_mode);
            session.set_open(&[], &[]);
        }
    }
    let _ = shared.market.set(session.market_source());
    log::info!(
        "engine started: {} cores configured, market data {}",
        config.servers.len(),
        market_data.as_str()
    );

    // The detect badges: `badges.json` beside the other settings when the user brought one,
    // the terminal's defaults otherwise.
    let badges = BadgesConfig::load();
    let mut reconnects: Vec<CoreId> = Vec::new();
    // Stops waiting for the order they belong to to appear.
    let mut stops: Vec<PendingStop> = Vec::new();
    // Orders placed from the phone, this session's and the ones the app remembered.
    let mut mine: HashSet<(CoreId, u64)> = std::mem::take(&mut *shared.mine_seed.lock().unwrap());
    // Cores whose watcher setting was asked for since they last connected.
    let mut watch_asked: HashSet<CoreId> = HashSet::new();
    let mut dirty = true;
    let mut last_publish = Instant::now() - PUBLISH_IDLE;
    // Log files past their ten days go at start and every hour while the app runs.
    crate::logs::purge();
    let mut last_purge = Instant::now();
    let mut last_open_sync = Instant::now();
    let mut open_charts: Vec<ChartKey> = Vec::new();
    // Cards whose thumbnail was already asked for.
    let mut sparks_asked: HashMap<CardKey, (u8, Instant)> = HashMap::new();
    let mut watch = LogWatch::default();
    // The core being added by its key, while it is checked and for a while after.
    let mut adding: Option<PendingAdd> = None;

    while !shared.stop.load(Ordering::Acquire) {
        thread::park_timeout(if open_charts.is_empty() { IDLE_PARK } else { CHART_PARK });
        if shared.stop.load(Ordering::Acquire) {
            break;
        }

        // Discard queued tokens, then clear the latch BEFORE the drain: a message that lands
        // during the drain must still be able to send a wake (see `FeedWakeRx::clear`).
        while wake_rx.try_recv().is_ok() {}
        wake_rx.clear();
        let drained = session.drain();
        dirty |= drained.any;
        dirty |= shared.cards_dirty.swap(false, Ordering::AcqRel);
        if drained.identity && !charts {
            session.map_cores_to_themselves();
        }

        // A core whose update or exchange identity asks for a fresh client, plus the manual
        // Reconnect presses. One respawn per core per pass.
        let mut respawns = session.take_update_respawn_requests();
        respawns.extend(session.take_identity_respawn_requests(Instant::now()));
        respawns.append(&mut reconnects);
        respawns.sort_unstable();
        respawns.dedup();
        for id in respawns {
            if session.reconnect(id, &config, crate::reports::sink()) {
                dirty = true;
            }
        }

        let now = Instant::now();
        if charts {
            // The charts drawn lately are the markets to hold open, each with its order book.
            let wanted = wanted_charts(&shared, now);
            if wanted != open_charts || now.duration_since(last_open_sync) >= OPEN_SYNC_EVERY {
                last_open_sync = now;
                session.set_open(&wanted, &wanted);
                open_charts = wanted;
            }
            if let Some(source) = shared.market.get() {
                for (core, market) in &open_charts {
                    // Pulls the market's order book from its provider into the shared view.
                    source.refresh_market(*core, market);
                }
            }
        }

        while let Ok(job) = jobs.try_recv() {
            let answer = match job.call {
                Call::SetControl { core, on } => set_control(&session, &config, core, on),
                Call::AddCore { key, name } => {
                    let added = add_core(&mut session, &mut config, market_data, &mut adding, &key, &name);
                    *shared.station_cores.lock().unwrap_or_else(|e| e.into_inner()) = station_cores_of(&config);
                    added
                }
                call => handle(&mut session, &config, &mut reconnects, &shared.ranker, &mut stops, call),
            };
            dirty = true;
            // The caller may have given up; its answer then has nowhere to go.
            let _ = job.reply.try_send(answer);
        }

        if now.duration_since(last_purge) >= Duration::from_secs(3600) {
            last_purge = now;
            crate::logs::purge();
        }
        let since_publish = now.duration_since(last_publish);
        if (dirty && since_publish >= PUBLISH_EVERY) || since_publish >= PUBLISH_IDLE {
            // A thumbnail filled in during the publish asks for the next one.
            tick_stops(&session, &mut stops, &mut mine);
            ask_watchers(&session, &config, &mut watch_asked);
            if let Some(add) = adding.as_mut() {
                tick_add(&mut session, &mut config, add);
            }
            let added = adding.as_ref().map(PendingAdd::dto);
            dirty = publish(&session, &config, &shared, &badges, &mut sparks_asked, &mut watch, added, &mine);
            last_publish = now;
        }
    }

    log::info!("engine stopping");
    drop(session);
}

/// How long a core added by its key has to answer before it is taken out again.
const ADD_WAIT: Duration = Duration::from_secs(45);
/// The colour a core added on the phone starts with.
const ADDED_COLOR: [u8; 3] = [0x4f, 0x8e, 0xe5];

/// Where adding a core stands.
#[derive(Clone, PartialEq)]
enum AddState {
    /// Connecting; nothing is written yet.
    Checking,
    /// Answered and written into `servers.enc`.
    Saved,
    /// Answered, but the file could not be written: the core is connected for this session only.
    NotSaved(String),
    /// Did not answer in time; taken out again, nothing written.
    Failed(String),
}

/// A core added by its key: connected at once, written to disk once it answers.
struct PendingAdd {
    id: CoreId,
    name: String,
    /// The row as it goes to disk: the terminal's defaults, none of the phone's feed tuning.
    row: moon_core::config::ServerConfig,
    since: Instant,
    state: AddState,
    /// The last stage or failure the connection reported.
    last: String,
}

impl PendingAdd {
    fn dto(&self) -> snapshot::AddingDto {
        let (state, text) = match &self.state {
            AddState::Checking => ("checking", self.last.clone()),
            AddState::Saved => ("saved", String::new()),
            AddState::NotSaved(text) => ("not_saved", text.clone()),
            AddState::Failed(text) => ("failed", text.clone()),
        };
        snapshot::AddingDto { core: self.id, name: self.name.clone(), state, text }
    }
}

/// Start adding a core by its key: checked for sense, connected now, saved once it answers.
fn add_core(
    session: &mut SessionManager,
    config: &mut AppConfig,
    market_data: MarketData,
    adding: &mut Option<PendingAdd>,
    key: &str,
    name: &str,
) -> ApiResult<Value> {
    let key = key.trim();
    if key.is_empty() {
        return Err(ApiError::new("bad_key", "the key is empty"));
    }
    if adding.as_ref().is_some_and(|add| add.state == AddState::Checking) {
        return Err(ApiError::new("busy", "another core is being checked"));
    }
    if moon_core::station_api::core_address(key, "").is_none() {
        return Err(ApiError::new("bad_key", "this is not a MoonBot key, or it names no address"));
    }
    if config.servers.iter().any(|server| server.key.expose().trim() == key) {
        return Err(ApiError::new("exists", "a core with this key is already in the list"));
    }
    if !moon_core::config::paths::servers_path().is_file() {
        return Err(ApiError::new("no_servers_file", "cores are added to servers.enc; open one first"));
    }
    // The file as it is on disk, to give the new core an identity no core there has.
    let disk = AppConfig::load(None, false).map_err(|e| ApiError::new("config", format!("{e:#}")))?;
    let uid = disk
        .servers
        .iter()
        .map(|server| server.uid)
        .chain(config.servers.iter().map(|server| server.uid.max(server.id)))
        .max()
        .unwrap_or(0)
        + 1;

    let base = if name.trim().is_empty() { "Ядро" } else { name.trim() };
    let taken = |candidate: &str| config.servers.iter().any(|server| server.name == candidate);
    let mut unique = base.to_string();
    let mut n = 2;
    while taken(&unique) {
        unique = format!("{base} {n}");
        n += 1;
    }

    let mut row = crate::open::blank_server()?;
    row.id = uid;
    row.uid = uid;
    row.name = unique.clone();
    row.active = true;
    row.key = moon_core::config::Secret::new(key);
    row.group = moon_core::config::servers::default_group();
    row.market = "BTCUSDT".into();
    row.color = ADDED_COLOR;
    row.transport = moon_core::config::seeded_transport(None, key);

    let mut live = row.clone();
    let core_log = config.servers.first().is_some_and(|server| server.feed.log);
    crate::open::tune(&mut live, market_data, core_log);
    config.servers.push(live);
    session.reconcile(config, crate::reports::sink());
    log::info!("adding core {uid} ({unique})");
    *adding = Some(PendingAdd {
        id: uid,
        name: unique.clone(),
        row,
        since: Instant::now(),
        state: AddState::Checking,
        last: String::new(),
    });
    Ok(ok(json!({ "core": uid, "name": unique })))
}

/// Follow the core being added: write it to disk once it answers, take it out if it does not.
fn tick_add(session: &mut SessionManager, config: &mut AppConfig, add: &mut PendingAdd) {
    if add.state != AddState::Checking {
        return;
    }
    let status = session.store().core(add.id).map(|data| data.status.clone());
    match status {
        Some(moon_core::feed::ConnStatus::Ready) => {
            add.state = match save_added(&add.row) {
                Ok(()) => AddState::Saved,
                Err(e) => AddState::NotSaved(e),
            };
            log::info!("core {} answered; saved: {}", add.id, add.state == AddState::Saved);
            return;
        }
        Some(moon_core::feed::ConnStatus::Failed(text) | moon_core::feed::ConnStatus::Stage(text)) => {
            add.last = text;
        }
        _ => {}
    }
    if add.since.elapsed() >= ADD_WAIT {
        config.servers.retain(|server| server.id != add.id);
        session.reconcile(config, crate::reports::sink());
        let why = if add.last.is_empty() { "no answer".to_string() } else { add.last.clone() };
        log::info!("core {} did not answer: {why}", add.id);
        add.state = AddState::Failed(why);
    }
}

/// Append the new core to `servers.enc` and the terminal's settings, read fresh from disk so the
/// phone's own tuning of the other cores stays out of the files.
fn save_added(row: &moon_core::config::ServerConfig) -> Result<(), String> {
    let mut disk = AppConfig::load(None, false).map_err(|e| format!("{e:#}"))?;
    if disk.servers.iter().any(|server| server.uid == row.uid) {
        return Err(format!("the file already has a core {}", row.uid));
    }
    disk.servers.push(row.clone());
    disk.save().map_err(|e| format!("{e:#}"))
}

/// Put `fresh`, a contiguous read sorted by time, over the stretch of `held` it covers, then drop
/// what is older than `keep_from_ms` and the oldest beyond `max`.
fn merge_ticks(held: &mut Vec<TickPoint>, fresh: Vec<TickPoint>, keep_from_ms: i64, max: usize) {
    if let (Some(first), Some(last)) = (fresh.first().map(|t| t.t_ms), fresh.last().map(|t| t.t_ms)) {
        let lo = held.partition_point(|t| t.t_ms < first);
        let hi = held.partition_point(|t| t.t_ms <= last);
        held.splice(lo..hi, fresh);
    }
    let old = held.partition_point(|t| t.t_ms < keep_from_ms);
    let over = held.len().saturating_sub(max);
    held.drain(..old.max(over));
}

/// The configured cores as a station takes them: real ones with a uid and a key.
fn station_cores_of(config: &AppConfig) -> Vec<crate::station::StationCore> {
    config
        .servers
        .iter()
        .filter(|s| !s.synthetic && s.uid != 0 && !s.key.is_empty())
        .map(|s| crate::station::StationCore {
            id: s.id,
            uid: s.uid,
            name: s.name.clone(),
            key: s.key.clone(),
            endpoint_override: moon_core::config::station_endpoint(&s.endpoint_override, s.endpoint_to_station)
                .to_owned(),
            active: s.active,
        })
        .collect()
}

/// The charts asked for within [`CHART_KEEP`], in a stable order; older ones are forgotten.
fn wanted_charts(shared: &Shared, now: Instant) -> Vec<ChartKey> {
    let mut wanted = shared.wanted.lock().unwrap_or_else(|e| e.into_inner());
    wanted.retain(|_, asked| now.saturating_duration_since(*asked) < CHART_KEEP);
    let mut charts: Vec<ChartKey> = wanted.keys().cloned().collect();
    for key in shared.kept.lock().unwrap_or_else(|e| e.into_inner()).iter() {
        if !charts.contains(key) {
            charts.push(key.clone());
        }
    }
    charts.sort();
    charts
}

// The order-line colours. An entry is dark yellow from its placing to its fill and after it, on
// the light theme black; an exit light blue, as the terminal's.
const LINE_ENTRY: [u8; 3] = [228, 158, 36];
const LINE_SELL: [u8; 3] = [127, 201, 255];
const LINE_SELL_SHORT: [u8; 3] = [211, 211, 211];
const LINE_STOP: [u8; 3] = [255, 127, 80];
const LINE_TRAILING: [u8; 3] = [100, 149, 237];
/// The position's liquidation price, red as in MoonBot.
const LINE_LIQUIDATION: [u8; 3] = [255, 45, 45];
const LIGHT_LIQUIDATION: [u8; 3] = [210, 0, 0];
const LABEL_SIZE: [u8; 3] = [235, 235, 235];
const LABEL_GAIN: [u8; 3] = [47, 168, 92];
const LABEL_LOSS: [u8; 3] = [255, 74, 74];
// The same on the light theme.
const LIGHT_ENTRY: [u8; 3] = [0, 0, 0];
const LIGHT_SELL: [u8; 3] = [0, 0, 255];
const LIGHT_SELL_SHORT: [u8; 3] = [128, 0, 0];
const LIGHT_STOP: [u8; 3] = [255, 0, 0];
const LIGHT_TRAILING: [u8; 3] = [0, 0, 139];
const LIGHT_SIZE: [u8; 3] = [0, 0, 0];
const LIGHT_GAIN: [u8; 3] = [0, 128, 0];
const LIGHT_LOSS: [u8; 3] = [220, 0, 0];

/// Every open order as the lines a chart of its market draws, captioned as the terminal does:
/// the entry with its size in dollars and the order's chart number, the exit and the stop with
/// their distance from the entry and the same number.
///
/// Also returns where each open position entered, as a mark for the chart.
fn chart_lines(
    session: &SessionManager,
    config: &AppConfig,
) -> (HashMap<ChartKey, Vec<OrderLine>>, HashMap<ChartKey, Vec<TradeMark>>) {
    let source = session.market_source();
    let mut lines: HashMap<ChartKey, Vec<OrderLine>> = HashMap::new();
    let mut entries: HashMap<ChartKey, Vec<TradeMark>> = HashMap::new();
    for server in &config.servers {
        let Some(data) = session.store().core(server.id) else {
            continue;
        };
        let markets: std::collections::BTreeSet<&str> = data
            .orders
            .iter()
            .filter(|o| !o.job_is_done)
            .map(|o| o.market.as_str())
            .collect();
        for market in markets {
            let quote = source.market_label(server.id, market).quote;
            let rate = if quote.is_empty() {
                None
            } else {
                source.currency_usd_rate(server.id, &quote)
            };
            let mut retained: Vec<_> = data.order_lines.iter_open_market(market).collect();
            retained.sort_by_key(|o| o.seq);
            let of_market = lines.entry((server.id, market.to_string())).or_default();
            if retained.is_empty() {
                // The line store has not caught up with these rows yet: draw them from the rows,
                // numbered in the order they are listed.
                let rows = data.orders.iter().filter(|o| !o.job_is_done && o.market == market);
                for (at, row) in rows.enumerate() {
                    let entry = (row.buy_price > 0.0).then_some(row.buy_price as f32);
                    let exit = (row.filled && row.sell_price > 0.0).then_some(row.sell_price as f32);
                    let stop = row.stop_loss.filter(|_| row.filled).map(|p| p as f32);
                    let fill = if row.filled { 100.0 } else { 0.0 };
                    let size = row.size as f32;
                    let who = Who { uid: row.uid, short: row.is_short };
                    push_order_lines(of_market, at as u32 + 1, who, fill, size, size, rate, [
                        entry, exit, stop, None, None,
                    ], LineTimes::default());
                }
                continue;
            }
            for order in retained {
                let price = |kind: LineKind| order.lines[kind as usize].current_price();
                let dated = |t: f64| (t.is_finite() && t > 1.0).then_some(t as i64);
                let placed = dated(order.create_ms);
                let filled = order.entry_fill_ms.and_then(dated);
                // The exit is placed when the entry fills, and moves after: its first step.
                let exit_placed = order.lines[LineKind::Sell as usize]
                    .steps
                    .first()
                    .and_then(|(t, _)| dated(*t))
                    .or(filled)
                    .or(placed);
                let times = LineTimes { placed, filled, exit_placed };
                push_order_lines(
                    of_market,
                    order.chart_num,
                    Who { uid: order.uid, short: order.is_short },
                    order.fill_pct,
                    order.size,
                    order.exit_size(),
                    rate,
                    [
                        price(LineKind::Buy),
                        price(LineKind::Sell),
                        price(LineKind::Stop),
                        price(LineKind::Trailing),
                        price(LineKind::TakeProfit),
                    ],
                    times,
                );
                // A position: where it entered, marked on the chart until it closes.
                if order.fill_pct > 0.0 {
                    let entered = order.entry_fill_ms.filter(|t| *t > 1.0);
                    let at = order.lines[LineKind::Buy as usize]
                        .steps
                        .last()
                        .map(|(_, p)| f64::from(*p))
                        .or_else(|| price(LineKind::Buy).map(f64::from))
                        .filter(|p| p.is_finite() && *p > 0.0);
                    if let (Some(t), Some(p)) = (entered, at) {
                        entries.entry((server.id, market.to_string())).or_default().push(TradeMark {
                            open_ms: t as i64,
                            open_price: p,
                            close: None,
                            short: order.is_short,
                            profit_pct: None,
                        });
                    }
                }
            }
        }
    }
    (lines, entries)
}

/// The spellings the report may file a chart's market under: the market and its coin.
fn report_coins(key: &ChartKey, source: Option<&MarketDataSource>) -> Vec<String> {
    let mut coins = vec![key.1.clone()];
    if let Some(source) = source {
        let coin = source.market_label(key.0, &key.1).coin;
        if !coin.is_empty() && !coins.iter().any(|c| c.eq_ignore_ascii_case(&coin)) {
            coins.push(coin);
        }
    }
    coins
}

/// When an order's lines begin on the chart (Unix ms): the entry where the order was placed and,
/// once filled, where it filled; the exit where it was placed. `None` draws a line across the
/// whole plot.
#[derive(Clone, Copy, Default)]
struct LineTimes {
    placed: Option<i64>,
    filled: Option<i64>,
    exit_placed: Option<i64>,
}

/// The order a set of lines belongs to.
#[derive(Clone, Copy)]
struct Who {
    uid: u64,
    short: bool,
}

/// The lines of one order: `[entry, exit, stop, trailing, take profit]` prices.
///
/// `size` is the entry's size and `exit_size` what the exit still has to sell, both in the base
/// coin: the exit's caption says what that sells for at the exit's price.
#[allow(clippy::too_many_arguments)]
fn push_order_lines(
    out: &mut Vec<OrderLine>,
    number: u32,
    who: Who,
    fill_pct: f32,
    size: f32,
    exit_size: f32,
    usd_rate: Option<f64>,
    prices: [Option<f32>; 5],
    times: LineTimes,
) {
    let short = who.short;
    let tag = if number > 0 { format!(" [{number}]") } else { String::new() };
    let valid = |price: Option<f32>| price.map(f64::from).filter(|p| p.is_finite() && *p > 0.0);
    let [entry, exit, stop, trailing, take] = prices.map(valid);
    let filled = fill_pct > 0.0;
    let percent = |level: f64, entry: f64| {
        let raw = (level - entry) / entry * 100.0;
        if short { -raw } else { raw }
    };
    // A caption's colour on the dark and on the light theme.
    let signed = |pct: f64| {
        if pct < 0.0 {
            (LABEL_LOSS, LIGHT_LOSS)
        } else {
            (LABEL_GAIN, LIGHT_GAIN)
        }
    };
    let line = |price: f64, role: LineRole, colors: ([u8; 3], [u8; 3]), dashed: bool, label: String, label_colors: ([u8; 3], [u8; 3])| {
        OrderLine {
            price,
            color: colors.0,
            light: colors.1,
            dashed,
            label,
            label_color: label_colors.0,
            label_light: label_colors.1,
            uid: who.uid,
            role,
            short: who.short,
            entry_price: None,
            tag: tag.clone(),
            usd_per_price: None,
            start_ms: None,
            fill_ms: None,
        }
    };
    let rate = usd_rate.filter(|rate| *rate > 0.0);
    // What `qty` of the coin comes to at `price`: dollars when the coin's rate is known.
    let worth = |qty: f32, price: f64| match rate {
        Some(rate) => format!("${}", compact_amount(f64::from(qty) * price * rate)),
        None => compact_amount(f64::from(qty)),
    };
    let size_label = (LABEL_SIZE, LIGHT_SIZE);
    if let Some(price) = entry {
        let amount = worth(size, price);
        let role = if filled { LineRole::Filled } else { LineRole::Entry };
        let mut drawn = line(price, role, (LINE_ENTRY, LIGHT_ENTRY), !filled, format!("{amount}{tag}"), size_label);
        drawn.usd_per_price = rate.map(|rate| f64::from(size) * rate);
        drawn.start_ms = times.placed;
        drawn.fill_ms = times.filled.filter(|_| filled);
        out.push(drawn);
    }
    if let Some(price) = exit {
        // The distance from the entry and what the exit still sells for at its price: the whole
        // position at first, less whatever part of it has sold.
        let amount = worth(exit_size, price);
        let label = match entry {
            Some(entry) => format!("{:+.2}% {amount}{tag}", percent(price, entry)),
            None => format!("{amount}{tag}"),
        };
        let label_colors = entry.map_or(size_label, |entry| signed(percent(price, entry)));
        let colors = if short { (LINE_SELL_SHORT, LIGHT_SELL_SHORT) } else { (LINE_SELL, LIGHT_SELL) };
        let mut drawn = line(price, LineRole::Exit, colors, false, label, label_colors);
        drawn.entry_price = entry;
        drawn.usd_per_price = rate.map(|rate| f64::from(exit_size) * rate);
        drawn.start_ms = times.exit_placed;
        out.push(drawn);
    }
    if let Some(price) = stop {
        let label = entry.map_or_else(String::new, |entry| {
            format!("{:+.2}%{tag}", percent(price, entry))
        });
        let label_colors = entry.map_or(size_label, |entry| signed(percent(price, entry)));
        let mut drawn = line(price, LineRole::Stop, (LINE_STOP, LIGHT_STOP), false, label, label_colors);
        drawn.entry_price = entry;
        out.push(drawn);
    }
    if let Some(price) = trailing {
        out.push(line(price, LineRole::Trailing, (LINE_TRAILING, LIGHT_TRAILING), true, String::new(), size_label));
    }
    // The take-profit trigger is not drawn: it got in the way of the exit and the book, and
    // moving it by hand did nothing visible (v1.10b).
    let _ = take;
}

/// An amount with two decimals at most and a thousands letter: `5.92K`, `60`, `1.25M`.
fn compact_amount(value: f64) -> String {
    let magnitude = value.abs();
    let (scaled, unit) = if magnitude >= 1e9 {
        (value / 1e9, "B")
    } else if magnitude >= 1e6 {
        (value / 1e6, "M")
    } else if magnitude >= 1e3 {
        (value / 1e3, "K")
    } else {
        (value, "")
    };
    let text = format!("{scaled:.2}");
    let text = text.trim_end_matches('0').trim_end_matches('.');
    format!("{text}{unit}")
}

/// How often the newest closed trade is looked for again once the report changed.
const LAST_DEAL_EVERY: Duration = Duration::from_secs(4);

/// The newest closed trade of the cores, re-read when the report changed and a few seconds
/// passed; its market named by the core's catalog.
fn last_deal(
    session: &SessionManager,
    shared: &Shared,
    names: &HashMap<(u64, u64), String>,
    now_ms: i64,
) -> Option<crate::reports::LastDeal> {
    let generation = crate::reports::generation();
    let mut held = shared.last_deal.lock().unwrap_or_else(|e| e.into_inner());
    let due = held.0 != generation && held.1.is_none_or(|at| at.elapsed() >= LAST_DEAL_EVERY);
    if due {
        let cores: Vec<u64> = shared.cores.iter().copied().filter(|c| !shared.synthetic.contains(c)).collect();
        let mut deal = crate::reports::last_deal(&cores, names, now_ms);
        if let Some(deal) = deal.as_mut() {
            let quote = shared.quotes.get(&deal.core).map(String::as_str).unwrap_or_default();
            deal.market = session
                .market_source()
                .resolve_market(deal.core, quote, &deal.coin)
                .unwrap_or_default();
        }
        *held = (generation, Some(Instant::now()), deal);
    } else if let Some(deal) = held.2.as_mut().filter(|d| d.market.is_empty()) {
        // The catalog may have come in since.
        let quote = shared.quotes.get(&deal.core).map(String::as_str).unwrap_or_default();
        if let Some(market) = session.market_source().resolve_market(deal.core, quote, &deal.coin) {
            deal.market = market;
        }
    }
    held.2.clone()
}

/// Build the snapshot and publish it when it differs from the one already out.
///
/// Returns:
///     Whether another publish is due at once: a detect card got its thumbnail in this pass.
fn publish(
    session: &SessionManager,
    config: &AppConfig,
    shared: &Arc<Shared>,
    badges: &BadgesConfig,
    sparks_asked: &mut HashMap<CardKey, (u8, Instant)>,
    watch: &mut LogWatch,
    adding: Option<snapshot::AddingDto>,
    mine: &HashSet<(CoreId, u64)>,
) -> bool {
    let now_ms = moon_core::util::now_unix_ms_i64();
    watch.scan(session, config, now_ms);
    let (lines, entries) = chart_lines(session, config);
    *shared.lines.lock().unwrap_or_else(|e| e.into_inner()) = Arc::new(lines);
    *shared.entries.lock().unwrap_or_else(|e| e.into_inner()) = Arc::new(entries);
    let cards = {
        let sparks = shared.sparks.lock().unwrap_or_else(|e| e.into_inner());
        let mut prices = shared.detect_prices.lock().unwrap_or_else(|e| e.into_inner());
        cards::build(session, config, badges, &sparks, &mut prices, now_ms)
    };
    let again = request_sparks(config, shared, &cards, sparks_asked, now_ms);
    let mut snapshot = snapshot::build(
        session,
        config,
        shared.mode.as_str(),
        cards,
        watch.events.iter().cloned().collect(),
        adding,
        mine,
    );
    // Strategy names for the report, and the newest closed trade for the chart's corner.
    let names: HashMap<(u64, u64), String> = snapshot
        .strategies
        .iter()
        .map(|s| ((s.core, s.id.0), s.name.clone()))
        .collect();
    *shared.names.lock().unwrap_or_else(|e| e.into_inner()) = Arc::new(names.clone());
    *shared.kinds.lock().unwrap_or_else(|e| e.into_inner()) = Arc::new(
        snapshot.strategies.iter().map(|s| ((s.core, s.id.0), s.kind.clone())).collect(),
    );
    snapshot.last_deal = last_deal(session, shared, &names, now_ms);
    // The cores the ranking worker samples: connected ones with a real exchange behind them.
    let online: Vec<CoreId> = snapshot
        .cores
        .iter()
        .filter(|core| core.run.online && !shared.synthetic.contains(&core.id))
        .map(|core| core.id)
        .collect();
    *shared.online.lock().unwrap_or_else(|e| e.into_inner()) = Arc::new(online);

    // What the chart needs from the same state: what each core trades, and its newest detects.
    let kinds: HashMap<CoreId, &'static str> = session
        .core_venues()
        .iter()
        .filter_map(|(core, venue)| {
            let kind = match venue.resolved()?.kind {
                MarketKind::Spot => "spot",
                MarketKind::Futures => "futures",
                MarketKind::Quarterly => "quarterly",
            };
            Some((*core, kind))
        })
        .collect();
    *shared.core_kinds.lock().unwrap_or_else(|e| e.into_inner()) = Arc::new(kinds);
    {
        let mut at = shared.pings_at.lock().unwrap_or_else(|e| e.into_inner());
        if at.is_none_or(|at| at.elapsed() >= PING_EVERY) {
            *at = Some(Instant::now());
            let pings = snapshot
                .cores
                .iter()
                .map(|core| {
                    let slow = crate::alerts::exchange_slow_ms(&core.brand);
                    (core.id, (core.health.ping_ms, core.health.exchange_ms, slow))
                })
                .collect();
            *shared.pings.lock().unwrap_or_else(|e| e.into_inner()) = Arc::new(pings);
        }
    }
    let mut newest: HashMap<ChartKey, &snapshot::DetectDto> = HashMap::new();
    for detect in &snapshot.detects {
        if detect.time_ms < now_ms as f64 - DETECT_NOTE_MS {
            continue;
        }
        let key = (detect.core, detect.market.clone());
        match newest.get(&key) {
            Some(held) if held.time_ms >= detect.time_ms => {}
            _ => {
                newest.insert(key, detect);
            }
        }
    }
    // The strategy's name alone: the core's own message is long and covers the chart.
    let notes: HashMap<ChartKey, String> = newest
        .into_iter()
        .filter_map(|(key, detect)| {
            let name = chart::drawable(&detect.strategy).trim().to_string();
            (!name.is_empty()).then_some((key, name))
        })
        .collect();
    *shared.detect_notes.lock().unwrap_or_else(|e| e.into_inner()) = Arc::new(notes);

    *shared.core_names.lock().unwrap_or_else(|e| e.into_inner()) =
        Arc::new(snapshot.cores.iter().map(|core| (core.id, core.name.clone())).collect());
    shared.alerts.lock().unwrap_or_else(|e| e.into_inner()).step(
        &snapshot.cores,
        &snapshot.orders,
        &snapshot.detects,
        &snapshot.events,
        now_ms,
    );

    let body = match serde_json::to_string(&snapshot) {
        Ok(body) => body,
        Err(e) => {
            log::error!("snapshot not serialised: {e}");
            return again;
        }
    };
    let mut current = shared.body.lock().unwrap_or_else(|e| e.into_inner());
    if **current != *body {
        *current = Arc::from(body);
        // Under the lock: a reader that sees the new revision can only read the new body.
        shared.rev.fetch_add(1, Ordering::AcqRel);
    }
    again
}

/// Events kept for the app; it notifies about those newer than the last one it saw.
const MAX_EVENTS: usize = 50;

/// Watches the cores' own log for the exchange refusing requests over its limits.
#[derive(Default)]
struct LogWatch {
    cursors: HashMap<CoreId, u64>,
    events: std::collections::VecDeque<snapshot::EventDto>,
    seq: u64,
}

impl LogWatch {
    fn scan(&mut self, session: &SessionManager, config: &AppConfig, now_ms: i64) {
        for server in &config.servers {
            let Some(data) = session.store().core(server.id) else { continue };
            let Some(cursor) = self.cursors.get(&server.id).copied() else {
                // What was logged before the app looked is not news.
                self.cursors.insert(server.id, data.log_seq);
                continue;
            };
            let (lines, seq) = data.log_since(cursor);
            let mut found: Vec<String> = Vec::new();
            for line in lines {
                if is_api_limit(&line.msg) {
                    found.push(line.msg.clone());
                }
            }
            self.cursors.insert(server.id, seq);
            // One event per burst: a core that is refused logs it on every request.
            if let Some(text) = found.pop() {
                let recent = self.events.iter().rev().any(|e| {
                    e.core == server.id && e.kind == "api_limit" && now_ms - e.time_ms < 60_000
                });
                if !recent {
                    self.seq += 1;
                    self.events.push_back(snapshot::EventDto {
                        seq: self.seq,
                        core: server.id,
                        kind: "api_limit",
                        text: api_limit_reason(&chart::drawable(&text)),
                        time_ms: now_ms,
                    });
                    while self.events.len() > MAX_EVENTS {
                        self.events.pop_front();
                    }
                }
            }
        }
    }
}

/// Whether a core's log line says the exchange refused requests over its limits.
///
/// An exchange's ordinary answer can name its limits too: every Binance WS API reply carries a
/// `rateLimits` list, and a successful one is no refusal. A limit is reported only when it was
/// hit: an error code that means it, or the limit named together with "exceeded".
fn is_api_limit(message: &str) -> bool {
    let text = message.to_lowercase();
    if text.contains("\"status\":200") {
        return false;
    }
    let hit = [
        "too many request",
        "too much request",
        "way too many",
        "-1003",
        "-1015",
        "\"status\":429",
        "\"status\":418",
        "error 429",
        "code 429",
        "(429)",
        "ip banned",
        "is banned",
        "лимит запрос",
        "лимит api",
    ]
    .iter()
    .any(|needle| text.contains(needle));
    let named = ["rate limit", "ratelimit", "request weight", "api limit"]
        .iter()
        .any(|needle| text.contains(needle));
    let exceeded = ["exceed", "reached", "превыш"].iter().any(|needle| text.contains(needle));
    hit || (named && exceeded)
}

/// What an API-limit line says, in a few words: the exchange's message is long and technical.
fn api_limit_reason(message: &str) -> String {
    let text = message.to_lowercase();
    let reason = if text.contains("banned") || text.contains("\"status\":418") {
        "IP заблокирован биржей за превышение лимитов"
    } else if text.contains("-1015") {
        "Слишком много новых ордеров"
    } else if text.contains("-1003") || text.contains("429") || text.contains("too many") || text.contains("too much") {
        "Слишком много запросов"
    } else {
        "Превышен лимит запросов"
    };
    // A core's log line starts with its time and, for a market's request, the coin.
    let mut words = message.split_whitespace();
    let time = words.next().filter(|w| w.len() == 8 && w.as_bytes()[2] == b':');
    let coin = time.and(words.next()).and_then(|w| w.strip_suffix(':')).filter(|w| {
        !w.is_empty() && w.len() <= 12 && w.chars().all(|c| c.is_ascii_alphanumeric())
    });
    match coin {
        Some(coin) => format!("{coin}: {reason}"),
        None => reason.to_string(),
    }
}

/// What a core really has free for one market, in the currency that market is margined in.
///
/// The core's own figure (`btc_balance_total`) is in its base currency. A market quoted in
/// another one — a USDC contract on a USDT core — draws on that currency's wallet, and a
/// coin-margined (quarterly) contract on its coin's.
fn free_balance(session: &SessionManager, core: CoreId, market: &str) -> ApiResult<Value> {
    let store = session.store();
    let data = store.core(core).ok_or_else(|| ApiError::new("unknown_core", "no such core"))?;
    let assets = &data.assets;
    let base = assets.base_currency.trim().to_string();
    let row = assets.rows.iter().find(|row| row.market == market);
    let quarterly = session
        .core_venues()
        .get(&core)
        .and_then(|venue| venue.resolved())
        .is_some_and(|resolved| matches!(resolved.kind, MarketKind::Quarterly));
    let wallet = |coin: &str| {
        assets
            .rows
            .iter()
            .find(|row| row.coin.eq_ignore_ascii_case(coin) && !row.coin.is_empty())
            .map(|row| row.qty)
    };
    if quarterly {
        let coin = row.map(|row| row.coin.clone()).filter(|coin| !coin.is_empty());
        if let Some(coin) = coin {
            if let Some(free) = row.map(|row| row.qty).or_else(|| wallet(&coin)) {
                return Ok(ok(json!({ "currency": coin, "free": free })));
            }
        }
    }
    let upper = market.to_ascii_uppercase();
    let quote = row
        .map(|row| row.quote.trim().to_string())
        .filter(|quote| !quote.is_empty())
        .unwrap_or_else(|| {
            if upper.ends_with("USDC") || upper.ends_with("PERP") {
                "USDC".to_string()
            } else if upper.ends_with("USDT") {
                "USDT".to_string()
            } else {
                base.clone()
            }
        });
    if !quote.eq_ignore_ascii_case(&base) {
        if let Some(free) = wallet(&quote) {
            return Ok(ok(json!({ "currency": quote, "free": free })));
        }
    }
    Ok(ok(json!({ "currency": base, "free": assets.global.btc_total })))
}

/// Ask each connected core once per connection whether a watcher follows its orders: the core
/// tells only when asked.
fn ask_watchers(session: &SessionManager, config: &AppConfig, asked: &mut HashSet<CoreId>) {
    for server in &config.servers {
        let ready = session
            .store()
            .core(server.id)
            .is_some_and(|data| matches!(data.status, moon_core::feed::ConnStatus::Ready));
        if !ready {
            asked.remove(&server.id);
            continue;
        }
        if server.synthetic || asked.contains(&server.id) {
            continue;
        }
        asked.insert(server.id);
        if let Err(e) = session.watchdog_cmd(server.id, moon_core::feed::WatchdogCmd::Refresh) {
            log::warn!("core {}: asking for the watcher failed: {e}", server.id);
        }
    }
}

/// Switch "Следить за ордерами этого бота" on one core, leaving the rest of its watcher
/// settings and rows as they are.
fn set_control(session: &SessionManager, config: &AppConfig, core: CoreId, on: bool) -> ApiResult<Value> {
    online(session, config, core)?;
    let state = session
        .store()
        .core(core)
        .filter(|data| data.watchdog_fresh)
        .and_then(|data| data.watchdog.clone())
        .ok_or_else(|| ApiError::new("no_state", "the core has not told its watcher settings yet"))?;
    let ip = state.server_ip.trim();
    if ip.is_empty() || ip == "0.0.0.0" {
        return Err(ApiError::new("no_watcher", "no watcher address is set on this core"));
    }
    let mut edit = state.edit();
    edit.sign_orders = on;
    // The rows are the watcher's own business: none is touched.
    edit.entries.clear();
    edit.validate().map_err(|e| ApiError::new("bad_request", format!("{e:?}")))?;
    session
        .watchdog_cmd(core, moon_core::feed::WatchdogCmd::Apply(edit))
        .map_err(|e| unavailable(e.to_string()))?;
    let _ = session.watchdog_cmd(core, moon_core::feed::WatchdogCmd::Refresh);
    log::info!("core {core}: watcher order control {}", if on { "on" } else { "off" });
    Ok(ok(json!({ "result": "sent", "on": on })))
}

/// Get the thumbnail and deltas of every card that has none yet, and forget those of cards
/// that are gone.
///
/// A live core's are read on a worker thread: the read copies history and may wait on the
/// kline cache, neither of which belongs on the owner. The worker raises `cards_dirty` when it
/// is done. The demo core's are computed here.
///
/// Returns:
///     Whether a thumbnail was filled in right away, so the cards should be published again.
fn request_sparks(
    config: &AppConfig,
    shared: &Arc<Shared>,
    cards: &[CardDto],
    asked: &mut HashMap<CardKey, (u8, Instant)>,
    now_ms: i64,
) -> bool {
    let alive: HashSet<CardKey> = cards.iter().map(|card| (card.core, card.seq)).collect();
    asked.retain(|key, _| alive.contains(key));
    shared
        .sparks
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .retain(|key, _| alive.contains(key));

    let mut filled = false;
    let mut jobs = Vec::new();
    for card in cards {
        let key = (card.core, card.seq);
        // A card is read once; one whose market had no history yet (a detect seconds old on a
        // market nothing here had open) is read again every few seconds, a few times, until the
        // line and the changes come.
        let incomplete = shared
            .sparks
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&key)
            .is_none_or(|spark| spark.line.is_empty() || spark.delta_1h.is_none());
        match asked.get(&key) {
            None => {}
            Some((tries, at)) if incomplete && *tries < SPARK_TRIES && at.elapsed() >= SPARK_RETRY => {}
            Some(_) => continue,
        }
        let tries = asked.get(&key).map_or(0, |(t, _)| *t);
        asked.insert(key, (tries + 1, Instant::now()));
        if config.servers.iter().any(|s| s.id == card.core && s.synthetic) {
            let day = chart::demo_day(&card.market, now_ms);
            let change = |from: f32, to: f32| f64::from((to - from) / from * 100.0);
            let last = day[day.len() - 1];
            let spark = Spark {
                delta_1h: Some(change(day[day.len() - 13], last)),
                delta_24h: Some(change(day[0], last)),
                line: cards::thin(&day, cards::SPARK_POINTS),
            };
            shared.sparks.lock().unwrap_or_else(|e| e.into_inner()).insert(key, spark);
            filled = true;
        } else if let Some(source) = shared.market.get() {
            let request = source.prepare_detect_snapshot(card.core, &card.market, SPARK_BARS);
            jobs.push((key, card.market.clone(), request));
        }
    }
    if !jobs.is_empty() {
        let shared = Arc::clone(shared);
        let spawned = thread::Builder::new().name("mm-sparks".into()).spawn(move || {
            for (key, market, request) in jobs {
                let built = request.build();
                let mut spark = Spark {
                    line: cards::thin(&built.line, cards::SPARK_POINTS),
                    delta_1h: Some(f64::from(built.delta_1h)),
                    delta_24h: Some(f64::from(built.delta_24h)),
                };
                if built.line.is_empty() {
                    // No history for this market here: the catalog still knows its deltas. Two
                    // exact zeros are a catalog that has not filled them, not a flat day.
                    let ticker = shared
                        .market
                        .get()
                        .and_then(|source| source.market_ticker(key.0, &market))
                        .filter(|t| t.delta_1h_pct != 0.0 || t.delta_24h_pct != 0.0);
                    spark.delta_1h = ticker.map(|t| t.delta_1h_pct);
                    spark.delta_24h = ticker.map(|t| t.delta_24h_pct);
                }
                shared.sparks.lock().unwrap_or_else(|e| e.into_inner()).insert(key, spark);
            }
            shared.cards_dirty.store(true, Ordering::Release);
        });
        if let Err(e) = spawned {
            log::warn!("detect thumbnails not read: {e}");
        }
    }
    filled
}

/// Execute one command. Runs on the owner thread.
///
/// Every command that changes something on a core is sent ONLY to a core that is connected right
/// now: a core's command channel survives a disconnect and replays what was queued when the core
/// comes back, which for a trading action can be long after the press meant anything.
fn handle(
    session: &mut SessionManager,
    config: &AppConfig,
    reconnects: &mut Vec<CoreId>,
    ranker: &Ranker,
    stops: &mut Vec<PendingStop>,
    call: Call,
) -> ApiResult<Value> {
    match call {
        // Served by `Engine::snapshot` without the owner; kept total for a caller that routes
        // everything here.
        Call::Snapshot { .. } => Ok(ok(json!({}))),
        Call::Log { max } => {
            let lines: Vec<Value> = moon_core::applog::snapshot(max.min(2000))
                .into_iter()
                .map(|line| {
                    json!({
                        "ts": line.ts,
                        "level": line.level.as_str(),
                        "target": line.target,
                        "msg": line.msg,
                    })
                })
                .collect();
            Ok(ok(json!({ "lines": lines })))
        }
        Call::Run { core, switch, on } => {
            known(config, core)?;
            let switch = match switch {
                RunSwitchName::Trading => RunSwitch::Trading,
                RunSwitchName::AutoDetect => RunSwitch::AutoDetect,
            };
            let outcome = session.dispatch_run(&[core], switch, on);
            if outcome.offline > 0 {
                return Err(offline(core));
            }
            if outcome.refused() > 0 {
                return Err(unavailable("the core's command channel refused the send"));
            }
            let result = if outcome.sent.is_empty() { "already" } else { "sent" };
            Ok(ok(json!({ "result": result })))
        }
        Call::CancelOrder { core, uid } => {
            online(session, config, core)?;
            order_listed(session, core, uid.0, false)?;
            session
                .cancel_order(core, uid.0)
                .map_err(|e| unavailable(e.to_string()))?;
            Ok(ok(json!({ "result": "sent" })))
        }
        Call::OrderPanic { core, uid, on } => {
            online(session, config, core)?;
            order_listed(session, core, uid.0, true)?;
            session
                .turn_order_panic_sell(core, uid.0, on)
                .map_err(|e| unavailable(e.to_string()))?;
            Ok(ok(json!({ "result": "sent" })))
        }
        Call::PanicMarket { core, market, on } => {
            online(session, config, core)?;
            let has_position = session.store().core(core).is_some_and(|data| {
                data.orders
                    .iter()
                    .any(|o| o.market == market && o.filled && !o.job_is_done)
            });
            if !has_position {
                return Err(ApiError::new(
                    "not_found",
                    format!("no open position on {market}"),
                ));
            }
            session
                .panic_sell_market(core, market, on)
                .map_err(|e| unavailable(e.to_string()))?;
            Ok(ok(json!({ "result": "sent" })))
        }
        Call::CancelBuys { core } => {
            online(session, config, core)?;
            // The buys the core has not filled yet: not on the exchange (`None`) or a limit buy
            // waiting (`BuySet`) — what the chart's Cancel Buy takes, once per market.
            let markets: Vec<String> = session
                .store()
                .core(core)
                .map(|data| {
                    data.orders
                        .iter()
                        .filter(|o| !o.job_is_done && matches!(o.status.as_str(), "None" | "BuySet"))
                        .map(|o| o.market.clone())
                        .collect()
                })
                .unwrap_or_default();
            let count = markets.len();
            let unique: std::collections::BTreeSet<String> = markets.into_iter().collect();
            for market in unique {
                session
                    .cancel_market_buys(core, market)
                    .map_err(|e| unavailable(e.to_string()))?;
            }
            Ok(ok(json!({ "result": "sent", "count": count })))
        }
        Call::CancelMarketBuys { core, market } => {
            online(session, config, core)?;
            session
                .cancel_market_buys(core, market)
                .map_err(|e| unavailable(e.to_string()))?;
            Ok(ok(json!({ "result": "sent" })))
        }
        Call::Accounts { refresh } => {
            let reply = crate::accounts::accounts(session, config, refresh);
            Ok(ok(serde_json::to_value(reply).unwrap_or(Value::Null)))
        }
        Call::JoinSells { core, market, short } => {
            online(session, config, core)?;
            let market = market.trim().to_string();
            let sells = session.store().core(core).map_or(0, |data| {
                data.orders
                    .iter()
                    .filter(|o| o.market == market && o.filled && o.is_short == short && !o.job_is_done)
                    .count()
            });
            if sells < 2 {
                return Err(ApiError::new("nothing_to_join", format!("fewer than two sells on {market}")));
            }
            session
                .join_sells(core, market, short)
                .map_err(|e| unavailable(e.to_string()))?;
            Ok(ok(json!({ "result": "sent", "count": sells })))
        }
        Call::SplitOrder { core, uid } => {
            online(session, config, core)?;
            let open = session
                .store()
                .core(core)
                .is_some_and(|data| data.orders.iter().any(|o| o.uid == uid.0 && o.filled && !o.job_is_done));
            if !open {
                return Err(ApiError::new("not_found", format!("no open position {}", uid.0)));
            }
            session
                .split_order(core, uid.0, 2)
                .map_err(|e| unavailable(e.to_string()))?;
            Ok(ok(json!({ "result": "sent" })))
        }
        Call::PlaceOrder { core, market, short, price, usd, take_pct, stop_pct } => {
            let market = market.trim();
            if let Some(pct) = stop_pct {
                if !(pct.is_finite() && pct > 0.0 && pct < 100.0) {
                    return Err(ApiError::new("bad_request", "a stop loss must be between 0% and 100%"));
                }
            }
            let before = market_uids(session, core, market);
            let reply = place_order(session, config, core, market, short, price, usd, take_pct)?;
            let entry = reply["price"].as_f64().unwrap_or(price);
            queue_placed(stops, core, market, short, before, stop_pct.and_then(|pct| stop_level(entry, pct, short)));
            Ok(reply)
        }
        Call::MoveLine { core, market, uid, role, price } => {
            online(session, config, core)?;
            move_line(session, core, market.trim(), uid.0, role, price)
        }
        Call::TakeTo { core, market, price, step } => {
            online(session, config, core)?;
            take_to(session, core, market.trim(), price, step)
        }
        Call::SetLeverage { core, market, leverage } => {
            online(session, config, core)?;
            let market = market.trim();
            if market.is_empty() || !(1..=1000).contains(&leverage) {
                return Err(ApiError::new("bad_request", "leverage must be 1..=1000 on a market"));
            }
            let figures = session.market_source().market_figures(core, market, false);
            if let Some(max) = figures.and_then(|f| f.max_leverage) {
                if leverage > max {
                    return Err(ApiError::new(
                        "above_max",
                        format!("{market} allows at most {max}x"),
                    ));
                }
            }
            session
                .set_leverage(core, market.to_string(), leverage)
                .map_err(|e| unavailable(e.to_string()))?;
            log::info!("leverage {leverage}x asked for core={core} market={market}");
            Ok(ok(json!({ "result": "sent", "leverage": leverage })))
        }
        Call::Markets { core, query, limit } => {
            known(config, core)?;
            let limit = limit.clamp(1, 100);
            let query = query.trim();
            let synthetic = config.servers.iter().any(|s| s.id == core && s.synthetic);
            let mut markets: Vec<String> = Vec::new();
            if query.is_empty() {
                // What the core is busy with: its orders first, then its newest detects.
                if let Some(data) = session.store().core(core) {
                    let mut push = |market: &str| {
                        if !market.is_empty() && !markets.iter().any(|m| m == market) {
                            markets.push(market.to_string());
                        }
                    };
                    for order in data.orders.iter().filter(|o| !o.job_is_done) {
                        push(&order.market);
                    }
                    let mut detects: Vec<_> = data.detects.iter().collect();
                    detects.sort_by(|a, b| b.time_ms.total_cmp(&a.time_ms));
                    for detect in detects {
                        push(&detect.market);
                    }
                }
                if synthetic && markets.is_empty() {
                    markets = chart::DEMO_MARKETS.iter().map(|m| m.to_string()).collect();
                }
            } else if synthetic {
                let query = query.to_uppercase();
                markets = chart::DEMO_MARKETS
                    .iter()
                    .filter(|m| m.contains(&query))
                    .map(|m| m.to_string())
                    .collect();
            } else {
                markets = session.market_source().search_markets(core, query, limit);
            }
            markets.truncate(limit);
            Ok(ok(json!({ "markets": markets })))
        }
        Call::Search { cores, query, limit } => {
            let limit = limit.clamp(1, 100);
            let query = query.trim().to_string();
            let source = session.market_source();
            let mut rows: Vec<Value> = Vec::new();
            let mut seen: HashSet<(Option<CoreId>, String)> = HashSet::new();
            for core in cores {
                let Some(server) = config.servers.iter().find(|s| s.id == core) else {
                    continue;
                };
                // A core with no connection has no market list to search.
                if !server.synthetic && online(session, config, core).is_err() {
                    continue;
                }
                let found = match handle(session, config, reconnects, ranker, stops, Call::Markets {
                    core,
                    query: query.clone(),
                    limit,
                }) {
                    Ok(reply) => reply,
                    Err(_) => continue,
                };
                let provider = source.provider_of(core);
                for market in found["markets"].as_array().into_iter().flatten() {
                    let Some(market) = market.as_str() else { continue };
                    // One row per market of an exchange, whichever of the cores reads it first.
                    let key = (provider.or(Some(core)), market.to_string());
                    if !seen.insert(key) {
                        continue;
                    }
                    let figures =
                        ranker.figures(Some(&source), server.synthetic, core, market, RankInterval::H1);
                    rows.push(json!({
                        "core": core,
                        "market": market,
                        "range_pct": figures.range_pct,
                        "change_pct": figures.change_pct,
                        "vol_24h": figures.vol_24h,
                        "vol": figures.vol,
                        "vol_1h_usdt": figures.vol_1h_usdt,
                    }));
                }
            }
            // Best matches of every core first, in the order the cores are listed.
            rows.truncate(limit);
            Ok(ok(json!({ "rows": rows })))
        }
        Call::Rank { .. }
        | Call::Keep { .. }
        | Call::Figures { .. }
        | Call::Report { .. }
        | Call::MarkHistory { .. }
        | Call::AlertsConfig { .. }
        | Call::Alerts
        | Call::StationKey { .. }
        | Call::StationProbe { .. }
        | Call::StationCheck { .. }
        | Call::StationInstall { .. }
        | Call::StationStatus { .. }
        | Call::StationCores { .. }
        | Call::StationRemoveCores { .. }
        | Call::StationUpdate { .. }
        | Call::StationBot { .. }
        | Call::StationTape { .. }
        | Call::StationAutoUpdate { .. }
        | Call::StationRemove { .. }
        | Call::StationForget { .. }
        | Call::StationJob
        | Call::DayStats { .. }
        | Call::ReportFile { .. } => {
            Err(ApiError::new("bad_request", "served off the owner"))
        }
        Call::FreeBalance { core, market } => free_balance(session, core, market.trim()),
        Call::AddCore { .. } | Call::SetControl { .. } => {
            Err(ApiError::new("bad_request", "handled by the owner loop"))
        }
        Call::Strategy { core, id, on } => {
            online(session, config, core)?;
            let checked = session
                .store()
                .core(core)
                .and_then(|data| data.strategies.iter().find(|s| s.id == id.0))
                .map(|s| s.checked);
            match checked {
                None => Err(ApiError::new("not_found", "no such strategy on this core")),
                Some(now) if now == on => Ok(ok(json!({ "result": "already" }))),
                Some(_) => {
                    session
                        .apply_strategies(core, vec![(id.0, on)], None)
                        .map_err(|e| unavailable(e.to_string()))?;
                    Ok(ok(json!({ "result": "sent" })))
                }
            }
        }
        Call::Reconnect { core } => {
            known(config, core)?;
            if !reconnects.contains(&core) {
                reconnects.push(core);
            }
            Ok(ok(json!({ "result": "queued" })))
        }
        Call::Restart { core } => {
            online(session, config, core)?;
            session
                .restart_now(core)
                .map_err(|e| unavailable(e.to_string()))?;
            Ok(ok(json!({ "result": "sent" })))
        }
        Call::StrategyGet { core, id } => {
            known(config, core)?;
            let data = session
                .store()
                .core(core)
                .ok_or_else(|| ApiError::new("not_found", "no data from this core yet"))?;
            let row = data
                .strategies
                .iter()
                .find(|s| s.id == id.0)
                .ok_or_else(|| ApiError::new("not_found", "no such strategy on this core"))?;
            let view = strategy::view(core, row, data.schema.as_ref(), data.strategy_edit(id.0));
            let view = serde_json::to_value(view)
                .map_err(|e| ApiError::new("internal", e.to_string()))?;
            Ok(ok(json!({ "strategy": view })))
        }
        Call::StrategyEdit { core, id, changes } => {
            online(session, config, core)?;
            let data = session
                .store()
                .core(core)
                .ok_or_else(|| ApiError::new("not_found", "no data from this core yet"))?;
            let row = data
                .strategies
                .iter()
                .find(|s| s.id == id.0)
                .ok_or_else(|| ApiError::new("not_found", "no such strategy on this core"))?;
            let edits = strategy::checked_changes(row, data.schema.as_ref(), &changes)?;
            if edits.is_empty() {
                return Ok(ok(json!({ "result": "already" })));
            }
            let count = edits.len();
            session
                .edit_strategies(core, vec![(id.0, edits)])
                .map_err(|e| unavailable(e.to_string()))?;
            Ok(ok(json!({ "result": "sent", "count": count })))
        }
    }
}

/// Place one manual order as the terminal's chart does (`manual_order_terms`, `place_order`):
/// the dollar amount becomes the market's own unit, the take profit rides with the order as an
/// absolute price and as the exit generation the core confirms before the order goes out, and
/// the stop loss is the one saved for the core in the imported settings.
#[allow(clippy::too_many_arguments)]
fn place_order(
    session: &SessionManager,
    config: &AppConfig,
    core: CoreId,
    market: &str,
    short: bool,
    price: f64,
    usd: f64,
    take_pct: f64,
) -> ApiResult<Value> {
    online(session, config, core)?;
    let Some(server) = config.servers.iter().find(|s| s.id == core) else {
        return Err(ApiError::new("not_found", format!("no core {core}")));
    };
    if market.is_empty() || !(price.is_finite() && price > 0.0) || !(usd.is_finite() && usd > 0.0) {
        return Err(ApiError::new("bad_request", "an order needs a market, a price and an amount"));
    }
    let source = session.market_source();
    let kind = session
        .core_venues()
        .get(&core)
        .and_then(|venue| venue.resolved())
        .map(|venue| venue.kind);
    if short && kind == Some(MarketKind::Spot) {
        return Err(ApiError::new("spot_short", "a spot market cannot be shorted"));
    }
    // A spot account in another currency than the market's quote cannot pay for it, unless the
    // core borrows on margin (the terminal's `manual_quote_mismatch`).
    if kind == Some(MarketKind::Spot) {
        let account = session.core_base(core).map(str::trim).unwrap_or("");
        let borrowing = session
            .store()
            .core(core)
            .filter(|data| !data.core_config_stale)
            .and_then(|data| data.core_config.as_ref())
            .map(|config| config.special.auto_margin_borrow);
        let quote = source.market_label(core, market).quote;
        let quote = quote.trim();
        if borrowing == Some(false)
            && !account.is_empty()
            && !quote.is_empty()
            && !account.eq_ignore_ascii_case(quote)
        {
            return Err(ApiError::new(
                "quote_mismatch",
                format!("the account is in {account}, {market} is priced in {quote}"),
            ));
        }
    }
    // On the market's price grid, as the line the finger pointed at.
    let price = match source.price_tick(core, market).filter(|tick| *tick > 0.0) {
        Some(tick) => ((price / tick).round() * tick).max(tick),
        None => price,
    };
    let size = if server.synthetic {
        usd / price
    } else {
        let rules = source.order_size_rules(core, market).ok_or_else(|| {
            ApiError::new("no_market_info", format!("{market}: the market's figures have not arrived"))
        })?;
        if let Some(floor) = rules.min_order_usd.filter(|floor| usd < *floor) {
            return Err(ApiError::new(
                "below_min",
                format!("{market} takes orders from ${floor:.2}"),
            ));
        }
        // Inverse markets take the coin; linear and spot ones the account's currency.
        let rate = match rules.unit {
            MarketQuantityUnit::Contracts(_) => Some(price),
            MarketQuantityUnit::Coins => session
                .core_base(core)
                .and_then(|base| source.currency_usd_rate(core, base)),
        };
        match rate.filter(|rate| rate.is_finite() && *rate > 0.0) {
            Some(rate) => usd / rate,
            None => {
                return Err(ApiError::new(
                    "no_rate",
                    "the account currency's dollar rate is not known yet",
                ))
            }
        }
    };
    if !(size.is_finite() && size > 0.0) {
        return Err(ApiError::new("bad_request", "the order size came out empty"));
    }
    // Not sent to the core (see below): the order carries its own take.
    let exit = GroupExitSettings::default();
    if !(take_pct.is_finite() && take_pct > 0.0 && take_pct <= 900.0) {
        return Err(ApiError::new("bad_request", "take profit must be above 0% and up to 900%"));
    }
    let take = take_pct;
    let take_price = moon_core::feed::percentage_take_price(price, take, short)
        .map(|target| on_tick(session, core, market, target))
        .unwrap_or(0.0);
    // The take rides with the order itself (its planned sell), and the core's own exit settings
    // stay as they are: synchronising them first, as the terminal's toolbar does, would write this
    // take over the fixed takes set in Moonbot.
    session
        .place_order(core, market.to_string(), short, price, size, None, exit, take_price, false)
        .map_err(|e| unavailable(e.to_string()))?;
    log::info!(
        "manual order sent: core={core} market={market} side={} price={price} size={size} \
         usd={usd} take={take}% ({take_price})",
        if short { "short" } else { "long" }
    );
    let stop: Option<f32> = None;
    Ok(ok(json!({
        "result": "sent",
        "price": price,
        "size": size,
        "usd": usd,
        "take_pct": take,
        "take_price": take_price,
        "stop_loss_pct": stop,
    })))
}

/// How long a queued stop waits for its order to appear: the terminal's own limit.
const PENDING_STOP_TTL: Duration = Duration::from_secs(15);

/// A stop loss for an order just sent, put on it as soon as the core lists it.
pub struct PendingStop {
    core: CoreId,
    market: String,
    short: bool,
    /// The market's orders before this one, so the new one is the order not among them.
    before: HashSet<u64>,
    /// The stop to put on it, when one was asked for.
    form: Option<moon_core::feed::OrderStopsForm>,
    at: Instant,
}

/// The stop's price for an entry: below a long, above a short.
fn stop_level(entry: f64, pct: f64, short: bool) -> Option<f64> {
    let level = if short { entry * (1.0 + pct / 100.0) } else { entry * (1.0 - pct / 100.0) };
    (level.is_finite() && level > 0.0).then_some(level)
}

/// Queue the chart's stop for the order about to appear, as the terminal does for its own: an
/// absolute price written on the order, so neither a strategy nor the core's settings decide it.
/// The uids of a market's orders on a core, as they are now.
fn market_uids(session: &SessionManager, core: CoreId, market: &str) -> HashSet<u64> {
    session
        .store()
        .core(core)
        .map(|data| data.order_lines.iter_market(market).map(|order| order.uid).collect())
        .unwrap_or_default()
}

/// Remember an order placed from the phone until the core lists it: then it is marked as the
/// phone's own, and gets its stop when one was asked for.
fn queue_placed(
    stops: &mut Vec<PendingStop>,
    core: CoreId,
    market: &str,
    short: bool,
    before: HashSet<u64>,
    stop_level: Option<f64>,
) {
    // One order per market and side waits at a time: a newer click replaces an older one, and
    // the older one's uids are no longer "before" it.
    stops.retain(|stop| !(stop.core == core && stop.market == market && stop.short == short));
    stops.push(PendingStop {
        core,
        market: market.to_string(),
        short,
        before,
        form: stop_level.map(|level| moon_core::feed::OrderStopsForm {
            sl: Some(moon_core::feed::StopGroupEdit { on: true, fixed: true, price: level }),
            ..Default::default()
        }),
        at: Instant::now(),
    });
}

/// Mark each placed order as the phone's own once the core lists it, and put its stop on it;
/// drop those that waited too long.
fn tick_stops(session: &SessionManager, stops: &mut Vec<PendingStop>, mine: &mut HashSet<(CoreId, u64)>) {
    stops.retain(|stop| {
        if stop.at.elapsed() >= PENDING_STOP_TTL {
            log::warn!("core {} {}: a placed order never appeared", stop.core, stop.market);
            return false;
        }
        let uid = session.store().core(stop.core).and_then(|data| {
            data.order_lines
                .iter_market(&stop.market)
                .find(|order| {
                    order.closed_ms.is_none()
                        && !order.pending
                        && order.is_short == stop.short
                        && !stop.before.contains(&order.uid)
                })
                .map(|order| order.uid)
        });
        let Some(uid) = uid else { return true };
        mine.insert((stop.core, uid));
        if let Some(form) = stop.form {
            match session.update_order_stops(stop.core, uid, form) {
                Ok(()) => log::info!("core {} {}: stop put on order {uid}", stop.core, stop.market),
                Err(e) => log::warn!("core {} {}: stop on order {uid} failed: {e}", stop.core, stop.market),
            }
        }
        false
    });
}

/// What moving an order needs to know of it.
#[derive(Clone, Copy)]
struct Movable {
    uid: u64,
    seq: u64,
    number: u32,
    short: bool,
    fill_pct: f32,
    panic_sell: bool,
}

/// The market's open orders that are not waiting on a trigger, in the order they were opened.
fn movable(session: &SessionManager, core: CoreId, market: &str) -> Vec<Movable> {
    let mut orders: Vec<Movable> = session
        .store()
        .core(core)
        .map(|data| {
            data.order_lines
                .iter_open_market(market)
                .filter(|o| !o.pending)
                .map(|o| Movable {
                    uid: o.uid,
                    seq: o.seq as u64,
                    number: o.chart_num,
                    short: o.is_short,
                    fill_pct: o.fill_pct,
                    panic_sell: o.panic_sell,
                })
                .collect()
        })
        .unwrap_or_default();
    orders.sort_by_key(|o| o.seq);
    orders
}

/// An open order of the core by its id, from the core's own list.
fn listed_order(session: &SessionManager, core: CoreId, uid: u64) -> Option<Movable> {
    let data = session.store().core(core)?;
    let row = data.orders.iter().find(|o| o.uid == uid && !o.job_is_done)?;
    Some(Movable {
        uid,
        seq: 0,
        number: 0,
        short: row.is_short,
        fill_pct: if row.filled { 100.0 } else { 0.0 },
        panic_sell: data.order_lines.order_panic_sell(uid),
    })
}

/// On the market's price grid.
fn on_tick(session: &SessionManager, core: CoreId, market: &str, price: f64) -> f64 {
    match session.market_source().price_tick(core, market).filter(|tick| *tick > 0.0) {
        Some(tick) => ((price / tick).round() * tick).max(tick),
        None => price,
    }
}

/// Move one level of an order, routed as the terminal routes a dragged line.
fn move_line(
    session: &SessionManager,
    core: CoreId,
    market: &str,
    uid: u64,
    role: LineRole,
    price: f64,
) -> ApiResult<Value> {
    if !(price.is_finite() && price > 0.0) {
        return Err(ApiError::new("bad_request", "a line moves to a positive price"));
    }
    // The market's line store first; failing it, the core's open orders as listed — an order
    // the line store has not caught up with, or filed under another market name.
    let order = movable(session, core, market)
        .into_iter()
        .find(|o| o.uid == uid)
        .or_else(|| listed_order(session, core, uid))
        .ok_or_else(|| {
            log::warn!("line move: no open order uid={uid} on core={core} market={market}");
            ApiError::new("not_found", format!("no open order {uid} on {market}"))
        })?;
    let price = on_tick(session, core, market, price);
    let sent = match role {
        LineRole::Entry if order.fill_pct > 0.0 => {
            return Err(ApiError::new("bad_request", "a filled entry does not move"));
        }
        LineRole::Filled => return Err(ApiError::new("bad_request", "a filled entry does not move")),
        LineRole::Liquidation => {
            return Err(ApiError::new("bad_request", "the liquidation price is the exchange's, not an order's"));
        }
        LineRole::Entry => session.move_order(core, uid, price),
        LineRole::Exit => {
            // Under panic sell the core would hold the exit where it is: drop that first.
            if order.panic_sell {
                let _ = session.turn_order_panic_sell(core, uid, false);
            }
            session.move_order(core, uid, price)
        }
        LineRole::Stop => session.move_order_stop_price(core, uid, OrderLinePriceKind::StopLoss, price),
        LineRole::Trailing => {
            session.move_order_stop_price(core, uid, OrderLinePriceKind::Trailing, price)
        }
        LineRole::Take => session.move_order_stop_price(core, uid, OrderLinePriceKind::TakeProfit, price),
    };
    sent.map_err(|e| unavailable(e.to_string()))?;
    log::info!("line moved: core={core} market={market} uid={uid} role={role:?} price={price}");
    Ok(ok(json!({ "result": "sent", "price": price })))
}

/// The Take button: the exits of the open positions one at a time, in the order they were
/// opened; with no position, every waiting entry together when they are all of one side.
fn take_to(
    session: &SessionManager,
    core: CoreId,
    market: &str,
    price: f64,
    step: usize,
) -> ApiResult<Value> {
    if !(price.is_finite() && price > 0.0) {
        return Err(ApiError::new("bad_request", "a price is needed"));
    }
    let price = on_tick(session, core, market, price);
    let orders = movable(session, core, market);
    let positions: Vec<_> = orders.iter().filter(|o| o.fill_pct > 0.0).collect();
    if !positions.is_empty() {
        let order = positions[step % positions.len()];
        if order.panic_sell {
            let _ = session.turn_order_panic_sell(core, order.uid, false);
        }
        session.move_order(core, order.uid, price).map_err(|e| unavailable(e.to_string()))?;
        log::info!("take: exit of order {} on {market} moved to {price}", order.uid);
        return Ok(ok(json!({
            "result": "exit",
            "uid": order.uid,
            "number": order.number,
            "of": positions.len(),
            "price": price,
        })));
    }
    let entries: Vec<_> = orders.iter().filter(|o| o.fill_pct <= 0.0).collect();
    if entries.is_empty() {
        return Err(ApiError::new("nothing_to_move", format!("no orders on {market}")));
    }
    let short = entries[0].short;
    if entries.iter().any(|o| o.short != short) {
        return Err(ApiError::new(
            "mixed_sides",
            "long and short entries wait together: the Take button moves neither",
        ));
    }
    if entries.len() == 1 {
        session.move_order(core, entries[0].uid, price).map_err(|e| unavailable(e.to_string()))?;
    } else {
        // Moonbot's parallel shift: the entry nearest the price lands on it, the rest keep
        // their spacing.
        let side = if short { MoveSide::Short } else { MoveSide::Long };
        session
            .move_orders_to_price(core, market.to_string(), false, MoveKind::ParallelShift, price, side)
            .map_err(|e| unavailable(e.to_string()))?;
    }
    log::info!("take: {} entries on {market} moved to {price}", entries.len());
    Ok(ok(json!({ "result": "entries", "of": entries.len(), "price": price })))
}

/// The core is one of the configured ones.
fn known(config: &AppConfig, core: CoreId) -> ApiResult<()> {
    if config.servers.iter().any(|s| s.id == core) {
        Ok(())
    } else {
        Err(ApiError::new("not_found", format!("no core {core}")))
    }
}

/// The core is configured and connected right now.
fn online(session: &SessionManager, config: &AppConfig, core: CoreId) -> ApiResult<()> {
    known(config, core)?;
    if session.core_run_state(core).online {
        Ok(())
    } else {
        Err(offline(core))
    }
}

/// The order is on the core's open orders; with `position`, it must also have filled.
fn order_listed(session: &SessionManager, core: CoreId, uid: u64, position: bool) -> ApiResult<()> {
    let listed = session.store().core(core).is_some_and(|data| {
        data.orders
            .iter()
            .any(|o| o.uid == uid && !o.job_is_done && (!position || o.filled))
    });
    if listed {
        Ok(())
    } else {
        Err(ApiError::new("not_found", "no such open order on this core"))
    }
}

fn offline(core: CoreId) -> ApiError {
    ApiError::new("offline", format!("core {core} is not connected; nothing was sent"))
}

fn unavailable(detail: impl Into<String>) -> ApiError {
    ApiError::new("unavailable", detail)
}

#[cfg(test)]
mod tests {
    use super::{api_limit_reason, is_api_limit, merge_ticks};
    use crate::chart::TickPoint;

    fn ticks(times: &[i64]) -> Vec<TickPoint> {
        times.iter().map(|&t_ms| TickPoint { t_ms, price: 1.0, qty: t_ms as f32, buy: true }).collect()
    }

    #[test]
    fn the_mark_line_is_the_cores_with_the_exchange_around_it() {
        let state = super::ExtraState {
            marks: vec![(100_000, 2.0), (102_000, 2.1)],
            exchange: vec![(40_000, 1.0), (99_000, 1.5), (101_000, 9.0), (105_000, 9.0), (111_000, 3.0)],
            ..Default::default()
        };
        // Before the core's first point and after it went quiet: the exchange; between: the core.
        let line: Vec<i64> = state.mark_line().iter().map(|(t, _)| *t).collect();
        assert_eq!(line, vec![40_000, 99_000, 100_000, 102_000, 111_000]);
        // No core points: the exchange alone.
        let alone = super::ExtraState { exchange: vec![(1, 1.0)], ..Default::default() };
        assert_eq!(alone.mark_line(), vec![(1, 1.0)]);
    }

    #[test]
    fn a_ring_that_started_over_keeps_the_trades_already_held() {
        let mut held = Vec::new();
        merge_ticks(&mut held, ticks(&[10, 20, 30, 40]), 0, 100);
        // The ring now holds only what came after it started over.
        merge_ticks(&mut held, ticks(&[40, 50]), 0, 100);
        assert_eq!(held.iter().map(|t| t.t_ms).collect::<Vec<_>>(), vec![10, 20, 30, 40, 50]);
        // An empty read changes nothing; a read of an older stretch replaces only that stretch.
        merge_ticks(&mut held, Vec::new(), 0, 100);
        merge_ticks(&mut held, ticks(&[20, 25, 30]), 0, 100);
        assert_eq!(held.iter().map(|t| t.t_ms).collect::<Vec<_>>(), vec![10, 20, 25, 30, 40, 50]);
        // Too old and too many are dropped from the front.
        merge_ticks(&mut held, Vec::new(), 25, 100);
        assert_eq!(held.first().map(|t| t.t_ms), Some(25));
        merge_ticks(&mut held, Vec::new(), 0, 2);
        assert_eq!(held.iter().map(|t| t.t_ms).collect::<Vec<_>>(), vec![40, 50]);
    }

    #[test]
    fn a_successful_reply_naming_its_limits_is_no_refusal() {
        let line = r#"07:33:13 ETH: [1] (3) WS_API <1360251069895767> => {"id":"1360251069895767","status":200,"result":{"symbol":"ETHUSDT","orderId":51392774749},"rateLimits":[{"rateLimitType":"REQUEST_WEIGHT","interval":"MINUTE","limit":6000,"count":12}]}"#;
        assert!(!is_api_limit(line));
    }

    #[test]
    fn refusals_are_found_and_said_briefly() {
        let line = r#"07:33:13 ETH: WS_API => {"status":429,"error":{"code":-1003,"msg":"Too many requests; current limit is 6000 request weight per 1 MINUTE."}}"#;
        assert!(is_api_limit(line));
        assert_eq!(api_limit_reason(line), "ETH: Слишком много запросов");
        assert!(is_api_limit("Rate limit exceeded for this IP"));
        assert!(!is_api_limit("rateLimits: REQUEST_WEIGHT 12/6000"));
        assert_eq!(api_limit_reason("Way too many requests; IP banned until 1700000000"), "IP заблокирован биржей за превышение лимитов");
    }
}
