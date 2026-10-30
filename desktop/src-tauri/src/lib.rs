//! College WiFi desktop: signs in to the campus captive portal (IIIT Dharwad, Sophos) automatically, shows today's
//! data left, and lives in the tray / menu bar. No telemetry: the only network traffic is to the campus portal and
//! the standard connectivity probes, and only while on the campus network.
pub mod accounts;
pub mod diag;
pub mod net;
pub mod portal;
pub mod usage;
pub mod userportal;

use futures::StreamExt;
use accounts::Account;
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
    /// v1.0 kept one login here. Since v1.1 it mirrors the active account (and is migrated into `accounts`).
    pub username: String,
    /// v1.1: several logins, in the user's order (first = primary, tried first on every connect).
    pub accounts: Vec<Account>,
    /// The account the portal last accepted on this computer.
    pub active_id: Option<String>,
    pub auto_login: bool,
    pub theme: String,
    pub portal: String,
    pub ssids: Vec<String>,
    pub read_usage: bool,
    /// Write the connection log to college-wifi.log as well (off by default).
    pub log_file: bool,
    pub cert_pin: Option<String>,
    pub quota: Option<usage::Quota>,
    pub quota_at: Option<i64>,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            username: String::new(),
            accounts: Vec::new(),
            active_id: None,
            auto_login: true,
            theme: "system".into(),
            portal: portal::DEFAULT_BASE.into(),
            ssids: vec![portal::DEFAULT_SSID.into()],
            read_usage: true,
            log_file: false,
            cert_pin: None,
            quota: None,
            quota_at: None,
        }
    }
}

/// One row on the Accounts page.
#[derive(Clone, Debug, Serialize, Default)]
pub struct AccountView {
    pub id: String,
    pub user: String,
    pub index: usize,
    pub primary: bool,
    pub active: bool,
    pub chip: String,
    pub state: String,
    pub message: String,
    pub has_password: bool,
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
    pub accounts: Vec<AccountView>,
    pub active_user: Option<String>,
    pub auto_login: bool,
    pub theme: String,
    pub portal: String,
    pub ssids: Vec<String>,
    pub read_usage: bool,
    pub start_at_login: bool,
    pub log_file: bool,
    pub log_path: String,
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
    /// The Wi-Fi (re)connected since the last sign-in chain: accounts at a device limit get another try.
    fresh: AtomicBool,
    /// Signed in with the last account left, but the probe is still intercepted: don't re-login every minute.
    blocked: AtomicBool,
    usage_running: AtomicBool,
    generation: AtomicU64,
    tray_status: Mutex<Option<MenuItem<tauri::Wry>>>,
    log: diag::Log,
    demo: bool,
}

fn now() -> i64 {
    portal::now_ms() as i64
}

/// The next local midnight after `now` (campus quotas reset daily).
fn next_reset(now_ms: i64) -> i64 {
    use chrono::{Duration as D, Local, TimeZone};
    let Some(t) = Local.timestamp_millis_opt(now_ms).single() else { return now_ms + 24 * 3_600_000 };
    let tomorrow = t.date_naive() + D::days(1);
    match tomorrow.and_hms_opt(0, 0, 0).and_then(|n| Local.from_local_datetime(&n).earliest()) {
        Some(m) => m.timestamp_millis(),
        None => now_ms + 24 * 3_600_000,
    }
}

fn clock(ms: i64) -> String {
    use chrono::{Local, TimeZone};
    Local.timestamp_millis_opt(ms).single().map(|t| t.format("%-I:%M %p").to_string().to_lowercase()).unwrap_or_default()
}

fn new_id() -> String {
    use sha2::{Digest, Sha256};
    static N: AtomicU64 = AtomicU64::new(0);
    let seed = format!("{}-{}-{}", portal::now_ms(), std::process::id(), N.fetch_add(1, Ordering::SeqCst));
    let h = Sha256::digest(seed.as_bytes());
    h.iter().take(4).map(|b| format!("{:02x}", b)).collect()
}

fn config_path(app: &AppHandle) -> PathBuf {
    let dir = app.path().app_config_dir().unwrap_or_else(|_| std::env::temp_dir().join("college-wifi"));
    let _ = std::fs::create_dir_all(&dir);
    dir.join("settings.json")
}

