//! The chart picture, laid out and coloured like MoonTerminal's tick chart: the last-price line
//! with a cross per trade, the price axis down the left edge, the order book as depth walls on
//! the right, rolling bought/sold volume along the floor, own order lines across the plot and
//! the captions in its corners.
//!
//! Drawn on the CPU straight into the pixel buffer the app shows, so the phone and the desktop
//! check (`mmcli --chart`) produce the same picture from the same code. Colours and proportions
//! are the terminal's dark theme defaults (`moon_core::config::ChartTheme`).

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

use serde_json::{json, Value};
use tiny_skia::{
    Color, FillRule, LineCap, LineJoin, Paint, PathBuilder, PixmapMut, Rect, Stroke, StrokeDash,
    Transform,
};

/// One trade.
#[derive(Clone, Copy)]
pub struct TickPoint {
    pub t_ms: i64,
    pub price: f32,
    pub qty: f32,
    pub buy: bool,
}

/// One price level of the order book.
#[derive(Clone, Copy)]
pub struct BookLevel {
    pub price: f32,
    /// Size of the level in the quote currency.
    pub notional: f32,
    pub ask: bool,
}

/// One horizontal line of an own order, styled the way the terminal's `orders.toml` defaults
/// style it.
#[derive(Clone, Default)]
pub struct OrderLine {
    pub price: f64,
    pub color: [u8; 3],
    /// The same line on the light theme.
    pub light: [u8; 3],
    /// An entry not filled yet, or a level the core watches itself.
    pub dashed: bool,
    /// The caption: `$5.92K [5]` on an entry, `+1.20% [5]` on an exit, `-3% [5]` on a stop.
    /// Empty draws the line alone.
    pub label: String,
    pub label_color: [u8; 3],
    pub label_light: [u8; 3],
    /// The order the line belongs to, and which of its levels it is: what dragging it moves.
    pub uid: u64,
    pub role: LineRole,
    pub short: bool,
    /// What the caption is made of, so a dragged line can be captioned at its new price: the
    /// entry price, the order's `[n]` tag, and the dollars per unit of price the level stands
    /// for (its size in the base coin times the coin's dollar rate).
    pub entry_price: Option<f64>,
    pub tag: String,
    pub usd_per_price: Option<f64>,
    /// When the order was placed (Unix ms): the line starts there with a small cross; nothing of
    /// it is drawn before. `None` draws it across the whole plot.
    pub start_ms: Option<i64>,
    /// When a filled entry filled: up to it the line is drawn faint and thin, a dot marks the
    /// fill, and from it the line is drawn whole.
    pub fill_ms: Option<i64>,
}

/// Which level of an order a line draws.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LineRole {
    /// The entry, while not filled: moving it moves the order.
    #[default]
    Entry,
    /// The entry of a filled order: drawn, not moved.
    Filled,
    /// The resting exit order.
    Exit,
    Stop,
    Trailing,
    Take,
    /// The position's liquidation price, as MoonBot draws it: red, not movable.
    Liquidation,
}

impl OrderLine {
    fn in_theme(&self) -> [u8; 3] {
        if std::ptr::eq(pal(), &LIGHT) { self.light } else { self.color }
    }

    fn label_in_theme(&self) -> [u8; 3] {
        if std::ptr::eq(pal(), &LIGHT) { self.label_light } else { self.label_color }
    }

    /// Put the line at `price` while it is dragged, its caption reckoned for the new price as
    /// the order will have it: an exit gives its distance from the entry and what it sells for
    /// and nothing more, a stop its distance and price, an entry what it buys for and where.
    pub fn moved_to(&mut self, price: f64, tick: Option<f64>) {
        self.price = price;
        let amount = self.usd_per_price.map(|per| format!("${}", compact_amount(per * price)));
        let pct = self.entry_price.filter(|e| *e > 0.0).map(|entry| {
            let raw = (price - entry) / entry * 100.0;
            if self.short { -raw } else { raw }
        });
        let at = price_text(price, tick, None);
        let parts: Vec<String> = match self.role {
            LineRole::Entry | LineRole::Filled => [amount, Some(at)].into_iter().flatten().collect(),
            // A take carries only what it gains and what it sells for: the price and the
            // order's number would only crowd the finger's way.
            LineRole::Exit => [pct.map(|p| format!("{p:+.2}%")), amount].into_iter().flatten().collect(),
            _ => [pct.map(|p| format!("{p:+.2}%")), Some(at)].into_iter().flatten().collect(),
        };
        let tag = if self.role == LineRole::Exit && !parts.is_empty() { "" } else { self.tag.as_str() };
        self.label = format!("{}{tag}", parts.join(" "));
        if let Some(pct) = pct.filter(|_| !matches!(self.role, LineRole::Entry | LineRole::Filled)) {
            (self.label_color, self.label_light) =
                if pct < 0.0 { (LABEL_LOSS_RGB, LIGHT_LOSS_RGB) } else { (LABEL_GAIN_RGB, LIGHT_GAIN_RGB) };
        }
    }
}

/// Caption colours of a gain and a loss, on the dark and the light theme.
const LABEL_GAIN_RGB: [u8; 3] = [47, 168, 92];
const LABEL_LOSS_RGB: [u8; 3] = [255, 74, 74];
const LIGHT_GAIN_RGB: [u8; 3] = [0, 128, 0];
const LIGHT_LOSS_RGB: [u8; 3] = [220, 0, 0];

/// An amount with two decimals at most and a thousands letter: `5.92K`, `60`, `1.25M`.
pub fn compact_amount(value: f64) -> String {
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

/// One candle of the market's history: what the chart shows before its first trade.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Candle {
    pub t_ms: i64,
    /// How long it lasts: a coarser bar may stand in where the finer ones are not loaded.
    pub tf_ms: i64,
    pub open: f32,
    pub high: f32,
    pub low: f32,
    pub close: f32,
    /// Traded in the quote currency.
    pub volume: f32,
}

/// The texts in the plot's corners.
#[derive(Clone, Default)]
pub struct Captions {
    /// Signed price change over the hour and the day, percent.
    pub delta_1h: Option<f64>,
    pub delta_24h: Option<f64>,
    pub funding_pct: Option<f64>,
    pub funding_at_ms: Option<i64>,
    /// Bought and sold over the last minute, in the quote currency.
    pub buy_1m: Option<f64>,
    pub sell_1m: Option<f64>,
    /// The name of the newest detect's strategy on this market.
    pub detect: Option<String>,
    /// The market's price grid, for how many decimals a price is printed with.
    pub price_tick: Option<f64>,
    /// The core's ping and its exchange's, for the corner under the price scale.
    pub ping_ms: Option<u32>,
    pub exchange_ms: Option<u32>,
    /// The exchange latency counted as slow (red); 0 for the usual 250 ms.
    pub exchange_slow_ms: u32,
    /// What the market's trades closed this session made, in USDT.
    pub session_usdt: Option<f64>,
    /// The app keeps a session: its place is shown, with a dash until it is read.
    pub session_asked: bool,
}

/// Everything one frame draws.
#[derive(Default)]
pub struct ChartData {
    /// Trades in ascending time order. May start before the window: the rolling volume at the
    /// window's left edge is summed over trades that came just before it.
    pub ticks: Vec<TickPoint>,
    /// Every level of the book, both sides, in any order.
    pub book: Vec<BookLevel>,
    pub last_price: Option<f32>,
    pub lines: Vec<OrderLine>,
    /// History in ascending time; drawn where there are no trades yet.
    pub candles: Vec<Candle>,
    /// The futures mark price over time, ascending; a point with no positive finite price is
    /// a break in the line.
    pub marks: Vec<(i64, f32)>,
    /// Forced exits: drawn as yellow crosses.
    pub liquidations: Vec<TickPoint>,
    /// The market's own trades: where each position entered and, once closed, where it left.
    pub trades: Vec<crate::reports::TradeMark>,
    /// Shown in the middle of the plot when there is nothing to draw yet.
    pub note: Option<String>,
    pub captions: Captions,
}

/// The window a frame shows.
pub struct View {
    pub width: u32,
    pub height: u32,
    /// Pixels per density-independent unit: text and strokes scale by it.
    pub scale: f32,
    pub from_ms: i64,
    pub to_ms: i64,
    pub now_ms: i64,
    /// Minutes to add to UTC for the time labels.
    pub tz_offset_min: i32,
    /// The prices at the plot's bottom and top edge.
    pub price_lo: f64,
    pub price_hi: f64,
    /// The visible price range as a percentage of the price, as the badge prints it: `20%`.
    pub scale_badge: Option<String>,
    /// A crosshair at this point, in pixels.
    pub cursor: Option<(f32, f32)>,
    /// Mark each side of the book in thirds with the depth from the spread.
    pub book_marks: bool,
    /// The take's levels to flash: this % above and below the middle of the spread.
    pub take_flash_pct: Option<f64>,
    /// The light theme.
    pub light: bool,
    /// Pixels at the plot's left edge the app covers with its own buttons: the volume scale and
    /// the top-left captions keep clear of them.
    pub left_inset: f32,
}

/// How the price range is chosen.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Scale {
    /// Fit what the window holds.
    Auto,
    /// A fixed share of the price: 20 shows a band 20% of the price tall.
    Percent(f64),
}

/// The visible prices: `range` around `center`.
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct PriceWindow {
    pub center: f64,
    pub range: f64,
}

impl PriceWindow {
    pub fn lo(&self) -> f64 {
        self.center - self.range / 2.0
    }

    pub fn hi(&self) -> f64 {
        self.center + self.range / 2.0
    }
}

/// Choose the price window for a frame, the way the terminal's chart view does.
///
/// Following the present, the spread sits in the middle. `Auto` widens at once to hold every
/// trade in view with a tenth of the height spare at each edge, and narrows gradually so one
/// spike does not make the scale jump back and forth. A fixed percentage glides after the
/// spread.
///
/// Args:
///     previous: The window the last frame of this chart used.
///     visible: Lowest and highest price of what the window holds: trades and nearby order lines.
///     last: The price to centre on: the middle of the spread, or the last trade.
///     scale: The chosen scale.
///     live: Whether the window follows the present.
///     elapsed_s: Seconds since `previous` was drawn: the easing is by time, not by frame, so
///         the scale moves alike however often frames are asked for.
///
/// Returns:
///     The window, or `None` when there is no price to build one around.
pub fn fit_price(
    previous: Option<PriceWindow>,
    visible: Option<(f64, f64)>,
    last: Option<f64>,
    scale: Scale,
    live: bool,
    elapsed_s: f64,
    held: Option<f64>,
) -> Option<PriceWindow> {
    // How far toward its target the window moves in `elapsed_s`, for a time constant `tau`.
    let toward = |tau: f64| 1.0 - (-elapsed_s.clamp(0.0, 5.0) / tau).exp();
    // A centre the user holds, dragging the picture or a few seconds after: kept as given, with
    // the range the scale asks for or, automatic, the one already shown.
    if let Some(center) = held.filter(|c| c.is_finite() && *c > 0.0) {
        let range = match scale {
            Scale::Percent(percent) => (center * percent / 100.0).max(center * 1e-6),
            Scale::Auto => previous.map(|before| before.range).filter(|r| *r > 0.0).unwrap_or(center * 0.01),
        };
        return Some(PriceWindow { center, range });
    }
    let middle = visible.map(|(lo, hi)| (lo + hi) / 2.0);
    let target = if live { last.or(middle) } else { middle.or(last) }.filter(|c| *c > 0.0)?;
    let floor = target * 0.0005;
    // The centre glides from where the last frame had it: after the price, back from where the
    // user left it, and through the past as the window slides. One three ranges away or more is
    // jumped to.
    // Within a few hundredths of the range of where it stands, the centre stays put: the middle
    // of the spread flicks between levels with every quote, and a picture that followed each
    // flick would shake.
    let glide = |range: f64| match previous {
        Some(before) if range > 0.0 => {
            let off = target - before.center;
            if off.abs() / range > 3.0 {
                target
            } else if off.abs() <= range * CENTER_DEAD_ZONE {
                before.center
            } else {
                before.center + off * toward(0.12)
            }
        }
        _ => target,
    };
    match scale {
        Scale::Auto => {
            let center = glide(previous.map_or(0.0, |before| before.range));
            let range = match visible {
                Some((lo, hi)) => (center - lo).max(hi - center).max(floor) * 2.0 * 1.2,
                None => target * 0.001,
            };
            let range = match previous {
                Some(before) if live && range < before.range => {
                    before.range + (range - before.range) * toward(0.7)
                }
                _ => range,
            };
            Some(PriceWindow { center, range })
        }
        Scale::Percent(percent) => {
            // Measured against the price the window is already centred on, so the range does
            // not breathe with every tick.
            let anchor = previous.map_or(target, |before| before.center);
            let range = (anchor * percent / 100.0).max(target * 1e-6);
            Some(PriceWindow { center: glide(range), range })
        }
    }
}

