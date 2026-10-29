//! College WiFi desktop: signs in to the campus captive portal (IIIT Dharwad, Sophos) automatically, shows today's
//! data left, and lives in the tray / menu bar. No telemetry: the only network traffic is to the campus portal and
//! the standard connectivity probes, and only while on the campus network.
pub mod net;
pub mod portal;
pub mod usage;
pub mod userportal;

use futures::StreamExt;
use portal::{Kind, Live};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, WindowEvent};
use tauri_plugin_autostart::ManagerExt;

const KEYCHAIN_SERVICE: &str = "College WiFi (bot.paradox.wifi)";
const CHECK_EVERY: Duration = Duration::from_secs(60);
const USAGE_MIN_GAP_MS: i64 = 5 * 60_000;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub username: String,
    pub auto_login: bool,
    pub theme: String,
    pub portal: String,
    pub ssids: Vec<String>,
    pub read_usage: bool,
    pub cert_pin: Option<String>,
    pub quota: Option<usage::Quota>,
    pub quota_at: Option<i64>,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            username: String::new(),
            auto_login: true,
            theme: "system".into(),
            portal: portal::DEFAULT_BASE.into(),
            ssids: vec![portal::DEFAULT_SSID.into()],
            read_usage: true,
            cert_pin: None,
            quota: None,
            quota_at: None,
        }
    }
}

/// What the window and the tray show.
#[derive(Clone, Debug, Serialize, Default)]
pub struct View {
    /// connected | signed_out | busy | away | offline | setup | error
    pub state: String,
    pub title: String,
    pub detail: String,
    pub ssid: Option<String>,
    pub network: String,
    pub on_campus: bool,
    pub busy: bool,
    pub has_password: bool,
    pub username: String,
    pub auto_login: bool,
    pub theme: String,
    pub portal: String,
    pub ssids: Vec<String>,
    pub read_usage: bool,
    pub start_at_login: bool,
    pub usage: Option<UsageView>,
    pub usage_note: Option<String>,
    pub checked_at: Option<i64>,
    pub version: String,
    pub keychain: String,
    pub os: String,
    /// Screenshot mode only: which page to open (COLLEGE_WIFI_VIEW).
    pub demo_view: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct UsageView {
    pub left: String,
    pub used: String,
    pub cap: String,
    pub line: String,
    pub fraction: f64,
    pub period: String,
    pub updated_at: i64,
    pub has_cap: bool,
}

fn usage_view(q: &usage::Quota, at: i64) -> UsageView {
    let c = q.cap();
    UsageView {
        left: if c > 0 { usage::bytes_scaled(q.left().max(0), c) } else { "—".into() },
        used: usage::bytes_scaled(q.used().max(0), c.max(1)),
        cap: if c > 0 { usage::bytes(c) } else { "No limit".into() },
        line: usage::line(q),
        fraction: q.fraction_used(),
        period: q.period.clone(),
        updated_at: at,
        has_cap: c > 0,
    }
}

pub struct Engine {
    app: AppHandle,
    settings: Mutex<Settings>,
    view: Mutex<View>,
    busy: AtomicBool,
    /// The user tapped Log out on this association: stay out until the network changes or they tap Log in.
    paused: AtomicBool,
    usage_running: AtomicBool,
    generation: AtomicU64,
    tray_status: Mutex<Option<MenuItem<tauri::Wry>>>,
    demo: bool,
}

fn now() -> i64 {
    portal::now_ms() as i64
}

fn config_path(app: &AppHandle) -> PathBuf {
    let dir = app.path().app_config_dir().unwrap_or_else(|_| std::env::temp_dir().join("college-wifi"));
    let _ = std::fs::create_dir_all(&dir);
    dir.join("settings.json")
}

fn keychain_name() -> &'static str {
    if cfg!(target_os = "windows") { "Windows Credential Manager" } else if cfg!(target_os = "macos") { "macOS Keychain" } else { "the system keyring (Secret Service)" }
}

