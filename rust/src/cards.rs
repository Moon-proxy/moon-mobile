//! Detect cards: the short-lived buttons the terminal's detect feed shows beside the chart.
//!
//! A detect becomes a card under the terminal's own rule — its strategy asks for a sound alert,
//! or it is a drawn-alert firing — and lives for the strategy's `KeepAlert` seconds. One card per
//! core and market: a market that fires again restarts its card rather than adding a second.

use std::collections::HashMap;

use moon_core::config::{AppConfig, BadgesConfig};
use moon_core::feed::DetectRow;
use moon_core::session::{CoreId, SessionManager};
use serde::Serialize;

/// A card's identity: its core and the detect's cursor on that core.
pub type CardKey = (CoreId, u64);

/// What a card shows besides the detect itself, read from market history off the owner thread.
#[derive(Clone, Default)]
pub struct Spark {
    /// The market's last day as closes, oldest first; empty when no history was at hand.
    pub line: Vec<f32>,
    /// Signed price change, percent.
    pub delta_1h: Option<f64>,
    pub delta_24h: Option<f64>,
}

#[derive(Serialize)]
pub struct CardDto {
    pub core: CoreId,
    pub core_name: String,
    /// The core's colour from its settings, `#rrggbb`: the card's rail and its core chip.
    pub core_color: String,
    pub seq: u64,
    pub market: String,
    /// The coin without quote currency or contract tail, as a coin column prints it.
    pub coin: String,
    /// When the detect arrived and how long its card lives, so the client counts down itself.
    pub born_ms: i64,
    pub ttl_ms: i64,
    /// The detect kind's badge: its code (`PMP`, `DRP`, ...) and colour.
    pub badge: String,
    pub badge_color: String,
    pub short: bool,
    pub alert: bool,
    pub delta_1h: Option<f64>,
    pub delta_24h: Option<f64>,
    pub line: Vec<f32>,
    /// The strategy that fired and the detect's own line, as Moonbot prints it in its log.
    pub strategy: String,
    pub msg: String,
    /// How the price moved since the detect, percent: from the price when the card appeared to
    /// the price now.
    pub since_pct: Option<f64>,
}

/// The longest detect line a card carries.
const MSG_KEEP: usize = 140;

/// How long a drawn-alert firing with no `KeepAlert` of its own stays: the terminal's default
/// Alerts duration.
const ALERT_SECS: i64 = 60;
/// How long a card of the demo core stays; its invented detects name no strategy.
const DEMO_SECS: i64 = 60;
/// Strategy-kind ordinals of PumpDetection and Drops, as the badge table numbers them.
const KIND_PUMP: u8 = 5;
const KIND_DROPS: u8 = 2;
/// Points kept of a thumbnail line: more than a card's width in pixels would be wasted.
pub const SPARK_POINTS: usize = 48;

/// The card's lifetime in milliseconds: the terminal's `detect_card_ttl_ms`.
fn ttl_ms(row: &DetectRow, synthetic: bool) -> i64 {
    let seconds = if row.keep_alert_secs > 0 {
        i64::from(row.keep_alert_secs)
    } else if synthetic {
        DEMO_SECS
    } else if row.is_alert {
        ALERT_SECS
    } else {
        1
    };
    seconds * 1000
}

