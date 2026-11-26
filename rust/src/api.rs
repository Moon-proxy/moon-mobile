//! Wire types of the bridge: every request and reply is one JSON object.
//!
//! The same shapes serve the Android app (through JNI, in-process) and, later, the server
//! gateway (over a socket), so nothing here may depend on where the engine runs.

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

/// Where the cores come from.
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "source", rename_all = "snake_case")]
pub enum Source {
    /// The terminal's own `servers.enc` (and `cfg/` when present) copied into `data_dir`.
    /// `password` is the file's ENCRYPTION password (Settings → Security on the desktop).
    ServersEnc { password: String },
    /// Cores given directly by their keys (Moonbot → Copy Key). Nothing is written to disk
    /// by the bridge; the app keeps the keys in the platform's secure storage.
    Keys { cores: Vec<KeyCore> },
    /// One synthetic core with no network: a deterministic demo of every screen.
    Demo,
}

/// One core named by its key.
#[derive(Debug, Clone, Deserialize)]
pub struct KeyCore {
    /// Stable non-zero id chosen by the app; it keys everything the core owns.
    pub uid: u64,
    pub name: String,
    pub key: String,
    /// `host:port`, `:port` or a host; empty follows the key.
    #[serde(default)]
    pub endpoint_override: String,
}

/// How much market data the session keeps.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum MarketData {
    /// Accounts only: status, orders, detects, strategies. No trade stream at all, so memory
    /// and traffic stay small; a chart cannot be drawn.
    #[default]
    None,
    /// Charts on demand: one provider per exchange and currency keeps trades and the order book
    /// only for the markets whose chart is open, and no trade stream while none is.
    Scoped,
    /// The terminal's own mode: one provider per exchange and currency keeps every market's
    /// history (about 1–1.5 GB per exchange). Needed for the spike screener.
    Full,
}

impl MarketData {
    pub fn as_str(self) -> &'static str {
        match self {
            MarketData::None => "none",
            MarketData::Scoped => "scoped",
            MarketData::Full => "full",
        }
    }
}

/// `open` request.
#[derive(Debug, Clone, Deserialize)]
pub struct OpenRequest {
    /// App-private directory holding `servers.enc`, `cfg/`, logs and caches.
    pub data_dir: String,
    #[serde(flatten)]
    pub source: Source,
    #[serde(default)]
    pub market_data: MarketData,
    /// Retained-history depth, percent of the terminal's baseline (75..=800). Only read in
    /// `Full` mode; absent keeps the value from `settings.toml` or the default.
    #[serde(default)]
    pub chart_memory_percent: Option<u16>,
    /// The app's own changes to cores, over what `settings.toml` says.
    #[serde(default)]
    pub overrides: Vec<CoreOverride>,
    /// Have the cores send their log: what the API-limit notifications read.
    #[serde(default)]
    pub core_log: bool,
    /// Orders placed from this phone in earlier sessions, as `core:uid`.
    #[serde(default)]
    pub mine: Vec<String>,
}

/// One core as the app changed it: switched off or on, or another colour.
#[derive(Debug, Clone, Deserialize)]
pub struct CoreOverride {
    pub core: u64,
    #[serde(default)]
    pub active: Option<bool>,
    /// `#rrggbb`.
    #[serde(default)]
    pub color: Option<String>,
}

/// A market of one core.
#[derive(Debug, Clone, Deserialize)]
pub struct ChartRef {
    pub core: u64,
    pub market: String,
}