fn entry(user: &str) -> Result<keyring::Entry, String> {
    keyring::Entry::new(KEYCHAIN_SERVICE, user).map_err(|e| e.to_string())
}

fn get_password(user: &str) -> Option<String> {
    if user.is_empty() { return None; }
    entry(user).ok()?.get_password().ok()
}

impl Engine {
    fn new(app: AppHandle, demo: bool) -> Arc<Engine> {
        let settings: Settings = std::fs::read_to_string(config_path(&app)).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
        let e = Arc::new(Engine {
            app,
            settings: Mutex::new(settings),
            view: Mutex::new(View { state: "offline".into(), title: "Checking…".into(), ..Default::default() }),
            busy: AtomicBool::new(false),
            paused: AtomicBool::new(false),
            usage_running: AtomicBool::new(false),
            generation: AtomicU64::new(0),
            tray_status: Mutex::new(None),
            demo,
        });
        e
    }

    fn save(&self) {
        let s = self.settings.lock().unwrap().clone();
        if let Ok(j) = serde_json::to_string_pretty(&s) { let _ = std::fs::write(config_path(&self.app), j); }
    }

    fn settings(&self) -> Settings {
        self.settings.lock().unwrap().clone()
    }

    /// Fill in the settings-derived fields and push the view to the window and the tray.
    fn publish(&self, f: impl FnOnce(&mut View)) {
        let s = self.settings();
        let start_at_login = self.app.autolaunch().is_enabled().unwrap_or(false);
        let has_password = if self.demo { true } else { get_password(&s.username).is_some() };
        let v = {
            let mut v = self.view.lock().unwrap();
            f(&mut v);
            v.busy = self.busy.load(Ordering::SeqCst);
            v.username = s.username.clone();
            v.has_password = has_password;
            v.auto_login = s.auto_login;
            v.theme = s.theme.clone();
            v.portal = s.portal.clone();
            v.ssids = s.ssids.clone();
            v.read_usage = s.read_usage;
            v.start_at_login = start_at_login;
            v.version = env!("CARGO_PKG_VERSION").into();
            v.keychain = keychain_name().into();
            v.os = std::env::consts::OS.into();
            if self.demo { v.demo_view = std::env::var("COLLEGE_WIFI_VIEW").ok(); }
            if v.usage.is_none() || self.demo {
                if let (Some(q), Some(at)) = (&s.quota, s.quota_at) { v.usage = Some(usage_view(q, at)); }
            }
            v.clone()
        };
        let _ = self.app.emit("state", &v);
        self.update_tray(&v);
    }

    fn update_tray(&self, v: &View) {
        let on = v.state == "connected";
        if let Some(t) = self.app.tray_by_id("main") {
            let icon = tray_icon(on);
            let _ = t.set_icon(Some(icon));
            #[cfg(target_os = "macos")]
            let _ = t.set_icon_as_template(true);
            let tip = match &v.usage { Some(u) if on && u.has_cap => format!("College WiFi · {} · {} left", v.title, u.left), _ => format!("College WiFi · {}", v.title) };
            let _ = t.set_tooltip(Some(tip));
        }
        if let Some(item) = self.tray_status.lock().unwrap().as_ref() {
            let _ = item.set_text(match &v.usage { Some(u) if on && u.has_cap => format!("{} · {} left", v.title, u.left), _ => v.title.clone() });
        }
    }

    fn set_state(&self, state: &str, title: &str, detail: &str) {
        let (st, ti, de) = (state.to_string(), title.to_string(), detail.to_string());
        self.publish(move |v| { v.state = st; v.title = ti; v.detail = de; v.checked_at = Some(now()); });
    }

