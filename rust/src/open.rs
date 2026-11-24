//! Turn an [`OpenRequest`] into the terminal's `AppConfig`, initialising the process-wide parts
//! of `moon-core` on the way: its data root, its logger, the key of `servers.enc`.

use std::path::PathBuf;
use std::sync::OnceLock;

use moon_core::config::crypto::{self, AccessError};
use moon_core::config::{paths, AppConfig, Secret, ServerConfig};

use crate::api::{ApiError, ApiResult, KeyCore, MarketData, OpenRequest, Source};

/// The data root this process was first opened on. `moon-core` accepts one per process.
static DATA_DIR: OnceLock<PathBuf> = OnceLock::new();

/// Load the configuration `request` names, ready to start sessions on.
pub fn load(request: &OpenRequest) -> ApiResult<AppConfig> {
    init_process(&request.data_dir)?;
    let mut config = match &request.source {
        Source::ServersEnc { password } => from_servers_enc(password)?,
        Source::Keys { cores } => from_keys(cores)?,
        Source::Demo => demo()?,
    };
    if let Some(percent) = request.chart_memory_percent {
        config.chart_memory_percent = percent.clamp(75, 800);
    }
    for server in &mut config.servers {
        tune(server, request.market_data, request.core_log);
        if let Some(change) = request.overrides.iter().find(|o| o.core == server.id) {
            if let Some(active) = change.active {
                server.active = active;
            }
            if let Some(color) = change.color.as_deref().and_then(parse_color) {
                server.color = color;
            }
        }
    }
    // Order-line maths (stops, take-profit levels) follow the user's own style settings.
    moon_core::session::order_lines::apply_orders_config(&config.orders);
    Ok(config)
}

/// What the phone asks of a core's feed, whatever the terminal's settings say.
pub(crate) fn tune(server: &mut ServerConfig, market_data: MarketData, core_log: bool) {
    // The report replica: the cores' closed trades for the Report, the chart's trade marks and
    // the last deal. The writer behind it is started once per process (`reports::sink`).
    server.feed.reports = true;
    // The arbitrage tables are not shown by any screen yet.
    server.feed.arb = false;
    if market_data == MarketData::None {
        // Chart alerts belong to charts.
        server.feed.alerts = false;
    }
    // The core's log is read only for the exchange's API-limit errors.
    server.feed.log = core_log;
    server.feed.log_delivery = core_log;
}

/// `#rrggbb` as a colour.
fn parse_color(text: &str) -> Option<[u8; 3]> {
    let hex = text.trim().trim_start_matches('#');
    if hex.len() != 6 {
        return None;
    }
    let value = u32::from_str_radix(hex, 16).ok()?;
    Some([(value >> 16) as u8, (value >> 8) as u8, value as u8])
}

/// Point `moon-core` at the data root and install its logger. Once per process; a later open
/// must name the same directory.
fn init_process(data_dir: &str) -> ApiResult<()> {
    let dir = PathBuf::from(data_dir);
    if data_dir.trim().is_empty() || !dir.is_absolute() {
        return Err(ApiError::new("data_dir", "data_dir must be an absolute path"));
    }
    if let Some(first) = DATA_DIR.get() {
        return if *first == dir {
            Ok(())
        } else {
            Err(ApiError::new(
                "data_dir",
                "this process is already bound to another data_dir; restart the app to change it",
            ))
        };
    }
    std::fs::create_dir_all(&dir)
        .map_err(|e| ApiError::new("data_dir", format!("{}: {e}", dir.display())))?;
    if !paths::set_data_dir_override(dir.clone()) {
        return Err(ApiError::new("data_dir", "the data root was already set"));
    }
    let _ = DATA_DIR.set(dir);

    let (diag, diag_error) = moon_core::diagnostics::init();
    let filter = format!("{},moonmobile=info", moon_core::diagnostics::filter_string(&diag));
    // Another logger may already be installed (a host with its own); the bridge then logs
    // through that one and the terminal's log files are simply not written.
    if moon_core::applog::install(&filter).is_ok() {
        moon_core::applog::set_file_logging(crate::logs::enabled(), crate::logs::KEEP_DAYS);
        moon_core::applog::purge_old();
    }
    if let Some(error) = diag_error {
        log::warn!("{error}");
    }
    log::info!("moonmobile {} on moon-core v0.67.3", env!("CARGO_PKG_VERSION"));
    Ok(())
}

/// The shortest password a new `servers.enc` takes.
const MIN_PASSWORD_CHARS: usize = 6;