/// How far the price may stray from the middle of the picture, as a share of the range shown,
/// before the picture follows it.
const CENTER_DEAD_ZONE: f64 = 0.03;

/// How far past the span of the trades an order line may pull an automatic fit, as a share of
/// that span, and the least such allowance as a share of the price. The terminal's own bounds:
/// a target a long way off must not squash the trades into a corner.
const ORDER_FIT_SLACK: f64 = 0.5;
const ORDER_FIT_MIN_FRAC: f64 = 0.005;

/// The price span of the trades inside `[from_ms, to_ms]`, widened to the own order lines that
/// lie close to it.
pub fn visible_span(
    ticks: &[TickPoint],
    candles: &[Candle],
    lines: &[OrderLine],
    from_ms: i64,
    to_ms: i64,
) -> Option<(f64, f64)> {
    let (mut lo, mut hi) = ticks
        .iter()
        .filter(|t| t.t_ms >= from_ms && t.t_ms <= to_ms && t.price > 0.0)
        .fold((f64::MAX, f64::MIN), |(lo, hi), t| {
            (lo.min(f64::from(t.price)), hi.max(f64::from(t.price)))
        });
    // The candles drawn: those before the first trade in view.
    let first_trade = ticks
        .iter()
        .find(|t| t.t_ms >= from_ms && t.price > 0.0)
        .map_or(to_ms, |t| t.t_ms);
    for candle in candles {
        if candle.t_ms + candle.tf_ms > from_ms && candle.t_ms < to_ms.min(first_trade) {
            if candle.low > 0.0 && candle.high >= candle.low {
                lo = lo.min(f64::from(candle.low));
                hi = hi.max(f64::from(candle.high));
            }
        }
    }
    if lo > hi {
        return None;
    }
    let reach = ((hi - lo) * ORDER_FIT_SLACK).max(hi * ORDER_FIT_MIN_FRAC);
    let (mut out_lo, mut out_hi) = (lo, hi);
    for line in lines {
        if line.price >= lo - reach && line.price <= hi + reach {
            out_lo = out_lo.min(line.price);
            out_hi = out_hi.max(line.price);
        }
    }
    Some((out_lo, out_hi))
}

/// The interval the rolling volume is summed over at a window width: Moonbot's `Auto` table,
/// keyed by the minutes that fit on screen.
pub fn volume_interval_ms(span_ms: i64) -> i64 {
    let minutes = (span_ms as f64 / 60_000.0).round() as i64;
    let seconds = match minutes {
        m if m >= 120 => 300,
        m if m >= 60 => 180,
        m if m >= 40 => 60,
        m if m >= 20 => 30,
        m if m >= 10 => 15,
        m if m >= 5 => 5,
        _ => 2,
    };
    seconds * 1000
}

/// The colours of one chart theme: the terminal's dark defaults and its light set
/// (`moon_core::config::ChartTheme`, `orders.toml`).
pub struct Palette {
    pub bg: [u8; 3],
    pub grid: [u8; 3],
    pub label: [u8; 3],
    pub tick_buy: [u8; 3],
    pub tick_sell: [u8; 3],
    pub price_line: [u8; 3],
    pub price_line_alpha: u8,
    pub book_bg_ask: [u8; 3],
    pub book_bg_bid: [u8; 3],
    pub book_bid: [u8; 3],
    pub book_ask: [u8; 3],
    pub volume_up: [u8; 3],
    pub volume_down: [u8; 3],
    pub candle_up: [u8; 3],
    pub candle_down: [u8; 3],
    pub green: [u8; 3],
    pub red: [u8; 3],
    pub cross: [u8; 3],
    /// Behind a readout: the crosshair's price and time.
    pub readout_bg: [u8; 3],
    /// Behind the last price on the axis.
    pub last_bg: [u8; 3],
    /// Text on a readout.
    pub strong: [u8; 3],
    /// Behind an order line's caption.
    pub line_label_bg: [u8; 3],
    /// The book's third marks.
    pub marks_line: [u8; 3],
    pub ask_text: [u8; 3],
    pub bid_text: [u8; 3],
}

/// The liquidation dots.
const MARK_YELLOW: [u8; 3] = [255, 204, 0];

/// The mark price line, orange as Moonbot draws it; a deeper orange on the light theme.
fn mark_orange() -> [u8; 3] {
    if std::ptr::eq(pal(), &LIGHT) {
        [230, 120, 0]
    } else {
        [255, 160, 0]
    }
}

/// The price under the book when it fell or rose over the last minute: bright on both themes.
const BRIGHT_RED: [u8; 3] = [255, 59, 48];
const BRIGHT_GREEN: [u8; 3] = [40, 205, 65];

pub const DARK: Palette = Palette {
    bg: [30, 30, 30],
    grid: [40, 40, 40],
    label: [211, 211, 211],
    tick_buy: [47, 168, 92],
    tick_sell: [255, 142, 90],
    price_line: [209, 153, 92],
    price_line_alpha: 209,
    book_bg_ask: [42, 30, 27],
    book_bg_bid: [30, 36, 26],
    book_bid: [28, 100, 64],
    book_ask: [140, 46, 46],
    volume_up: [26, 158, 92],
    volume_down: [230, 59, 59],
    candle_up: [26, 158, 92],
    candle_down: [230, 59, 59],
    green: [47, 168, 92],
    red: [255, 74, 74],
    cross: [128, 128, 128],
    readout_bg: [44, 44, 44],
    last_bg: [58, 58, 58],
    strong: [255, 255, 255],
    line_label_bg: [26, 26, 26],
    marks_line: [235, 235, 235],
    ask_text: [255, 160, 150],
    bid_text: [140, 230, 170],
};

pub const LIGHT: Palette = Palette {
    bg: [255, 255, 255],
    grid: [236, 239, 242],
    label: [0, 0, 0],
    tick_buy: [47, 168, 92],
    tick_sell: [230, 110, 50],
    price_line: [166, 110, 46],
    price_line_alpha: 230,
    book_bg_ask: [255, 244, 242],
    book_bg_bid: [243, 250, 242],
    book_bid: [118, 197, 157],
    book_ask: [240, 137, 137],
    volume_up: [26, 158, 92],
    volume_down: [230, 59, 59],
    candle_up: [26, 158, 92],
    candle_down: [230, 59, 59],
    green: [0, 128, 0],
    red: [220, 0, 0],
    cross: [128, 128, 128],
    readout_bg: [224, 227, 231],
    last_bg: [210, 214, 219],
    strong: [0, 0, 0],
    line_label_bg: [255, 255, 255],
    marks_line: [60, 60, 60],
    ask_text: [190, 40, 30],
    bid_text: [0, 120, 60],
};

thread_local! {
    /// The theme of the frame this thread is drawing.
    static THEME: std::cell::Cell<&'static Palette> = const { std::cell::Cell::new(&DARK) };
}

fn pal() -> &'static Palette {
    THEME.with(|theme| theme.get())
}
/// Share of the plot's height the volume band takes along its floor.
const VOLUME_BAND_FRAC: f32 = 0.18;
/// Above this many trades in view the crosses are left out and the line is drawn per pixel
/// column: they would only merge into a band, at many times the cost.
const MAX_MARKS: usize = 6000;