fn log_path(app: &AppHandle) -> PathBuf {
    let dir = app.path().app_log_dir().unwrap_or_else(|_| std::env::temp_dir().join("college-wifi"));
    dir.join("college-wifi.log")
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

fn get_password_why(user: &str) -> Result<String, String> {
    if user.is_empty() { return Err("no username".into()); }
    entry(user)?.get_password().map_err(|e| e.to_string())
}

fn kind_title(k: Kind) -> &'static str {
    match k {
        Kind::WrongPassword => "wrong username or password",
        Kind::LimitSession => "device / login limit reached",
        Kind::LimitQuota => "data or time limit reached",
        Kind::Expired => "account expired or disabled",
        Kind::Refused => "refused",
        Kind::Ok => "signed in",
        Kind::Unreadable => "unclear answer",
    }
}

/// How one sign-in chain ended.
enum Chain { SignedIn, AllRefused, Stopped }

impl Engine {
    fn new(app: AppHandle, demo: bool) -> Arc<Engine> {
        let mut settings: Settings = std::fs::read_to_string(config_path(&app)).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
        // v1.0 → v1.1: the single saved login becomes account 1 (its password stays where it is in the keychain)
        let legacy = settings.username.clone();
        let migrated = accounts::migrate(&mut settings.accounts, &legacy, &new_id());
        if migrated { settings.active_id = settings.accounts.first().map(|a| a.id.clone()); }
        let e = Arc::new(Engine {
            app,
            settings: Mutex::new(settings),
            view: Mutex::new(View { state: "offline".into(), title: "Checking…".into(), ..Default::default() }),
            busy: AtomicBool::new(false),
            paused: AtomicBool::new(false),
            fresh: AtomicBool::new(true),
            blocked: AtomicBool::new(false),
            usage_running: AtomicBool::new(false),
            generation: AtomicU64::new(0),
            tray_status: Mutex::new(None),
            log: diag::Log::default(),
            demo,
        });
        if !demo {
            if e.settings().log_file { e.log.set_file(Some(log_path(&e.app))); }
            e.log(&format!("College WiFi {} started on {} · portal {} · {} saved account(s){}", env!("CARGO_PKG_VERSION"), std::env::consts::OS,
                e.settings().portal, e.settings().accounts.len(), if migrated { " (your v1.0 login is now account 1)" } else { "" }));
            if migrated { e.save(); }
        }
        e
    }

    fn log(&self, line: &str) {
        self.log.add(now(), line);
    }

    fn save(&self) {
        let s = {
            let mut s = self.settings.lock().unwrap();
            // keep v1.0's field pointing at the active login, so an older build still finds one
            let active = s.active_id.clone();
            let u = s.accounts.iter().find(|a| Some(&a.id) == active.as_ref()).or(s.accounts.first()).map(|a| a.user.clone()).unwrap_or_default();
            s.username = u;
            s.clone()
        };
        if let Ok(j) = serde_json::to_string_pretty(&s) { let _ = std::fs::write(config_path(&self.app), j); }
    }

    fn settings(&self) -> Settings {
        self.settings.lock().unwrap().clone()
    }

    /// The account the portal last accepted here, else the primary.
    fn active_account(&self) -> Option<Account> {
        let s = self.settings();
        s.accounts.iter().find(|a| Some(&a.id) == s.active_id.as_ref()).or(s.accounts.first()).cloned()
    }

    fn store_account(&self, a: &Account) {
        {
            let mut s = self.settings.lock().unwrap();
            if let Some(x) = s.accounts.iter_mut().find(|x| x.id == a.id) { *x = a.clone(); }
        }
        self.save();
    }