    /// Where are we? Wi-Fi name if the OS shares it, else a quick knock on the portal (no data sent).
    fn locate(&self) -> (Option<String>, bool) {
        let s = self.settings();
        let ssid = net::current_ssid();
        let by_name = ssid.as_ref().map(|n| s.ssids.iter().any(|x| x.eq_ignore_ascii_case(n.trim()))).unwrap_or(false);
        let on = by_name || portal::portal_reachable(&s.portal, 900);
        (ssid, on)
    }

    /// The one check: run on start, on every network change, every 60 s while on the campus network, and on demand.
    pub fn check(self: &Arc<Self>, reason: &str) {
        if self.demo { return; }
        if self.busy.swap(true, Ordering::SeqCst) { return; }
        let me = self.clone();
        let reason = reason.to_string();
        std::thread::spawn(move || {
            me.do_check(&reason);
            me.busy.store(false, Ordering::SeqCst);
            me.publish(|_| {});
        });
    }

    fn do_check(self: &Arc<Self>, reason: &str) {
        let s = self.settings();
        let (ssid, on_campus) = self.locate();
        let was_on = self.view.lock().unwrap().on_campus;
        let prev_ssid = self.view.lock().unwrap().ssid.clone();
        if reason == "network" && (!was_on || prev_ssid != ssid) { self.paused.store(false, Ordering::SeqCst); }
        let network = if on_campus { "campus portal".to_string() } else { String::new() };
        {
            let (ss, nw) = (ssid.clone(), network.clone());
            self.publish(move |v| { v.ssid = ss; v.on_campus = on_campus; v.network = nw; });
        }
        if !on_campus {
            let words = match &ssid { Some(n) => format!("{} isn't the college network", n), None => "Connect to the college Wi-Fi to sign in".into() };
            self.set_state("away", "Not on college Wi-Fi", &words);
            return;
        }
        if s.username.is_empty() || get_password(&s.username).is_none() {
            self.set_state("setup", "Add your login", "Open the menu → Account to save your portal username and password");
            return;
        }
        let live = portal::live(&s.portal, &s.username);
        let live = if live == Live::Unknown { portal::internet() } else { live };
        match live {
            Live::In => {
                self.set_state("connected", "Connected", "Signed in to the campus portal");
                self.paused.store(false, Ordering::SeqCst);
                self.maybe_usage(false);
            }
            _ if self.paused.load(Ordering::SeqCst) => {
                self.set_state("signed_out", "Logged out", "Logged out by you · auto-login paused until Wi-Fi reconnects or you tap the button");
            }
            _ if s.auto_login => { self.do_login(); }
            _ => self.set_state("signed_out", "Not signed in", "Auto-login is off · tap the button to sign in"),
        }
    }

    /// Sign in: the portal is retried every second for up to 15 s when it does not answer (Session.FAST_RETRY).
    fn do_login(self: &Arc<Self>) {
        let s = self.settings();
        let Some(pass) = get_password(&s.username) else {
            self.set_state("setup", "Add your login", "Open the menu → Account to save your portal username and password");
            return;
        };
        self.publish(|v| { v.state = "busy".into(); v.title = "Connecting…".into(); v.detail = "Signing in to the campus portal".into(); });
        let started = std::time::Instant::now();
        loop {
            match portal::login(&s.portal, &s.username, &pass) {
                Ok(reply) => {
                    let (kind, words) = portal::verdict(&reply);
                    match kind {
                        Kind::Ok => {
                            self.paused.store(false, Ordering::SeqCst);
                            self.set_state("connected", "Connected", &words);
                            self.maybe_usage(true);
                        }
                        Kind::WrongPassword => self.set_state("error", "Wrong password", &words),
                        Kind::LimitSession => self.set_state("error", "Login limit reached", &words),
                        Kind::LimitQuota => self.set_state("error", "Data limit reached", &words),
                        Kind::Expired => self.set_state("error", "Account expired", &words),
                        _ => self.set_state("error", "Portal said no", &words),
                    }
                    return;
                }
                Err(portal::PortalError::Unreachable(_)) if started.elapsed() < Duration::from_secs(15) => std::thread::sleep(Duration::from_secs(1)),
                Err(e) => {
                    self.set_state("error", "Couldn't sign in", &format!("{} · will try again on the next Wi-Fi change or tap", e));
                    return;
                }
            }
        }
    }

