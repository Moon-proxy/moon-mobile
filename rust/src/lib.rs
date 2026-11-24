//! MoonMobile bridge: MoonTerminal's `moon-core` behind a handful of JSON functions.
//!
//! One engine per process. [`open`] loads a configuration and starts it, [`snapshot`] reads the
//! state it publishes, [`call`] sends it a command, [`close`] stops it. Every function takes and
//! returns JSON text (shapes in [`api`]), so the same surface serves the Android app through JNI,
//! the iOS app through C (`ffi`) and, later, a server gateway through a socket.

mod alerts;
mod station;
pub mod api;
mod cards;
mod chart;
mod engine;
mod export;
#[cfg(feature = "ffi")]
mod ffi;
#[cfg(feature = "android")]
mod jni;
mod open;
mod logs;
mod rank;
mod reports;
mod settings_file;
mod snapshot;
mod strategy;

#[cfg(all(target_os = "android", not(feature = "android")))]
compile_error!("build the Android library with `--features android`: without it the app finds no JNI entry points");
#[cfg(all(target_os = "ios", not(feature = "ffi")))]
compile_error!("build the iOS library with `--features ffi`: without it the app finds no C entry points");

use std::sync::RwLock;

use serde_json::json;

use api::{ok, render, ApiError, Call, ChartRequest, OpenRequest};
use engine::Engine;

/// The process's one engine. Readers (`snapshot`, `call`) share it; `open` and `close` replace it.
static ENGINE: RwLock<Option<Engine>> = RwLock::new(None);

/// The bridge's own version.
pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

/// Whether an engine is running.
pub fn is_open() -> bool {
    ENGINE.read().unwrap_or_else(|e| e.into_inner()).is_some()
}

/// Load the configuration `request` names (an [`OpenRequest`]) and start the engine on it.
///
/// Blocks while the key of `servers.enc` is derived from the password — a visible fraction of a
/// second — so it must not run on a UI thread.
///
/// Returns:
///     `{"ok":true,"cores":N,"market_data":"none"|"scoped"|"full"}`, or an error reply: `bad_request`,
///     `already_open`, and the codes of `open::load`.
pub fn open(request: &str) -> String {
    render((|| {
        let request: OpenRequest = serde_json::from_str(request)
            .map_err(|e| ApiError::new("bad_request", e.to_string()))?;
        let mut slot = ENGINE.write().unwrap_or_else(|e| e.into_inner());
        if slot.is_some() {
            return Err(ApiError::new("already_open", "an engine is already running"));
        }
        let config = open::load(&request)?;
        let cores = config.servers.len();
        let mine = request
            .mine
            .iter()
            .filter_map(|text| {
                let (core, uid) = text.split_once(':')?;
                Some((core.trim().parse().ok()?, uid.trim().parse().ok()?))
            })
            .collect();
        *slot = Some(Engine::start(config, request.market_data, mine)?);
        Ok(ok(json!({
            "cores": cores,
            "market_data": request.market_data.as_str(),
        })))
    })())
}

/// The latest state. A negative `since` always returns it; with `since` equal to the current
/// revision the reply only says `"changed":false`.
pub fn snapshot(since: i64) -> String {
    let slot = ENGINE.read().unwrap_or_else(|e| e.into_inner());
    match slot.as_ref() {
        Some(engine) => engine.snapshot(u64::try_from(since).ok()),
        None => closed(),
    }
}

/// Run one [`Call`] on the engine and wait for its answer.
/// A request that needs no engine: the app's section of `settings.toml`.
pub fn tool(request: &str) -> String {
    let parsed: Result<settings_file::Tool, _> = serde_json::from_str(request);
    match parsed {
        Ok(tool) => render(settings_file::run(tool)),
        Err(e) => ApiError::new("bad_request", format!("{e}")).to_json().to_string(),
    }
}