    /// Fill in the settings-derived fields and push the view to the window and the tray.
    fn publish(&self, f: impl FnOnce(&mut View)) {
        let s = self.settings();
        let start_at_login = self.app.autolaunch().is_enabled().unwrap_or(false);
        let t = now();
        let active_id = s.active_id.clone().or_else(|| s.accounts.first().map(|a| a.id.clone()));
        let rows: Vec<AccountView> = s.accounts.iter().enumerate().map(|(i, a)| {
            let is_active = Some(&a.id) == active_id.as_ref();
            AccountView {
                id: a.id.clone(), user: a.user.clone(), index: i + 1, primary: i == 0, active: is_active,
                chip: accounts::chip(a, is_active, t, &clock), state: a.state.clone(), message: a.message.clone(),
                has_password: self.demo || get_password(&a.user).is_some(),
            }
        }).collect();
        let active_user = rows.iter().find(|r| r.active).map(|r| r.user.clone());
        let has_password = rows.iter().any(|r| r.has_password);
        let v = {
            let mut v = self.view.lock().unwrap();
            f(&mut v);
            v.busy = self.busy.load(Ordering::SeqCst);
            v.username = active_user.clone().unwrap_or_default();
            v.active_user = active_user;
            v.accounts = rows;
            v.has_password = has_password;
            v.auto_login = s.auto_login;
            v.theme = s.theme.clone();
            v.portal = s.portal.clone();
            v.ssids = s.ssids.clone();
            v.read_usage = s.read_usage;
            v.log_file = s.log_file;
            v.log_path = log_path(&self.app).to_string_lossy().into_owned();
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
        if state == "error" || state == "connected" || state == "signed_out" { self.log(&format!("{}: {}{}", state, title, if detail.is_empty() { String::new() } else { format!(" · {}", detail) })); }
        let (st, ti, de) = (state.to_string(), title.to_string(), detail.to_string());
        self.publish(move |v| { v.state = st; v.title = ti; v.detail = de; v.checked_at = Some(now()); });
    }

    fn busy_line(&self, title: &str, detail: &str) {
        let (ti, de) = (title.to_string(), detail.to_string());
        self.publish(move |v| { v.state = "busy".into(); v.title = ti; v.detail = de; });
    }

    /// Where are we? Wi-Fi name if the OS shares it, else a quick knock on the portal (no data sent). When neither
    /// says campus and this isn't the quiet timer, ask the connectivity probe: an intercepting Sophos portal names
    /// its own address, and a moved portal is adopted (Android's "Portal moved").
    fn locate(&self, reason: &str) -> (Option<String>, bool) {
        let s = self.settings();
        let ssid = net::current_ssid();
        let by_name = ssid.as_ref().map(|n| s.ssids.iter().any(|x| x.eq_ignore_ascii_case(n.trim()))).unwrap_or(false);
        if portal::portal_reachable(&s.portal, 900) { return (ssid, true); }
        if portal::via().is_some() || by_name {
            // the default route may not lead to the portal (VPN, hotspot, Ethernet): the Wi-Fi's own address might
            return (ssid, true);
        }
        if reason != "timer" {
            if let (Live::Out, Some(to)) = portal::probe() {
                if let Some(b) = portal::sophos_base_from(&to) {
                    if b != s.portal && portal::portal_reachable(&b, 1500) {
                        self.log(&format!("the network sent the connectivity check to {} · the portal is at {} now (was {})", to, b, s.portal));
                        { let mut st = self.settings.lock().unwrap(); st.portal = b; st.cert_pin = None; }
                        self.save();
                        return (ssid, true);
                    }
                }
            }
        }
        (ssid, false)
    }

    /// The one check: run on start, on every network change, every 60 s, and on demand.
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
        let (ssid, on_campus) = self.locate(reason);
        let s = self.settings();
        let was_on = self.view.lock().unwrap().on_campus;
        let prev_ssid = self.view.lock().unwrap().ssid.clone();
        if reason == "network" && (!was_on || prev_ssid != ssid) { self.paused.store(false, Ordering::SeqCst); }
        if was_on != on_campus || prev_ssid != ssid || reason != "timer" {
            self.log(&format!("check ({}): Wi-Fi {} · {}", reason, ssid.as_deref().map(|n| format!("\u{201c}{}\u{201d}", n)).unwrap_or_else(|| "name not shared by the system".into()),
                if on_campus { format!("campus portal {} answers", s.portal) } else { format!("portal {} doesn't answer", s.portal) }));
        }
        let network = if on_campus { "campus portal".to_string() } else { String::new() };
        {
            let (ss, nw) = (ssid.clone(), network.clone());
            self.publish(move |v| { v.ssid = ss; v.on_campus = on_campus; v.network = nw; });
        }
        if !on_campus {
            let words = match &ssid {
                Some(n) => format!("{} isn't the college network, and the portal at {} doesn't answer", n, s.portal),
                None => format!("Connect to the college Wi-Fi to sign in (the portal at {} doesn't answer from here)", s.portal),
            };
            self.set_state("away", "Not on college Wi-Fi", &words);
            return;
        }
        if s.accounts.is_empty() {
            self.set_state("setup", "Add your login", "Open Accounts in the sidebar to save your portal username and password");
            return;
        }
        let active = self.active_account();
        let user = active.as_ref().map(|a| a.user.clone()).unwrap_or_default();
        let live = portal::live(&s.portal, &user);
        // Sophos answers "live" per user; the probe says whether THIS computer actually has the internet
        let (net_now, _) = portal::probe();
        let state = match (live, net_now) {
            (_, Live::In) => Live::In,
            (Live::In, Live::Unknown) => Live::In,
            (Live::In, Live::Out) if self.blocked.load(Ordering::SeqCst) => Live::In,
            (Live::In, Live::Out) => {
                self.log(&format!("the portal says {} is signed in, but the internet is still intercepted on this computer", user));
                Live::Out
            }
            (l, _) => l,
        };
        match state {
            Live::In => {
                let detail = match &active { Some(a) => self.signed_in_words(a), None => "Signed in to the campus portal".into() };
                let was = self.view.lock().unwrap().state.clone();
                if was != "connected" { self.set_state("connected", "Connected", &detail); } else { self.publish(move |v| { v.detail = detail; v.checked_at = Some(now()); }); }
                self.paused.store(false, Ordering::SeqCst);
                self.maybe_usage(false);
            }
            _ if self.paused.load(Ordering::SeqCst) => {
                self.set_state("signed_out", "Logged out", "Logged out by you · auto-login paused until Wi-Fi reconnects or you tap the button");
            }
            _ if s.auto_login => { self.do_login(false); }
            _ => self.set_state("signed_out", "Not signed in", "Auto-login is off · tap the button to sign in"),
        }
    }

