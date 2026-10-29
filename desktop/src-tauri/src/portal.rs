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
    /// Nothing answered (not on the campus network, or the portal is down).
    Unreachable(String),
    /// The portal answered, but with an HTTP error / redirect / page we cannot read.
    Http(u16),
    Unparseable(u16),
}

impl std::fmt::Display for PortalError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PortalError::Unreachable(_) => write!(f, "Couldn't reach the college portal"),
            PortalError::Http(c) => write!(f, "Portal HTTP {}", c),
            PortalError::Unparseable(c) => write!(f, "Portal reply not recognised (HTTP {})", c),
        }
    }
}

pub fn client(connect_ms: u64, read_ms: u64) -> reqwest::blocking::Client {
    reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_millis(connect_ms))
        .timeout(Duration::from_millis(connect_ms + read_ms))
        .redirect(reqwest::redirect::Policy::none())
        .no_proxy()
        .user_agent("Mozilla/5.0 (Desktop) CollegeWiFi/1.0")
        .build()
        .expect("http client")
}

fn read_capped(resp: reqwest::blocking::Response, cap: usize) -> String {
    use std::io::Read;
    let mut buf = Vec::new();
    let _ = resp.take(cap as u64).read_to_end(&mut buf);
    String::from_utf8_lossy(&buf).into_owned()
}

/// POST to a fixed path on the portal. pass == None omits the password field (logout).
pub fn post(base: &str, path: &str, mode: &str, user: &str, pass: Option<&str>, connect_ms: u64, read_ms: u64) -> Result<Reply, PortalError> {
    let body = form_body(mode, user, pass, now_ms());
    let resp = client(connect_ms, read_ms)
        .post(format!("{}{}", base, path))
        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        .header("Referer", format!("{}/httpclient.html", base))
        .body(body)
        .send()
        .map_err(|e| PortalError::Unreachable(e.to_string()))?;
    let http = resp.status().as_u16();
    let mut text = read_capped(resp, 65536);
    if let Some(p) = pass { if !p.is_empty() { text = text.replace(p, "[redacted]"); } }
    match parse(&text, http) {
        Some(r) => Ok(fill(Some(r), user).unwrap()),
        None if http >= 300 => Err(PortalError::Http(http)),
        None => Err(PortalError::Unparseable(http)),
    }
}

pub fn login(base: &str, user: &str, pass: &str) -> Result<Reply, PortalError> {
    post(base, "/login.xml", "191", user, Some(pass), 5000, 8000)
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
    let c = client(2500, 3500);
    for p in PROBES.iter().take(2) {
        if let Ok(r) = c.get(*p).send() {
            let code = r.status().as_u16();
            let body = read_capped(r, 16384);
            return if is_online(p, code, &body) { Live::In } else { Live::Out };
        }
    }
    Live::Unknown
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
}