/// Draw one frame into `pixels` (RGBA, premultiplied, `width * height * 4` bytes).
///
/// Returns:
///     What the app needs to place its own controls over the picture and to map a touch back
///     onto it: the plot's rectangle, where the volume band and the book begin, and the prices
///     and times the plot spans.
pub fn render(data: &ChartData, view: &View, pixels: &mut [u8]) -> Option<Value> {
    let (w, h) = (view.width, view.height);
    let mut pix = PixmapMut::from_bytes(pixels, w, h)?;
    let s = view.scale.clamp(0.5, 6.0);
    THEME.with(|theme| theme.set(if view.light { &LIGHT } else { &DARK }));
    let (wf, hf) = (w as f32, h as f32);
    pix.fill(rgb(pal().bg));

    let (lo, hi) = (view.price_lo, view.price_hi);
    let priced = hi > lo && lo.is_finite() && hi.is_finite();
    let tick = data.captions.price_tick;

    // The price grid first: its labels decide how wide the axis column on the left is.
    let time_h = 17.0 * s;
    let plot_h = (hf - time_h).max(1.0);
    let mut levels: Vec<(f64, String)> = Vec::new();
    if priced {
        let lines = (plot_h / (46.0 * s)).clamp(3.0, 10.0);
        let step = nice_interval(hi - lo, f64::from(lines));
        let mut level = (lo / step).ceil() * step;
        while level < hi && levels.len() < 40 {
            levels.push((level, price_text(level, tick, Some(step))));
            level += step;
        }
    }
    let last = data.last_price.filter(|p| *p > 0.0);
    let last_label = last.map(|p| price_text(f64::from(p), tick, None));
    let axis_font = 10.0 * s;
    let widest = levels
        .iter()
        .map(|(_, label)| label.as_str())
        .chain(last_label.as_deref())
        .map(|label| text_width(label, axis_font))
        .fold(0f32, f32::max);
    let axis_w = (widest + 8.0 * s).clamp(34.0 * s, wf * 0.22);

    let book_w = (wf * 0.13).clamp(60.0 * s, 150.0 * s).min(wf * 0.3);
    let plot = Zone {
        x: axis_w,
        y: 0.0,
        w: (wf - book_w - axis_w).max(1.0),
        h: plot_h,
    };
    let band_top = plot.h * (1.0 - VOLUME_BAND_FRAC);
    let book = Zone { x: plot.x + plot.w, y: plot.y, w: book_w, h: plot.h };

    let span_ms = (view.to_ms - view.from_ms).max(1) as f64;
    let x_of = |t: i64| plot.x + ((t - view.from_ms) as f64 / span_ms) as f32 * plot.w;
    let y_of = |p: f64| plot.y + ((hi - p) / (hi - lo)) as f32 * plot.h;
    let first_seen = data.ticks.partition_point(|t| t.t_ms < view.from_ms);
    let seen: Vec<TickPoint> = data.ticks[first_seen..]
        .iter()
        .filter(|t| t.t_ms <= view.to_ms && t.price > 0.0)
        .copied()
        .collect();

    // Grid: price lines across the axis column and the plot, time lines down the plot.
    for (level, _) in &levels {
        fill(&mut pix, 0.0, y_of(*level), book.x, 1.0, pal().grid, 255);
    }
    let time_step = nice_time_step(span_ms / f64::from((plot.w / (92.0 * s)).max(1.0)));
    let offset_ms = i64::from(view.tz_offset_min) * 60_000;
    let mut time_marks: Vec<(f32, String)> = Vec::new();
    let mut t = ((view.from_ms + offset_ms).div_euclid(time_step) + 1) * time_step - offset_ms;
    while t < view.to_ms && time_marks.len() < 60 {
        let x = x_of(t);
        fill(&mut pix, x, plot.y, 1.0, plot.h, pal().grid, 255);
        time_marks.push((x, clock(t + offset_ms, time_step < 60_000)));
        t += time_step;
    }

    // History stands in where there are no trades yet.
    let candles_until = seen.first().map_or(view.to_ms, |t| t.t_ms);
    let drawn_candles = data
        .candles
        .iter()
        .filter(|c| c.t_ms + c.tf_ms > view.from_ms && c.t_ms < candles_until)
        .count();
    if priced && drawn_candles > 0 {
        candles(&mut pix, &plot, &data.candles, candles_until, view, &x_of, &y_of, s);
    }
    volume_band(&mut pix, &plot, band_top, &data.ticks, &data.candles, candles_until, view, s);
    if priced {
        let scale = (span_ms / f64::from(plot.w.max(1.0)), (hi - lo) / f64::from(plot.h.max(1.0)));
        trades(&mut pix, &plot, &seen, scale, &x_of, &y_of, s);
        mark_line(&mut pix, &data.marks, view, &x_of, &y_of, s);
        liquidation_marks(&mut pix, &data.liquidations, view, &x_of, &y_of, s);
        trade_marks(&mut pix, &data.trades, view, &x_of, &y_of, s);
    }

    // The price axis, over whatever ran into its column. The last price has a label of its
    // own, and a grid label it would cover is left out.
    let last_y = last
        .filter(|_| priced)
        .map(|last| y_of(f64::from(last)).clamp(7.0 * s, plot.h - 7.0 * s));
    for (level, label) in &levels {
        let y = y_of(*level);
        if y < 7.0 * s || y > plot.h - 5.0 * s {
            continue;
        }
        if last_y.is_some_and(|at| (at - y).abs() < 13.0 * s) {
            continue;
        }
        text(&mut pix, 3.0 * s, y + 3.5 * s, label, axis_font, pal().label, 255);
    }
    if let (Some(y), Some(label)) = (last_y, &last_label) {
        fill(&mut pix, 0.0, y - 7.0 * s, axis_w - 2.0 * s, 14.0 * s, pal().last_bg, 255);
        text(&mut pix, 3.0 * s, y + 3.5 * s, label, axis_font, pal().strong, 255);
    }

    // The time axis under the plot.
    fill(&mut pix, 0.0, plot.h, wf, hf - plot.h, pal().bg, 255);
    // Its left corner, under the price scale: the core's ping and its exchange's, each green
    // under its slow mark (250 ms; Hyperliquid's exchange 1000 ms) and red from it.
    let mut time_left = 2.0;
    if data.captions.ping_ms.is_some() || data.captions.exchange_ms.is_some() {
        let font = 9.0 * s;
        let exchange_slow = match data.captions.exchange_slow_ms {
            0 => crate::alerts::PING_SLOW,
            ms => ms,
        };
        let shade = |ms: Option<u32>, slow: u32| match ms {
            Some(ms) if ms < slow => pal().green,
            Some(_) => pal().red,
            None => pal().label,
        };
        let number = |ms: Option<u32>| ms.map_or_else(|| "–".to_owned(), |ms| ms.to_string());
        // The slash goes in the core's colour, «мс» in the exchange's, as their numbers.
        let core_shade = shade(data.captions.ping_ms, crate::alerts::PING_SLOW);
        let exchange_shade = shade(data.captions.exchange_ms, exchange_slow);
        let parts = [
            (number(data.captions.ping_ms), core_shade),
            ("/".to_owned(), core_shade),
            (number(data.captions.exchange_ms), exchange_shade),
            ("мс".to_owned(), exchange_shade),
        ];
        let mut x = 3.0 * s;
        for (part, color) in &parts {
            text(&mut pix, x, plot.h + 12.0 * s, part, font, *color, 255);
            x += text_width(part, font);
        }
        time_left = x + 6.0 * s;
    }
    for (x, label) in &time_marks {
        let half = text_width(label, axis_font) / 2.0;
        if x - half > time_left && x + half < book.x {
            text(&mut pix, x - half, plot.h + 12.0 * s, label, axis_font, pal().label, 255);
        }
    }
    // Its right corner, under the book: the price now, red when it fell over the last minute,
    // green when it rose.
    if let (Some(price), Some(label)) = (last, &last_label) {
        let newest = data.ticks.last().map(|t| t.t_ms);
        let minute_ago = newest.and_then(|now| {
            data.ticks
                .iter()
                .rev()
                .find(|t| t.t_ms <= now - 60_000)
                .or_else(|| data.ticks.first())
                .map(|t| t.price)
        });
        let colour = match minute_ago {
            Some(before) if price < before => BRIGHT_RED,
            Some(before) if price > before => BRIGHT_GREEN,
            _ => pal().strong,
        };
        let font = 11.0 * s;
        let width = text_width(label, font);
        let x = (book.x + book.w - width - 4.0 * s).max(book.x + 2.0 * s);
        text(&mut pix, x, plot.h + 12.5 * s, label, font, colour, 255);
        text(&mut pix, x + 0.6 * s, plot.h + 12.5 * s, label, font, colour, 255);
    }

    let depth = if priced {
        Depth::new(&data.book)
    } else {
        Depth::default()
    };
    if priced {
        order_book(&mut pix, &book, &depth, lo, hi, &y_of, s);
        // Order lines run over the book too, so a level and an order on it read together. Their
        // captions keep clear of the caption block in the plot's top right corner.
        let corner = caption_corner(&plot, data, view, s);
        order_lines(&mut pix, &plot, &book, band_top, corner, &data.lines, lo, hi, &y_of, &x_of, tick, s);
        if view.book_marks {
            book_marks(&mut pix, &book, &depth, lo, hi, last, &y_of, s);
        }
        if let Some(pct) = view.take_flash_pct {
            let mid = match (depth.bids.first(), depth.asks.first()) {
                (Some(bid), Some(ask)) => Some((bid.0 + ask.0) / 2.0),
                _ => None,
            };
            if let Some(mid) = mid.filter(|m| m.is_finite() && *m > 0.0) {
                take_flash(&mut pix, &plot, wf, mid, pct, lo, hi, &y_of, s);
            }
        }
    }
    captions(&mut pix, &plot, data, view, s);
    if let (true, Some(cursor)) = (priced, view.cursor) {
        crosshair(&mut pix, &plot, &book, band_top, cursor, data, &depth, view, last, s);
    }
    if (seen.is_empty() && drawn_candles == 0) || !priced {
        let message = data.note.as_deref().unwrap_or(if priced {
            "Сделок в этом окне нет"
        } else {
            "Жду данные…"
        });
        let width = text_width(message, 13.0 * s);
        let x = plot.x + (plot.w - width) / 2.0;
        text(&mut pix, x, plot.h * 0.5, message, 13.0 * s, pal().label, 200);
    }

    Some(json!({
        "plot": { "x": plot.x, "y": plot.y, "w": plot.w, "h": plot.h },
        "band_top": band_top,
        "book_x": book.x,
        "best_bid": depth.bids.first().map(|level| level.0),
        "best_ask": depth.asks.first().map(|level| level.0),
        "price_lo": lo,
        "price_hi": hi,
        "from_ms": view.from_ms,
        "to_ms": view.to_ms,
        "last": last,
        "ticks": seen.len(),
        "candles": drawn_candles,
        "captions_bottom": captions_bottom(data, s),
        "lines": data
            .lines
            .iter()
            .filter(|line| line.uid != 0 && line.price > lo && line.price < hi)
            .map(|line| json!({
                // As text: order ids use the full unsigned 64-bit range, which a number in the
                // app's JSON would round (and the core then finds no such order).
                "uid": line.uid.to_string(),
                "role": line.role,
                "price": line.price,
                "y": y_of(line.price),
                "short": line.short,
            }))
            .collect::<Vec<_>>(),
    }))
}

struct Zone {
    x: f32,
    y: f32,
    w: f32,
    h: f32,
}

/// The last-price line as steps from trade to trade, and a cross per trade.
fn trades(
    pix: &mut PixmapMut,
    plot: &Zone,
    seen: &[TickPoint],
    (ms_per_px, price_per_px): (f64, f64),
    x_of: &dyn Fn(i64) -> f32,
    y_of: &dyn Fn(f64) -> f32,
    s: f32,
) {
    if seen.is_empty() {
        return;
    }
    let mut line = PathBuilder::new();
    if seen.len() <= MAX_MARKS {
        // The price holds until the next trade and then steps: a line between two trades would
        // draw prices nobody traded at.
        let mut held = y_of(f64::from(seen[0].price));
        line.move_to(x_of(seen[0].t_ms), held);
        for tick in &seen[1..] {
            let (x, y) = (x_of(tick.t_ms), y_of(f64::from(tick.price)));
            line.line_to(x, held);
            line.line_to(x, y);
            held = y;
        }
    } else {
        // One column per pixel: open, low, high, close keeps every spike without drawing every
        // trade.
        let columns = plot.w.max(1.0) as usize;
        let mut cells: Vec<Option<(f32, f32, f32, f32)>> = vec![None; columns];
        for tick in seen {
            let at = ((x_of(tick.t_ms) - plot.x).max(0.0) as usize).min(columns - 1);
            let y = y_of(f64::from(tick.price));
            cells[at] = Some(match cells[at] {
                None => (y, y, y, y),
                Some((open, low, high, _)) => (open, low.min(y), high.max(y), y),
            });
        }
        let mut started = false;
        for (at, cell) in cells.iter().enumerate() {
            let Some((open, low, high, close)) = cell else { continue };
            let x = plot.x + at as f32 + 0.5;
            if started {
                line.line_to(x, *open);
            } else {
                line.move_to(x, *open);
                started = true;
            }
            line.line_to(x, *low);
            line.line_to(x, *high);
            line.line_to(x, *close);
        }
    }
    if let Some(path) = line.finish() {
        stroke(pix, &path, pal().price_line, pal().price_line_alpha, 1.7 * s, None);
    }
    // A cross per trade, however tightly the window is squeezed: one per cell a cross wide, in
    // the colour most of the cell's trades have, at the first of them. Crosses that would lie on
    // top of each other would melt into a solid band; and leaving them out past some count
    // left the bare line, as if the trades were gone.
    let arm = 2.4 * s;
    let cell = 2.0 * arm + 1.3 * s;
    //
    // The cells are cut in time and price, not on the screen, and their size goes in powers of
    // two: dragging the picture or the price gliding by moves the cells with the trades, so the
    // same trades keep standing for them and no cross blinks in and out.
    let ladder = |raw: f64| if raw > 0.0 && raw.is_finite() { 2f64.powf(raw.log2().ceil()) } else { 0.0 };
    let cell_ms = ladder(f64::from(cell) * ms_per_px);
    let cell_price = ladder(f64::from(cell) * price_per_px);
    if !(cell_ms > 0.0 && cell_price > 0.0) {
        return;
    }
    // (where the cell's first trade is, buys − sells), a time column at a time: the trades come
    // in time order, so a column's cells are all gathered before the next one starts.
    let mut cells: Vec<(f32, f32, i32)> = Vec::new();
    let mut column: Vec<(i64, usize)> = Vec::new();
    let mut column_at = i64::MIN;
    for tick in seen {
        let at = (tick.t_ms as f64 / cell_ms).floor() as i64;
        if at != column_at {
            column.clear();
            column_at = at;
        }
        let level = (f64::from(tick.price) / cell_price).floor() as i64;
        let vote = if tick.buy { 1 } else { -1 };
        if let Some(&(_, index)) = column.iter().find(|(l, _)| *l == level) {
            cells[index].2 += vote;
            continue;
        }
        let (x, y) = (x_of(tick.t_ms), y_of(f64::from(tick.price)));
        if !(x.is_finite() && y.is_finite()) {
            continue;
        }
        column.push((level, cells.len()));
        cells.push((x, y, vote));
    }
    for (buy, color) in [(true, pal().tick_buy), (false, pal().tick_sell)] {
        let mut marks = PathBuilder::new();
        for (x, y, _) in cells.iter().filter(|c| (c.2 >= 0) == buy) {
            marks.move_to(x - arm, y - arm);
            marks.line_to(x + arm, y + arm);
            marks.move_to(x - arm, y + arm);
            marks.line_to(x + arm, y - arm);
        }
        if let Some(path) = marks.finish() {
            stroke(pix, &path, color, 255, 1.3 * s, None);
        }
    }
}

/// The futures mark price as a thin orange line through time.
fn mark_line(
    pix: &mut PixmapMut,
    marks: &[(i64, f32)],
    view: &View,
    x_of: &dyn Fn(i64) -> f32,
    y_of: &dyn Fn(f64) -> f32,
    s: f32,
) {
    let mut line = PathBuilder::new();
    // Moonbot marks each new mark price with a short upright tick on the line; drawn only where
    // the points stand far enough apart to tell one from the next.
    let mut points: Vec<(f32, f32)> = Vec::new();
    let mut drawing = false;
    let mut held: Option<f32> = None;
    for (t, price) in marks {
        if *t < view.from_ms - 60_000 || *t > view.to_ms + 60_000 {
            continue;
        }
        if !(price.is_finite() && *price > 0.0) {
            drawing = false;
            held = None;
            continue;
        }
        let (x, y) = (x_of(*t), y_of(f64::from(*price)));
        match (drawing, held) {
            // The mark holds until the next one, as a price does.
            (true, Some(before)) => {
                line.line_to(x, before);
                line.line_to(x, y);
            }
            _ => {
                line.move_to(x, y);
                drawing = true;
            }
        }
        points.push((x, y));
        held = Some(y);
    }
    // Ticks only while the visible points stand apart on average: a zoomed-out window of
    // minute closes reads as a plain line.
    let shown: Vec<&(f32, f32)> = points.iter().filter(|(x, _)| *x >= view_left(view, x_of) && *x <= view_right(view, x_of)).collect();
    if shown.len() >= 2 {
        let spread = (shown[shown.len() - 1].0 - shown[0].0) / (shown.len() - 1) as f32;
        if spread >= 4.0 * s {
            let tick_half = 2.5 * s;
            let mut ticks = PathBuilder::new();
            for (x, y) in shown {
                ticks.move_to(*x, *y - tick_half);
                ticks.line_to(*x, *y + tick_half);
            }
            if let Some(path) = ticks.finish() {
                stroke(pix, &path, mark_orange(), 240, 1.2 * s, None);
            }
        }
    }
    // The newest price holds up to now, a few seconds at most past its point.
    if let (true, Some(y), Some((t, _))) = (drawing, held, marks.last()) {
        let until = (*t + 15_000).min(view.now_ms).min(view.to_ms);
        if until > *t {
            line.line_to(x_of(until), y);
        }
    }
    if let Some(path) = line.finish() {
        stroke(pix, &path, mark_orange(), 240, 1.1 * s, None);
    }
}