    fn signed_in_words(&self, a: &Account) -> String {
        let s = self.settings();
        let n = s.accounts.len();
        let i = accounts::index_of(&s.accounts, &a.id).map(|i| i + 1).unwrap_or(1);
        if n > 1 { format!("Signed in as {} · account {} of {}", a.user, i, n) } else { format!("Signed in as {}", a.user) }
    }

    /// One sign-in try for one account. Unreachable on the current route → the same request from each of this
    /// computer's own private addresses (the Wi-Fi adapter's), like Android binding the request to the Wi-Fi network.
    fn try_login(&self, base: &str, user: &str, pass: &str) -> Result<portal::Reply, portal::PortalError> {
        let first = portal::login(base, user, pass);
        let Err(portal::PortalError::Unreachable(why)) = &first else { return first };
        let current = portal::via();
        for ip in portal::local_ipv4s() {
            if Some(ip) == current { continue; }
            match portal::login_quick(base, user, pass, Some(ip)) {
                Err(portal::PortalError::Unreachable(_)) => continue,
                other => {
                    self.log(&format!("the default route didn't reach the portal ({}) · it answered from this computer's address {}, using that from now on", why, ip));
                    portal::set_via(Some(ip));
                    return other;
                }
            }
        }
        if current.is_some() {
            if let Ok(r) = portal::login_via(base, user, pass, None) { portal::set_via(None); return Ok(r); }
        }
        first
    }

    /// Is the internet open now? Two looks a moment apart (Sophos may intercept the very first request after login).
    fn internet_after_login(&self) -> Live {
        std::thread::sleep(Duration::from_millis(1200));
        let first = portal::internet();
        if first != Live::Out { return first; }
        std::thread::sleep(Duration::from_millis(2500));
        portal::internet()
    }

    /// Sign in: try the accounts in order until one is accepted (Android `Session.runChain`). A refusal moves on to the
    /// next account at once; an unreachable portal is retried every second for up to 15 s, then the chain stops.
    fn do_login(self: &Arc<Self>, manual: bool) -> bool {
        let s = self.settings();
        let all = s.accounts.clone();
        if all.is_empty() {
            self.set_state("setup", "Add your login", "Open Accounts in the sidebar to save your portal username and password");
            return false;
        }
        let fresh = self.fresh.swap(false, Ordering::SeqCst);
        let cands = accounts::candidates(&all, now(), fresh, manual);
        self.log(&format!("sign-in ({}): {} of {} account(s) to try{}", if manual { "you tapped" } else { "automatic" }, cands.len(), all.len(), if fresh { ", fresh Wi-Fi connect" } else { "" }));
        match self.run_chain(&s.portal, &all, &cands) {
            Chain::SignedIn => true,
            Chain::AllRefused => { self.all_refused(); false }
            Chain::Stopped => false,
        }
    }

