//! The campus captive portal: a faithful port of the Android app's `Portal` vocabulary, `SophosProtocol`
//! (login.xml mode 191, live mode 192, logout mode 193) and `Detect`. The network calls use exactly the same
//! form fields, order, Referer and timestamps as the Android PortalClient. No logging of credentials.
use regex::Regex;
use serde::Serialize;
use std::sync::OnceLock;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

fn re(cell: &'static OnceLock<Regex>, pat: &str) -> &'static Regex {
    cell.get_or_init(|| Regex::new(pat).expect("regex"))
}

/// IIIT Dharwad's Sophos portal (the Android SophosProtocol.BASE).
pub const DEFAULT_BASE: &str = "http://172.16.16.16:8090";
pub const DEFAULT_SSID: &str = "IIIT DHARWAD-STUDENT";

// ---------------------------------------------------------------- vocabulary (Portal.java)
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum Kind { Ok, WrongPassword, LimitSession, LimitQuota, Expired, Refused, Unreadable }

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum Live { In, Out, Unknown }

const WRONG: &[&str] = &["invalid user","invalid username","invalid login","invalid credential","incorrect password","incorrect user","wrong password","wrong username",
 "password is incorrect","password incorrect","bad password","authentication failed","authentication failure","auth failed","firewall authentication failed","login failed. please check",
 "could not log you on","login failed","make sure your password is correct","user not found","unknown user","no such user","invalid password","username or password","user name or password","not authorized"];
const SESSION: &[&str] = &["maximum login limit","max login limit","login limit","maximum number of login","maximum number of devices","max devices","device limit","devices allowed",
 "maximum number of sessions","concurrent","simultaneous","no more sessions","already logged in from","already logged in on","already in use","maximum number of users","session limit",
 "too many sessions","too many devices","limit of devices","user is already logged in"];
const QUOTA: &[&str] = &["data transfer limit","data limit","data quota","quota","volume limit","traffic limit","bandwidth limit","usage limit","download limit","upload limit",
 "time limit","surfing time","allotted time","uptime limit","access time","time quota","exceeded","exhausted","used up","limit reached","limit exceeded","limit has been reached"];
const EXPIRED: &[&str] = &["account has expired","account expired","has expired","validity","account is disabled","account disabled","account locked","account is locked","not active","inactive","suspended"];
const SUCCESS: &[&str] = &["you are signed in","you have successfully logged in","successfully logged in","you are logged in","you are now logged in","login successful","logged in successfully",
 "authentication successful","successfully authenticated","you are connected","you're connected","connected to the internet"];

fn any(m: &str, keys: &[&str]) -> bool {
    keys.iter().any(|k| m.contains(k))
}

/// A portal's refusal text → kind. Specific limit/expiry phrases first, then wrong-password words, else Refused.
pub fn refusal(text: &str) -> Kind {
    let m = text.to_lowercase();
    if any(&m, EXPIRED) { return Kind::Expired; }
    if any(&m, SESSION) { return Kind::LimitSession; }
    if any(&m, QUOTA) { return Kind::LimitQuota; }
    if any(&m, WRONG) { return Kind::WrongPassword; }
    Kind::Refused
}

pub fn looks_signed_in(text: &str) -> bool {
    let m = text.to_lowercase();
    if m.contains("not logged") || m.contains("not signed") || m.contains("log in again") {
        return false;
    }
    any(&m, SUCCESS)
}

pub fn short_msg(m: &str) -> String {
    static WS: OnceLock<Regex> = OnceLock::new();
    let m = re(&WS, r"\s+").replace_all(m, " ").trim().to_string();
    if m.chars().count() > 140 { format!("{}\u{2026}", m.chars().take(137).collect::<String>()) } else { m }
}

pub const SOPHOS: &str = "sophos";

pub fn name(kind: &str) -> &'static str {
    match kind {
        "sophos" => "Sophos / Cyberoam", "fortinet" => "FortiGate", "pfsense" => "pfSense", "opnsense" => "OPNsense",
        "mikrotik" => "MikroTik Hotspot", "aruba" => "Aruba", "ciscowlc" => "Cisco WLC", "meraki" => "Cisco Meraki",
        "ruckus" => "Ruckus", "nomadix" => "Nomadix", _ => "Login page",
    }
}

