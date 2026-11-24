//! The app's log files. `moon-core` writes them, one file per day and source, into `logs/` of the
//! data root (`2026-11-16_app.log`, and one per core when its log is read); network addresses
//! are already blanked out in them. Here they are kept for ten days and exported for a period.

use std::fs;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use serde_json::{Value, json};

use crate::api::{ApiError, ApiResult};

/// Days a log file is kept; older ones are deleted at start and every hour after.
pub(crate) const KEEP_DAYS: u32 = 10;

/// Whether log files are written; the app sets it from its own settings.
static ENABLED: AtomicBool = AtomicBool::new(true);

pub(crate) fn enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

/// Switch writing log files on or off. Lines already written stay until they age out.
pub(crate) fn set_enabled(on: bool) {
    ENABLED.store(on, Ordering::Relaxed);
    moon_core::applog::set_file_logging(on, KEEP_DAYS);
}

/// Delete log files older than [`KEEP_DAYS`].
pub(crate) fn purge() {
    moon_core::applog::set_file_logging(enabled(), KEEP_DAYS);
    moon_core::applog::purge_old();
}

/// Days since 1970-01-01 of a `YYYY-MM-DD` date.
fn day_number(date: &str) -> Option<i64> {
    let mut parts = date.splitn(3, '-');
    let y: i64 = parts.next()?.parse().ok()?;
    let m: i64 = parts.next()?.parse().ok()?;
    let d: i64 = parts.next()?.parse().ok()?;
    if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    // Howard Hinnant's days_from_civil.
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    Some(era * 146_097 + doe - 719_468)
}

/// Milliseconds into the day of an `HH:MM:SS.mmm` stamp.
fn day_ms(hms: &str) -> Option<i64> {
    let (clock, millis) = hms.split_once('.').unwrap_or((hms, "0"));
    let mut parts = clock.splitn(3, ':');
    let h: i64 = parts.next()?.parse().ok()?;
    let m: i64 = parts.next()?.parse().ok()?;
    let s: i64 = parts.next()?.parse().ok()?;
    let ms: i64 = millis.get(..3.min(millis.len()))?.parse().unwrap_or(0);
    Some(((h * 60 + m) * 60 + s) * 1000 + ms)
}

/// Write every log line between `from_ms` and `to_ms` (true UTC) into one text file at `out`,
/// source by source, oldest first.
///
/// Args:
///     data_dir: The app's data root, the parent of `logs/`.
///
/// Returns:
///     `{"lines": N, "files": N, "bytes": N}`.
pub(crate) fn export(data_dir: &str, from_ms: i64, to_ms: i64, out: &str) -> ApiResult<Value> {
    let dir = PathBuf::from(data_dir).join("logs");
    let from_day = from_ms.div_euclid(86_400_000);
    let to_day = to_ms.div_euclid(86_400_000);
    let mut files: Vec<(String, PathBuf)> = fs::read_dir(&dir)
        .map(|entries| {
            entries
                .flatten()
                .map(|e| (e.file_name().to_string_lossy().into_owned(), e.path()))
                .filter(|(name, _)| name.ends_with(".log"))
                .filter(|(name, _)| {
                    name.get(..10)
                        .and_then(day_number)
                        .is_some_and(|day| day >= from_day && day <= to_day)
                })
                .collect()
        })
        .unwrap_or_default();
    // By source, then by date: each source reads top to bottom.
    files.sort_by(|a, b| {
        let label = |name: &str| name.get(11..).unwrap_or("").to_string();
        label(&a.0).cmp(&label(&b.0)).then(a.0.cmp(&b.0))
    });
    let file = fs::File::create(Path::new(out))
        .map_err(|e| ApiError::new("io", format!("{out}: {e}")))?;
    let mut writer = BufWriter::new(file);
    let write_err = |e: std::io::Error| ApiError::new("io", e.to_string());
    writeln!(
        writer,
        "MoonMobile — журнал за период (время UTC). Адреса серверов в журнале скрыты."
    )
    .map_err(write_err)?;
    let mut lines = 0usize;
    let mut current_label = String::new();
    for (name, path) in &files {
        let Some(day) = name.get(..10).and_then(day_number) else {
            continue;
        };
        let date = &name[..10];
        let label = name.get(11..name.len() - 4).unwrap_or("").to_string();
        let Ok(text) = fs::read_to_string(path) else {
            continue;
        };
        let mut header_written = false;
        for line in text.lines() {
            let stamp = line.split('\t').next().unwrap_or("");
            let at = day_ms(stamp).map(|ms| day * 86_400_000 + ms);
            if at.is_some_and(|at| at < from_ms || at > to_ms) {
                continue;
            }
            if !header_written {
                if label != current_label {
                    writeln!(writer, "\n===== {label} =====").map_err(write_err)?;
                    current_label = label.clone();
                }
                header_written = true;
            }
            writeln!(writer, "{date} {line}").map_err(write_err)?;
            lines += 1;
        }
    }
    writer.flush().map_err(write_err)?;
    let bytes = fs::metadata(out).map(|m| m.len()).unwrap_or(0);
    Ok(json!({ "ok": true, "lines": lines, "files": files.len(), "bytes": bytes }))
}

/// A line from the app itself (Kotlin side) into the same log.
pub(crate) fn app_line(text: &str) {
    log::info!(target: "app", "{}", text.chars().take(2000).collect::<String>());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dates_and_clock_stamps_read_as_utc_milliseconds() {
        assert_eq!(day_number("1970-01-01"), Some(0));
        assert_eq!(day_number("2026-11-16"), Some(20_773));
        assert_eq!(day_ms("01:02:03.456"), Some(3_723_456));
        assert_eq!(day_ms("00:00:00"), Some(0));
        assert!(day_number("nonsense").is_none());
    }

    #[test]
    fn an_export_keeps_only_the_period() {
        let root = std::env::temp_dir().join(format!("mm-logs-{}", std::process::id()));
        let logs = root.join("logs");
        fs::create_dir_all(&logs).unwrap();
        fs::write(
            logs.join("2026-11-16_app.log"),
            "00:00:01.000\tINFO\tapp\tearly\n12:00:00.000\tINFO\tapp\tnoon\n",
        )
        .unwrap();
        fs::write(logs.join("2026-11-01_app.log"), "12:00:00.000\tINFO\tapp\told\n").unwrap();
        let noon = 20_773 * 86_400_000 + 12 * 3_600_000;
        let out = root.join("out.txt");
        let reply = export(
            root.to_str().unwrap(),
            noon - 3_600_000,
            noon + 3_600_000,
            out.to_str().unwrap(),
        )
        .unwrap();
        assert_eq!(reply["lines"], 1);
        let text = fs::read_to_string(&out).unwrap();
        assert!(text.contains("noon") && !text.contains("early") && !text.contains("old"));
        let _ = fs::remove_dir_all(&root);
    }
}
