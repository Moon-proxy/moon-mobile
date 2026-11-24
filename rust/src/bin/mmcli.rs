//! Desktop check of the bridge: opens a configuration exactly as the phone does and prints
//! what the app would draw.
//!
//!   mmcli --data-dir DIR --demo
//!   mmcli --data-dir DIR --password PASS          (DIR holds servers.enc)
//!   mmcli --data-dir DIR --key NAME=KEY [--key NAME=KEY ...]
//!
//! Options: `--scoped` keeps market data for open charts only, `--full` exchange-wide;
//! `--seconds N` runs for N seconds (default 20); `--call JSON` sends one command after
//! connecting; `--json` prints raw snapshots instead of the table; `--chart CORE:MARKET` draws
//! that chart every half second and, with `--png FILE`, leaves the last frame in FILE
//! (`--size WxH`, `--span SECONDS`, `--scale F` shape it; `--scale-pct N` fixes the price
//! range at N percent of the price instead of fitting it).

use std::time::{Duration, Instant};

use serde_json::{json, Value};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut data_dir = None;
    let mut source: Option<Value> = None;
    let mut keys: Vec<Value> = Vec::new();
    let mut mode = "none";
    let mut chart: Option<(u64, String)> = None;
    let mut png: Option<String> = None;
    let mut size = (1280u32, 590u32);
    let mut span = 300i64;
    let mut scale = 2.0f32;
    let mut scale_pct: Option<f64> = None;
    let mut cursor: Option<[f32; 2]> = None;
    let mut marks = false;
    let mut light = false;
    let mut inset: f32 = 0.0;
    let mut seconds = 20u64;
    let mut calls: Vec<String> = Vec::new();
    let mut raw = false;
    let mut i = 0;
    while i < args.len() {
        let value = |i: &mut usize| -> String {
            *i += 1;
            args.get(*i).cloned().unwrap_or_else(|| usage("missing value"))
        };
        match args[i].as_str() {
            "--data-dir" => data_dir = Some(value(&mut i)),
            "--demo" => source = Some(json!({ "source": "demo" })),
            "--password" => {
                source = Some(json!({ "source": "servers_enc", "password": value(&mut i) }))
            }
            "--key" => {
                let pair = value(&mut i);
                let (name, key) = pair
                    .split_once('=')
                    .unwrap_or_else(|| usage("--key wants NAME=KEY"));
                keys.push(json!({ "uid": keys.len() as u64 + 1, "name": name, "key": key }));
            }
            "--full" => mode = "full",
            "--scoped" => mode = "scoped",
            "--chart" => {
                let pair = value(&mut i);
                let (core, market) = pair
                    .split_once(':')
                    .unwrap_or_else(|| usage("--chart wants CORE:MARKET"));
                let core = core.parse().unwrap_or_else(|_| usage("--chart wants CORE:MARKET"));
                chart = Some((core, market.to_string()));
            }
            "--png" => png = Some(value(&mut i)),
            "--size" => {
                let text = value(&mut i);
                let parsed = text
                    .split_once('x')
                    .and_then(|(w, h)| Some((w.parse().ok()?, h.parse().ok()?)));
                size = parsed.unwrap_or_else(|| usage("--size wants WxH"));
            }
            "--span" => span = value(&mut i).parse().unwrap_or_else(|_| usage("--span SECONDS")),
            "--scale" => scale = value(&mut i).parse().unwrap_or_else(|_| usage("--scale F")),
            "--cursor" => {
                let text = value(&mut i);
                let parsed = text
                    .split_once(',')
                    .and_then(|(x, y)| Some([x.parse().ok()?, y.parse().ok()?]));
                cursor = Some(parsed.unwrap_or_else(|| usage("--cursor wants X,Y")));
            }
            "--marks" => marks = true,
            "--light" => light = true,
            "--inset" => inset = value(&mut i).parse().unwrap_or_else(|_| usage("--inset PX")),
            "--scale-pct" => {
                scale_pct = Some(value(&mut i).parse().unwrap_or_else(|_| usage("--scale-pct N")))
            }
            "--seconds" => seconds = value(&mut i).parse().unwrap_or_else(|_| usage("--seconds N")),
            "--call" => calls.push(value(&mut i)),
            "--json" => raw = true,
            other => usage(&format!("unknown argument {other}")),
        }
        i += 1;
    }
    if !keys.is_empty() {
        source = Some(json!({ "source": "keys", "cores": keys }));
    }
    let data_dir = data_dir.unwrap_or_else(|| usage("--data-dir is required"));
    let mut request = source.unwrap_or_else(|| usage("one of --demo, --password, --key is required"));
    request["data_dir"] = json!(std::path::absolute(&data_dir)
        .unwrap_or_else(|_| data_dir.clone().into())
        .to_string_lossy());
    request["market_data"] = json!(mode);

    let opened = moonmobile::open(&request.to_string());
    println!("open: {opened}");
    if !opened.contains(r#""ok":true"#) {
        std::process::exit(1);
    }

    let started = Instant::now();
    let mut rev: i64 = -1;
    let mut calls_sent = false;
    let mut pixels = vec![0u8; size.0 as usize * size.1 as usize * 4];
    let mut last_frame = String::new();
    while started.elapsed() < Duration::from_secs(seconds) {
        std::thread::sleep(Duration::from_millis(500));
        if let Some((core, market)) = &chart {
            let frame = json!({
                "core": core, "market": market, "width": size.0, "height": size.1,
                "scale": scale, "span_ms": span * 1000, "scale_pct": scale_pct,
                "cursor": cursor, "book_marks": marks, "light": light, "left_inset": inset,
            });
            let drawing = Instant::now();
            let reply = moonmobile::chart(&frame.to_string(), &mut pixels);
            let took = drawing.elapsed();
            // The frame's numbers move with every trade; print it when its shape changes.
            let shape: Value = serde_json::from_str(&reply).unwrap_or(Value::Null);
            let shape = format!(
                "ok={} error={} ticks={} book={} last={}",
                shape["ok"], shape["error"], shape["ticks"], shape["book"], shape["last"]
            );
            if shape != last_frame {
                println!("chart: {shape} drawn in {:.1} ms", took.as_secs_f64() * 1000.0);
                last_frame = shape;
            }
        }
        let text = moonmobile::snapshot(rev);
        let snap: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
        if snap["changed"] == json!(false) {
            continue;
        }
        rev = snap["rev"].as_i64().unwrap_or(rev);
        if raw {
            println!("{text}");
        } else {
            print_table(&snap, started.elapsed());
        }
        // Commands go out once a core is connected, as a press in the app would.
        let ready = snap["cores"]
            .as_array()
            .is_some_and(|cores| cores.iter().any(|c| c["status"] == "ready"));
        if ready && !calls_sent {
            calls_sent = true;
            for call in &calls {
                println!("call {call}\n  -> {}", moonmobile::call(call));
            }
        }
    }
    if let (Some(path), Some(_)) = (&png, &chart) {
        match tiny_skia::PixmapRef::from_bytes(&pixels, size.0, size.1).map(|p| p.save_png(path)) {
            Some(Ok(())) => println!("chart saved to {path}"),
            other => println!("chart not saved: {other:?}"),
        }
    }
    moonmobile::close();
    println!("closed");
}