/// Fingerprint from the final login-page URL, its HTML and every URL seen on the way (Portal.fingerprint).
pub fn fingerprint(url: &str, html: &str, chain: &[String]) -> &'static str {
    static P800: OnceLock<Regex> = OnceLock::new();
    let u = url.to_lowercase();
    let h = html.to_lowercase();
    let mut a = u.clone();
    for c in chain { a.push(' '); a.push_str(&c.to_lowercase()); }
    if a.contains("/httpclient.html") || h.contains("login.xml") || h.contains("cyberoam") || (h.contains("sophos") && h.contains("producttype")) || a.contains(":8090/") { return "sophos"; }
    if a.contains("fgtauth") || h.contains("4tredir") || h.contains("name=\"magic\"") || h.contains("name=magic") || h.contains("fortinet") || h.contains("fortigate") { return "fortinet"; }
    if h.contains("/api/captiveportal/access") || h.contains("opnsense") { return "opnsense"; }
    if (h.contains("auth_user") && h.contains("auth_pass")) || h.contains("$portal_action$") || (re(&P800, r":800[23]/").is_match(&a) && a.contains("zone=")) { return "pfsense"; }
    if h.contains("chap-challenge") || h.contains("hexmd5(") || h.contains("$(link-login") || h.contains("mikrotik") || h.contains("document.sendin") || h.contains("name=\"sendin\"") { return "mikrotik"; }
    if a.contains("network-auth.com") || h.contains("meraki") { return "meraki"; }
    if a.contains("/cgi-bin/login") || a.contains("securelogin.arubanetworks.com") || h.contains("arubanetworks") || h.contains("clearpass") || h.contains("cmd=authenticate") { return "aruba"; }
    if (h.contains("buttonclicked") && h.contains("err_flag")) || a.contains("1.1.1.1/login.html") || a.contains("/fs/customwebauth") { return "ciscowlc"; }
    if a.contains("ruckus") || h.contains("ruckus") || a.contains(":9997/") || a.contains("/user/_allowuser.jsp") || a.contains("subscriberportal") { return "ruckus"; }
    if h.contains("nomadix") || a.contains("nomadix") { return "nomadix"; }
    "generic"
}

// ---------------------------------------------------------------- Sophos wire format (SophosProtocol.java)
#[derive(Clone, Debug, PartialEq)]
pub struct Reply { pub status: String, pub message: String, pub http_code: u16 }

fn unescape(s: &str) -> String {
    s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}

fn find(pat: &'static OnceLock<Regex>, p: &str, s: &str) -> bool {
    re(pat, p).is_match(s)
}

impl Reply {
    pub fn new(status: &str, message: &str, http: u16) -> Reply {
        Reply { status: status.trim().to_string(), message: unescape(message).trim().to_string(), http_code: http }
    }
    pub fn success(&self) -> bool {
        static R: OnceLock<Regex> = OnceLock::new();
        let m = self.message.to_lowercase();
        self.status.eq_ignore_ascii_case("LIVE")
            || (!m.contains("not signed") && !m.contains("not logged") && !m.contains("failed") && !m.contains("signed out") && !m.contains("logged out")
                && find(&R, r"(?s)signed\s+in|logged\s+in|successfully\s+authenticated", &m))
    }
    pub fn rejected(&self) -> bool {
        let c = self.code();
        c == "REJECTED" || c == "LOGIN_LIMIT" || c == "DATA_LIMIT"
    }
    pub fn signed_out(&self) -> bool {
        static R: OnceLock<Regex> = OnceLock::new();
        let m = self.message.to_lowercase();
        if self.status.eq_ignore_ascii_case("LOGOUT") { return true; }
        if find(&R, r"(?s)(?:signed|logged)\s*(?:out|off)", &m) { return true; }
        self.status.eq_ignore_ascii_case("LOGIN") && m.is_empty()
    }
    pub fn code(&self) -> &'static str {
        if self.success() { return "PORTAL_CONFIRMED"; }
        let m = self.message.to_lowercase();
        if m.contains("maximum login") || m.contains("login limit") { return "LOGIN_LIMIT"; }
        if m.contains("data transfer") || m.contains("quota") || m.contains("bandwidth limit") { return "DATA_LIMIT"; }
        if m.contains("invalid") || m.contains("incorrect") || m.contains("wrong") || m.contains("denied") || m.contains("failed") { return "REJECTED"; }
        "PORTAL_REJECTED"
    }
}

/// DOM first (no DTDs, no entities), then a tolerant regex for slightly malformed portal XML.
pub fn parse(body: &str, http: u16) -> Option<Reply> {
    static ST: OnceLock<Regex> = OnceLock::new();
    static MS: OnceLock<Regex> = OnceLock::new();
    if body.trim().is_empty() { return None; }
    if let Ok(doc) = roxmltree::Document::parse(body.trim()) {
        let text = |name: &str| -> String {
            doc.descendants().find(|n| n.is_element() && n.tag_name().name() == name)
                .map(|n| n.descendants().filter(|d| d.is_text()).filter_map(|d| d.text()).collect::<String>().trim().to_string())
                .unwrap_or_default()
        };
        let (s, m) = (text("status"), text("message"));
        if !s.is_empty() || !m.is_empty() { return Some(Reply::new(&s, &m, http)); }
    }
    let grab = |r: &Regex| r.captures(body).map(|c| c[1].replace("&#39;", "'").replace("&amp;", "&").trim().to_string()).unwrap_or_default();
    let s = grab(re(&ST, r"(?is)<status>\s*(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?\s*</status>"));
    let m = grab(re(&MS, r"(?is)<message>\s*(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?\s*</message>"));
    if s.is_empty() && m.is_empty() { None } else { Some(Reply::new(&s, &m, http)) }
}

/// Sophos messages are templates ("You are signed in as {username}"): fill the user, drop any other placeholder.
pub fn fill(r: Option<Reply>, user: &str) -> Option<Reply> {
    static U: OnceLock<Regex> = OnceLock::new();
    static P: OnceLock<Regex> = OnceLock::new();
    static WS: OnceLock<Regex> = OnceLock::new();
    let r = r?;
    if !r.message.contains('{') { return Some(r); }
    let m = re(&U, r"(?i)\{\s*user(?:name)?\s*\}").replace_all(&r.message, regex::NoExpand(user)).to_string();
    let m = re(&P, r"\{[A-Za-z_]+\}").replace_all(&m, "").to_string();
    let m = re(&WS, r"\s{2,}").replace_all(&m, " ").trim().to_string();
    Some(Reply::new(&r.status, &m, r.http_code))
}

