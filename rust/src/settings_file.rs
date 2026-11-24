//! The app's own section, `[moonmobile]`, in the terminal's `settings.toml`: one file carries the
//! terminal's per-core settings and every setting of the app, to restore on another phone.
//!
//! The terminal reads the file without minding the section (serde skips unknown keys) but drops it
//! whenever it saves its own settings, so the section always goes at the end, after the
//! terminal's part, and is replaced whole.

use serde::Deserialize;
use serde_json::{json, Value};

use crate::api::{ApiError, ApiResult};

const SECTION: &str = "moonmobile";

/// A stateless request: no engine needed.
#[derive(Debug, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case")]
pub enum Tool {
    /// The file with `section` as its `[moonmobile]` part, the terminal's part kept as written.
    SettingsMerge {
        #[serde(default)]
        toml: String,
        section: Value,
    },
    /// The `[moonmobile]` part of a file, `null` when it has none.
    SettingsRead { toml: String },
    /// Start a new, empty `servers.enc` in the data root, encrypted with `password` alone.
    CreateServers { data_dir: String, password: String },
    /// Every log line of the period into one text file at `path`.
    ExportLogs {
        data_dir: String,
        from_ms: i64,
        to_ms: i64,
        path: String,
    },
    /// Write log files or not; files older than ten days go either way.
    LogFiles { on: bool },
    /// A line from the app itself into the log.
    AppLog { text: String },
}

pub fn run(tool: Tool) -> ApiResult<Value> {
    match tool {
        Tool::SettingsMerge { toml, section } => Ok(json!({ "ok": true, "toml": merge(&toml, &section)? })),
        Tool::SettingsRead { toml } => Ok(json!({ "ok": true, "section": read(&toml)? })),
        Tool::CreateServers { data_dir, password } => crate::open::create_servers(&data_dir, &password),
        Tool::ExportLogs { data_dir, from_ms, to_ms, path } => crate::logs::export(&data_dir, from_ms, to_ms, &path),
        Tool::LogFiles { on } => {
            crate::logs::set_enabled(on);
            crate::logs::purge();
            Ok(json!({ "ok": true }))
        }
        Tool::AppLog { text } => {
            crate::logs::app_line(&text);
            Ok(json!({ "ok": true }))
        }
    }
}

/// The terminal's part of `text`: everything before the app's section.
fn terminal_part(text: &str) -> &str {
    let mut at = 0;
    for line in text.split_inclusive('\n') {
        let head = line.trim_start();
        if head.starts_with(&format!("[{SECTION}]")) || head.starts_with(&format!("[{SECTION}.")) {
            return &text[..at];
        }
        at += line.len();
    }
    text
}

fn merge(text: &str, section: &Value) -> ApiResult<String> {
    let mut wrapper = serde_json::Map::new();
    wrapper.insert(SECTION.to_string(), without_nulls(section.clone()));
    let ours = toml::to_string(&Value::Object(wrapper))
        .map_err(|e| ApiError::new("bad_request", format!("section: {e}")))?;
    let theirs = terminal_part(text).trim_end();
    let mut out = String::with_capacity(theirs.len() + ours.len() + 64);
    if !theirs.is_empty() {
        out.push_str(theirs);
        out.push_str("\n\n");
    }
    out.push_str("# MoonMobile: the phone's own settings. MoonTerminal ignores this part and drops it\n");
    out.push_str("# when it saves; keep this file as a copy rather than in the terminal's folder.\n");
    out.push_str(&ours);
    // The terminal's part must still read as TOML with ours after it.
    toml::from_str::<toml::Table>(&out)
        .map_err(|e| ApiError::new("bad_request", format!("the merged file does not parse: {e}")))?;
    Ok(out)
}

fn read(text: &str) -> ApiResult<Value> {
    let table: toml::Table = toml::from_str(text)
        .map_err(|e| ApiError::new("damaged", format!("settings.toml: {e}")))?;
    match table.get(SECTION) {
        Some(section) => serde_json::to_value(section)
            .map_err(|e| ApiError::new("internal", format!("section: {e}"))),
        None => Ok(Value::Null),
    }
}

/// TOML has no null: a missing key says the same.
fn without_nulls(value: Value) -> Value {
    match value {
        Value::Object(map) => Value::Object(
            map.into_iter()
                .filter(|(_, v)| !v.is_null())
                .map(|(k, v)| (k, without_nulls(v)))
                .collect(),
        ),
        Value::Array(items) => {
            Value::Array(items.into_iter().filter(|v| !v.is_null()).map(without_nulls).collect())
        }
        other => other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TERMINAL: &str = "version = 7\nlanguage = \"ru\"\n\n[[servers]]\nuid = 3\nname = \"Main\"\nactive = true\n";

    #[test]
    fn the_section_goes_after_the_terminals_part_and_reads_back() {
        let section = json!({
            "theme": "dark",
            "chart": { "sizes": [50.0, 100.0], "stop_on": true, "stop_pct": 2.5 },
            "cores": { "3": { "active": false, "color": "#ff8800" } },
            "picker": { "cores": null, "history": ["3:BTCUSDT"] },
        });
        let merged = merge(TERMINAL, &section).unwrap();
        assert!(merged.starts_with("version = 7\n"));
        let back = read(&merged).unwrap();
        assert_eq!(back["theme"], "dark");
        assert_eq!(back["chart"]["stop_pct"], 2.5);
        assert_eq!(back["cores"]["3"]["color"], "#ff8800");
        assert!(back["picker"].get("cores").is_none());
        // The terminal's own data is untouched.
        let table: toml::Table = toml::from_str(&merged).unwrap();
        assert_eq!(table["servers"][0]["name"].as_str(), Some("Main"));
    }

    #[test]
    fn a_full_section_as_the_phone_sends_it() {
        let section = json!({
            "version": 1, "theme": "system", "charts": true,
            "chart": { "sizes": [25.0, 50.0, 100.0, 200.0, 500.0, 1000.0], "size_sel": 1,
                       "takes": [0.5, 1.0, 1.5, 2.0, 3.0, 5.0], "take_sel": 0, "scale_pct": 0.0,
                       "span_ms": 120000, "cursor_mode": false, "single_tap": true,
                       "stop_on": false, "stop_pct": 2.0 },
            "notify": { "core_lost": true, "trades": true, "entries_only": true,
                        "all_strategies": true, "strategies": [], "ping": true, "cpu": true,
                        "memory": true, "api_limits": true },
            "picker": { "spot": true, "futures": true, "liquidity": false, "rank_by": "volatility",
                        "interval": "5m", "history": [] },
            "halt": {},
            "cores": {},
        });
        let merged = merge(TERMINAL, &section).unwrap();
        let back = read(&merged).unwrap();
        assert_eq!(back["chart"]["span_ms"], 120000);
        assert_eq!(back["notify"]["memory"], true);
    }

    #[test]
    fn merging_again_replaces_the_section() {
        let first = merge(TERMINAL, &json!({ "theme": "dark" })).unwrap();
        let second = merge(&first, &json!({ "theme": "light" })).unwrap();
        assert_eq!(second.matches("[moonmobile]").count(), 1);
        assert_eq!(read(&second).unwrap()["theme"], "light");
        assert!(read(TERMINAL).unwrap().is_null());
        // A phone with no terminal file still exports its own part.
        let alone = merge("", &json!({ "theme": "system" })).unwrap();
        assert_eq!(read(&alone).unwrap()["theme"], "system");
    }
}
