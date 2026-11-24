//! The station on a Linux server (Settings → Станция): its setup over SSH and its upkeep, by
//! MoonTerminal's own `moon-remote` — the same server steps, pins and checks as the terminal's
//! Settings → Telegram → Station, so a station set up from the phone is the terminal's station.
//!
//! Everything that talks to the server runs as one background job at a time: the app starts it
//! with one call and reads its progress lines and its end with `station_job`. The app's SSH key
//! is kept by the phone (its keystore) and handed in with `station_key`; the server's pins live in
//! the bridge's data root (`remote/hosts.toml`), as on the desktop.

use std::sync::Mutex;
use std::thread;

use moon_core::config::Secret;
use moon_remote::error::StationError;
use moon_remote::hosts::Hosts;
use moon_remote::progress::{Progress, Step};
use moon_remote::setup::{self, FirstAccess, NeedsAdminPassword, Setup, StationBinary};
use moon_remote::ssh::{OpenError, Target};
use moon_remote::station::{self as remote, BotChange, CoreKey, TapeWindow};
use serde::Deserialize;
use serde_json::{json, Value};
use zeroize::Zeroizing;

use crate::api::{ok, ApiError, ApiResult};

/// A server as the app names it.
#[derive(Debug, Clone, Deserialize)]
pub struct Server {
    pub host: String,
    #[serde(default = "ssh_port")]
    pub port: u16,
}

fn ssh_port() -> u16 {
    22
}

impl Server {
    fn target(&self) -> ApiResult<Target> {
        let host = self.host.trim();
        if host.is_empty() || host.contains(char::is_whitespace) {
            return Err(ApiError::new("bad_request", "no server address"));
        }
        Ok(Target { host: host.to_owned(), port: self.port })
    }
}

/// What the provider gave for the first login: used for the setup only, never kept.
#[derive(Debug, Clone, Deserialize)]
pub struct Login {
    pub user: String,
    #[serde(default)]
    pub password: String,
    /// A private key's text (OpenSSH, PuTTY, PKCS#8 or RSA PEM) instead of the password.
    #[serde(default)]
    pub key: String,
    #[serde(default)]
    pub passphrase: String,
}

impl Login {
    fn first_access(&self) -> anyhow::Result<FirstAccess> {
        let user = self.user.trim();
        anyhow::ensure!(!user.is_empty(), "no login");
        if self.key.trim().is_empty() {
            anyhow::ensure!(!self.password.is_empty(), StationError::EmptyPassword);
            return Ok(FirstAccess::Password {
                user: user.to_owned(),
                password: Zeroizing::new(self.password.clone()),
            });
        }
        let passphrase = Some(self.passphrase.as_str()).filter(|p| !p.is_empty());
        let key = moon_remote::keys::parse(&self.key, passphrase)
            .map_err(|e| anyhow::anyhow!("the key file: {e}"))?;
        Ok(FirstAccess::Key {
            user: user.to_owned(),
            key: Box::new(key),
            // A user other than root may need sudo's password; root needs none.
            sudo_password: (user != "root" && !self.password.is_empty())
                .then(|| Zeroizing::new(self.password.clone())),
        })
    }
}

/// One of the phone's cores as the station takes it.
#[derive(Clone)]
pub struct StationCore {
    /// The core's id in this session, as the app names it.
    pub id: u64,
    /// Its stable uid: the station keeps it by that.
    pub uid: u64,
    pub name: String,
    pub key: Secret,
    pub endpoint_override: String,
    pub active: bool,
}

/// The job running now, or the last one, with what it said.
struct Job {
    kind: &'static str,
    lines: Vec<String>,
    end: Option<Result<Value, String>>,
}

static JOB: Mutex<Option<Job>> = Mutex::new(None);

/// The current job: `{"kind","running","lines":[..]}` and, once it ended, `"result"` or `"error"`.
pub fn job() -> Value {
    let job = JOB.lock().unwrap_or_else(|e| e.into_inner());
    ok(match job.as_ref() {
        None => json!({ "kind": null, "running": false, "lines": [] }),
        Some(job) => {
            let mut out = json!({
                "kind": job.kind,
                "running": job.end.is_none(),
                "lines": job.lines,
            });
            match &job.end {
                Some(Ok(result)) => out["result"] = result.clone(),
                Some(Err(error)) => out["error"] = json!(error),
                None => {}
            }
            out
        }
    })
}