    fn do_logout(self: &Arc<Self>) {
        let s = self.settings();
        self.publish(|v| { v.state = "busy".into(); v.title = "Logging out…".into(); v.detail = String::new(); });
        self.paused.store(true, Ordering::SeqCst);
        match portal::logout(&s.portal, &s.username) {
            Some(true) => self.set_state("signed_out", "Logged out", "Auto-login paused until Wi-Fi reconnects or you tap the button"),
            Some(false) => self.set_state("signed_out", "Logged out", "The portal answered without confirming · auto-login paused"),
            None => self.set_state("error", "Couldn't reach the portal", "Not logged out · try again on the college Wi-Fi"),
        }
    }

    /// Power button: log in when not connected, log out when connected.
    pub fn toggle(self: &Arc<Self>, want: Option<bool>) {
        if self.demo { return; }
        if self.busy.swap(true, Ordering::SeqCst) { return; }
        let me = self.clone();
        std::thread::spawn(move || {
            let connected = me.view.lock().unwrap().state == "connected";
            let login = want.unwrap_or(!connected);
            if login { me.paused.store(false, Ordering::SeqCst); me.do_login(); } else { me.do_logout(); }
            me.busy.store(false, Ordering::SeqCst);
            me.publish(|_| {});
        });
    }

    /// Read the User Portal's usage table (at most every 5 min unless forced).
    fn maybe_usage(self: &Arc<Self>, force: bool) {
        let s = self.settings();
        if !s.read_usage || s.username.is_empty() { return; }
        if !force { if let Some(at) = s.quota_at { if now() - at < USAGE_MIN_GAP_MS { return; } } }
        if self.usage_running.swap(true, Ordering::SeqCst) { return; }
        let me = self.clone();
        std::thread::spawn(move || {
            let s = me.settings();
            let host = portal::host_port(&s.portal).map(|h| h.0).unwrap_or_default();
            let Some(pass) = get_password(&s.username) else { me.usage_running.store(false, Ordering::SeqCst); return; };
            let out = userportal::fetch(&me.app, &host, &s.username, &pass, s.cert_pin.clone());
            drop(pass);
            let parsed = out.page.as_deref().and_then(usage::parse);
            {
                let mut st = me.settings.lock().unwrap();
                if st.cert_pin.is_none() { st.cert_pin = out.pin_seen.clone(); }
                if let Some(q) = &parsed { st.quota = Some(q.clone()); st.quota_at = Some(now()); }
            }
            me.save();
            let note = match (&parsed, &out.error) {
                (Some(_), _) => None,
                (None, Some(e)) => Some(format!("Usage not updated: {}", e)),
                (None, None) => Some("Usage not updated: the page had no usage table".into()),
            };
            let view = parsed.map(|q| usage_view(&q, now()));
            me.publish(move |v| { if view.is_some() { v.usage = view; } v.usage_note = note; });
            me.usage_running.store(false, Ordering::SeqCst);
        });
    }

    pub fn refresh(self: &Arc<Self>) {
        self.check("refresh");
        if self.view.lock().unwrap().state == "connected" { self.maybe_usage(true); }
    }
}

// ---------------------------------------------------------------- commands for the window
type E<'a> = tauri::State<'a, Arc<Engine>>;

#[tauri::command]
fn get_state(engine: E) -> View {
    engine.publish(|_| {});
    engine.view.lock().unwrap().clone()
}

#[tauri::command]
fn toggle(engine: E) {
    engine.toggle(None);
}

#[tauri::command]
fn refresh(engine: E) {
    engine.refresh();
}

