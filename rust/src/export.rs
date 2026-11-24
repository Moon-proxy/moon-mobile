//! The report written to a file for the period and filters on screen: an Excel workbook with real
//! numbers in its cells, or CSV for other programs. Built here so every platform writes the same
//! file; the app only picks where it goes.

use std::collections::HashMap;
use std::io::Write;

use chrono_tz::Tz;
use moon_core::session::CoreId;
use serde::Deserialize;
use serde_json::Value;

/// The most trades one file takes.
pub const MAX_ROWS: usize = 100_000;
/// Trades read from the report per page.
pub const PAGE: usize = 500;

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ExportFormat {
    Xlsx,
    Csv,
}

/// One closed trade, as the report's columns give it.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct Trade {
    pub core: CoreId,
    pub coin: String,
    pub market: String,
    pub strategy: String,
    pub short: bool,
    pub leverage: Option<f64>,
    pub open_ms: Option<i64>,
    pub close_ms: Option<i64>,
    pub buy: Option<f64>,
    pub sell: Option<f64>,
    pub pct: Option<f64>,
    pub usdt: Option<f64>,
    pub native: Option<f64>,
    pub currency: String,
    pub volume: Option<f64>,
    pub reason: String,
}

/// The trades of one report page (`{columns, rows}`), read by column name.
pub fn trades(page: &Value) -> Vec<Trade> {
    let Some(columns) = page.get("columns").and_then(Value::as_array) else {
        return Vec::new();
    };
    let index: HashMap<&str, usize> = columns
        .iter()
        .enumerate()
        .filter_map(|(at, name)| name.as_str().map(|name| (name, at)))
        .collect();
    let Some(rows) = page.get("rows").and_then(Value::as_array) else {
        return Vec::new();
    };
    rows.iter()
        .filter_map(Value::as_array)
        .map(|row| {
            let cell = |name: &str| index.get(name).and_then(|at| row.get(*at)).filter(|v| !v.is_null());
            let text = |name: &str| match cell(name) {
                Some(Value::String(s)) => s.clone(),
                Some(other) => other.to_string(),
                None => String::new(),
            };
            let number = |name: &str| match cell(name) {
                Some(Value::Number(n)) => n.as_f64(),
                Some(Value::String(s)) => s.trim().parse::<f64>().ok(),
                _ => None,
            }
            .filter(|v| !v.is_nan());
            let reason = text("sellreason");
            Trade {
                core: text("core").parse().unwrap_or(0),
                coin: text("coin"),
                market: text("market"),
                strategy: text("strategy_name"),
                short: text("side") == "short",
                leverage: number("lev"),
                open_ms: number("open_utc_ms").map(|v| v as i64),
                close_ms: number("close_utc_ms").map(|v| v as i64),
                buy: number("buyprice"),
                sell: number("sellprice"),
                pct: number("profit_pct"),
                usdt: number("profit_usdt"),
                native: number("profit_native"),
                currency: text("currency"),
                volume: number("volume_usdt"),
                reason: if reason.is_empty() { text("reason") } else { reason },
            }
        })
        .collect()
}

/// The zone the file's dates are written in: `name` (IANA, as the phone reports it), else the
/// machine's own, else UTC.
pub fn zone(name: &str) -> Tz {
    name.parse::<Tz>()
        .ok()
        .or_else(|| iana_time_zone::get_timezone().ok().and_then(|id| id.parse::<Tz>().ok()))
        .unwrap_or(Tz::UTC)
}

const HEADER: [&str; 16] = [
    "Закрыта", "Открыта", "Ядро", "Монета", "Рынок", "Сторона", "Плечо", "Стратегия",
    "Цена входа", "Цена выхода", "Профит %", "Профит USDT", "Профит в валюте", "Валюта",
    "Объём USDT", "Причина",
];

enum Cell {
    Empty,
    Text(String),
    Number(f64),
}

fn text(value: impl Into<String>) -> Cell {
    Cell::Text(value.into())
}

fn number(value: Option<f64>) -> Cell {
    match value {
        Some(v) if v.is_finite() => Cell::Number(v),
        _ => Cell::Empty,
    }
}

fn stamp(ms: Option<i64>, zone: Tz) -> Cell {
    match ms.and_then(chrono::DateTime::from_timestamp_millis) {
        Some(at) => text(at.with_timezone(&zone).format("%d.%m.%Y %H:%M:%S").to_string()),
        None => Cell::Empty,
    }
}

/// A coin without its quote: `SHAZUSDT` → `SHAZ`.
fn bare_coin(coin: &str) -> String {
    for quote in ["USDT", "USDC", "FDUSD", "BUSD", "USD"] {
        let cut = coin.len().saturating_sub(quote.len());
        if coin.len() > quote.len() && coin.is_char_boundary(cut) && coin[cut..].eq_ignore_ascii_case(quote) {
            return coin[..cut].to_string();
        }
    }
    coin.to_string()
}