/// Write a new, empty `servers.enc` into the data root, encrypted with `password` alone: the
/// phone's own list of cores, started from nothing. Cores are then added by their keys.
///
/// Refused when a `servers.enc` is already there, so an existing list is never replaced.
pub(crate) fn create_servers(data_dir: &str, password: &str) -> ApiResult<serde_json::Value> {
    init_process(data_dir)?;
    if password.chars().count() < MIN_PASSWORD_CHARS {
        return Err(ApiError::new(
            "short_password",
            format!("the password needs at least {MIN_PASSWORD_CHARS} characters"),
        ));
    }
    if paths::servers_path().exists() {
        return Err(ApiError::new("exists", "servers.enc is already there"));
    }
    // Runs Argon2id: the caller is off its UI thread.
    crypto::create_with_password(password).map_err(|e| ApiError::new("config", e.to_string()))?;
    let mut config = AppConfig::load(None, false).map_err(|e| ApiError::new("config", format!("{e:#}")))?;
    config.save().map_err(|e| ApiError::new("config", format!("{e:#}")))?;
    log::info!("a new servers.enc was created");
    Ok(serde_json::json!({ "ok": true }))
}

/// Open the terminal's own `servers.enc` with its encryption password.
fn from_servers_enc(password: &str) -> ApiResult<AppConfig> {
    let path = paths::servers_path();
    let bytes = match std::fs::read(&path) {
        Ok(bytes) => bytes,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            return Err(ApiError::new(
                "no_servers_file",
                "servers.enc is not in the data directory; import it first",
            ));
        }
        Err(e) => return Err(ApiError::new("data_dir", format!("servers.enc: {e}"))),
    };
    // Runs Argon2id: a visible fraction of a second. The caller is off its UI thread.
    crypto::unlock_with_password(&bytes, password).map_err(|e| match e {
        AccessError::WrongPassword => ApiError::new("wrong_password", "wrong encryption password"),
        // No password slot in the file: it opens only on the machine that wrote it.
        AccessError::NoKey | AccessError::NeedsPassword => ApiError::new(
            "no_password_slot",
            "this servers.enc has no encryption password; set one on the desktop \
             (Settings → Security) and copy the file again",
        ),
        AccessError::Damaged(detail) => ApiError::new("damaged", detail),
        AccessError::Backend(detail) => ApiError::new("internal", detail),
    })?;
    // The vault now holds the file key, so `load` reads the same file without asking again.
    AppConfig::load(None, false).map_err(|e| ApiError::new("config", format!("{e:#}")))
}

/// Build a configuration from cores named by their keys, the way the station does.
fn from_keys(cores: &[KeyCore]) -> ApiResult<AppConfig> {
    if cores.is_empty() {
        return Err(ApiError::new("bad_request", "no cores given"));
    }
    let mut seen = std::collections::HashSet::new();
    let mut servers = Vec::with_capacity(cores.len());
    for core in cores {
        if core.uid == 0 || !seen.insert(core.uid) {
            return Err(ApiError::new(
                "bad_request",
                format!("core {:?}: uid must be non-zero and unique", core.name),
            ));
        }
        if core.key.trim().is_empty() {
            return Err(ApiError::new("bad_request", format!("core {:?}: empty key", core.name)));
        }
        let mut server = blank_server()?;
        server.uid = core.uid;
        server.name = core.name.clone();
        server.key = Secret::new(core.key.trim());
        server.endpoint_override = core.endpoint_override.trim().to_string();
        servers.push(server);
    }
    Ok(AppConfig::headless(servers))
}

/// One synthetic core: no network, a deterministic stream of everything the screens show.
fn demo() -> ApiResult<AppConfig> {
    let mut server = blank_server()?;
    server.uid = 1;
    server.name = "Demo".to_string();
    server.key = Secret::new("synthetic");
    server.synthetic = true;
    Ok(AppConfig::headless(vec![server]))
}

/// A server with the terminal's own defaults for every field. `id` alone has no default; the
/// headless config sets it from the uid.
pub(crate) fn blank_server() -> ApiResult<ServerConfig> {
    serde_json::from_value(serde_json::json!({ "id": 0 }))
        .map_err(|e| ApiError::new("internal", format!("default server: {e}")))
}

#[cfg(test)]
mod create_tests {
    use super::{create_servers, from_servers_enc};

    #[test]
    fn a_new_file_opens_by_its_password_alone() {
        let dir = std::env::temp_dir().join(format!("mm-create-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let data_dir = dir.to_string_lossy().to_string();
        assert!(create_servers(&data_dir, "abc").is_err(), "too short");
        create_servers(&data_dir, "secret-1").expect("created");
        assert!(dir.join("servers.enc").is_file());
        assert!(create_servers(&data_dir, "secret-1").is_err(), "never over an existing file");
        let config = from_servers_enc("secret-1").expect("opens");
        assert!(config.servers.is_empty());
    }
}