/// Start `work` as the station's job, unless one runs.
fn start<F>(kind: &'static str, work: F) -> ApiResult<Value>
where
    F: FnOnce(&mut dyn FnMut(Progress)) -> anyhow::Result<Value> + Send + 'static,
{
    {
        let mut job = JOB.lock().unwrap_or_else(|e| e.into_inner());
        if job.as_ref().is_some_and(|j| j.end.is_none()) {
            return Err(ApiError::new("busy", "Станция ещё выполняет прошлое действие."));
        }
        *job = Some(Job { kind, lines: Vec::new(), end: None });
    }
    let spawned = thread::Builder::new().name("mm-station".into()).spawn(move || {
        let mut say = |progress: Progress| {
            log::info!("station: {progress}");
            if let Some(line) = progress_text(&progress) {
                if let Some(job) = JOB.lock().unwrap_or_else(|e| e.into_inner()).as_mut() {
                    job.lines.push(line);
                }
            }
        };
        let end = work(&mut say).map_err(|e| {
            log::warn!("station {kind}: {e:#}");
            error_text(&e)
        });
        if let Some(job) = JOB.lock().unwrap_or_else(|e| e.into_inner()).as_mut() {
            job.end = Some(end);
        }
    });
    if let Err(e) = spawned {
        *JOB.lock().unwrap_or_else(|e| e.into_inner()) = None;
        return Err(ApiError::new("internal", format!("the station job did not start: {e}")));
    }
    Ok(ok(json!({ "started": kind })))
}

/// Hand in the app's SSH key, or make one: `{"key"?, "public"}`; `key` comes back only when it
/// was made here, for the app to keep.
pub fn key(given: Option<&str>) -> ApiResult<Value> {
    let internal = |e: anyhow::Error| ApiError::new("internal", format!("{e:#}"));
    let (text, made) = match given.map(str::trim).filter(|t| !t.is_empty()) {
        Some(text) => (Zeroizing::new(text.to_owned()), false),
        None => (moon_remote::app_key::generate_openssh("moonmobile").map_err(internal)?, true),
    };
    moon_remote::app_key::inject(Some(&text)).map_err(internal)?;
    let key = moon_remote::app_key::load_or_create().map_err(internal)?;
    let public = moon_remote::app_key::authorized_line(&key).map_err(internal)?;
    let mut out = json!({ "public": public });
    if made {
        out["key"] = json!(&*text);
    }
    Ok(ok(out))
}

/// Read the key the server presents, without logging in: `{"fingerprint"}`.
pub fn probe(server: &Server) -> ApiResult<Value> {
    let target = server.target()?;
    start("probe", move |_say| {
        let seen = setup::probe_host_key(target)?;
        Ok(json!({ "fingerprint": seen.fingerprint }))
    })
}

fn setup_of(target: Target, login: &Login, fingerprint: &str, check_only: bool) -> anyhow::Result<Setup> {
    Ok(Setup {
        target,
        first: login.first_access()?,
        legacy_admin_password: None,
        station: if check_only { StationBinary::Keep } else { StationBinary::Release },
        host_key: Some(fingerprint.trim().to_owned()).filter(|f| !f.is_empty()),
        check_only,
    })
}

/// Log in and read the server, changing nothing: `{"ufw_active","ports":[..],"ports_unknown"}` —
/// what the firewall would close.
pub fn check(server: &Server, login: Login, fingerprint: String) -> ApiResult<Value> {
    let target = server.target()?;
    start("check", move |say| {
        let setup = setup_of(target, &login, &fingerprint, true)?;
        let mut probe = Vec::new();
        let mut ports = Vec::new();
        let mut unknown = false;
        setup::run(&setup, &mut |progress: Progress| {
            match &progress {
                Progress::Diagnostic(line) => probe.push(line.clone()),
                Progress::Step { step: Step::FirewallWillClose, diagnostic } => {
                    ports = diagnostic.split(',').map(|p| p.trim().to_owned()).filter(|p| !p.is_empty()).collect();
                }
                Progress::Step { step: Step::FirewallPortsUnknown, .. } => unknown = true,
                _ => {}
            }
            say(progress);
        })?;
        let ufw_active = probe.iter().any(|line| line.trim() == "probe: ufw=active");
        Ok(json!({ "ufw_active": ufw_active, "ports": ports, "ports_unknown": unknown }))
    })
}

/// Set the server up and send it the picked cores: `{"cores":N}`.
pub fn install(server: &Server, login: Login, fingerprint: String, cores: Vec<StationCore>) -> ApiResult<Value> {
    let target = server.target()?;
    if cores.is_empty() {
        return Err(ApiError::new("bad_request", "Выберите хотя бы одно ядро для станции."));
    }
    start("install", move |say| {
        let setup = setup_of(target.clone(), &login, &fingerprint, false)?;
        setup::run(&setup, say)?;
        let keys = core_keys(&cores);
        remote::push_cores(&target, &keys, None, say)?;
        Ok(json!({ "cores": keys.len() }))
    })
}

fn core_keys(cores: &[StationCore]) -> Vec<CoreKey> {
    cores
        .iter()
        .map(|core| CoreKey {
            uid: core.uid,
            name: core.name.clone(),
            transport: None,
            key: core.key.clone(),
            endpoint_override: core.endpoint_override.clone(),
        })
        .collect()
}

/// Send cores to a station set up before; those it has keep their place.
pub fn push_cores(server: &Server, cores: Vec<StationCore>) -> ApiResult<Value> {
    let target = server.target()?;
    if cores.is_empty() {
        return Err(ApiError::new("bad_request", "Выберите хотя бы одно ядро для станции."));
    }
    start("cores", move |say| {
        let keys = core_keys(&cores);
        remote::push_cores(&target, &keys, None, say)?;
        Ok(json!({ "cores": keys.len() }))
    })
}

/// Take cores off the station, by their uids.
pub fn remove_cores(server: &Server, uids: Vec<u64>) -> ApiResult<Value> {
    let target = server.target()?;
    start("cores_remove", move |say| {
        remote::remove_cores(&target, &uids, say)?;
        Ok(json!({ "removed": uids.len() }))
    })
}

/// How the station is doing: running, version, cores, bot, tape, auto-update.
pub fn status(server: &Server) -> ApiResult<Value> {
    let target = server.target()?;
    start("status", move |say| {
        say(Progress::step(Step::Status, "status read"));
        let state = remote::bot::bot_state(&target)?;
        let station = state.station.as_deref();
        let bot = state.bot.as_ref().map(|bot| {
            json!({
                "status": format!("{:?}", bot.status),
                "paired": state.paired(),
                "polling": state.polling(),
                "chats": state.access.as_ref().map_or(0, |a| a.authorized_chat_ids.len()),
            })
        });
        Ok(json!({
            "running": !state.stopped,
            "answers": !state.stopped && !state.no_api,
            "version": station.map(|s| s.station_version.clone()),
            "cores_ready": station.map(|s| s.cores_ready),
            "cores_total": station.map(|s| s.cores_total),
            "cores": station.and_then(|s| s.cores.as_ref()).map(|cores| {
                cores.iter().map(|c| json!({ "uid": c.uid, "name": c.name })).collect::<Vec<_>>()
            }),
            "auto_update": station.and_then(|s| s.auto_update),
            "last_update": station.and_then(|s| s.last_update.clone()),
            "tape": state.tape.map(|t| json!({ "margin_s": t.margin_s, "long_position_min": t.long_position_min })),
            "has_token": state.has_token,
            "bot": bot,
            "pairing_code": state.pairing_code(),
        }))
    })
}

/// Install the newest station release, if there is one.
pub fn update(server: &Server) -> ApiResult<Value> {
    let target = server.target()?;
    start("update", move |say| {
        remote::update_from_release(&target, say)?;
        Ok(json!({}))
    })
}

/// Give the station's bot a token, or take the bot off (`off`).
pub fn bot(server: &Server, token: Option<String>, off: bool) -> ApiResult<Value> {
    let target = server.target()?;
    let token = token.map(|t| Secret::new(t.trim())).filter(|t| !t.is_empty());
    if !off && token.is_none() {
        return Err(ApiError::new("bad_request", "Введите токен бота."));
    }
    start("bot", move |say| {
        let change = BotChange { mini_app: None, zone: None, language: None };
        remote::push_telegram(&target, token.as_ref(), &change, off, say)?;
        Ok(json!({}))
    })
}

/// The window the station records around a trade.
pub fn tape(server: &Server, margin_s: u32, long_position_min: u32) -> ApiResult<Value> {
    let target = server.target()?;
    start("tape", move |say| {
        remote::push_tape(&target, TapeWindow { margin_s, long_position_min }, None, None, say)?;
        Ok(json!({}))
    })
}

/// The station updating itself from new releases.
pub fn auto_update(server: &Server, on: bool) -> ApiResult<Value> {
    let target = server.target()?;
    start("auto_update", move |say| {
        remote::push_auto_update(&target, on, say)?;
        Ok(json!({ "on": on }))
    })
}

/// Stop the station on the server and delete its keys and bot; the administrator stays.
pub fn remove(server: &Server) -> ApiResult<Value> {
    let target = server.target()?;
    start("remove", move |say| {
        say(Progress::step(Step::StationRemove, "remove station credentials"));
        let host = Hosts::load(&Hosts::path())?
            .get(&target.addr())
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("{} was never set up here", target.addr()))?;
        remote::access::remove_station(&host)?;
        Ok(json!({}))
    })
}