fn cells(trade: &Trade, names: &HashMap<CoreId, String>, zone: Tz) -> Vec<Cell> {
    vec![
        stamp(trade.close_ms, zone),
        stamp(trade.open_ms, zone),
        text(names.get(&trade.core).cloned().unwrap_or_else(|| trade.core.to_string())),
        text(bare_coin(&trade.coin)),
        text(trade.market.clone()),
        text(if trade.short { "Short" } else { "Long" }),
        number(trade.leverage),
        text(trade.strategy.clone()),
        number(trade.buy),
        number(trade.sell),
        number(trade.pct),
        number(trade.usdt),
        number(trade.native),
        text(trade.currency.clone()),
        number(trade.volume),
        text(trade.reason.clone()),
    ]
}

/// A number as plain digits: whole numbers without a decimal point, never an exponent.
fn digits(value: f64) -> String {
    if value.fract() == 0.0 && value.abs() < 1e15 {
        format!("{}", value as i64)
    } else {
        format!("{value}")
    }
}

/// CSV: comma separated, dot decimals, UTF-8 with a BOM so Excel reads the Cyrillic right.
pub fn csv(trades: &[Trade], names: &HashMap<CoreId, String>, zone: Tz) -> Vec<u8> {
    fn field(cell: &Cell) -> String {
        match cell {
            Cell::Empty => String::new(),
            Cell::Number(v) => digits(*v),
            Cell::Text(s) if s.contains([',', '"', '\n', '\r']) => format!("\"{}\"", s.replace('"', "\"\"")),
            Cell::Text(s) => s.clone(),
        }
    }
    let mut out = String::from("\u{FEFF}");
    let header: Vec<String> = HEADER.iter().map(|h| field(&text(*h))).collect();
    out.push_str(&header.join(","));
    out.push_str("\r\n");
    for trade in trades {
        let line: Vec<String> = cells(trade, names, zone).iter().map(field).collect();
        out.push_str(&line.join(","));
        out.push_str("\r\n");
    }
    out.into_bytes()
}

fn xml(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for ch in text.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\t' | '\n' | '\r' => out.push(ch),
            _ if ch >= ' ' => out.push(ch),
            _ => {}
        }
    }
    out
}

fn sheet_row(sheet: &mut String, index: usize, values: &[Cell], bold: bool) {
    let style = if bold { " s=\"1\"" } else { "" };
    sheet.push_str(&format!("<row r=\"{index}\">"));
    for value in values {
        match value {
            Cell::Empty => sheet.push_str(&format!("<c{style}/>")),
            Cell::Number(v) => sheet.push_str(&format!("<c{style}><v>{}</v></c>", digits(*v))),
            Cell::Text(s) => sheet.push_str(&format!(
                "<c t=\"inlineStr\"{style}><is><t xml:space=\"preserve\">{}</t></is></c>",
                xml(s)
            )),
        }
    }
    sheet.push_str("</row>");
}

const CONTENT_TYPES: &str = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"#;
const ROOT_RELS: &str = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"#;
const WORKBOOK: &str = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Отчёт" sheetId="1" r:id="rId1"/></sheets></workbook>"#;
const WORKBOOK_RELS: &str = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"#;
const STYLES: &str = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"#;