pub fn parse_live(raw: &str) -> Live {
    static OUT: OnceLock<Regex> = OnceLock::new();
    static IN: OnceLock<Regex> = OnceLock::new();
    let r = raw.to_lowercase();
    if r.contains("login_again") || find(&OUT, r"(?s)<loginstatus>\s*(?:<!\[cdata\[)?\s*false", &r) || r.contains("relogon") { return Live::Out; }
    if find(&IN, r"(?s)<ack>\s*(?:<!\[cdata\[)?\s*ack\b", &r) || r.contains("<liverequesttime>") { return Live::In; }
    Live::Unknown
}

/// java.net.URLEncoder.encode(s, "UTF-8"): space → '+', [A-Za-z0-9.*_-] kept, everything else %XX.
pub fn java_urlencode(s: &str) -> String {
    let mut o = String::with_capacity(s.len() * 3);
    for b in s.as_bytes() {
        match *b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => o.push(*b as char),
            b' ' => o.push('+'),
            x => o.push_str(&format!("%{:02X}", x)),
        }
    }
    o
}

pub fn now_ms() -> u128 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0)
}

/// The exact body the Android app posts: mode, username, [password], a (epoch ms), producttype=0.
pub fn form_body(mode: &str, user: &str, pass: Option<&str>, a: u128) -> String {
    format!("mode={}&username={}{}&a={}&producttype=0", mode, java_urlencode(user),
        pass.map(|p| format!("&password={}", java_urlencode(p))).unwrap_or_default(), a)
}

pub fn live_path(user: &str, a: u128) -> String {
    format!("/live?mode=192&username={}&a={}&producttype=0", java_urlencode(user), a)
}

#[derive(Debug)]
pub enum PortalError {
    /// Nothing answered (not on the campus network, the portal is down, or the request never left this computer).
    /// Carries the reason in plain words, e.g. "no answer from 172.16.16.16:8090 (timed out)".
    Unreachable(String),
    /// The portal answered, but with an HTTP error / redirect / page we cannot read. Carries a short excerpt.
    Http(u16, String),
    Unparseable(u16, String),
}

impl std::fmt::Display for PortalError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PortalError::Unreachable(why) => write!(f, "Couldn't reach the portal: {}", why),
            PortalError::Http(c, said) if said.is_empty() => write!(f, "The portal answered HTTP {} instead of a sign-in reply", c),
            PortalError::Http(c, said) => write!(f, "The portal answered HTTP {} instead of a sign-in reply: \u{201c}{}\u{201d}", c, said),
            PortalError::Unparseable(c, said) if said.is_empty() => write!(f, "The portal's reply wasn't a sign-in answer (HTTP {})", c),
            PortalError::Unparseable(c, said) => write!(f, "The portal's reply wasn't a sign-in answer (HTTP {}): \u{201c}{}\u{201d}", c, said),
        }
    }
}

/// Same headers as the Android app's `Http` (a browser-like Accept, an honest user agent).
pub const USER_AGENT: &str = "Mozilla/5.0 (Desktop) CollegeWiFi/1.1 ParadoxWiFi/5";
const ACCEPT: &str = "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8";

static VIA: std::sync::Mutex<Option<std::net::IpAddr>> = std::sync::Mutex::new(None);

/// The local address the portal last answered on when the default route didn't reach it (None = default route).
pub fn via() -> Option<std::net::IpAddr> { *VIA.lock().unwrap() }
pub fn set_via(v: Option<std::net::IpAddr>) { *VIA.lock().unwrap() = v; }

pub fn client(connect_ms: u64, read_ms: u64) -> reqwest::blocking::Client {
    client_via(connect_ms, read_ms, via())
}

/// `via` = send from this local address (the Wi-Fi adapter's), the desktop twin of Android binding the request to
/// the Wi-Fi network. Needed when a VPN, a phone hotspot or Ethernet owns the default route.
pub fn client_via(connect_ms: u64, read_ms: u64, via: Option<std::net::IpAddr>) -> reqwest::blocking::Client {
    let mut b = reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_millis(connect_ms))
        .timeout(Duration::from_millis(connect_ms + read_ms))
        .redirect(reqwest::redirect::Policy::none())
        .no_proxy()
        .user_agent(USER_AGENT);
    if via.is_some() { b = b.local_address(via); }
    b.build().expect("http client")
}

fn read_capped(resp: reqwest::blocking::Response, cap: usize) -> String {
    use std::io::Read;
    let mut buf = Vec::new();
    let _ = resp.take(cap as u64).read_to_end(&mut buf);
    String::from_utf8_lossy(&buf).into_owned()
}