/// Forget the server here: its pin and administrator. The station keeps running there.
pub fn forget(server: &Server) -> ApiResult<Value> {
    let target = server.target()?;
    let path = Hosts::path();
    let mut hosts = Hosts::load(&path).map_err(|e| ApiError::new("internal", format!("{e:#}")))?;
    let forgot = hosts.forget(&target.addr());
    hosts.save(&path).map_err(|e| ApiError::new("internal", format!("{e:#}")))?;
    Ok(ok(json!({ "forgot": forgot })))
}

/// A progress step in the words MoonTerminal shows (its `station.progress.*`); diagnostics stay
/// in the log.
fn progress_text(progress: &Progress) -> Option<String> {
    let (step, detail) = match progress {
        Progress::Step { step, diagnostic } => (*step, diagnostic.as_str()),
        Progress::Text(text) => return Some(text.clone()),
        Progress::Diagnostic(_) => return None,
    };
    let text = match step {
        Step::Login => "Вход на сервер выполнен.",
        Step::Pin => "Ключ сервера сохранён.",
        Step::Probe => "Параметры сервера проверены.",
        Step::Admin => "Доступ администратора по ключу настроен.",
        Step::Helper => "Помощник станции установлен.",
        Step::Service => "Служба станции настроена.",
        Step::Harden => "SSH защищён: вход только по ключу, без root.",
        Step::Firewall => "Межсетевой экран настроен.",
        Step::Extras => "Дополнительные параметры сервера настроены.",
        Step::Install => "Служба станции установлена.",
        Step::Download => "Загрузка службы станции из релиза.",
        Step::TapePending => "Окно ленты будет настроено при отправке ядер.",
        Step::TapeWritten => "Окно ленты сохранено; станция применит его при следующем запуске.",
        Step::TapeReload => "Окно ленты сохранено, станция перечитывает настройки.",
        Step::TapeApplied => "Станция подтвердила новое окно ленты.",
        Step::AutoUpdateWritten => "Автообновление сохранено; станция применит его при следующем запуске.",
        Step::AutoUpdateApplied => "Станция подтвердила настройку автообновления.",
        Step::TokenWritten => "Токен бота сохранён в шифрованных данных.",
        Step::TokenDropped => "Токен бота удалён со станции.",
        Step::ChatsDropped => "Привязки чатов бота удалены.",
        Step::CoreWritten => "Ключ ядра сохранён в шифрованных данных.",
        Step::CoreDropped => "Ключ исключённого ядра удалён со станции.",
        Step::EndpointNotSent => "Адрес ядра не отправлен: служба станции его ещё не понимает. Обновите службу и отправьте ядра снова.",
        Step::Status => "Состояние станции прочитано.",
        Step::Update => "Запрос обновления службы завершён.",
        Step::NoNewRelease => "Ничего не установлено: более нового релиза службы нет.",
        Step::Unversioned => "Ничего не установлено: сборка станции не из релиза.",
        Step::ValuationSend => "Отправка кеша оценки в USDT.",
        Step::ValuationKept => "Станция сохранила собственный кеш оценки.",
        Step::ValuationWritten => "Кеш оценки отправлен на станцию.",
        Step::ChatsTransferred => "Привязки чатов переданы на станцию.",
        Step::BotStarted => "Станция запущена с ботом.",
        Step::UndoHandover => "Передача не удалась: убираем бота со станции.",
        Step::BotRemoved => "Бот станции выключен; токен и привязки чатов удалены.",
        Step::BotReady => "Бот станции готов.",
        Step::AddressProbe => "Проверяем отпечаток нового адреса.",
        Step::InstallProbe => "Читаем ключ сервера.",
        Step::AddressVerify => "Проверяем вход с подтверждённым отпечатком.",
        Step::StationRemove => "Останавливаем станцию и удаляем ключи и бота.",
        Step::FirewallWillClose => {
            return Some(format!("Межсетевой экран закроет эти порты, которые сейчас используются: {detail}."));
        }
        Step::FirewallPortsUnknown => {
            "Не удалось перечислить используемые сейчас порты; межсетевой экран закроет все входящие порты, кроме SSH."
        }
    };
    Some(text.to_owned())
}