/// The cards alive at `now_ms`, newest first.
///
/// Args:
///     sparks: Thumbnails and deltas already read, by card.
///     prices: The price each card's market had when the card first appeared, kept between calls;
///         filled here for new cards and pruned to the live ones.
pub fn build(
    session: &SessionManager,
    config: &AppConfig,
    badges: &BadgesConfig,
    sparks: &HashMap<CardKey, Spark>,
    prices: &mut HashMap<CardKey, f64>,
    now_ms: i64,
) -> Vec<CardDto> {
    let source = session.market_source();
    let mut cards = Vec::new();
    for server in &config.servers {
        let Some(data) = session.store().core(server.id) else {
            continue;
        };
        let mut newest: HashMap<&str, &DetectRow> = HashMap::new();
        for row in data.detects.iter() {
            if !(row.sound_alert || row.is_alert || server.synthetic) {
                continue;
            }
            let born = row.time_ms as i64;
            if now_ms >= born + ttl_ms(row, server.synthetic) {
                continue;
            }
            match newest.get(row.market.as_str()) {
                Some(held) if held.time_ms >= row.time_ms => {}
                _ => {
                    newest.insert(&row.market, row);
                }
            }
        }
        for row in newest.into_values() {
            // The demo core's invented detects name no strategy kind; give its cards the two
            // commonest badges so the demo shows what a real feed looks like.
            let kind = match (server.synthetic, row.kind) {
                (true, 0) => [KIND_PUMP, KIND_DROPS][(row.seq % 2) as usize],
                (_, kind) => kind,
            };
            let spark = sparks.get(&(server.id, row.seq));
            let now_price = source
                .latest_price(server.id, &row.market)
                .ok()
                .map(f64::from)
                .filter(|p| p.is_finite() && *p > 0.0);
            let at_price = match (prices.get(&(server.id, row.seq)), now_price) {
                (Some(at), _) => Some(*at),
                (None, Some(now)) => {
                    prices.insert((server.id, row.seq), now);
                    Some(now)
                }
                (None, None) => None,
            };
            let since_pct = match (at_price, now_price) {
                (Some(at), Some(now)) => Some((now - at) / at * 100.0),
                _ => None,
            };
            let label = source.market_label(server.id, &row.market);
            let coin = match label.display_coin() {
                "" => row.market.clone(),
                coin => coin.to_string(),
            };
            cards.push(CardDto {
                core: server.id,
                core_name: server.name.clone(),
                core_color: hex(server.color),
                seq: row.seq,
                market: row.market.clone(),
                coin,
                born_ms: row.time_ms as i64,
                ttl_ms: ttl_ms(row, server.synthetic),
                badge: badges.code(kind, row.is_short).to_string(),
                badge_color: hex(badges.color(kind, false)),
                short: row.is_short,
                alert: row.is_alert,
                delta_1h: spark.and_then(|s| s.delta_1h),
                delta_24h: spark.and_then(|s| s.delta_24h),
                line: spark.map(|s| s.line.clone()).unwrap_or_default(),
                strategy: row.strat_name.clone(),
                msg: row.msg.chars().take(MSG_KEEP).collect::<String>().trim().to_string(),
                since_pct,
            });
        }
    }
    prices.retain(|key, _| cards.iter().any(|c| (c.core, c.seq) == *key));
    cards.sort_by(|a, b| {
        b.born_ms
            .cmp(&a.born_ms)
            .then(a.core.cmp(&b.core))
            .then_with(|| a.market.cmp(&b.market))
    });
    cards
}

/// `line` reduced to at most `points` values, keeping its first and last.
pub fn thin(line: &[f32], points: usize) -> Vec<f32> {
    if line.len() <= points || points < 2 {
        return line.to_vec();
    }
    (0..points)
        .map(|i| line[i * (line.len() - 1) / (points - 1)])
        .collect()
}

fn hex(color: [u8; 3]) -> String {
    format!("#{:02x}{:02x}{:02x}", color[0], color[1], color[2])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_thinned_line_keeps_its_ends() {
        let line: Vec<f32> = (0..288).map(|i| i as f32).collect();
        let thinned = thin(&line, SPARK_POINTS);
        assert_eq!(thinned.len(), SPARK_POINTS);
        assert_eq!((thinned[0], thinned[SPARK_POINTS - 1]), (0.0, 287.0));
        assert_eq!(thin(&[1.0, 2.0], SPARK_POINTS), vec![1.0, 2.0]);
    }

    #[test]
    fn colours_print_as_hex() {
        assert_eq!(hex([255, 179, 71]), "#ffb347");
    }
}