/// A one-sheet Excel workbook: the header in bold and frozen, the trades below it, and the sums
/// of the period's profit and volume under them.
pub fn xlsx(trades: &[Trade], names: &HashMap<CoreId, String>, zone: Tz) -> Result<Vec<u8>, String> {
    let mut sheet = String::new();
    sheet.push_str(r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"#);
    sheet.push_str(r#"<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">"#);
    sheet.push_str(r#"<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>"#);
    sheet.push_str(r#"<cols><col min="1" max="2" width="20" customWidth="1"/><col min="3" max="8" width="14" customWidth="1"/><col min="9" max="16" width="13" customWidth="1"/></cols>"#);
    sheet.push_str("<sheetData>");
    let header: Vec<Cell> = HEADER.iter().map(|h| text(*h)).collect();
    sheet_row(&mut sheet, 1, &header, true);
    for (at, trade) in trades.iter().enumerate() {
        sheet_row(&mut sheet, at + 2, &cells(trade, names, zone), false);
    }
    let profit: f64 = trades.iter().filter_map(|t| t.usdt).sum();
    let volume: f64 = trades.iter().filter_map(|t| t.volume).sum();
    let mut totals: Vec<Cell> = (0..HEADER.len()).map(|_| Cell::Empty).collect();
    totals[0] = text(format!("Итого: {} сделок", trades.len()));
    totals[11] = number(Some(profit));
    totals[14] = number(Some(volume));
    sheet_row(&mut sheet, trades.len() + 3, &totals, true);
    sheet.push_str("</sheetData></worksheet>");

    let files: [(&str, &str); 6] = [
        ("[Content_Types].xml", CONTENT_TYPES),
        ("_rels/.rels", ROOT_RELS),
        ("xl/workbook.xml", WORKBOOK),
        ("xl/_rels/workbook.xml.rels", WORKBOOK_RELS),
        ("xl/styles.xml", STYLES),
        ("xl/worksheets/sheet1.xml", &sheet),
    ];
    let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    let options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for (name, body) in files {
        zip.start_file(name, options).map_err(|e| e.to_string())?;
        zip.write_all(body.as_bytes()).map_err(|e| e.to_string())?;
    }
    let cursor = zip.finish().map_err(|e| e.to_string())?;
    Ok(cursor.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn page() -> Value {
        json!({
            "columns": ["core", "coin", "side", "lev", "open_utc_ms", "close_utc_ms", "buyprice",
                        "sellprice", "profit_pct", "profit_usdt", "profit_native", "currency",
                        "volume_usdt", "sellreason", "reason", "strategy_name", "market"],
            "rows": [
                ["1", "SHAZUSDT", "long", 5, 1_700_000_000_000i64, 1_700_000_360_000i64, 0.5, 0.52,
                 4.0, 2.5, null, "USDT", 125.0, "", "TP", "Drop, 5%", "SHAZUSDT"],
                ["2", "BTCUSDT", "short", null, null, 1_700_003_600_000i64, 37000, 36900.5,
                 0.27, "-1.25", null, "USDT", 1000, "Stop \"loss\"", "", "", "BTCUSDT"]
            ],
            "has_more": false
        })
    }

    fn names() -> HashMap<CoreId, String> {
        [(1, "Bin-Spot-A".to_string())].into_iter().collect()
    }

    #[test]
    fn rows_are_read_by_column_name() {
        let rows = trades(&page());
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].strategy, "Drop, 5%");
        assert_eq!(rows[0].reason, "TP");
        assert_eq!(rows[1].usdt, Some(-1.25));
        assert!(rows[1].short);
        assert_eq!(rows[1].open_ms, None);
    }

    #[test]
    fn csv_quotes_and_numbers() {
        let text = String::from_utf8(csv(&trades(&page()), &names(), Tz::UTC)).unwrap();
        let lines: Vec<&str> = text.split("\r\n").collect();
        assert!(lines[0].starts_with("\u{FEFF}Закрыта,Открыта,Ядро"));
        assert_eq!(
            lines[1],
            "14.11.2023 22:19:20,14.11.2023 22:13:20,Bin-Spot-A,SHAZ,SHAZUSDT,Long,5,\"Drop, 5%\",0.5,0.52,4,2.5,,USDT,125,TP"
        );
        assert_eq!(
            lines[2],
            "14.11.2023 23:13:20,,2,BTC,BTCUSDT,Short,,,37000,36900.5,0.27,-1.25,,USDT,1000,\"Stop \"\"loss\"\"\""
        );
    }

    #[test]
    fn dates_in_the_phones_zone() {
        let text = String::from_utf8(csv(&trades(&page()), &names(), zone("Europe/Moscow"))).unwrap();
        assert!(text.contains("15.11.2023 01:19:20"));
        assert_eq!(zone("not a zone").name().is_empty(), false);
    }

    #[test]
    fn workbook_has_the_sheet_and_totals() {
        let bytes = xlsx(&trades(&page()), &names(), Tz::UTC).unwrap();
        let mut archive = zip::ZipArchive::new(std::io::Cursor::new(bytes)).unwrap();
        assert_eq!(archive.len(), 6);
        let mut sheet = String::new();
        std::io::Read::read_to_string(&mut archive.by_name("xl/worksheets/sheet1.xml").unwrap(), &mut sheet).unwrap();
        assert!(sheet.contains("<row r=\"5\">"));
        assert!(sheet.contains("Итого: 2 сделок"));
        assert!(sheet.contains("<c s=\"1\"><v>1.25</v></c>"));
        assert!(sheet.contains("<c s=\"1\"><v>1125</v></c>"));
        assert!(sheet.contains("Stop &quot;loss&quot;"));
    }

    #[test]
    fn plain_digits() {
        assert_eq!(digits(5.0), "5");
        assert_eq!(digits(0.0000001), "0.0000001");
        assert_eq!(digits(-1.25), "-1.25");
    }
}