/// What went wrong, in MoonTerminal's words (its `station.error.*`), with the details after.
fn error_text(e: &anyhow::Error) -> String {
    if let Some(error) = e.downcast_ref::<StationError>() {
        return match error {
            StationError::EmptyPassword => "Введите пароль сервера.".into(),
            StationError::PasswordOneLine => "Пароль не должен содержать перенос строки. Введите его заново.".into(),
            StationError::NoCorePicked => "Выберите хотя бы одно ядро для станции.".into(),
            StationError::CoreMissing(uid) => format!("Ядра {uid} нет в списке ядер."),
            StationError::CoreWithoutKey(name) => format!("У ядра «{name}» нет ключа."),
            StationError::HelperTooOld => "Помощник станции устарел. Установите станцию заново.".into(),
            StationError::ServiceTooOld => "Служба станции устарела. Нажмите «Обновить службу».".into(),
            StationError::TerminalTooOld => "Станция использует более новую версию API. Обновите MoonMobile.".into(),
            StationError::Timeout => "Сервер не ответил вовремя. Проверьте сеть и состояние сервера, затем повторите действие.".into(),
            StationError::SystemdMissing => "На сервере нет systemd. Выберите Linux-сервер с systemd 250 или новее.".into(),
            StationError::SystemdTooOld => "Версия systemd слишком старая для шифрованных ключей. Обновите ОС сервера до systemd 250 или новее.".into(),
            StationError::PasswordStillOffered => "Сервер всё ещё предлагает вход по паролю. Проверьте настройки SSH и повторите установку.".into(),
            StationError::PasswordStillWorks => "Вход по паролю всё ещё работает. Проверьте настройки SSH и повторите установку.".into(),
            StationError::BotTokenMissing => "На станции нет токена бота.".into(),
            StationError::ConfigMissing => "На станции ещё нет настроек. Сначала отправьте ядра.".into(),
            StationError::BotAlreadyPresent => "На станции уже есть бот. Сначала уберите его со станции.".into(),
            StationError::BotStopped => "Станция остановилась после передачи бота. Проверьте её журнал.".into(),
            StationError::BotNotReady => "Бот станции не начал опрос вовремя. Проверьте состояние станции.".into(),
            StationError::HostKeyUnconfirmed => "Ключ сервера не подтверждён; на сервер ничего не отправлено.".into(),
            StationError::NoAptGet => "Система этого сервера не поддерживается: нужен Debian или Ubuntu. Ничего не изменено.".into(),
            StationError::SshdNoInclude => "Настройки SSH на этом сервере устроены необычно, поэтому установка не может безопасно их изменить. Ничего не изменено.".into(),
        };
    }
    if let Some(error) = e.downcast_ref::<OpenError>() {
        return match error {
            OpenError::Refused { .. } => "Сервер отклонил вход. Проверьте логин, пароль или ключ.".into(),
            OpenError::HostKeyChanged { .. } => {
                "Ключ сервера изменился. Если сервер переустановлен, нажмите «Забыть станцию» и установите заново.".into()
            }
            OpenError::Unreachable(_) => format!("Сервер недоступен. Проверьте адрес, порт SSH и сеть. Подробности: {e:#}"),
            OpenError::Other(_) => format!("Не удалось выполнить действие на станции. Подробности: {e:#}"),
        };
    }
    if e.downcast_ref::<NeedsAdminPassword>().is_some() {
        return "Этот сервер настроен старой версией MoonTerminal: обновите его настройку из MoonTerminal.".into();
    }
    format!("Не удалось выполнить действие на станции. Подробности: {e:#}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_key_made_here_comes_back_and_is_taken_again() {
        let made = key(None).unwrap();
        let text = made["key"].as_str().unwrap().to_owned();
        let public = made["public"].as_str().unwrap().to_owned();
        assert!(public.starts_with("ssh-ed25519 "), "{public}");
        assert!(text.contains("OPENSSH PRIVATE KEY"));
        let again = key(Some(&text)).unwrap();
        assert_eq!(again["public"], json!(public));
        assert!(again.get("key").is_none());
    }

    #[test]
    fn steps_read_as_the_terminal_says_them() {
        let ports = Progress::step(Step::FirewallWillClose, "80, 443");
        assert_eq!(
            progress_text(&ports).unwrap(),
            "Межсетевой экран закроет эти порты, которые сейчас используются: 80, 443."
        );
        assert!(progress_text(&Progress::Diagnostic("probe: ufw=active".into())).is_none());
        let refused = anyhow::Error::new(OpenError::Refused { password_offered: true });
        assert_eq!(error_text(&refused), "Сервер отклонил вход. Проверьте логин, пароль или ключ.");
    }

    #[test]
    fn a_password_login_needs_its_password() {
        let login = Login { user: "root".into(), password: String::new(), key: String::new(), passphrase: String::new() };
        let e = login.first_access().err().unwrap();
        assert_eq!(error_text(&e), "Введите пароль сервера.");
    }
}