/// The x of the window's left and right edges.
fn view_left(view: &View, x_of: &dyn Fn(i64) -> f32) -> f32 {
    x_of(view.from_ms)
}

fn view_right(view: &View, x_of: &dyn Fn(i64) -> f32) -> f32 {
    x_of(view.to_ms.min(view.now_ms.max(view.from_ms)))
}

/// A position's entry, and its exit once closed, as MoonBot marks them: a large cross at each end
/// joined by a thin line, with a short tick in the middle of the line. The entry is blue; the
/// exit is green for a gain and red for a loss.
fn trade_marks(
    pix: &mut PixmapMut,
    trades: &[crate::reports::TradeMark],
    view: &View,
    x_of: &dyn Fn(i64) -> f32,
    y_of: &dyn Fn(f64) -> f32,
    s: f32,
) {
    const ENTRY: [u8; 3] = [64, 156, 255];
    const LINK: [u8; 3] = [120, 190, 255];
    const GAIN: [u8; 3] = [60, 220, 120];
    const LOSS: [u8; 3] = [255, 84, 84];
    let arm = 4.5 * s;
    let halo = pal().bg;
    let cross = |pix: &mut PixmapMut, x: f32, y: f32, color: [u8; 3]| {
        let mut path = PathBuilder::new();
        path.move_to(x - arm, y - arm);
        path.line_to(x + arm, y + arm);
        path.move_to(x - arm, y + arm);
        path.line_to(x + arm, y - arm);
        if let Some(path) = path.finish() {
            stroke(pix, &path, halo, 200, 4.2 * s, None);
            stroke(pix, &path, color, 255, 2.2 * s, None);
        }
    };
    for trade in trades {
        let end_ms = trade.close.map_or(view.to_ms, |(t, _)| t);
        if end_ms < view.from_ms || trade.open_ms > view.to_ms || trade.open_price <= 0.0 {
            continue;
        }
        let (x0, y0) = (x_of(trade.open_ms), y_of(trade.open_price));
        if let Some((close_ms, close_price)) = trade.close.filter(|(_, p)| *p > 0.0) {
            let (x1, y1) = (x_of(close_ms), y_of(close_price));
            let mut link = PathBuilder::new();
            link.move_to(x0, y0);
            link.line_to(x1, y1);
            let (mx, my) = ((x0 + x1) / 2.0, (y0 + y1) / 2.0);
            link.move_to(mx, my - 3.0 * s);
            link.line_to(mx, my + 3.0 * s);
            if let Some(path) = link.finish() {
                stroke(pix, &path, LINK, 230, 1.3 * s, None);
            }
            let won = match trade.profit_pct {
                Some(pct) => pct >= 0.0,
                None => (close_price >= trade.open_price) != trade.short,
            };
            if close_ms >= view.from_ms && close_ms <= view.to_ms {
                cross(pix, x1, y1, if won { GAIN } else { LOSS });
            }
        }
        if trade.open_ms >= view.from_ms {
            cross(pix, x0, y0, ENTRY);
        }
    }
}

/// Liquidations as small yellow dots.
fn liquidation_marks(
    pix: &mut PixmapMut,
    liquidations: &[TickPoint],
    view: &View,
    x_of: &dyn Fn(i64) -> f32,
    y_of: &dyn Fn(f64) -> f32,
    s: f32,
) {
    let radius = 1.7 * s;
    let mut dots = PathBuilder::new();
    for tick in liquidations.iter().filter(|t| t.t_ms >= view.from_ms && t.t_ms <= view.to_ms && t.price > 0.0) {
        dots.push_circle(x_of(tick.t_ms), y_of(f64::from(tick.price)), radius);
    }
    if let Some(path) = dots.finish() {
        let mut paint = Paint::default();
        let [r, g, b] = if std::ptr::eq(pal(), &LIGHT) { [200, 150, 0] } else { MARK_YELLOW };
        paint.set_color_rgba8(r, g, b, 255);
        paint.anti_alias = true;
        pix.fill_path(&path, &paint, FillRule::Winding, Transform::identity(), None);
    }
}

/// The history before the first trade as candles; their turnover joins the volume band.
#[allow(clippy::too_many_arguments)]
fn candles(
    pix: &mut PixmapMut,
    plot: &Zone,
    candles: &[Candle],
    until_ms: i64,
    view: &View,
    x_of: &dyn Fn(i64) -> f32,
    y_of: &dyn Fn(f64) -> f32,
    s: f32,
) {
    let shown: Vec<&Candle> = candles
        .iter()
        .filter(|c| c.t_ms + c.tf_ms > view.from_ms && c.t_ms < until_ms && c.high >= c.low && c.low > 0.0)
        .collect();
    let left = plot.x;
    let cut = x_of(until_ms).min(plot.x + plot.w);
    for candle in shown {
        let x0 = x_of(candle.t_ms).max(left);
        let x1 = x_of(candle.t_ms + candle.tf_ms).min(cut);
        if x1 <= x0 {
            continue;
        }
        let width = x1 - x0;
        let body_w = if width >= 3.0 { (width * 0.72).max(1.0) } else { width.max(1.0) };
        let mid = x0 + width / 2.0;
        let up = candle.close >= candle.open;
        let color = if up { pal().candle_up } else { pal().candle_down };
        let (y_high, y_low) = (y_of(f64::from(candle.high)), y_of(f64::from(candle.low)));
        let (y_open, y_close) = (y_of(f64::from(candle.open)), y_of(f64::from(candle.close)));
        let wick = (0.9 * s).max(1.0);
        fill(pix, mid - wick / 2.0, y_high, wick, (y_low - y_high).max(1.0), color, 230);
        let top = y_open.min(y_close);
        let height = (y_open - y_close).abs().max(1.0);
        fill(pix, mid - body_w / 2.0, top, body_w, height, color, 217);
    }
}

/// What the volume band sums: the trades where the chart has them, and before the first of
/// them the history's candles, each spread evenly over its own time and counted bought when it
/// closed up. Ascending in time.
fn volume_events(
    ticks: &[TickPoint],
    candles: &[Candle],
    candles_until: i64,
    from_ms: i64,
    to_ms: i64,
    piece_ms: i64,
) -> Vec<(i64, f64, bool)> {
    let mut events: Vec<(i64, f64, bool)> = Vec::new();
    for candle in candles {
        if candle.t_ms + candle.tf_ms <= from_ms || candle.t_ms >= candles_until.min(to_ms) {
            continue;
        }
        let value = f64::from(candle.volume);
        if !(value > 0.0) || !value.is_finite() {
            continue;
        }
        let buy = candle.close >= candle.open;
        let tf = candle.tf_ms.max(1);
        let pieces = (tf / piece_ms.max(1)).clamp(1, 60);
        for piece in 0..pieces {
            let t = candle.t_ms + tf * piece / pieces + tf / pieces / 2;
            if t >= candles_until {
                break;
            }
            events.push((t, value / pieces as f64, buy));
        }
    }
    events.sort_by_key(|e| e.0);
    let first = ticks.partition_point(|t| t.t_ms < from_ms);
    events.extend(
        ticks[first..]
            .iter()
            .take_while(|t| t.t_ms <= to_ms)
            .map(|t| (t.t_ms, f64::from(t.price) * f64::from(t.qty), t.buy)),
    );
    events
}

/// Bought and sold as rolling sums along the plot's floor, the way Moonbot's `Vol` indicator
/// draws them: a trade stays in the picture for a whole interval, so the band is two continuous
/// hills on one linear scale, with a bracket naming its top and its half. Where the chart has no
/// trades yet its history's candles stand in, so the band runs the whole window on one scale.
#[allow(clippy::too_many_arguments)]
fn volume_band(
    pix: &mut PixmapMut,
    plot: &Zone,
    band_top: f32,
    ticks: &[TickPoint],
    candles: &[Candle],
    candles_until: i64,
    view: &View,
    s: f32,
) {
    let span = (view.to_ms - view.from_ms).max(1);
    let samples = ((plot.w / (2.0 * s)) as usize).clamp(40, 700);
    let step = span / (samples as i64 - 1).max(1);
    // An interval shorter than two samples would let a lone trade fall between them and blink
    // as the window moves.
    let interval = volume_interval_ms(span).max(2 * step);
    let end = view.to_ms.min(view.now_ms.max(ticks.last().map_or(i64::MIN, |t| t.t_ms)));
    let events = volume_events(ticks, candles, candles_until, view.from_ms - interval, end, (interval / 2).max(1_000));
    let (mut buy, mut sell) = (0f64, 0f64);
    let (mut entering, mut leaving) = (0usize, 0usize);
    let mut rows: Vec<(f32, f64, f64)> = Vec::with_capacity(samples);
    for i in 0..samples {
        let t = view.from_ms + span * i as i64 / (samples as i64 - 1);
        if t > end {
            break;
        }
        while entering < events.len() && events[entering].0 <= t {
            let (_, value, side) = events[entering];
            if side {
                buy += value;
            } else {
                sell += value;
            }
            entering += 1;
        }
        while leaving < entering && events[leaving].0 <= t - interval {
            let (_, value, side) = events[leaving];
            if side {
                buy -= value;
            } else {
                sell -= value;
            }
            leaving += 1;
        }
        let x = plot.x + (t - view.from_ms) as f32 / span as f32 * plot.w;
        rows.push((x, buy.max(0.0), sell.max(0.0)));
    }
    let top = rows.iter().fold(0f64, |top, row| top.max(row.1).max(row.2));
    if !(top > 0.0) || rows.len() < 2 {
        return;
    }
    let floor = plot.y + plot.h;
    // The scale's top stands a little under the band's, so the label above it has room.
    let room = floor - band_top - 12.0 * s;
    for (side, color) in [(false, pal().volume_down), (true, pal().volume_up)] {
        let mut hill = PathBuilder::new();
        hill.move_to(rows[0].0, floor);
        for row in &rows {
            let value = if side { row.1 } else { row.2 };
            hill.line_to(row.0, floor - (value / top) as f32 * room);
        }
        hill.line_to(rows[rows.len() - 1].0, floor);
        hill.close();
        if let Some(path) = hill.finish() {
            let mut paint = Paint::default();
            paint.set_color_rgba8(color[0], color[1], color[2], 120);
            paint.anti_alias = true;
            pix.fill_path(&path, &paint, FillRule::Winding, Transform::identity(), None);
        }
    }
    // The bracket: a stem with a tick at the top, at the half and on the floor.
    let stem_x = plot.x + 4.0 * s + view.left_inset;
    let top_y = floor - room;
    let half_y = floor - room / 2.0;
    fill(pix, stem_x, top_y, 1.5 * s, room, pal().label, 150);
    for y in [top_y, half_y, floor - 1.5 * s] {
        fill(pix, stem_x, y, 7.0 * s, 1.5 * s, pal().label, 150);
    }
    let (whole, half) = money_pair(top);
    let white = pal().strong;
    text(pix, stem_x + 10.0 * s, top_y + 4.0 * s, &whole, 11.0 * s, white, 255);
    text(pix, stem_x + 10.0 * s, half_y + 4.0 * s, &half, 11.0 * s, white, 255);
}