fn print_table(snap: &Value, at: Duration) {
    println!(
        "--- {:>5.1}s rev {} | orders {} | detects {} | strategies {}",
        at.as_secs_f32(),
        snap["rev"],
        snap["orders"].as_array().map_or(0, Vec::len),
        snap["detects"].as_array().map_or(0, Vec::len),
        snap["strategies"].as_array().map_or(0, Vec::len),
    );
    for core in snap["cores"].as_array().into_iter().flatten() {
        println!(
            "  core {} {:<14} {:<12} {:<18} {} trading={} auto={} orders={} pos={} strat={}/{} {}",
            core["id"],
            core["name"].as_str().unwrap_or(""),
            core["status"].as_str().unwrap_or(""),
            core["exchange"].as_str().unwrap_or(""),
            core["base"].as_str().unwrap_or(""),
            core["run"]["trading"],
            core["run"]["auto_detect"],
            core["orders"],
            core["positions"],
            core["strategies_checked"],
            core["strategies_total"],
            core["status_text"].as_str().unwrap_or(""),
        );
    }
    for order in snap["orders"].as_array().into_iter().flatten().take(8) {
        println!(
            "    order {} {:<12} {} size={} buy={} sell={} price={} pnl={} [{}]",
            order["uid"].as_str().unwrap_or(""),
            order["market"].as_str().unwrap_or(""),
            if order["short"] == json!(true) { "SHORT" } else { "LONG " },
            order["size"],
            order["buy_price"],
            order["sell_price"],
            order["price"],
            order["pnl_pct"],
            order["status"].as_str().unwrap_or(""),
        );
    }
    for detect in snap["detects"].as_array().into_iter().flatten().take(3) {
        println!(
            "    detect {:<12} {} | {}",
            detect["market"].as_str().unwrap_or(""),
            detect["strategy"].as_str().unwrap_or(""),
            detect["msg"].as_str().unwrap_or(""),
        );
    }
}

fn usage(problem: &str) -> ! {
    eprintln!("mmcli: {problem}");
    eprintln!(
        "usage: mmcli --data-dir DIR (--demo | --password PASS | --key NAME=KEY ...) \
         [--full] [--seconds N] [--call JSON] [--json]"
    );
    std::process::exit(2);
}