#[tauri::command]
fn save_login(engine: E, username: String, password: String) -> Result<(), String> {
    let username = username.trim().to_string();
    if username.is_empty() { return Err("Enter your portal username".into()); }
    let old = engine.settings().username;
    if !password.is_empty() {
        entry(&username)?.set_password(&password).map_err(|e| format!("Couldn't save to {}: {}", keychain_name(), e))?;
    } else if get_password(&username).is_none() {
        return Err("Enter your password".into());
    }
    if !old.is_empty() && old != username { if let Ok(e) = entry(&old) { let _ = e.delete_credential(); } }
    {
        let mut s = engine.settings.lock().unwrap();
        if s.username != username { s.quota = None; s.quota_at = None; }
        s.username = username;
    }
    engine.save();
    engine.paused.store(false, Ordering::SeqCst);
    engine.check("login-saved");
    Ok(())
}

#[tauri::command]
fn forget_login(engine: E) {
    let user = engine.settings().username;
    if let Ok(e) = entry(&user) { let _ = e.delete_credential(); }
    {
        let mut s = engine.settings.lock().unwrap();
        s.username.clear();
        s.quota = None;
        s.quota_at = None;
    }
    engine.save();
    engine.publish(|v| { v.usage = None; v.state = "setup".into(); v.title = "Add your login".into(); v.detail = "Open the menu → Account to save your portal username and password".into(); });
}

#[derive(Deserialize)]
struct Patch {
    auto_login: Option<bool>,
    theme: Option<String>,
    portal: Option<String>,
    ssids: Option<Vec<String>>,
    read_usage: Option<bool>,
    start_at_login: Option<bool>,
}

#[tauri::command]
fn set_settings(engine: E, patch: Patch) -> Result<(), String> {
    if let Some(on) = patch.start_at_login {
        let al = engine.app.autolaunch();
        if on { al.enable() } else { al.disable() }.map_err(|e| e.to_string())?;
    }
    {
        let mut s = engine.settings.lock().unwrap();
        if let Some(v) = patch.auto_login { s.auto_login = v; }
        if let Some(v) = patch.theme { if ["light", "dark", "system"].contains(&v.as_str()) { s.theme = v; } }
        if let Some(v) = patch.portal {
            let v = v.trim().trim_end_matches('/').to_string();
            let v = if v.starts_with("http://") || v.starts_with("https://") { v } else { format!("http://{}", v) };
            if url::Url::parse(&v).is_err() { return Err("That portal address doesn't look right".into()); }
            if v != s.portal { s.cert_pin = None; }
            s.portal = v;
        }
        if let Some(v) = patch.ssids { s.ssids = v.into_iter().map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect(); }
        if let Some(v) = patch.read_usage { s.read_usage = v; }
    }
    engine.save();
    engine.publish(|_| {});
    Ok(())
}

#[tauri::command]
fn quit(app: AppHandle) {
    app.exit(0);
}

fn tray_icon(on: bool) -> tauri::image::Image<'static> {
    if cfg!(target_os = "macos") {
        if on { tauri::include_image!("icons/tray-on-template.png") } else { tauri::include_image!("icons/tray-off-template.png") }
    } else if on {
        tauri::include_image!("icons/tray-on.png")
    } else {
        tauri::include_image!("icons/tray-off.png")
    }
}

fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

fn demo_settings() -> Settings {
    // Screenshot mode (COLLEGE_WIFI_DEMO=1): sample numbers like the phone widget's, no network, nothing saved.
    let q = usage::Quota { allotted: 13 * usage::GB, total: (5.2 * usage::GB as f64) as i64, remaining: (7.8 * usage::GB as f64) as i64, up_to_last: 0, current: 0, down: usage::NA, up: usage::NA, period: "daily".into() };
    Settings { username: "21bcs001".into(), quota: Some(q), quota_at: Some(now() - 4 * 60_000), ..Default::default() }
}