    fn run_chain(self: &Arc<Self>, base: &str, all: &[Account], cands: &[Account]) -> Chain {
        let n = all.len();
        for (k, a) in cands.iter().enumerate() {
            let idx = accounts::index_of(all, &a.id).map(|i| i + 1).unwrap_or(1);
            let which = if n > 1 { format!(" (account {} of {})", idx, n) } else { String::new() };
            let pass = match get_password_why(&a.user) {
                Ok(p) => p,
                Err(e) => {
                    self.log(&format!("{}: no password found in {} ({}) · skipped", a.user, keychain_name(), e));
                    let mut x = a.clone();
                    x.state = "WRONG_PASSWORD".into(); x.until = i64::MAX; x.state_at = now();
                    x.message = format!("Password not found in {} · edit the account and enter it again", keychain_name());
                    self.store_account(&x);
                    continue;
                }
            };
            self.busy_line(if k == 0 { "Connecting…" } else { "Trying the next account…" }, &format!("Signing in as {}{}", a.user, which));
            self.log(&format!("signing in as {}{} at {}", a.user, which, base));
            let started = std::time::Instant::now();
            let result = loop {
                match self.try_login(base, &a.user, &pass) {
                    Err(portal::PortalError::Unreachable(why)) if started.elapsed() < Duration::from_secs(15) => {
                        self.log(&format!("no answer yet: {} · retrying", why));
                        std::thread::sleep(Duration::from_secs(1));
                    }
                    other => break other,
                }
            };
            match result {
                Ok(reply) => {
                    let (kind, words) = portal::verdict(&reply);
                    self.log(&format!("portal answered: status \u{201c}{}\u{201d}, message \u{201c}{}\u{201d} → {}", reply.status, diag::redact(&reply.message, Some(&pass)), kind_title(kind)));
                    let mut x = a.clone();
                    match kind {
                        Kind::Ok => {
                            let open = self.internet_after_login();
                            if open == Live::Out && k + 1 < cands.len() {
                                // v5.1 "blocked": signed in, but the portal intercepts the internet (a used-up data quota)
                                let t = now();
                                accounts::mark(&mut x, Kind::LimitQuota, "Signed in, but the portal blocked the internet (data limit used up?)", t, t + accounts::BLOCKED_RETRY_MS);
                                self.store_account(&x);
                                self.log(&format!("{} signed in but the internet stays blocked · signing it out and moving on", a.user));
                                let _ = portal::logout(base, &a.user);
                                continue;
                            }
                            accounts::mark(&mut x, Kind::Ok, "", now(), 0);
                            { let mut st = self.settings.lock().unwrap(); st.active_id = Some(x.id.clone()); }
                            self.store_account(&x);
                            self.paused.store(false, Ordering::SeqCst);
                            let blocked = open == Live::Out;
                            self.blocked.store(blocked, Ordering::SeqCst);
                            let detail = if blocked {
                                self.log(&format!("{} signed in, but the internet still looks blocked and there is no other account to try", a.user));
                                format!("{} · but the portal still blocks the internet (data limit used up?)", self.signed_in_words(&x))
                            } else { self.signed_in_words(&x) };
                            self.set_state("connected", "Connected", &detail);
                            self.maybe_usage(true);
                            return Chain::SignedIn;
                        }
                        Kind::Unreadable => {
                            self.set_state("error", "Portal rejected the login", &format!("It answered \u{201c}{}\u{201d} without saying why ({}). Will try again on the next Wi-Fi change or tap.", short(&reply.status), a.user));
                            return Chain::Stopped;
                        }
                        _ => {
                            accounts::mark(&mut x, kind, &words, now(), next_reset(now()));
                            self.store_account(&x);
                        }
                    }
                }
                Err(portal::PortalError::Unreachable(why)) => {
                    self.set_state("error", "Couldn't reach the portal", &format!("{} · tried for 15 s. Check you're on the college Wi-Fi (and that no VPN is on). Will try again on the next Wi-Fi change or tap.", capital(&why)));
                    return Chain::Stopped;
                }
                Err(e) => {
                    self.set_state("error", "Portal reply not understood", &format!("{} · will try again on the next Wi-Fi change or tap", e));
                    return Chain::Stopped;
                }
            }
        }
        Chain::AllRefused
    }

    /// Every candidate refused, or none was left to try (Android `Session.allRefused`).
    fn all_refused(&self) {
        let s = self.settings();
        let all = &s.accounts;
        let t = now();
        if accounts::all_quota(all, t) {
            let m = if all.len() == 1 { "Data limit reached on your account".to_string() } else { format!("Data limit reached on all {} accounts", all.len()) };
            self.set_state("error", &m, "Auto-login waits for the limit to reset (after midnight) · tap the button to retry now, or add another account");
            return;
        }
        if all.len() == 1 {
            let a = &all[0];
            let why = match a.state.as_str() {
                "WRONG_PASSWORD" => "wrong username or password · fix it under Accounts (it won't be retried until you do, so the portal never locks you out)",
                "EXPIRED" => "account expired or disabled",
                "LIMIT_SESSION" => "device / login limit · sign out on another device, or it retries in 15 min or on the next Wi-Fi connect",
                "LIMIT_QUOTA" => "data or time limit · retries after midnight",
                _ => "it retries in 15 min · tap the button to try now",
            };
            let said = if a.message.is_empty() { String::new() } else { format!("Portal said: \u{201c}{}\u{201d} · ", a.message) };
            self.set_state("error", "Portal rejected the login", &format!("{}{} ({})", said, why, a.user));
            return;
        }
        let summary: Vec<String> = all.iter().map(|a| format!("{}: {}", a.user, if a.state.is_empty() { "not tried".to_string() } else { accounts::chip(a, false, t, &clock).to_lowercase() })).collect();
        self.set_state("error", "Every account was refused", &format!("{} · open Accounts for what the portal said", summary.join(" · ")));
    }