pub fn call(request: &str) -> String {
    let call: Call = match serde_json::from_str(request) {
        Ok(call) => call,
        Err(e) => return render(Err(ApiError::new("bad_request", e.to_string()))),
    };
    let slot = ENGINE.read().unwrap_or_else(|e| e.into_inner());
    let Some(engine) = slot.as_ref() else {
        return closed();
    };
    match call {
        Call::Snapshot { since } => engine.snapshot(since),
        Call::Rank { cores, by, interval, limit } => {
            render(Ok(engine.rank(&cores, by, interval, limit)))
        }
        Call::Keep { charts } => render(Ok(engine.keep(&charts))),
        Call::Figures { items, interval } => render(Ok(engine.figures(&items, interval))),
        Call::Report { from_ms, to_ms, cores, offset, limit, coin, strategy, emulator } => {
            let narrow = crate::reports::Narrow { coin, strategy, emulator };
            render(engine.report(from_ms, to_ms, &cores, offset, limit, &narrow))
        }
        Call::MarkHistory { core, market, points } => render(Ok(engine.mark_history(core, &market, &points))),
        Call::AlertsConfig { settings, server_names } => render(Ok(engine.alerts_config(settings, server_names))),
        Call::Alerts => render(Ok(engine.alerts())),
        Call::StationKey { key } => render(station::key(key.as_deref())),
        Call::StationProbe { server } => render(station::probe(&server)),
        Call::StationCheck { server, login, fingerprint } => render(station::check(&server, login, fingerprint)),
        Call::StationInstall { server, login, fingerprint, cores } => {
            render(station::install(&server, login, fingerprint, engine.station_cores(&cores)))
        }
        Call::StationStatus { server } => render(station::status(&server)),
        Call::StationCores { server, cores } => render(station::push_cores(&server, engine.station_cores(&cores))),
        Call::StationRemoveCores { server, uids } => render(station::remove_cores(&server, uids)),
        Call::StationUpdate { server } => render(station::update(&server)),
        Call::StationBot { server, token, off } => render(station::bot(&server, token, off)),
        Call::StationTape { server, margin_s, long_position_min } => {
            render(station::tape(&server, margin_s, long_position_min))
        }
        Call::StationAutoUpdate { server, on } => render(station::auto_update(&server, on)),
        Call::StationRemove { server } => render(station::remove(&server)),
        Call::StationForget { server } => render(station::forget(&server)),
        Call::StationJob => render(Ok(station::job())),
        Call::DayStats { from_ms, to_ms } => render(engine.day_stats(from_ms, to_ms)),
        Call::ReportFile { from_ms, to_ms, cores, coin, strategy, emulator, format, path, tz } => {
            let narrow = crate::reports::Narrow { coin, strategy, emulator };
            render(engine.report_file(from_ms, to_ms, &cores, &narrow, format, &path, &tz))
        }
        other => render(engine.call(other)),
    }
}

/// Draw one chart frame (a [`ChartRequest`]) into `pixels`: premultiplied RGBA, `width * height
/// * 4` bytes. Runs on the caller's thread and never waits on the engine's owner, so a slow
/// frame holds up nothing but itself.
///
/// Returns:
///     `{"ok":true,"plot":{x,y,w,h},"band_top":..,"book_x":..,"price_lo":..,"price_hi":..,
///     "from_ms":..,"to_ms":..,"last":..,"ticks":N,"live":bool}`, or an error reply:
///     `bad_request`, `closed`, `not_found`, `no_market_data`.
pub fn chart(request: &str, pixels: &mut [u8]) -> String {
    let request: ChartRequest = match serde_json::from_str(request) {
        Ok(request) => request,
        Err(e) => return render(Err(ApiError::new("bad_request", e.to_string()))),
    };
    let slot = ENGINE.read().unwrap_or_else(|e| e.into_inner());
    match slot.as_ref() {
        Some(engine) => render(engine.chart(&request, pixels)),
        None => closed(),
    }
}

/// Stop the engine and every core connection. A no-op when none is running.
pub fn close() {
    let engine = ENGINE.write().unwrap_or_else(|e| e.into_inner()).take();
    // Dropped outside the lock: stopping joins the owner thread, and a reader must not wait on it.
    drop(engine);
}

fn closed() -> String {
    render(Err(ApiError::new("closed", "no engine is running")))
}