/// Own orders across the plot and the book, captioned at the plot's right edge. A level outside
/// the visible prices is named at the edge it lies beyond, so an order is never simply missing
/// from its chart.
#[allow(clippy::too_many_arguments)]
fn order_lines(
    pix: &mut PixmapMut,
    plot: &Zone,
    book: &Zone,
    band_top: f32,
    (corner_left, corner_bottom): (f32, f32),
    lines: &[OrderLine],
    lo: f64,
    hi: f64,
    y_of: &dyn Fn(f64) -> f32,
    x_of: &dyn Fn(i64) -> f32,
    tick: Option<f64>,
    s: f32,
) {
    let right = book.x + book.w;
    let (mut above_x, mut below_x) = (plot.x + 14.0 * s, plot.x + 14.0 * s);
    // Where the labels of the lines inside the range went, so two close levels sit side by side.
    let mut placed: Vec<(f32, f32)> = Vec::new();
    for line in lines {
        // Placed after the window's right edge: not there yet in this stretch of time.
        let start_x = line.start_ms.map(|t| x_of(t)).filter(|x| x.is_finite());
        if start_x.is_some_and(|x| x > plot.x + plot.w) {
            continue;
        }
        if line.price >= hi || line.price <= lo {
            // The liquidation price off the plot is not named in the corner: it sat behind the
            // Short button and told nothing the position's card does not.
            if line.label.is_empty() || line.role == LineRole::Liquidation {
                continue;
            }
            let above = line.price >= hi;
            let price = price_text(line.price, tick, None);
            let label = format!("{} {} {price}", if above { '▲' } else { '▼' }, line.label);
            let width = text_width(&label, 10.0 * s);
            let left = if above { &mut above_x } else { &mut below_x };
            if *left + width > plot.x + plot.w * 0.62 {
                continue;
            }
            let y = if above { 46.0 * s } else { band_top - 6.0 * s };
            fill(pix, *left - 3.0 * s, y - 11.0 * s, width + 6.0 * s, 15.0 * s, pal().bg, 215);
            text(pix, *left, y, &label, 10.0 * s, line.label_in_theme(), 255);
            *left += width + 12.0 * s;
            continue;
        }
        let y = y_of(line.price);
        let color = line.in_theme();
        let from = start_x.map_or(plot.x, |x| x.max(plot.x));
        let segment = |pix: &mut PixmapMut, a: f32, b: f32, alpha: u8, width: f32, dash: Option<Vec<f32>>| {
            if b <= a {
                return;
            }
            let mut path = PathBuilder::new();
            path.move_to(a, y);
            path.line_to(b, y);
            if let Some(path) = path.finish() {
                stroke(pix, &path, color, alpha, width, dash);
            }
        };
        let fill_x = line.fill_ms.map(|t| x_of(t)).filter(|x| x.is_finite());
        match fill_x {
            // A filled entry: faint and thin while it waited, a dot where it filled, whole after.
            Some(at) => {
                let at = at.clamp(from, right);
                segment(pix, from, at, 128, 1.0 * s, None);
                segment(pix, at, right, 255, 1.6 * s, None);
                if at > plot.x && at < plot.x + plot.w {
                    let mut dot = PathBuilder::new();
                    dot.push_circle(at, y, 2.6 * s);
                    if let Some(dot) = dot.finish() {
                        let mut paint = Paint::default();
                        paint.set_color_rgba8(color[0], color[1], color[2], 255);
                        paint.anti_alias = true;
                        pix.fill_path(&dot, &paint, FillRule::Winding, Transform::identity(), None);
                    }
                }
            }
            None => {
                let dash = line.dashed.then(|| vec![6.0 * s, 4.0 * s]);
                segment(pix, from, right, 255, 1.4 * s, dash);
            }
        }
        // A small cross where the order was placed.
        if let Some(x) = start_x.filter(|x| *x >= plot.x) {
            let arm = 3.2 * s;
            let mut cross = PathBuilder::new();
            cross.move_to(x - arm, y - arm);
            cross.line_to(x + arm, y + arm);
            cross.move_to(x - arm, y + arm);
            cross.line_to(x + arm, y - arm);
            if let Some(cross) = cross.finish() {
                stroke(pix, &cross, color, 255, 1.5 * s, None);
            }
        }
        if line.label.is_empty() {
            continue;
        }
        let width = text_width(&line.label, 11.0 * s);
        let start = if y < corner_bottom + 8.0 * s {
            corner_left - 10.0 * s
        } else {
            plot.x + plot.w - 6.0 * s
        };
        // Takes keep their place at the right and lie over one another when close; other
        // captions step left of whatever already sits at their height.
        let edge = if line.role == LineRole::Exit {
            start
        } else {
            placed
                .iter()
                .filter(|(at, _)| (at - y).abs() < 17.0 * s)
                .map(|(_, left)| *left - 10.0 * s)
                .fold(start, f32::min)
        };
        let x = edge - width;
        if x < plot.x + plot.w * 0.2 {
            continue;
        }
        placed.push((y, x));
        // A line at the very top or bottom keeps its whole caption on screen.
        let ly = y.clamp(plot.y + 9.0 * s, plot.y + plot.h - 9.0 * s);
        fill(pix, x - 4.0 * s, ly - 8.0 * s, width + 8.0 * s, 16.0 * s, pal().line_label_bg, 217);
        text(pix, x, ly + 4.0 * s, &line.label, 11.0 * s, line.label_in_theme(), 255);
        if std::ptr::eq(pal(), &LIGHT) {
            // Thin dark strokes read pale on white: drawn twice, a hair apart, they read bold.
            text(pix, x + 0.6 * s, ly + 4.0 * s, &line.label, 11.0 * s, line.label_in_theme(), 255);
        }
    }
}

/// The book as two cumulative profiles: per side, the levels from the spread outwards with the
/// running sum of their size.
#[derive(Default)]
struct Depth {
    /// `(price, cumulative quote size up to and including this level)`, best first.
    asks: Vec<(f64, f64)>,
    bids: Vec<(f64, f64)>,
}

impl Depth {
    fn new(levels: &[BookLevel]) -> Self {
        let side = |ask: bool| {
            let mut side: Vec<(f64, f64)> = levels
                .iter()
                .filter(|l| l.ask == ask && l.price > 0.0 && l.notional > 0.0)
                .map(|l| (f64::from(l.price), f64::from(l.notional)))
                .collect();
            if ask {
                side.sort_by(|a, b| a.0.total_cmp(&b.0));
            } else {
                side.sort_by(|a, b| b.0.total_cmp(&a.0));
            }
            let mut cum = 0.0;
            for level in &mut side {
                cum += level.1;
                level.1 = cum;
            }
            side
        };
        Self { asks: side(true), bids: side(false) }
    }

    /// The size between the spread and `price` on the side `price` lies on: every ask at or
    /// under it, or every bid at or over it. Zero inside the spread.
    fn at(&self, price: f64) -> (f64, bool) {
        if let Some(&(best, _)) = self.asks.first() {
            if price >= best {
                let count = self.asks.partition_point(|level| level.0 <= price);
                return (self.asks[count - 1].1, true);
            }
        }
        if let Some(&(best, _)) = self.bids.first() {
            if price <= best {
                let count = self.bids.partition_point(|level| level.0 >= price);
                return (self.bids[count - 1].1, false);
            }
        }
        (0.0, false)
    }
}

/// The order book: a backdrop tinted by side and, per pixel row, a bar as long as the size
/// between the spread and that row's price. Drawn row by row, so the steps never leave a gap
/// between them; both sides share one scale, the deeper visible side reaching the full width.
fn order_book(
    pix: &mut PixmapMut,
    zone: &Zone,
    depth: &Depth,
    lo: f64,
    hi: f64,
    y_of: &dyn Fn(f64) -> f32,
    s: f32,
) {
    let _ = s;
    let bottom = zone.y + zone.h;
    let rows = zone.h.ceil().max(1.0) as usize;
    let price_of_row = |row: usize| hi - (row as f64 + 0.5) / zone.h as f64 * (hi - lo);
    if let Some(&(ask, _)) = depth.asks.first() {
        let y = y_of(ask).clamp(zone.y, bottom).round();
        fill(pix, zone.x, zone.y, zone.w, y - zone.y, pal().book_bg_ask, 255);
    }
    if let Some(&(bid, _)) = depth.bids.first() {
        let y = y_of(bid).clamp(zone.y, bottom).round();
        fill(pix, zone.x, y, zone.w, bottom - y, pal().book_bg_bid, 255);
    }
    let cells: Vec<(f64, bool)> = (0..rows).map(|row| depth.at(price_of_row(row))).collect();
    let most = cells.iter().fold(0f64, |most, cell| most.max(cell.0));
    if !(most > 0.0) {
        return;
    }
    let right = zone.x + zone.w;
    // Runs of rows with the same length become one rectangle.
    let mut start = 0usize;
    while start < rows {
        let (value, ask) = cells[start];
        let mut end = start + 1;
        while end < rows && cells[end] == (value, ask) {
            end += 1;
        }
        if value > 0.0 {
            let width = ((value / most) as f32 * zone.w).round().max(1.0);
            let color = if ask { pal().book_ask } else { pal().book_bid };
            fill(pix, right - width, zone.y + start as f32, width, (end - start) as f32, color, 255);
        }
        start = end;
    }
}

/// Each side of the book cut in thirds between the spread and the edge of the plot, each cut
/// naming the size from the spread to it and how far it lies from the last price.
#[allow(clippy::too_many_arguments)]
/// The take's two levels, for a moment after its % is picked: dashed light blue across the plot
/// and the book at that % above and below the middle of the spread, where a take would sit.
#[allow(clippy::too_many_arguments)]
fn take_flash(
    pix: &mut PixmapMut,
    plot: &Zone,
    width: f32,
    mid: f64,
    pct: f64,
    lo: f64,
    hi: f64,
    y_of: &dyn Fn(f64) -> f32,
    s: f32,
) {
    const BLUE: [u8; 3] = [0x7F, 0xC9, 0xFF];
    let shown = if pct.fract() == 0.0 { format!("{pct:.0}") } else { format!("{pct}") };
    for (price, label) in [(mid * (1.0 + pct / 100.0), format!("+{shown}%")), (mid * (1.0 - pct / 100.0), format!("−{shown}%"))] {
        if !(lo..=hi).contains(&price) {
            continue;
        }
        let y = y_of(price);
        let mut x = plot.x;
        while x < width {
            fill(pix, x, y - 0.75 * s, (6.0 * s).min(width - x), 1.5 * s, BLUE, 235);
            x += 10.0 * s;
        }
        text(pix, plot.x + 4.0 * s, y - 3.0 * s, &label, 10.5 * s, BLUE, 255);
    }
}

fn book_marks(
    pix: &mut PixmapMut,
    zone: &Zone,
    depth: &Depth,
    lo: f64,
    hi: f64,
    last: Option<f32>,
    y_of: &dyn Fn(f64) -> f32,
    s: f32,
) {
    let font = 9.5 * s;
    let bottom = zone.y + zone.h;
    let sides = [
        (depth.asks.first().map(|l| l.0), hi, zone.y, true),
        (depth.bids.first().map(|l| l.0), lo, bottom, false),
    ];
    for (best, edge_price, edge_y, ask) in sides {
        let Some(best) = best else { continue };
        let start = y_of(best).clamp(zone.y, bottom);
        if (edge_y - start).abs() < 30.0 * s {
            continue;
        }
        // Where the captions already printed lie, outermost first: one that would lie over another
        // nearer the edge is left out — the one farthest from the spread stays.
        let mut taken: Vec<f32> = Vec::new();
        for k in (1..=3).rev() {
            let fraction = k as f64 / 3.0;
            let price = best + (edge_price - best) * fraction;
            // The last cut sits a little inside the edge so its caption stays on screen.
            let price = if k == 3 { best + (edge_price - best) * 0.97 } else { price };
            let y = y_of(price);
            let (cum, _) = depth.at(price);
            let mut path = PathBuilder::new();
            path.move_to(zone.x, y);
            path.line_to(zone.x + zone.w, y);
            if let Some(path) = path.finish() {
                stroke(pix, &path, pal().marks_line, 110, 1.0 * s, Some(vec![3.0 * s, 3.0 * s]));
            }
            let mut label = compact(cum);
            if let Some(last) = last.filter(|l| *l > 0.0) {
                let away = (price - f64::from(last)) / f64::from(last) * 100.0;
                label.push_str(&format!(" {away:+.2}%"));
            }
            // At the book's right edge, where its bars start, so the black text lies on colour.
            let x = zone.x + zone.w - text_width(&label, font) - 3.0 * s;
            // Captions sit on the far side of their cut, away from the spread, unless that side
            // runs off the book: then on the near side, whole.
            let above = y - 3.0 * s;
            let below = y + 11.0 * s;
            let text_y = if ask {
                if above - font < zone.y { below } else { above }
            } else if below > bottom {
                above
            } else {
                below
            };
            if taken.iter().any(|at| (at - text_y).abs() < font + 2.0 * s) {
                continue;
            }
            taken.push(text_y);
            // Plain text over the book's colour, no backdrop: white on the dark theme, black on
            // the light one.
            text(pix, x, text_y, &label, font, pal().strong, 255);
        }
    }
}