    fn do_logout(self: &Arc<Self>) {
        let s = self.settings();
        let user = self.active_account().map(|a| a.user).unwrap_or_default();
        self.publish(|v| { v.state = "busy".into(); v.title = "Logging out…".into(); v.detail = String::new(); });
        self.paused.store(true, Ordering::SeqCst);
        self.log(&format!("logging out {}", user));
        match portal::logout(&s.portal, &user) {
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
            if login { me.paused.store(false, Ordering::SeqCst); me.do_login(true); } else { me.do_logout(); }
            me.busy.store(false, Ordering::SeqCst);
            me.publish(|_| {});
        });
    }

    /// "Use now": this account becomes the primary, the current one is signed out, and this one signs in.
    pub fn switch_to(self: &Arc<Self>, id: String) {
        if self.demo { return; }
        let me = self.clone();
        std::thread::spawn(move || {
            // wait (bounded) for a running check to finish
            let t0 = std::time::Instant::now();
            while me.busy.swap(true, Ordering::SeqCst) {
                if t0.elapsed() > Duration::from_secs(30) { return; }
                std::thread::sleep(Duration::from_millis(200));
            }
            let s = me.settings();
            let target = s.accounts.iter().find(|a| a.id == id).cloned();
            if let Some(target) = target {
                let current = me.active_account();
                let connected = me.view.lock().unwrap().state == "connected";
                me.log(&format!("switching to {} (you chose it)", target.user));
                if connected {
                    if let Some(c) = current.filter(|c| c.id != target.id) {
                        me.busy_line("Switching account…", &format!("Signing out {}", c.user));
                        let r = portal::logout(&s.portal, &c.user);
                        me.log(&format!("sign-out of {} → {}", c.user, match r { Some(true) => "portal says signed out", Some(false) => "portal answered without confirming", None => "no answer, moving on anyway" }));
                    }
                }
                me.paused.store(false, Ordering::SeqCst);
                me.do_login(true);
            }
            me.busy.store(false, Ordering::SeqCst);
            me.publish(|_| {});
        });
    }

    /// Read the User Portal's usage table (at most every 5 min unless forced) for the active account.
    fn maybe_usage(self: &Arc<Self>, force: bool) {
        let s = self.settings();
        let Some(acc) = self.active_account() else { return };
        if !s.read_usage { return; }
        if !force { if let Some(at) = s.quota_at { if now() - at < USAGE_MIN_GAP_MS { return; } } }
        if self.usage_running.swap(true, Ordering::SeqCst) { return; }
        let me = self.clone();
        std::thread::spawn(move || {
            let s = me.settings();
            let host = portal::host_port(&s.portal).map(|h| h.0).unwrap_or_default();
            let Some(pass) = get_password(&acc.user) else { me.usage_running.store(false, Ordering::SeqCst); return; };
            let out = userportal::fetch(&me.app, &host, &acc.user, &pass, s.cert_pin.clone());
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
            if let Some(n) = &note { me.log(n); }
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

fn short(s: &str) -> String {
    portal::short_msg(s)
}

fn capital(s: &str) -> String {
    let mut c = s.chars();
    match c.next() { Some(f) => f.to_uppercase().collect::<String>() + c.as_str(), None => String::new() }
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

/// Add a login (id None) or edit one. An empty password on edit keeps the saved one.
#[tauri::command]
fn save_account(engine: E, id: Option<String>, username: String, password: String) -> Result<String, String> {
    if engine.demo { return Ok(id.unwrap_or_default()); }
    let username = username.trim().to_string();
    if username.is_empty() { return Err("Enter the portal username".into()); }
    let s = engine.settings();
    if accounts::has_user(&s.accounts, &username, id.as_deref()) { return Err(format!("{} is already saved", username)); }
    let existing = id.as_ref().and_then(|i| s.accounts.iter().find(|a| &a.id == i).cloned());
    if id.is_some() && existing.is_none() { return Err("That account is gone · reopen Accounts".into()); }
    let pass = if !password.is_empty() {
        password
    } else if let Some(old) = &existing {
        get_password(&old.user).ok_or_else(|| "Enter the password (none is saved for this account)".to_string())?
    } else {
        return Err("Enter the password".into());
    };
    entry(&username)?.set_password(&pass).map_err(|e| format!("Couldn't save to {}: {}", keychain_name(), e))?;
    // read it back: a keychain that accepts and then forgets is the one failure the portal can't explain
    if get_password(&username).as_deref() != Some(pass.as_str()) {
        return Err(format!("{} didn't keep the password · try again", keychain_name()));
    }
    drop(pass);
    let new_id_v = {
        let mut st = engine.settings.lock().unwrap();
        match &existing {
            Some(old) => {
                if old.user != username { if let Ok(e) = entry(&old.user) { let _ = e.delete_credential(); } }
                if let Some(a) = st.accounts.iter_mut().find(|a| a.id == old.id) { a.user = username.clone(); a.clear(); }
                if st.active_id.as_ref() == Some(&old.id) && old.user != username { st.quota = None; st.quota_at = None; }
                old.id.clone()
            }
            None => {
                let a = Account::new(&new_id(), &username);
                let i = a.id.clone();
                if st.accounts.is_empty() { st.active_id = Some(i.clone()); st.quota = None; st.quota_at = None; }
                st.accounts.push(a);
                i
            }
        }
    };
    engine.log(&format!("{} {} (password saved in {})", if existing.is_some() { "edited account" } else { "added account" }, username, keychain_name()));
    engine.save();
    engine.paused.store(false, Ordering::SeqCst);
    engine.publish(|_| {});
    engine.check("login-saved");
    Ok(new_id_v)
}

#[tauri::command]
fn remove_account(engine: E, id: String) -> Result<(), String> {
    let gone = {
        let mut st = engine.settings.lock().unwrap();
        let Some(i) = accounts::index_of(&st.accounts, &id) else { return Err("That account is already gone".into()) };
        let a = st.accounts.remove(i);
        if st.active_id.as_ref() == Some(&id) { st.active_id = st.accounts.first().map(|a| a.id.clone()); st.quota = None; st.quota_at = None; }
        a
    };
    if let Ok(e) = entry(&gone.user) { let _ = e.delete_credential(); }
    engine.log(&format!("removed account {} (its password was deleted from {})", gone.user, keychain_name()));
    engine.save();
    let empty = engine.settings().accounts.is_empty();
    engine.publish(move |v| { if empty { v.usage = None; v.state = "setup".into(); v.title = "Add your login".into(); v.detail = "Open Accounts in the sidebar to save your portal username and password".into(); } });
    Ok(())
}

/// Reorder: by = -1 (up) / +1 (down); by = 0 makes it the primary.
#[tauri::command]
fn move_account(engine: E, id: String, by: i32) -> Result<(), String> {
    {
        let mut st = engine.settings.lock().unwrap();
        let ok = if by == 0 { accounts::make_primary(&mut st.accounts, &id) } else { accounts::shift(&mut st.accounts, &id, by) };
        if !ok { return Err("Can't move it there".into()); }
    }
    engine.save();
    engine.publish(|_| {});
    Ok(())
}

/// Use this account now: make it the primary and sign in with it (signing the current one out first).
#[tauri::command]
fn use_account(engine: E, id: String) -> Result<(), String> {
    {
        let mut st = engine.settings.lock().unwrap();
        if !accounts::make_primary(&mut st.accounts, &id) { return Err("That account is gone".into()); }
        // the user asked for it: what the portal said last time is worth one more try
        if let Some(a) = st.accounts.iter_mut().find(|a| a.id == id) { if !a.parked() { a.clear(); } }
    }
    engine.save();
    engine.publish(|_| {});
    engine.switch_to(id);
    Ok(())
}

#[tauri::command]
fn get_log(engine: E) -> String {
    let s = engine.settings();
    let v = engine.view.lock().unwrap().clone();
    let head = format!("College WiFi {} · {} · portal {} · Wi-Fi {} · {} account(s) · state: {} — {}\n",
        env!("CARGO_PKG_VERSION"), std::env::consts::OS, s.portal, v.ssid.unwrap_or_else(|| "?".into()), s.accounts.len(), v.title, v.detail);
    head + &engine.log.text()
}

#[derive(Deserialize)]
struct Patch {
    auto_login: Option<bool>,
    theme: Option<String>,
    portal: Option<String>,
    ssids: Option<Vec<String>>,
    read_usage: Option<bool>,
    start_at_login: Option<bool>,
    log_file: Option<bool>,
}

#[tauri::command]
fn set_settings(engine: E, patch: Patch) -> Result<(), String> {
    if let Some(on) = patch.start_at_login {
        let al = engine.app.autolaunch();
        if on { al.enable() } else { al.disable() }.map_err(|e| e.to_string())?;
    }
    let mut portal_changed = false;
    {
        let mut s = engine.settings.lock().unwrap();
        if let Some(v) = patch.auto_login { s.auto_login = v; }
        if let Some(v) = patch.theme { if ["light", "dark", "system"].contains(&v.as_str()) { s.theme = v; } }
        if let Some(v) = patch.portal {
            let v = v.trim().trim_end_matches('/').to_string();
            let v = if v.starts_with("http://") || v.starts_with("https://") { v } else { format!("http://{}", v) };
            let v = match url::Url::parse(&v) { Ok(u) if u.host_str().is_some() => format!("{}://{}{}", u.scheme(), u.host_str().unwrap(), u.port().map(|p| format!(":{}", p)).unwrap_or_default()), _ => return Err("That portal address doesn't look right".into()) };
            if v != s.portal { s.cert_pin = None; portal_changed = true; }
            s.portal = v;
        }
        if let Some(v) = patch.ssids { s.ssids = v.into_iter().map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect(); }
        if let Some(v) = patch.read_usage { s.read_usage = v; }
        if let Some(v) = patch.log_file { s.log_file = v; }
    }
    if let Some(on) = patch.log_file {
        engine.log.set_file(if on { Some(log_path(&engine.app)) } else { None });
        engine.log(if on { "log file on" } else { "log file off" });
    }
    if portal_changed { portal::set_via(None); engine.log(&format!("portal address set to {}", engine.settings().portal)); }
    engine.save();
    engine.publish(|_| {});
    if portal_changed { engine.check("portal-changed"); }
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
    let mut a2 = Account::new("d2", "21bcs077");
    accounts::mark(&mut a2, Kind::LimitQuota, "Your data transfer has been exceeded", now() - 50 * 60_000, now() + 6 * 3_600_000);
    Settings {
        username: "21bcs001".into(),
        accounts: vec![Account::new("d1", "21bcs001"), a2, Account::new("d3", "21bcs123")],
        active_id: Some("d1".into()),
        quota: Some(q),
        quota_at: Some(now() - 4 * 60_000),
        ..Default::default()
    }
}

pub fn run() {
    let demo = std::env::var("COLLEGE_WIFI_DEMO").map(|v| v == "1").unwrap_or(false);
    let hidden = std::env::args().any(|a| a == "--hidden");
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .plugin(tauri_plugin_autostart::init(tauri_plugin_autostart::MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .invoke_handler(tauri::generate_handler![get_state, toggle, refresh, save_account, remove_account, move_account, use_account, get_log, set_settings, quit])
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
                    v.state = "connected".into(); v.title = "Connected".into(); v.detail = "Signed in as 21bcs001 · account 1 of 3".into();
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
                        while let Some(ev) = w.next().await {
                            if ev.is_err() { continue; }
                            // a join produces a burst of address events: wait until it has been quiet for 2.5 s, then look once
                            let e = e3.clone();
                            let g = e.generation.fetch_add(1, Ordering::SeqCst) + 1;
                            tauri::async_runtime::spawn(async move {
                                tokio::time::sleep(Duration::from_millis(2500)).await;
                                if e.generation.load(Ordering::SeqCst) == g {
                                    e.fresh.store(true, Ordering::SeqCst);
                                    e.blocked.store(false, Ordering::SeqCst);
                                    portal::set_via(None);
                                    e.check("network");
                                }
                            });
                        }
                    }
                    Err(err) => { e3.log(&format!("network change events unavailable ({err}); relying on the 60 s check")); }
                }
            });
            // light check every 60 s: on campus it re-checks the sign-in; off campus it is only the Wi-Fi name and a
            // TCP knock on the portal, so a missed network event can't leave the app stuck on "Not on college Wi-Fi"
            let e4 = engine.clone();
            tauri::async_runtime::spawn(async move {
                loop {
                    tokio::time::sleep(CHECK_EVERY).await;
                    e4.check("timer");
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