/// The deepest cause of a failed request, in plain words ("timed out", "connection refused", …).
pub fn why(e: &reqwest::Error, base: &str) -> String {
    use std::error::Error;
    let mut chain = vec![e.to_string()];
    let mut cur: Option<&(dyn Error + 'static)> = e.source();
    while let Some(c) = cur { chain.push(c.to_string()); cur = c.source(); }
    let all = chain.join(" | ").to_lowercase();
    let at = host_port(base).map(|(h, p)| format!("{}:{}", h, p)).unwrap_or_else(|| base.to_string());
    let plain = if e.is_timeout() || all.contains("timed out") || all.contains("10060") {
        "no answer (timed out)".to_string()
    } else if all.contains("refused") || all.contains("10061") {
        "the connection was refused".to_string()
    } else if all.contains("unreachable") || all.contains("no route") || all.contains("10051") || all.contains("10065") {
        "no route to it from this computer".to_string()
    } else if all.contains("dns") || all.contains("resolve") || all.contains("lookup") || all.contains("11001") {
        "its name couldn't be looked up".to_string()
    } else if all.contains("address not available") || all.contains("10049") {
        "this computer's Wi-Fi address isn't usable yet".to_string()
    } else if all.contains("reset") || all.contains("10054") || all.contains("closed") {
        "the connection was dropped".to_string()
    } else {
        short_msg(chain.last().map(|s| s.as_str()).unwrap_or("unknown error"))
    };
    format!("{} at {}", plain, at)
}

/// Plain text of an HTML page (scripts and styles dropped), for reading a portal's notice.
pub fn html_text(html: &str) -> String {
    static S: OnceLock<Regex> = OnceLock::new();
    static T: OnceLock<Regex> = OnceLock::new();
    static E: OnceLock<Regex> = OnceLock::new();
    let s = re(&S, r"(?is)<(script|style|noscript)[^>]*>.*?</(script|style|noscript)>").replace_all(html, " ");
    let s = re(&T, r"(?s)<[^>]*>").replace_all(&s, " ");
    let s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">");
    re(&E, r"\s+").replace_all(&s, " ").trim().to_string()
}

/// Android `Drivers.Sophos.pageVerdict` (v5.1): login.xml answered with an HTML page or a redirect instead of XML
/// (seen when a quota runs out). A clear limit / expiry sentence on that page is a refusal of THIS account, so the
/// failover moves on; anything else stays unreadable. Limits first: a block page that also says "validity" must not
/// park the account.
pub fn page_verdict(body: &str, location: &str) -> Option<Reply> {
    let text = html_text(body);
    let text: String = text.chars().take(2000).collect();
    let loc = location.replace(['_', '-'], " ");
    for t in [text.as_str(), loc.as_str()] {
        let m = t.to_lowercase();
        if m.is_empty() { continue; }
        let hit = any(&m, QUOTA) || any(&m, SESSION) || m.contains("account has expired") || m.contains("account expired");
        if hit {
            let said = if t == loc { format!("Portal redirected to {}", location) } else { short_msg(t) };
            return Some(Reply::new("LOGIN", &said, 200));
        }
    }
    None
}

/// POST to a fixed path on the portal. pass == None omits the password field (logout).
pub fn post(base: &str, path: &str, mode: &str, user: &str, pass: Option<&str>, connect_ms: u64, read_ms: u64) -> Result<Reply, PortalError> {
    post_via(base, path, mode, user, pass, connect_ms, read_ms, via())
}

#[allow(clippy::too_many_arguments)]
pub fn post_via(base: &str, path: &str, mode: &str, user: &str, pass: Option<&str>, connect_ms: u64, read_ms: u64, via: Option<std::net::IpAddr>) -> Result<Reply, PortalError> {
    let body = form_body(mode, user, pass, now_ms());
    let c = client_via(connect_ms, read_ms, via);
    let resp = c
        .post(format!("{}{}", base, path))
        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        .header("Accept", ACCEPT)
        .header("Referer", format!("{}/httpclient.html", base))
        .body(body)
        .send()
        .map_err(|e| PortalError::Unreachable(why(&e, base)))?;
    let http = resp.status().as_u16();
    let location = resp.headers().get("location").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
    let scrub = |s: String| match pass { Some(p) if !p.is_empty() => s.replace(p, "[redacted]"), _ => s };
    let text = scrub(read_capped(resp, 65536));
    match parse(&text, http) {
        Some(r) => Ok(fill(Some(r), user).unwrap()),
        None => {
            // a redirect or an HTML page: follow it (twice at most, GET, as Android does) and read its notice
            let mut page = text.clone();
            let mut loc = location.clone();
            let mut hops = 0;
            while (300..400).contains(&http) && !loc.is_empty() && hops < 2 {
                hops += 1;
                let next = url::Url::parse(base).ok().and_then(|b| b.join(&loc).ok());
                let Some(next) = next else { break };
                match c.get(next.as_str()).header("Accept", ACCEPT).send() {
                    Ok(r2) => {
                        let st = r2.status().as_u16();
                        let l2 = r2.headers().get("location").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
                        page = scrub(read_capped(r2, 65536));
                        if (300..400).contains(&st) && !l2.is_empty() { loc = l2; } else { break; }
                    }
                    Err(_) => break,
                }
            }
            if let Some(r) = page_verdict(&page, &location) { return Ok(r); }
            let said: String = html_text(&page).chars().take(120).collect();
            let said = if said.is_empty() && !location.is_empty() { format!("redirected to {}", short_msg(&location)) } else { said };
            if http >= 300 { Err(PortalError::Http(http, said)) } else { Err(PortalError::Unparseable(http, said)) }
        }
    }
}