pub fn run() {
    let demo = std::env::var("COLLEGE_WIFI_DEMO").map(|v| v == "1").unwrap_or(false);
    let hidden = std::env::args().any(|a| a == "--hidden");
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .plugin(tauri_plugin_autostart::init(tauri_plugin_autostart::MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .invoke_handler(tauri::generate_handler![get_state, toggle, refresh, save_login, forget_login, set_settings, quit])
        .setup(move |app| {
            let handle = app.handle().clone();
            let engine = Engine::new(handle.clone(), demo);
            if demo {
                let mut s = engine.settings.lock().unwrap();
                *s = demo_settings();
                if let Ok(t) = std::env::var("COLLEGE_WIFI_THEME") { s.theme = t; }
            }
            app.manage(engine.clone());

            // tray / menu bar
            let status = MenuItem::with_id(app, "status", "College WiFi", false, None::<&str>)?;
            let login = MenuItem::with_id(app, "login", "Log in", true, None::<&str>)?;
            let logout = MenuItem::with_id(app, "logout", "Log out", true, None::<&str>)?;
            let open = MenuItem::with_id(app, "open", "Open College WiFi", true, None::<&str>)?;
            let quit_i = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let sep1 = PredefinedMenuItem::separator(app)?;
            let sep2 = PredefinedMenuItem::separator(app)?;
            let menu = Menu::with_items(app, &[&status, &sep1, &login, &logout, &open, &sep2, &quit_i])?;
            *engine.tray_status.lock().unwrap() = Some(status);
            let e2 = engine.clone();
            let tray = TrayIconBuilder::with_id("main")
                .icon(tray_icon(false))
                .icon_as_template(cfg!(target_os = "macos"))
                .tooltip("College WiFi")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(move |app, ev| match ev.id.as_ref() {
                    "login" => e2.toggle(Some(true)),
                    "logout" => e2.toggle(Some(false)),
                    "open" => show_main(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, ev| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = ev { show_main(tray.app_handle()); }
                })
                .build(app);
            if let Err(e) = tray { eprintln!("tray unavailable: {e}"); }

            if !hidden { show_main(&handle); }
            if demo {
                engine.publish(|v| {
                    v.state = "connected".into(); v.title = "Connected".into(); v.detail = "You are signed in as 21bcs001".into();
                    v.ssid = Some(portal::DEFAULT_SSID.into()); v.network = "campus portal".into(); v.on_campus = true; v.checked_at = Some(now());
                });
                return Ok(());
            }

            // first look, then react to the network instead of polling
            engine.check("start");
            let e3 = engine.clone();
            tauri::async_runtime::spawn(async move {
                match if_watch::tokio::IfWatcher::new() {
                    Ok(mut w) => {
                        let mut last = std::time::Instant::now() - Duration::from_secs(10);
                        while let Some(ev) = w.next().await {
                            if ev.is_err() { continue; }
                            // a join produces a burst of address events: settle, then look once
                            if last.elapsed() < Duration::from_secs(3) { continue; }
                            last = std::time::Instant::now();
                            let e = e3.clone();
                            let g = e.generation.fetch_add(1, Ordering::SeqCst) + 1;
                            tokio::time::sleep(Duration::from_millis(2500)).await;
                            if e.generation.load(Ordering::SeqCst) == g { e.check("network"); }
                        }
                    }
                    Err(err) => eprintln!("network events unavailable ({err}); relying on the periodic check"),
                }
            });
            // light check: every 60 s, and only while on the campus network (or still unsure)
            let e4 = engine.clone();
            tauri::async_runtime::spawn(async move {
                loop {
                    tokio::time::sleep(CHECK_EVERY).await;
                    let on = e4.view.lock().unwrap().on_campus;
                    if on { e4.check("timer"); }
                }
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "main" {
                    let _ = window.hide();
                    api.prevent_close();
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("error while building College WiFi")
        .run(|app, ev| {
            #[cfg(target_os = "macos")]
            if let tauri::RunEvent::Reopen { .. } = ev { show_main(app); }
            let _ = (app, &ev);
        });
}