/// The crosshair: thin lines through the point across the plot and the book, the price and
/// time under it on the axes, the book's depth from the spread to that price, and the volume
/// bought and sold around that moment.
#[allow(clippy::too_many_arguments)]
fn crosshair(
    pix: &mut PixmapMut,
    plot: &Zone,
    book: &Zone,
    band_top: f32,
    (x, y): (f32, f32),
    data: &ChartData,
    depth: &Depth,
    view: &View,
    last: Option<f32>,
    s: f32,
) {
    let font = 10.5 * s;
    let (lo, hi) = (view.price_lo, view.price_hi);
    let y = y.clamp(plot.y, plot.y + plot.h - 1.0);
    let x = x.clamp(0.0, book.x + book.w - 1.0);
    let price = hi - f64::from((y - plot.y) / plot.h) * (hi - lo);
    let tick = data.captions.price_tick;
    let thin = (0.8 * s).max(1.0);
    fill(pix, plot.x, y, book.x + book.w - plot.x, thin, pal().cross, 200);
    let in_plot = x >= plot.x && x < plot.x + plot.w;
    if in_plot {
        fill(pix, x, plot.y, thin, plot.h, pal().cross, 200);
    }

    // The price on the left axis.
    let label = price_text(price, tick, None);
    let width = text_width(&label, font);
    fill(pix, 0.0, y - 8.0 * s, (width + 8.0 * s).max(plot.x - 2.0 * s), 16.0 * s, pal().readout_bg, 245);
    text(pix, 3.0 * s, y + 4.0 * s, &label, font, pal().strong, 255);

    // The book at that price: the size from the spread to it, and its distance from the last.
    let (cum, ask) = depth.at(price);
    let mut lines = vec![(compact(cum), if ask { pal().ask_text } else { pal().bid_text })];
    if let Some(last) = last.filter(|l| *l > 0.0) {
        let away = (price - f64::from(last)) / f64::from(last) * 100.0;
        lines.push((format!("{away:+.2}%"), if away < 0.0 { pal().red } else { pal().green }));
    }
    for (at, (line, color)) in lines.iter().enumerate() {
        let w = text_width(line, font);
        let top = y + 3.0 * s + at as f32 * 15.0 * s;
        fill(pix, book.x + 2.0 * s, top, w + 8.0 * s, 15.0 * s, pal().readout_bg, 235);
        text(pix, book.x + 6.0 * s, top + 11.0 * s, line, font, *color, 255);
    }

    if !in_plot {
        return;
    }
    // The time on the bottom axis.
    let span = (view.to_ms - view.from_ms).max(1);
    let t = view.from_ms + ((x - plot.x) / plot.w * span as f32) as i64;
    let local = t + i64::from(view.tz_offset_min) * 60_000;
    let label = clock(local, true);
    let width = text_width(&label, font);
    fill(pix, x - width / 2.0 - 4.0 * s, plot.y + plot.h + 1.0, width + 8.0 * s, 15.0 * s, pal().readout_bg, 245);
    text(pix, x - width / 2.0, plot.y + plot.h + 12.0 * s, &label, font, pal().strong, 255);

    // Bought and sold over the volume interval ending at the cursor, as the band draws them.
    let interval = volume_interval_ms(span);
    let (mut buy, mut sell) = (0f64, 0f64);
    for tick in data.ticks.iter().filter(|tk| tk.t_ms > t - interval && tk.t_ms <= t) {
        let value = f64::from(tick.price) * f64::from(tick.qty);
        if tick.buy {
            buy += value;
        } else {
            sell += value;
        }
    }
    let rows = [
        format!("Курсор {}", duration_text(interval / 1000)),
        format!("Bv: {}", compact(buy)),
        format!("Sv: {}", compact(sell)),
    ];
    let row = 15.0 * s;
    let right = plot.x + plot.w - 8.0 * s;
    for (at, line) in rows.iter().enumerate() {
        let w = text_width(line, 11.5 * s);
        text(pix, right - w, row * (CURSOR_ROW + at as f32), line, 11.5 * s, pal().label, 255);
    }
    let _ = band_top;
}

/// The right-hand caption block as [`captions`] lays it out: where its parts begin on the left.
struct CornerLayout {
    /// The right edge of the deltas, left of the block.
    delta_right: f32,
    /// The left edge of the deltas, or of the block when there are none.
    left: f32,
    /// Rows the block takes, counted in caption rows.
    rows: f32,
}

const DELTA_SAMPLE: &str = "Δ24ч: +00.00%";
const VOLUME_SAMPLE: &str = "Sv: 000.00K";

fn corner_layout(plot: &Zone, data: &ChartData, view: &View, s: f32) -> CornerLayout {
    let c = &data.captions;
    let font = 11.5 * s;
    let right = plot.x + plot.w - 8.0 * s;
    let mut badge = duration_text((view.to_ms - view.from_ms) / 1000);
    if let Some(percent) = &view.scale_badge {
        badge.push(' ');
        badge.push_str(percent);
    }
    let mut block_left = right - text_width(&badge, font);
    let mut rows: f32 = 1.3;
    if c.buy_1m.is_some() {
        let volumes = right - 26.0 * s - 6.0 * s - text_width(VOLUME_SAMPLE, font);
        block_left = block_left.min(volumes - text_width("1м ", font));
        rows = 3.4;
    }
    let delta_right = block_left - 24.0 * s;
    let mut left = block_left;
    if c.delta_1h.is_some() || c.delta_24h.is_some() {
        left = delta_right - text_width(DELTA_SAMPLE, font);
        rows = rows.max(2.3);
    }
    CornerLayout { delta_right, left, rows }
}

/// Where the caption block in the plot's top right corner begins on the left and ends below,
/// as [`captions`] and the crosshair lay it out.
fn caption_corner(plot: &Zone, data: &ChartData, view: &View, s: f32) -> (f32, f32) {
    let font = 11.5 * s;
    let row = 15.0 * s;
    let right = plot.x + plot.w - 8.0 * s;
    let layout = corner_layout(plot, data, view, s);
    let mut left = layout.left;
    let mut rows = layout.rows;
    if view.cursor.is_some_and(|(x, _)| x >= plot.x && x < plot.x + plot.w) {
        left = left.min(right - text_width("Курсор 15м 0с", font));
        rows = CURSOR_ROW + 2.7;
    }
    (left, row * rows)
}

/// The caption row the crosshair's readout starts on.
const CURSOR_ROW: f32 = 4.6;

/// How far down the top-left captions reach: the app puts its Short button under them.
fn captions_bottom(data: &ChartData, s: f32) -> f32 {
    let c = &data.captions;
    let row = 15.0 * s;
    let rows = if c.detect.as_deref().is_some_and(|d| !d.is_empty()) {
        2.0
    } else if c.funding_pct.is_some() || c.session_asked {
        1.0
    } else {
        0.0
    };
    row * rows + 5.0 * s
}

/// The texts in the plot's corners, placed where the terminal prints them: funding and the
/// newest detect top left; top right the badge with the minute's volume under it, and the
/// deltas beside them.
fn captions(pix: &mut PixmapMut, plot: &Zone, data: &ChartData, view: &View, s: f32) {
    let c = &data.captions;
    let font = 11.5 * s;
    let row = 15.0 * s;
    let right = plot.x + plot.w - 8.0 * s;
    let layout = corner_layout(plot, data, view, s);

    // Top right, first row: how much time and price the plot spans.
    let mut badge = duration_text((view.to_ms - view.from_ms) / 1000);
    if let Some(percent) = &view.scale_badge {
        badge.push(' ');
        badge.push_str(percent);
    }
    let badge_w = text_width(&badge, font);
    text(pix, right - badge_w, row, &badge, font, pal().label, 255);
    // Under it: what was bought and sold in the last minute, each with its bar.
    if let (Some(buy), Some(sell)) = (c.buy_1m, c.sell_1m) {
        let bar_room = 26.0 * s;
        let most = buy.max(sell).max(1e-9);
        let sides = [("Bv: ", buy, pal().volume_up), ("Sv: ", sell, pal().volume_down)];
        let mut left = f32::MAX;
        for (at, (name, value, color)) in sides.iter().enumerate() {
            let y = row * (2.0 + at as f32);
            let label = format!("{name}{}", compact(*value));
            let x = right - bar_room - 6.0 * s - text_width(&label, font);
            left = left.min(x);
            text(pix, x, y, &label, font, pal().label, 255);
            let width = ((value / most) as f32 * bar_room).max(2.0 * s);
            fill(pix, right - bar_room, y - 7.0 * s, width, 6.0 * s, *color, 255);
        }
        let tag = "1м ";
        text(pix, left - text_width(tag, font), row * 2.0, tag, font, pal().label, 170);
    }
    // Left of the block: the hour's and the day's delta.
    let deltas = [("Δ1ч: ", c.delta_1h), ("Δ24ч: ", c.delta_24h)];
    for (at, (name, value)) in deltas.iter().enumerate() {
        let Some(value) = value else { continue };
        let figure = format!("{value:+.2}%");
        let figure_w = text_width(&figure, font);
        let y = row * (at as f32 + 1.0);
        let color = if *value < 0.0 { pal().red } else { pal().green };
        text(pix, layout.delta_right - figure_w, y, &figure, font, color, 255);
        let name_x = layout.delta_right - figure_w - text_width(name, font);
        text(pix, name_x, y, name, font, pal().label, 255);
    }

    // Top left: the funding, and right after its time the session's result, as MoonBot's
    // `F: … Ses: 91.60$`. Where the row is too short for both, the funding's name shortens to
    // «Ф:»; a session not read yet shows a dash.
    let session = c.session_asked.then(|| match c.session_usdt.filter(|v| v.is_finite()) {
        Some(value) => (format!("{}$", money_text(value)), if value < 0.0 { pal().red } else { pal().green }),
        None => ("—".to_string(), pal().label),
    });
    let session_w = session
        .as_ref()
        .map_or(0.0, |(figure, _)| 10.0 * s + text_width("Сес: ", font) + text_width(figure, font));
    let room = layout.left - 8.0 * s;
    let start = plot.x + 14.0 * s;
    let mut after = start;
    if let Some(funding) = c.funding_pct {
        let time = c
            .funding_at_ms
            .filter(|at| *at > view.now_ms)
            .map(|at| format!("  {}", duration_text((at - view.now_ms) / 1000)))
            .unwrap_or_default();
        let mut line = format!("Фандинг: {funding:.2}%{time}");
        if start + text_width(&line, font) + session_w > room {
            line = format!("Ф: {funding:.2}%{time}");
        }
        text(pix, after, row, &line, font, pal().label, 255);
        after += text_width(&line, font) + 10.0 * s;
    }
    if let Some((figure, color)) = session {
        let name = "Сес: ";
        if after + text_width(name, font) + text_width(&figure, font) <= room {
            text(pix, after, row, name, font, pal().label, 255);
            text(pix, after + text_width(name, font), row, &figure, font, color, 255);
        }
    }

    // Under it: the name of the newest detect's strategy, cut to what is left of the row.
    if let Some(detect) = c.detect.as_deref().filter(|d| !d.is_empty()) {
        let line = format!("Детект: {detect}");
        let room = (layout.left - 20.0 * s - plot.x - 14.0 * s).max(80.0 * s);
        let line = ellipsize(&line, font, room);
        text(pix, plot.x + 14.0 * s, row * 2.0, &line, font, pal().label, 255);
    }
}

fn stroke(
    pix: &mut PixmapMut,
    path: &tiny_skia::Path,
    color: [u8; 3],
    alpha: u8,
    width: f32,
    dash: Option<Vec<f32>>,
) {
    let mut paint = Paint::default();
    paint.set_color_rgba8(color[0], color[1], color[2], alpha);
    paint.anti_alias = true;
    let stroke = Stroke {
        width,
        line_cap: LineCap::Butt,
        line_join: LineJoin::Miter,
        dash: dash.and_then(|pattern| StrokeDash::new(pattern, 0.0)),
        ..Stroke::default()
    };
    pix.stroke_path(path, &paint, &stroke, Transform::identity(), None);
}