/// One call on an open engine.
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case")]
pub enum Call {
    /// The latest state. With `since` equal to the current revision the reply is only
    /// `{"ok":true,"rev":N,"changed":false}`.
    Snapshot {
        #[serde(default)]
        since: Option<u64>,
    },
    /// Flip a run switch of one core: `trading` is Moonbot's own Start/Stop of the strategy
    /// engine, `auto_detect` its AutoDetect.
    Run {
        core: u64,
        switch: RunSwitchName,
        on: bool,
    },
    /// Cancel one open order.
    CancelOrder { core: u64, uid: Uid },
    /// Arm or disarm Panic Sell on one open position.
    OrderPanic { core: u64, uid: Uid, on: bool },
    /// Arm or disarm Panic Sell on every position of one market.
    PanicMarket { core: u64, market: String, on: bool },
    /// Cancel every buy of the core that has not filled yet. Positions and sells stay.
    CancelBuys { core: u64 },
    /// Cancel the buys of one market that have not filled yet: the chart's Cancel Buy.
    CancelMarketBuys { core: u64, market: String },
    /// The total balance taken apart: each exchange account once, with its coins and positions.
    /// With `refresh`, the counted spot cores are asked for their wallet list again.
    Accounts {
        #[serde(default)]
        refresh: bool,
    },
    /// Join every sell of one market and side into one, at their average: the chart's Join.
    /// A LIVE exchange action.
    JoinSells { core: u64, market: String, short: bool },
    /// Split one sell into two at its price: the chart's Split. A LIVE exchange action.
    SplitOrder { core: u64, uid: Uid },
    /// Place a manual order the way the terminal's chart does: `usd` worth at `price`, Long or
    /// Short, with a take profit `take_pct` from the entry. The stop loss is the one saved for
    /// the core's group in the imported settings, if any. A LIVE exchange action.
    PlaceOrder {
        core: u64,
        market: String,
        #[serde(default)]
        short: bool,
        price: f64,
        usd: f64,
        take_pct: f64,
        /// A stop loss this far from the entry, percent, put on the order once the core lists
        /// it; absent leaves the stop to the core's own settings.
        #[serde(default)]
        stop_pct: Option<f64>,
    },
    /// Move one level of an order to `price`, as when its line is dragged: the entry while not
    /// filled, the exit, the stop, the trailing stop or the take profit. A LIVE exchange action.
    MoveLine {
        core: u64,
        market: String,
        uid: Uid,
        role: crate::chart::LineRole,
        price: f64,
    },
    /// The chart's Take button: move the market's exits to `price` one at a time (the
    /// `step`-th exit in queue order), or with no position the waiting entries all together,
    /// keeping their spacing, when they are all of one side. A LIVE exchange action.
    TakeTo {
        core: u64,
        market: String,
        price: f64,
        #[serde(default)]
        step: usize,
    },
    /// Set the market's leverage on the exchange through the core. A LIVE exchange action.
    SetLeverage { core: u64, market: String, leverage: i32 },
    /// Markets of the core's exchange matching `query`, best match first, for the chart's
    /// market box. A blank query answers with the markets the core has orders or detects on.
    Markets {
        core: u64,
        #[serde(default)]
        query: String,
        #[serde(default = "default_markets_limit")]
        limit: usize,
    },
    /// Markets matching `query` across several cores, best match first, each named once per
    /// exchange (with the first listed core that reads it). A blank query answers with the
    /// markets the cores have orders or detects on.
    Search {
        cores: Vec<u64>,
        #[serde(default)]
        query: String,
        #[serde(default = "default_markets_limit")]
        limit: usize,
    },
    /// The report of the period, filtered as [`Call::Report`], written to `path` as an Excel
    /// workbook or CSV, dates in the zone `tz` (IANA; the phone's own when empty). Replies with
    /// the number of trades; with none no file is written. Served off the owner.
    ReportFile {
        from_ms: i64,
        to_ms: i64,
        #[serde(default)]
        cores: Vec<u64>,
        #[serde(default)]
        coin: String,
        #[serde(default)]
        strategy: String,
        #[serde(default)]
        emulator: bool,
        format: crate::export::ExportFormat,
        path: String,
        #[serde(default)]
        tz: String,
    },
    /// The app's notification switches (Settings → Уведомления) and its names for the servers.
    /// Nothing is notified before the first one. Served off the owner.
    AlertsConfig {
        settings: crate::alerts::AlertSettings,
        #[serde(default)]
        server_names: std::collections::HashMap<String, String>,
    },
    /// Notices and detect sounds decided since the last call, oldest first:
    /// `{"notices":[{"title","text"}],"sounds":["name"]}`. Served off the owner.
    Alerts,
    /// The station on a Linux server (Settings → Станция), by MoonTerminal's `moon-remote`: see
    /// `station.rs`. The ones that talk to the server start a background job; `StationJob` reads
    /// its progress and its end. Served off the owner.
    StationKey {
        #[serde(default)]
        key: Option<String>,
    },
    StationProbe { server: crate::station::Server },
    StationCheck { server: crate::station::Server, login: crate::station::Login, fingerprint: String },
    StationInstall {
        server: crate::station::Server,
        login: crate::station::Login,
        fingerprint: String,
        cores: Vec<u64>,
    },
    StationStatus { server: crate::station::Server },
    StationCores { server: crate::station::Server, cores: Vec<u64> },
    StationRemoveCores { server: crate::station::Server, uids: Vec<u64> },
    StationUpdate { server: crate::station::Server },
    StationBot {
        server: crate::station::Server,
        #[serde(default)]
        token: Option<String>,
        #[serde(default)]
        off: bool,
    },
    StationTape { server: crate::station::Server, margin_s: u32, long_position_min: u32 },
    StationAutoUpdate { server: crate::station::Server, on: bool },
    StationRemove { server: crate::station::Server },
    StationForget { server: crate::station::Server },
    StationJob,
    /// The trades closed in a day for the home-screen widget, by kind of strategy. Served off
    /// the owner.
    DayStats { from_ms: i64, to_ms: i64 },
    /// The markets of the cores' exchanges ranked by how far the price moved over `interval`
    /// (`1m`, `5m`, `15m`, `1h`, `1d`) or by the day's volume. Read-only; served off the owner.
    Rank {
        cores: Vec<u64>,
        by: crate::rank::RankBy,
        interval: crate::rank::RankInterval,
        #[serde(default = "default_markets_limit")]
        limit: usize,
    },
    /// Keep these charts' markets streaming though they are not drawn: the chart tabs in the
    /// background. Replaces the previous list. Read-only; served off the owner.
    Keep { charts: Vec<ChartRef> },
    /// How far these markets moved over `interval` and the day's volume, as the ranking measures
    /// them. Read-only; served off the owner.
    Figures {
        items: Vec<ChartRef>,
        interval: crate::rank::RankInterval,
    },
    /// Add a core by its MoonBot key: it connects at once, and is written into `servers.enc` only
    /// once it answered. The snapshot's `adding` tells how the check goes.
    AddCore {
        key: String,
        #[serde(default)]
        name: String,
    },
    /// What a core really has free for one market: in the market's own margin currency (USDC on
    /// a USDC contract of a USDT core), or in the coin of a coin-margined (quarterly) contract.
    FreeBalance { core: u64, market: String },
    /// Switch "Следить за ордерами этого бота" (the watcher's order control) on one core.
    SetControl { core: u64, on: bool },
    /// Tick or untick one strategy. Does not start or stop the engine.
    Strategy { core: u64, id: Uid, on: bool },
    /// Rebuild the core's connection on a fresh client.
    Reconnect { core: u64 },
    /// Moonbot's own "Start / Restart": start the market runtime if it is down, leave passive
    /// mode, start the ticked strategies. It does not restart the core's process.
    Restart { core: u64 },
    /// One strategy laid out by its schema, for the editor.
    StrategyGet { core: u64, id: Uid },
    /// Change fields of one strategy. `changes` maps field names to their new text; every value
    /// is checked against the field's type first and a single bad one refuses the whole edit.
    StrategyEdit {
        core: u64,
        id: Uid,
        changes: std::collections::BTreeMap<String, String>,
    },
    /// One page of the cores' report, closed trades between `from_ms` and `to_ms` (true UTC,
    /// at most 366 days), newest first, with the totals of the whole period. Empty `cores`
    /// reads them all. Read-only; served off the owner.
    Report {
        from_ms: i64,
        to_ms: i64,
        #[serde(default)]
        cores: Vec<u64>,
        #[serde(default)]
        offset: usize,
        #[serde(default = "default_report_limit")]
        limit: usize,
        /// A coin (part of its name) and a strategy name to narrow the rows and totals to.
        #[serde(default)]
        coin: String,
        #[serde(default)]
        strategy: String,
        /// Count the cores' emulator trades too.
        #[serde(default)]
        emulator: bool,
    },
    /// The mark price of a chart's market before the chart opened, `[t_ms, price]` pairs the
    /// app fetched from the exchange. Read-only; served off the owner.
    MarkHistory {
        core: u64,
        market: String,
        points: Vec<(i64, f64)>,
    },
    /// The newest application log lines.
    Log {
        #[serde(default = "default_log_max")]
        max: usize,
    },
}