pub fn login(base: &str, user: &str, pass: &str) -> Result<Reply, PortalError> {
    login_via(base, user, pass, via())
}

pub fn login_via(base: &str, user: &str, pass: &str, via: Option<std::net::IpAddr>) -> Result<Reply, PortalError> {
    post_via(base, "/login.xml", "191", user, Some(pass), 5000, 8000, via)
}

/// One sign-in try on a given local address with a short connect wait (the fallback across adapters).
pub fn login_quick(base: &str, user: &str, pass: &str, via: Option<std::net::IpAddr>) -> Result<Reply, PortalError> {
    post_via(base, "/login.xml", "191", user, Some(pass), 2500, 8000, via)
}

/// This computer's own private IPv4 addresses (the Wi-Fi adapter's among them), for `client_via`.
pub fn local_ipv4s() -> Vec<std::net::IpAddr> {
    let mut out = Vec::new();
    if let Ok(list) = if_addrs::get_if_addrs() {
        for i in list {
            if let std::net::IpAddr::V4(v4) = i.ip() {
                if v4.is_loopback() || v4.is_link_local() || v4.is_unspecified() { continue; }
                if !v4.is_private() { continue; }
                let ip = std::net::IpAddr::V4(v4);
                if !out.contains(&ip) { out.push(ip); }
            }
        }
    }
    out
}

/// Logout (mode 193, username only): /logout.xml first, then /login.xml (as Drivers.Sophos.logout).
/// Some(true) = the portal confirmed; Some(false) = it answered but not with "signed out"; None = nothing answered.
pub fn logout(base: &str, user: &str) -> Option<bool> {
    let mut answered = false;
    for path in ["/logout.xml", "/login.xml"] {
        match post(base, path, "193", user, None, 4000, 6000) {
            Ok(r) if r.signed_out() => return Some(true),
            Ok(_) => answered = true,
            Err(PortalError::Unreachable(_)) => {}
            Err(_) => answered = true,
        }
    }
    if answered { Some(false) } else { None }
}

/// Sophos keep-alive / "still signed in?" (GET /live, username only).
pub fn live(base: &str, user: &str) -> Live {
    let r = client(3000, 5000)
        .get(format!("{}{}", base, live_path(user, now_ms())))
        .header("Referer", format!("{}/httpclient.html", base))
        .send();
    match r {
        Ok(resp) => parse_live(&read_capped(resp, 65536)),
        Err(_) => Live::Unknown,
    }
}

// ---------------------------------------------------------------- Detect.java (probe the open internet)
pub const PROBES: &[&str] = &["http://connectivitycheck.gstatic.com/generate_204", "http://clients3.google.com/generate_204", "http://captive.apple.com/hotspot-detect.html"];

pub fn is_online(probe: &str, code: u16, body: &str) -> bool {
    if probe.contains("generate_204") { return code == 204; }
    (code == 200 && body.contains("<TITLE>Success</TITLE>")) || body.trim().eq_ignore_ascii_case("<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>")
}

/// Does the open internet answer? In = 204, Out = something (the portal) intercepted it, Unknown = no answer.
pub fn internet() -> Live {
    probe().0
}

/// The probe, plus where an intercepting portal sent us (its Location or a meta/JS redirect), when it did.
pub fn probe() -> (Live, Option<String>) {
    let c = client(2500, 3500);
    for p in PROBES.iter().take(2) {
        if let Ok(r) = c.get(*p).send() {
            let code = r.status().as_u16();
            let loc = r.headers().get("location").and_then(|v| v.to_str().ok()).map(|s| s.to_string());
            let body = read_capped(r, 16384);
            if is_online(p, code, &body) { return (Live::In, None); }
            let to = loc.or_else(|| page_redirect(&body)).and_then(|l| url::Url::parse(p).ok()?.join(&l).ok()).map(|u| u.to_string());
            return (Live::Out, to);
        }
    }
    (Live::Unknown, None)
}