fn fill(pix: &mut PixmapMut, x: f32, y: f32, w: f32, h: f32, color: [u8; 3], alpha: u8) {
    // An empty rectangle draws nothing, and tiny-skia logs a warning for every one it is given.
    if !(w > 0.0 && h > 0.0) {
        return;
    }
    let Some(rect) = Rect::from_xywh(x, y, w, h) else {
        return;
    };
    let mut paint = Paint::default();
    paint.set_color_rgba8(color[0], color[1], color[2], alpha);
    paint.anti_alias = false;
    pix.fill_rect(rect, &paint, Transform::identity(), None);
}

fn rgb(color: [u8; 3]) -> Color {
    Color::from_rgba8(color[0], color[1], color[2], 255)
}

// ---------------------------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------------------------

/// DejaVu Sans Mono, shipped inside the library: the picture must not depend on which fonts a
/// phone happens to carry.
static FONT_BYTES: &[u8] = include_bytes!("../assets/DejaVuSansMono.ttf");

type Glyph = (fontdue::Metrics, Vec<u8>);

fn font() -> &'static fontdue::Font {
    static FONT: OnceLock<fontdue::Font> = OnceLock::new();
    FONT.get_or_init(|| {
        fontdue::Font::from_bytes(FONT_BYTES, fontdue::FontSettings::default())
            .expect("the embedded font parses")
    })
}

/// Rasterised glyphs by character and size in tenths of a pixel.
fn glyph(ch: char, px: f32) -> Arc<Glyph> {
    static CACHE: OnceLock<Mutex<HashMap<(char, u32), Arc<Glyph>>>> = OnceLock::new();
    let key = (ch, (px * 10.0) as u32);
    let mut cache = CACHE
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if cache.len() > 4000 {
        cache.clear();
    }
    cache
        .entry(key)
        .or_insert_with(|| Arc::new(font().rasterize(ch, px)))
        .clone()
}

/// The characters of `text` this font can draw. A core's detect line may carry symbols it
/// cannot (a pointing hand, a flag): those are left out rather than drawn as empty boxes.
pub fn drawable(text: &str) -> String {
    text.chars()
        .filter(|ch| *ch == ' ' || font().lookup_glyph_index(*ch) != 0)
        .collect()
}

fn text_width(text: &str, px: f32) -> f32 {
    text.chars().map(|ch| glyph(ch, px).0.advance_width).sum()
}

/// `text` cut to `room` pixels, ending in an ellipsis when it did not fit.
fn ellipsize(text: &str, px: f32, room: f32) -> String {
    if text_width(text, px) <= room {
        return text.to_string();
    }
    let mut kept = String::new();
    for ch in text.chars() {
        kept.push(ch);
        if text_width(&kept, px) + text_width("…", px) > room {
            kept.pop();
            break;
        }
    }
    kept.push('…');
    kept
}

/// Draw `text` with its baseline at `y`, starting at `x`.
fn text(pix: &mut PixmapMut, x: f32, y: f32, text: &str, px: f32, color: [u8; 3], alpha: u8) {
    let (w, h) = (pix.width() as i32, pix.height() as i32);
    let data = pix.data_mut();
    let mut pen = x;
    for ch in text.chars() {
        let glyph = glyph(ch, px);
        let (metrics, coverage) = (&glyph.0, &glyph.1);
        let left = (pen + metrics.xmin as f32).round() as i32;
        let top = (y - metrics.ymin as f32 - metrics.height as f32).round() as i32;
        for row in 0..metrics.height as i32 {
            let py = top + row;
            if py < 0 || py >= h {
                continue;
            }
            for col in 0..metrics.width as i32 {
                let px_x = left + col;
                if px_x < 0 || px_x >= w {
                    continue;
                }
                let cover = u32::from(coverage[(row * metrics.width as i32 + col) as usize]);
                let a = cover * u32::from(alpha) / 255;
                if a == 0 {
                    continue;
                }
                let at = ((py * w + px_x) * 4) as usize;
                for (channel, value) in color.iter().enumerate() {
                    let under = u32::from(data[at + channel]);
                    data[at + channel] = ((u32::from(*value) * a + under * (255 - a)) / 255) as u8;
                }
                let under = u32::from(data[at + 3]);
                data[at + 3] = (a + under * (255 - a) / 255).min(255) as u8;
            }
        }
        pen += metrics.advance_width;
    }
}

// ---------------------------------------------------------------------------------------------
// Numbers
// ---------------------------------------------------------------------------------------------

/// A round price-grid interval for about `lines` lines: the terminal's `nice_interval`.
fn nice_interval(range: f64, lines: f64) -> f64 {
    let rough = range / lines.max(1.0);
    if !(rough > 0.0) || !rough.is_finite() {
        return 1.0;
    }
    let magnitude = 10f64.powf(rough.log10().floor());
    let n = rough / magnitude;
    let nice = if n < 1.5 {
        1.0
    } else if n < 3.0 {
        2.0
    } else if n < 7.0 {
        5.0
    } else {
        10.0
    };
    nice * magnitude
}

/// Decimals that show `step` exactly.
fn decimals_for(step: f64) -> usize {
    let mut decimals = 0usize;
    let mut value = step;
    while decimals < 12 && (value - value.round()).abs() > 1e-9 * value.abs().max(1.0) {
        value *= 10.0;
        decimals += 1;
    }
    decimals
}

/// A price as the chart prints it: on the market's own grid when its tick is known, otherwise
/// by its magnitude, with four significant digits for a price under one — and never finer than
/// a hundredth of a percent of the price: `0.19599999` on a 1e-8 grid reads `0.19600`, as digits
/// past that move with every tick and tell nothing.
///
/// Args:
///     tick: The market's price grid.
///     step: The grid interval the price is a label of; it may ask for more decimals than the
///         magnitude alone would give, never fewer.
fn price_text(price: f64, tick: Option<f64>, step: Option<f64>) -> String {
    // The decimals at which one unit of the last digit is 0.01% of the price.
    let by_share = {
        let unit = price.abs() * 1e-4;
        if unit > 0.0 && unit.is_finite() {
            (-unit.log10()).ceil().clamp(0.0, 12.0) as usize
        } else {
            12
        }
    };
    let by_size = {
        let magnitude = price.abs();
        if magnitude >= 1000.0 {
            1
        } else if magnitude >= 10.0 {
            2
        } else if magnitude >= 1.0 {
            3
        } else if magnitude > 0.0 {
            ((3.0 - magnitude.log10().floor()) as usize).clamp(4, 12)
        } else {
            4
        }
    };
    // A tick at or above the price itself is no grid for it (a market that reports its tick
    // in another unit): the price's own size decides then. A grid step may always ask for more.
    let tick = tick.filter(|tick| tick.is_finite() && *tick > 0.0 && (price == 0.0 || *tick < price.abs()));
    let by_step = step.map_or(0, decimals_for);
    let decimals = match tick {
        Some(tick) => decimals_for(tick).min(by_share).max(by_step),
        None => by_size.min(by_share).max(by_step),
    };
    format!("{price:.decimals$}")
}

/// An amount of money as the session prints it: two decimals, a thousands letter from 10K,
/// a minus when lost and no sign when made, as MoonBot's `Ses: 91.60$`.
fn money_text(value: f64) -> String {
    let magnitude = value.abs();
    let text = if magnitude >= 10_000.0 { compact_amount(magnitude) } else { format!("{magnitude:.2}") };
    if value < 0.0 && text.trim_start_matches(['0', '.']).len() > 0 { format!("-{text}") } else { text }
}

fn nice_time_step(raw_ms: f64) -> i64 {
    const STEPS: [i64; 15] = [
        1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000,
        1_800_000, 3_600_000, 7_200_000, 14_400_000,
    ];
    STEPS.iter().copied().find(|step| *step as f64 >= raw_ms).unwrap_or(14_400_000)
}

fn clock(local_ms: i64, seconds: bool) -> String {
    let day = local_ms.rem_euclid(86_400_000) / 1000;
    let (h, m, s) = (day / 3600, day / 60 % 60, day % 60);
    if seconds {
        format!("{h:02}:{m:02}:{s:02}")
    } else {
        format!("{h:02}:{m:02}")
    }
}

/// A length of time the way the terminal's badge prints it: `12м 31с`, `1ч 5м`, `45с`.
fn duration_text(seconds: i64) -> String {
    let seconds = seconds.max(0);
    let (h, m, s) = (seconds / 3600, seconds / 60 % 60, seconds % 60);
    if h > 0 {
        format!("{h}ч {m}м")
    } else if m > 0 {
        format!("{m}м {s}с")
    } else {
        format!("{s}с")
    }
}

/// An amount with two decimals and a thousands letter: `3.13K`, `169.79`, `0`.
fn compact(value: f64) -> String {
    let (scaled, unit) = if value >= 1e9 {
        (value / 1e9, "B")
    } else if value >= 1e6 {
        (value / 1e6, "M")
    } else if value >= 1e3 {
        (value / 1e3, "K")
    } else {
        (value, "")
    };
    let text = format!("{scaled:.2}");
    let text = text.trim_end_matches('0').trim_end_matches('.');
    format!("{text}{unit}")
}

/// The volume scale's top and its half, in one unit: `1.8 k$` over `0.90 k$`.
fn money_pair(top: f64) -> (String, String) {
    let (divisor, unit) = if top >= 1e6 {
        (1e6, "M$")
    } else if top >= 1e3 {
        (1e3, "k$")
    } else {
        (1.0, "$")
    };
    let figure = |value: f64| {
        let scaled = value / divisor;
        let decimals = if scaled >= 100.0 {
            0
        } else if scaled >= 1.0 {
            1
        } else {
            2
        };
        format!("{scaled:.decimals$} {unit}")
    };
    (figure(top), figure(top / 2.0))
}


// ---------------------------------------------------------------------------------------------
// Book layout and the demo market
// ---------------------------------------------------------------------------------------------

/// Markets the demo core offers in the chart's market box.
pub const DEMO_MARKETS: [&str; 4] = ["BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT"];

/// The invented market's price at `now_ms`.
pub fn demo_price(market: &str, now_ms: i64) -> f32 {
    demo_curve(demo_seed(market), now_ms) as f32
}

/// The invented market's last day as closes five minutes apart, for a detect card's thumbnail.
pub fn demo_day(market: &str, now_ms: i64) -> Vec<f32> {
    let seed = demo_seed(market);
    (0..288)
        .map(|i| demo_curve(seed, now_ms - (287 - i) * 300_000) as f32)
        .collect()
}

fn demo_seed(market: &str) -> u64 {
    market
        .bytes()
        .fold(0x9E37_79B9u64, |acc, b| acc.wrapping_mul(31).wrapping_add(u64::from(b)))
}

/// Around 100, where the demo core's own orders sit, so their lines cross the picture.
fn demo_curve(seed: u64, t_ms: i64) -> f64 {
    let t = t_ms as f64 / 1000.0 + (seed % 600) as f64;
    100.0
        * (1.0
            + 0.014 * (t / 4100.0 + (seed % 7) as f64).sin()
            + 0.011 * (t / 173.0).sin()
            + 0.005 * (t / 41.0 + 1.3).sin()
            + 0.0015 * (t / 7.0 + 0.4).sin())
}

/// The middle of the spread, when the book has both sides and they lie near the last trade: the
/// price a live chart keeps in its centre.
pub fn spread_middle(book: &[BookLevel], last: Option<f32>) -> Option<f32> {
    let ask = book
        .iter()
        .filter(|l| l.ask && l.price > 0.0)
        .map(|l| l.price)
        .fold(f32::MAX, f32::min);
    let bid = book
        .iter()
        .filter(|l| !l.ask && l.price > 0.0)
        .map(|l| l.price)
        .fold(f32::MIN, f32::max);
    if !(ask < f32::MAX && bid > f32::MIN && ask >= bid) {
        return None;
    }
    let middle = (ask + bid) / 2.0;
    match last.filter(|l| *l > 0.0) {
        // A book that has not caught up with the trades (or a stale side) is not followed.
        Some(last) if ((middle - last) / last).abs() > 0.02 => None,
        _ => Some(middle),
    }
}

/// The demo market's history as candles of `tf_ms` from `from_ms` up to `to_ms`.
pub fn demo_candles(market: &str, from_ms: i64, to_ms: i64, tf_ms: i64) -> Vec<Candle> {
    let seed = demo_seed(market);
    let mut out = Vec::new();
    let mut t = from_ms - from_ms.rem_euclid(tf_ms);
    while t < to_ms && out.len() < 2000 {
        let steps = 12;
        let prices: Vec<f32> = (0..=steps)
            .map(|i| demo_curve(seed, t + tf_ms * i / steps) as f32)
            .collect();
        let low = prices.iter().copied().fold(f32::MAX, f32::min);
        let high = prices.iter().copied().fold(f32::MIN, f32::max);
        let roll = hash(seed ^ (t / tf_ms) as u64);
        out.push(Candle {
            t_ms: t,
            tf_ms,
            open: prices[0],
            high,
            low,
            close: prices[steps as usize],
            volume: (20_000 + roll % 180_000) as f32,
        });
        t += tf_ms;
    }
    out
}