fn default_report_limit() -> usize {
    200
}

fn default_log_max() -> usize {
    200
}

fn default_markets_limit() -> usize {
    30
}

/// One chart frame to draw. The picture goes into the caller's pixel buffer; the reply carries
/// what maps a touch back onto it.
#[derive(Debug, Clone, Deserialize)]
pub struct ChartRequest {
    pub core: u64,
    pub market: String,
    /// The buffer's size in pixels; it holds `width * height * 4` bytes of premultiplied RGBA.
    pub width: u32,
    pub height: u32,
    /// Pixels per density-independent unit of the screen, for text and stroke sizes.
    #[serde(default = "default_scale")]
    pub scale: f32,
    /// Width of the time window.
    pub span_ms: i64,
    /// Right edge of the window in Unix milliseconds. Absent follows the present, with a little
    /// room to the right of the last trade.
    #[serde(default)]
    pub end_ms: Option<i64>,
    /// The visible price range as a percentage of the price: 20 shows a band 20% of the price
    /// tall around the last price. Absent or zero fits the range to what the window holds.
    #[serde(default)]
    pub scale_pct: Option<f64>,
    /// A crosshair at this point of the picture, in pixels, with its readouts.
    #[serde(default)]
    pub cursor: Option<[f32; 2]>,
    /// Mark the order book in thirds per side, each mark naming the depth from the spread to it.
    #[serde(default)]
    pub book_marks: bool,
    /// The take's levels for a moment after its % is picked: this % above and below the
    /// middle of the spread, dashed across the plot and the book.
    #[serde(default)]
    pub take_flash_pct: Option<f64>,
    /// When the trading session began (Unix ms): the chart shows what the market's trades
    /// closed since then made, beside the funding. Absent shows no session.
    #[serde(default)]
    pub session_from_ms: Option<i64>,
    /// Minutes to add to UTC for the time labels.
    #[serde(default)]
    pub tz_offset_min: i32,
    /// Draw in the terminal's light theme.
    #[serde(default)]
    pub light: bool,
    /// The price the user holds in the middle while dragging the picture and a few seconds
    /// after; absent lets the chart centre itself.
    #[serde(default)]
    pub center: Option<f64>,
    /// Pixels at the plot's left edge the app covers with its buttons.
    #[serde(default)]
    pub left_inset: f32,
    /// A line the finger holds: drawn at `price` instead of where the order has it.
    #[serde(default)]
    pub drag: Option<DragLine>,
    /// Draw the futures mark price line (Settings → График).
    #[serde(default = "default_true")]
    pub mark_line: bool,
    /// Draw the market's liquidations.
    #[serde(default = "default_true")]
    pub liquidations: bool,
}