/// A meta refresh or `location = "…"` script redirect in an intercept page.
pub fn page_redirect(body: &str) -> Option<String> {
    static M: OnceLock<Regex> = OnceLock::new();
    static J: OnceLock<Regex> = OnceLock::new();
    if let Some(c) = re(&M, r#"(?is)<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]*content\s*=\s*["']?\s*\d*\s*;?\s*url\s*=\s*([^"'>\s]+)"#).captures(body) { return Some(c[1].to_string()); }
    if let Some(c) = re(&J, r#"(?is)(?:window\.|document\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']"#).captures(body) { return Some(c[1].to_string()); }
    None
}

/// The Sophos portal an intercepted probe points at (port 8090, /httpclient.html, login.xml), as a base URL.
pub fn sophos_base_from(url: &str) -> Option<String> {
    let u = url::Url::parse(url).ok()?;
    let host = u.host_str()?;
    let port = u.port_or_known_default()?;
    let lower = url.to_lowercase();
    let looks = port == 8090 || lower.contains("httpclient.html") || lower.contains("login.xml") || lower.contains("cyberoam") || lower.contains("sophos");
    if !looks { return None; }
    let port = if port == 8090 || u.port().is_some() { port } else { 8090 };
    Some(format!("http://{}:{}", host, port))
}

pub fn host_port(base: &str) -> Option<(String, u16)> {
    let u = url::Url::parse(base).ok()?;
    Some((u.host_str()?.to_string(), u.port_or_known_default()?))
}

/// A quick TCP knock on the portal (no data sent): is the campus portal on this network?
pub fn portal_reachable(base: &str, ms: u64) -> bool {
    use std::net::{TcpStream, ToSocketAddrs};
    let Some((h, p)) = host_port(base) else { return false };
    let Ok(addrs) = (h.as_str(), p).to_socket_addrs() else { return false };
    addrs.into_iter().any(|a| TcpStream::connect_timeout(&a, Duration::from_millis(ms)).is_ok())
}

/// Human words for a sign-in answer (the status line under the power button).
pub fn verdict(r: &Reply) -> (Kind, String) {
    if r.success() { return (Kind::Ok, if r.message.is_empty() { "Signed in".into() } else { short_msg(&r.message) }); }
    if r.message.is_empty() { return (Kind::Unreadable, format!("Portal answered \"{}\"", r.status)); }
    (refusal(&r.message), short_msg(&r.message))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn refusal_classifier_android_cases() {
        let cases = [
            ("You have reached the maximum login limit.", Kind::LimitSession),
            ("You have reached Maximum Login Limit.", Kind::LimitSession),
            ("Your data transfer has been exceeded, Please contact the administrator", Kind::LimitQuota),
            ("Your time limit has been reached", Kind::LimitQuota),
            ("Your account has expired", Kind::Expired),
            ("Login failed.", Kind::WrongPassword),
            ("Invalid user name/password. Please contact the administrator.", Kind::WrongPassword),
            ("The system could not log you on. Make sure your password is correct", Kind::WrongPassword),
            ("Firewall authentication failed", Kind::WrongPassword),
            ("Invalid credentials specified", Kind::WrongPassword),
            ("invalid username or password", Kind::WrongPassword),
            ("no more sessions are allowed for user u1", Kind::LimitSession),
            ("user u1 has reached traffic limit", Kind::LimitQuota),
            ("user u1 has reached uptime limit", Kind::LimitQuota),
            ("Maximum number of devices reached", Kind::LimitSession),
            ("Simultaneous use limit", Kind::LimitSession),
            ("Daily quota exhausted", Kind::LimitQuota),
            ("Bandwidth limit exceeded", Kind::LimitQuota),
            ("Account is disabled", Kind::Expired),
            ("Unrecognized authentication response", Kind::Refused),
            ("Something odd happened", Kind::Refused),
        ];
        for (m, k) in cases { assert_eq!(refusal(m), k, "{}", m); }
    }

    #[test]
    fn sophos_login_reply_and_fill() {
        let x = "<?xml version='1.0' ?><requestresponse><status><![CDATA[LIVE]]></status><message><![CDATA[You are signed in as {username}]]></message><logoutmessage><![CDATA[You have successfully logged off]]></logoutmessage><state><![CDATA[]]></state></requestresponse>";
        let r = fill(parse(x, 200), "21abc002").unwrap();
        assert_eq!(r.status, "LIVE");
        assert_eq!(r.message, "You are signed in as 21abc002");
        assert!(r.success());
        assert_eq!(r.code(), "PORTAL_CONFIRMED");
        let r2 = fill(Some(Reply::new("LIVE", "Hi {username}, quota {quota} left", 200)), "x").unwrap();
        assert_eq!(r2.message, "Hi x, quota left");
        let r3 = fill(Some(Reply::new("LIVE", "You are signed in as abc", 200)), "abc").unwrap();
        assert_eq!(r3.message, "You are signed in as abc");
    }

    #[test]
    fn sophos_refusals() {
        let r = parse("<requestresponse><status>LOGIN</status><message><![CDATA[Login failed. Invalid user name/password. Please contact the administrator.]]></message></requestresponse>", 200).unwrap();
        assert!(!r.success());
        assert_eq!(r.code(), "REJECTED");
        assert!(r.rejected());
        assert_eq!(verdict(&r).0, Kind::WrongPassword);
        let l = parse("<requestresponse><status>LOGIN</status><message>You have reached Maximum Login Limit.</message></requestresponse>", 200).unwrap();
        assert_eq!(l.code(), "LOGIN_LIMIT");
        assert_eq!(verdict(&l).0, Kind::LimitSession);
        let q = parse("<requestresponse><status>LOGIN</status><message>Your data transfer has been exceeded</message></requestresponse>", 200).unwrap();
        assert_eq!(q.code(), "DATA_LIMIT");
        assert_eq!(verdict(&q).0, Kind::LimitQuota);
    }

    #[test]
    fn sophos_logout_and_malformed() {
        let o = parse("<requestresponse><status><![CDATA[LOGIN]]></status><message><![CDATA[You&#39;ve signed out]]></message></requestresponse>", 200).unwrap();
        assert_eq!(o.message, "You've signed out");
        assert!(o.signed_out());
        assert!(!o.success());
        assert!(parse("<status>LOGOUT</status>", 200).unwrap().signed_out());
        assert!(parse("<requestresponse><status>LOGIN</status><message></message></requestresponse>", 200).unwrap().signed_out());
        // tolerant regex path: not well-formed XML
        let m = parse("<html><status>LIVE<message>You are signed in as a & b</message></html>", 200).unwrap();
        assert_eq!(m.message, "You are signed in as a & b");
        assert!(parse("", 200).is_none());
        assert!(parse("<html><body>Hello</body></html>", 302).is_none());
        // a DTD is refused by the XML parser, never expanded
        let x = "<?xml version='1.0'?><!DOCTYPE r [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><r><status>LIVE</status><message>&e;</message></r>";
        if let Some(r) = parse(x, 200) { assert!(!r.message.contains("root:")); }
    }

    #[test]
    fn live_parsing() {
        assert_eq!(parse_live("200\n<?xml version='1.0' ?><requestresponse><ack><![CDATA[ack]]></ack></requestresponse>"), Live::In);
        assert_eq!(parse_live("200\n<requestresponse><liverequesttime>180</liverequesttime></requestresponse>"), Live::In);
        assert_eq!(parse_live("200\n<requestresponse><ack><![CDATA[login_again]]></ack></requestresponse>"), Live::Out);
        assert_eq!(parse_live("<loginstatus><![CDATA[FALSE]]></loginstatus>"), Live::Out);
        assert_eq!(parse_live("500\n"), Live::Unknown);
    }

    #[test]
    fn wire_format_matches_android() {
        assert_eq!(form_body("191", "21bcs001", Some("p@ss word&="), 1730000000000),
            "mode=191&username=21bcs001&password=p%40ss+word%26%3D&a=1730000000000&producttype=0");
        assert_eq!(form_body("193", "21bcs001", None, 5), "mode=193&username=21bcs001&a=5&producttype=0");
        assert_eq!(live_path("a b", 7), "/live?mode=192&username=a+b&a=7&producttype=0");
        assert_eq!(java_urlencode("ü*-._~"), "%C3%BC*-._%7E");
    }

    #[test]
    fn fingerprints_and_probe() {
        assert_eq!(fingerprint("http://172.16.16.16:8090/httpclient.html", "", &[]), "sophos");
        assert_eq!(fingerprint("http://10.0.0.1/", "<form action=login.xml>", &[]), "sophos");
        assert_eq!(fingerprint("http://1.2.3.4:1000/fgtauth?abc", "", &[]), "fortinet");
        assert_eq!(fingerprint("http://x/", "<input name=auth_user><input name=auth_pass>", &[]), "pfsense");
        assert_eq!(fingerprint("http://x/", "plain", &[]), "generic");
        assert!(is_online("http://connectivitycheck.gstatic.com/generate_204", 204, ""));
        assert!(!is_online("http://connectivitycheck.gstatic.com/generate_204", 200, "<html>portal</html>"));
        assert!(is_online("http://captive.apple.com/hotspot-detect.html", 200, "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>"));
        assert!(looks_signed_in("You are signed in as x"));
        assert!(!looks_signed_in("You are not signed in"));
        assert_eq!(host_port("http://172.16.16.16:8090"), Some(("172.16.16.16".into(), 8090)));
        assert_eq!(short_msg(&"a ".repeat(100)).chars().count(), 138);
    }

    #[test]
    fn page_verdict_reads_block_pages_like_android() {
        let q = page_verdict("<html><body><h1>Access denied</h1><p>Your data transfer limit has been exceeded.</p><script>var quota=1</script></body></html>", "").unwrap();
        assert_eq!(verdict(&q).0, Kind::LimitQuota);
        let s = page_verdict("<p>You have reached Maximum Login Limit.</p>", "").unwrap();
        assert_eq!(verdict(&s).0, Kind::LimitSession);
        let r = page_verdict("", "http://172.16.16.16:8090/quota_exceeded.html").unwrap();
        assert_eq!(verdict(&r).0, Kind::LimitQuota);
        assert!(r.message.starts_with("Portal redirected to"));
        assert!(page_verdict("<html><body>Welcome to the portal</body></html>", "").is_none());
        assert_eq!(html_text("<style>x{}</style><b>Hi</b>&nbsp;there<script>1</script>"), "Hi there");
    }

    #[test]
    fn errors_say_which_failure_it_was() {
        let u = PortalError::Unreachable("no answer (timed out) at 172.16.16.16:8090".into()).to_string();
        assert!(u.starts_with("Couldn't reach the portal: no answer"), "{}", u);
        let h = PortalError::Http(302, "redirected to /x".into()).to_string();
        assert!(h.contains("HTTP 302") && h.contains("redirected"));
        assert!(!PortalError::Unparseable(200, String::new()).to_string().contains('\u{201c}'));
    }

    #[test]
    fn portal_discovery_from_an_intercepted_probe() {
        assert_eq!(sophos_base_from("http://172.16.16.16:8090/httpclient.html?url=x").as_deref(), Some("http://172.16.16.16:8090"));
        assert_eq!(sophos_base_from("http://10.10.0.1:8090/").as_deref(), Some("http://10.10.0.1:8090"));
        assert_eq!(sophos_base_from("https://gw.campus.in/httpclient.html").as_deref(), Some("http://gw.campus.in:8090"));
        assert_eq!(sophos_base_from("http://login.example.com/portal"), None);
        assert_eq!(page_redirect("<meta http-equiv=\"refresh\" content=\"0; url=http://172.16.16.16:8090/httpclient.html\">").as_deref(), Some("http://172.16.16.16:8090/httpclient.html"));
        assert_eq!(page_redirect("<script>window.location=\"http://1.2.3.4:8090/\"</script>").as_deref(), Some("http://1.2.3.4:8090/"));
        assert_eq!(page_redirect("<html>hi</html>"), None);
    }

    /// A one-shot HTTP server on 127.0.0.1 that answers each request with the next canned reply and hands back
    /// what it received.
    fn mock(replies: Vec<String>) -> (String, std::sync::mpsc::Receiver<String>) {
        use std::io::{Read, Write};
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", l.local_addr().unwrap());
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            for reply in replies {
                let Ok((mut s, _)) = l.accept() else { return };
                s.set_read_timeout(Some(Duration::from_secs(3))).ok();
                let mut buf = vec![0u8; 16384];
                let mut got = Vec::new();
                loop {
                    let n = s.read(&mut buf).unwrap_or(0);
                    if n == 0 { break; }
                    got.extend_from_slice(&buf[..n]);
                    let t = String::from_utf8_lossy(&got).to_string();
                    if let Some(h) = t.find("\r\n\r\n") {
                        let len = t[..h].lines().find_map(|l| l.to_lowercase().strip_prefix("content-length:").map(|v| v.trim().parse::<usize>().unwrap_or(0))).unwrap_or(0);
                        if got.len() >= h + 4 + len { break; }
                    }
                }
                let _ = tx.send(String::from_utf8_lossy(&got).to_string());
                let _ = s.write_all(reply.as_bytes());
            }
        });
        (base, rx)
    }

    fn resp(code: &str, headers: &str, body: &str) -> String {
        format!("HTTP/1.1 {}\r\nContent-Length: {}\r\nConnection: close\r\n{}\r\n{}", code, body.len(), headers, body)
    }

    #[test]
    fn login_over_the_wire_like_android() {
        let ok = "<?xml version='1.0' ?><requestresponse><status><![CDATA[LIVE]]></status><message><![CDATA[You are signed in as {username}]]></message></requestresponse>";
        let (base, rx) = mock(vec![resp("200 OK", "Content-Type: text/xml\r\n", ok)]);
        let r = login_via(&base, "21bcs001", "p@ss word&=", None).unwrap();
        assert_eq!(verdict(&r), (Kind::Ok, "You are signed in as 21bcs001".to_string()));
        let req = rx.recv().unwrap();
        assert!(req.starts_with("POST /login.xml HTTP/1.1\r\n"), "{}", req);
        let lower = req.to_lowercase();
        assert!(lower.contains("content-type: application/x-www-form-urlencoded; charset=utf-8"));
        assert!(lower.contains(&format!("referer: {}/httpclient.html", base)));
        let body = req.split("\r\n\r\n").nth(1).unwrap();
        assert!(body.starts_with("mode=191&username=21bcs001&password=p%40ss+word%26%3D&a="), "{}", body);
        assert!(body.ends_with("&producttype=0"));
    }

    #[test]
    fn wrong_password_is_the_portal_words_not_a_network_error() {
        let no = "<requestresponse><status>LOGIN</status><message><![CDATA[Login failed. Invalid user name/password. Please contact the administrator.]]></message></requestresponse>";
        let (base, _rx) = mock(vec![resp("200 OK", "", no)]);
        let r = login_via(&base, "u", "secret-pw", None).unwrap();
        let (k, words) = verdict(&r);
        assert_eq!(k, Kind::WrongPassword);
        assert!(words.contains("Invalid user name/password"));
    }

    #[test]
    fn redirect_to_a_block_page_is_a_quota_refusal() {
        let (base, _rx) = mock(vec![
            resp("302 Found", "Location: /blocked.html\r\n", ""),
            resp("200 OK", "Content-Type: text/html\r\n", "<html><body><p>Your data transfer limit has been exceeded.</p></body></html>"),
        ]);
        let r = login_via(&base, "u", "pw", None).unwrap();
        assert_eq!(verdict(&r).0, Kind::LimitQuota);
    }

    #[test]
    fn an_unreadable_page_says_what_it_was() {
        let (base, _rx) = mock(vec![resp("200 OK", "", "<html><body>Under maintenance</body></html>")]);
        match login_via(&base, "u", "pw", None) {
            Err(e @ PortalError::Unparseable(200, _)) => assert!(e.to_string().contains("Under maintenance"), "{}", e),
            other => panic!("{:?}", other),
        }
    }

    #[test]
    fn nothing_listening_is_unreachable_with_a_reason() {
        let port = { let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap(); l.local_addr().unwrap().port() };
        let base = format!("http://127.0.0.1:{}", port);
        match login_via(&base, "u", "pw", None) {
            Err(PortalError::Unreachable(why)) => assert!((why.contains("refused") || why.contains("timed out")) && why.contains(&format!("127.0.0.1:{}", port)), "{}", why),
            other => panic!("{:?}", other),
        }
        assert!(!local_ipv4s().iter().any(|ip| ip.is_loopback()));
    }

    #[test]
    fn live_check_against_a_mock() {
        let (base, rx) = mock(vec![resp("200 OK", "", "<?xml version='1.0' ?><requestresponse><ack><![CDATA[ack]]></ack></requestresponse>")]);
        assert_eq!(live(&base, "21bcs001"), Live::In);
        assert!(rx.recv().unwrap().starts_with("GET /live?mode=192&username=21bcs001&a="));
    }
}