/// An invented market for the demo core: the same trades and book for the same name and time,
/// so a chart that scrolls or zooms shows one consistent history.
pub fn demo_market(
    market: &str,
    from_ms: i64,
    to_ms: i64,
    now_ms: i64,
) -> (Vec<TickPoint>, Vec<BookLevel>) {
    let seed = demo_seed(market);
    let price_at = |t_ms: i64| demo_curve(seed, t_ms);
    const SLOT_MS: i64 = 200;
    let mut ticks = Vec::new();
    let end = to_ms.min(now_ms);
    let mut slot = from_ms.div_euclid(SLOT_MS);
    while slot * SLOT_MS <= end && ticks.len() < 60_000 {
        let roll = hash(seed ^ slot as u64);
        // Quiet stretches with a trade every few seconds, and bursts.
        let busy = hash(seed ^ (slot / 150) as u64) % 4 == 0;
        if roll % 100 < if busy { 45 } else { 4 } {
            let t_ms = slot * SLOT_MS + (roll >> 8) as i64 % SLOT_MS;
            if t_ms >= from_ms && t_ms <= end {
                let jitter = ((roll >> 20) % 200) as f64 / 200.0 - 0.5;
                let price = price_at(t_ms) * (1.0 + jitter * 0.0012);
                let qty = 0.2 + ((roll >> 32) % 1000) as f64 / 180.0;
                ticks.push(TickPoint {
                    t_ms,
                    price: price as f32,
                    qty: qty as f32,
                    buy: (roll >> 44) & 1 == 0,
                });
            }
        }
        slot += 1;
    }
    let mid = price_at(now_ms);
    let mut book = Vec::with_capacity(400);
    for i in 0..200u64 {
        for ask in [true, false] {
            let offset = (i as f64 + 0.6) * 0.00035 * mid;
            let roll = hash(seed ^ (i << 1 | u64::from(ask)) ^ (now_ms / 2000) as u64);
            let wall = if roll % 17 == 0 { 6.0 } else { 1.0 };
            book.push(BookLevel {
                price: (if ask { mid + offset } else { mid - offset }) as f32,
                notional: ((1500 + roll % 9000) as f64 * wall) as f32,
                ask,
            });
        }
    }
    (ticks, book)
}

fn hash(mut x: u64) -> u64 {
    x ^= x >> 33;
    x = x.wrapping_mul(0xFF51_AFD7_ED55_8CCD);
    x ^= x >> 33;
    x = x.wrapping_mul(0xC4CE_B9FE_1A85_EC53);
    x ^ (x >> 33)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn grid_steps_are_round() {
        assert!((nice_interval(0.003, 6.0) - 0.0005).abs() < 1e-12);
        assert_eq!(nice_interval(126.0, 6.0), 20.0);
        assert_eq!(decimals_for(0.0005), 4);
        assert_eq!(decimals_for(2.5), 1);
        assert_eq!(decimals_for(10.0), 0);
        assert_eq!(nice_time_step(7_000.0), 10_000);
    }

    #[test]
    fn prices_print_on_the_markets_grid() {
        assert_eq!(price_text(65323.9, Some(0.1), None), "65324");
        assert_eq!(price_text(65300.0, None, Some(100.0)), "65300");
        assert_eq!(price_text(74.1, Some(0.01), None), "74.10");
        assert_eq!(price_text(0.19599999, Some(1e-8), None), "0.19600");
        assert_eq!(price_text(0.208, Some(1e-8), Some(0.002)), "0.20800");
        assert_eq!(price_text(0.009833, None, None), "0.009833");
        assert_eq!(price_text(1.5, None, Some(0.0005)), "1.5000");
    }

    #[test]
    fn captions_print_like_the_terminal() {
        assert_eq!(duration_text(751), "12м 31с");
        assert_eq!(duration_text(3900), "1ч 5м");
        assert_eq!(compact(3130.0), "3.13K");
        assert_eq!(compact(169.79), "169.79");
        assert_eq!(compact(0.0), "0");
        assert_eq!(money_pair(1800.0), ("1.8 k$".to_string(), "0.90 k$".to_string()));
    }

    #[test]
    fn the_scale_follows_the_terminals_rules() {
        // Auto, live: the last price in the middle, everything in view inside, a fifth spare.
        let auto =
            fit_price(None, Some((98.0, 101.0)), Some(100.0), Scale::Auto, true, 0.2, None).unwrap();
        assert_eq!(auto.center, 100.0);
        assert!((auto.range - 4.8).abs() < 1e-9);
        // It widens at once and narrows gradually.
        let wider = fit_price(Some(auto), Some((90.0, 101.0)), Some(100.0), Scale::Auto, true, 0.2, None)
            .unwrap();
        assert!((wider.range - 24.0).abs() < 1e-9);
        let narrower =
            fit_price(Some(wider), Some((99.0, 101.0)), Some(100.0), Scale::Auto, true, 0.2, None)
                .unwrap();
        assert!(narrower.range < wider.range && narrower.range > 2.4);
        // The same time in more, shorter frames eases the same distance.
        let mut stepped = wider;
        for _ in 0..4 {
            stepped =
                fit_price(Some(stepped), Some((99.0, 101.0)), Some(100.0), Scale::Auto, true, 0.05, None)
                    .unwrap();
        }
        assert!((stepped.range - narrower.range).abs() < 1e-9);
        // A fixed 20% is 20% of the price, glides after the spread and jumps to one that left
        // the window.
        let fixed = fit_price(None, None, Some(100.0), Scale::Percent(20.0), true, 0.2, None).unwrap();
        assert_eq!((fixed.center, fixed.range), (100.0, 20.0));
        let followed =
            fit_price(Some(fixed), None, Some(102.0), Scale::Percent(20.0), true, 0.2, None).unwrap();
        assert!(followed.center > 101.5 && followed.center < 102.0);
        let caught =
            fit_price(Some(fixed), None, Some(200.0), Scale::Percent(20.0), true, 0.04, None).unwrap();
        assert_eq!(caught.center, 200.0);
        // A centre the user holds stays put, with the range the scale gives; an automatic scale
        // keeps the range on screen.
        let held =
            fit_price(Some(fixed), None, Some(102.0), Scale::Percent(20.0), true, 0.2, Some(90.0)).unwrap();
        assert_eq!((held.center, held.range), (90.0, 18.0));
        let held_auto =
            fit_price(Some(wider), Some((99.0, 101.0)), Some(100.0), Scale::Auto, true, 0.2, Some(97.0))
                .unwrap();
        assert_eq!((held_auto.center, held_auto.range), (97.0, wider.range));
        // Let go, it glides back to the spread rather than jumping.
        let back =
            fit_price(Some(held), None, Some(100.0), Scale::Percent(20.0), true, 0.04, None).unwrap();
        assert!(back.center > 90.0 && back.center < 100.0);
        assert!(fit_price(None, None, None, Scale::Auto, true, 0.2, None).is_none());
    }

    #[test]
    fn a_distant_order_line_does_not_stretch_the_fit() {
        let ticks = [
            TickPoint { t_ms: 10, price: 100.0, qty: 1.0, buy: true },
            TickPoint { t_ms: 20, price: 101.0, qty: 1.0, buy: false },
        ];
        let line = |price: f64| OrderLine {
            price,
            color: [127, 201, 255],
            light: [0, 0, 255],
            dashed: false,
            label: String::new(),
            label_color: [211, 211, 211],
            label_light: [0, 0, 0],
            uid: 1,
            role: LineRole::Exit,
            short: false,
            ..OrderLine::default()
        };
        let span = visible_span(&ticks, &[], &[line(101.4), line(127.0)], 0, 100).unwrap();
        assert_eq!(span.0, 100.0);
        assert!((span.1 - 101.4).abs() < 1e-9);
        assert!(visible_span(&ticks, &[], &[], 30, 100).is_none());
    }

    #[test]
    fn a_dragged_exit_is_captioned_at_its_new_price() {
        let mut line = OrderLine {
            price: 101.0,
            role: LineRole::Exit,
            entry_price: Some(100.0),
            usd_per_price: Some(20.0),
            tag: " [3]".into(),
            ..OrderLine::default()
        };
        line.moved_to(102.0, Some(0.01));
        assert_eq!(line.label, "+2.00% $2.04K");
        line.moved_to(99.0, Some(0.01));
        assert!(line.label.starts_with("-1.00%"));
        assert_eq!(line.label_color, LABEL_LOSS_RGB);
    }

    #[test]
    fn the_volume_interval_is_moonbots_table() {
        assert_eq!(volume_interval_ms(60_000), 2_000);
        assert_eq!(volume_interval_ms(12 * 60_000), 15_000);
        assert_eq!(volume_interval_ms(3 * 3_600_000), 300_000);
    }

    #[test]
    fn a_frame_draws_without_any_data() {
        let view = View {
            width: 320,
            height: 160,
            scale: 1.0,
            from_ms: 0,
            to_ms: 60_000,
            now_ms: 50_000,
            tz_offset_min: 0,
            price_lo: 0.0,
            price_hi: 0.0,
            scale_badge: None,
            cursor: Some((100.0, 50.0)),
            book_marks: true,
            take_flash_pct: None,
            light: false,
            left_inset: 0.0,
        };
        let mut pixels = vec![0u8; 320 * 160 * 4];
        let meta = render(&ChartData::default(), &view, &mut pixels).unwrap();
        assert_eq!(meta["ticks"], 0);
        assert!(pixels.iter().any(|b| *b != 0));
    }

    #[test]
    fn the_lines_on_screen_are_reported_for_dragging() {
        let (ticks, book) = demo_market("SYNTH0", 0, 600_000, 600_000);
        let mid = f64::from(demo_price("SYNTH0", 600_000));
        let line = OrderLine {
            price: mid * 0.995,
            color: [255, 106, 0],
            light: [176, 0, 0],
            dashed: true,
            label: "$100 [1]".into(),
            label_color: [235, 235, 235],
            label_light: [0, 0, 0],
            uid: 42,
            role: LineRole::Entry,
            short: false,
            ..OrderLine::default()
        };
        let data = ChartData {
            ticks,
            book,
            lines: vec![line],
            last_price: Some(mid as f32),
            ..ChartData::default()
        };
        let view = View {
            width: 800,
            height: 400,
            scale: 1.0,
            from_ms: 0,
            to_ms: 600_000,
            now_ms: 600_000,
            tz_offset_min: 0,
            price_lo: mid * 0.98,
            price_hi: mid * 1.02,
            scale_badge: None,
            cursor: None,
            book_marks: false,
            take_flash_pct: None,
            light: true,
            left_inset: 60.0,
        };
        let mut pixels = vec![0u8; 800 * 400 * 4];
        let meta = render(&data, &view, &mut pixels).unwrap();
        let lines = meta["lines"].as_array().unwrap();
        assert_eq!(lines.len(), 1);
        assert_eq!(lines[0]["uid"], "42");
        assert_eq!(lines[0]["role"], "entry");
        let y = lines[0]["y"].as_f64().unwrap();
        assert!(y > 200.0 && y < 400.0, "{y}");
    }

    #[test]
    fn the_demo_market_is_the_same_twice() {
        let (a, book) = demo_market("SYNTH0", 1_000_000, 1_300_000, 1_300_000);
        let (b, _) = demo_market("SYNTH0", 1_000_000, 1_300_000, 1_300_000);
        assert!(a.len() > 20);
        assert_eq!(a.len(), b.len());
        assert!(a.windows(2).all(|w| w[0].t_ms <= w[1].t_ms));
        let mid = f64::from(demo_price("SYNTH0", 1_300_000));
        let depth = Depth::new(&book);
        // The profile grows away from the spread on both sides and is zero inside it.
        let (near, ask) = depth.at(mid * 1.002);
        let (far, _) = depth.at(mid * 1.01);
        assert!(ask && near > 0.0 && far > near);
        let (bid_near, ask_side) = depth.at(mid * 0.998);
        assert!(!ask_side && bid_near > 0.0);
        assert_eq!(depth.at(mid).0, 0.0);
    }

    #[test]
    fn undrawable_symbols_are_left_out() {
        assert_eq!(drawable("Детект: \u{1F680} 1.2%"), "Детект:  1.2%");
    }
}