/// An own line being dragged.
#[derive(Debug, Clone, Deserialize)]
pub struct DragLine {
    pub uid: Uid,
    pub role: crate::chart::LineRole,
    pub price: f64,
}

fn default_true() -> bool {
    true
}

fn default_scale() -> f32 {
    1.0
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RunSwitchName {
    Trading,
    AutoDetect,
}

/// A 64-bit id on the wire. Written as a decimal STRING — order and strategy ids use the full
/// `u64` range, which a JSON number does not survive in every client — and read from either a
/// string or a number.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Uid(pub u64);

impl Serialize for Uid {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.collect_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for Uid {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        #[serde(untagged)]
        enum Raw {
            Num(u64),
            Text(String),
        }
        match Raw::deserialize(d)? {
            Raw::Num(n) => Ok(Uid(n)),
            Raw::Text(t) => t
                .trim()
                .parse::<u64>()
                .map(Uid)
                .map_err(|_| serde::de::Error::custom("id is not an unsigned integer")),
        }
    }
}

/// Why a request was not carried out. `code` is stable and meant for the client's own wording;
/// `message` is a developer-facing detail.
#[derive(Debug, Clone)]
pub struct ApiError {
    pub code: &'static str,
    pub message: String,
}

impl ApiError {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    pub fn to_json(&self) -> Value {
        json!({ "ok": false, "error": self.code, "message": self.message })
    }
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.message)
    }
}

impl std::error::Error for ApiError {}

pub type ApiResult<T> = Result<T, ApiError>;

/// A successful reply carrying `fields` beside `"ok": true`.
pub fn ok(fields: Value) -> Value {
    let mut out = json!({ "ok": true });
    if let (Some(out), Value::Object(fields)) = (out.as_object_mut(), fields) {
        out.extend(fields);
    }
    out
}

/// Render any reply as the string that crosses the boundary.
pub fn render(result: ApiResult<Value>) -> String {
    match result {
        Ok(value) => value.to_string(),
        Err(error) => error.to_json().to_string(),
    }
}
