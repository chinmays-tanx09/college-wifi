//! Reads "Internet usage" from the campus Sophos User Portal (https://<portal host>:4443/userportal/), the way the
//! Android UserPortalEngine does: a hidden browser signs in like a person would, opens "Internet usage", waits for
//! the daily-cycle table and hands the page to `usage::parse`. Never clicks Logout.
//!
//! The portal uses a self-signed certificate, which system webviews refuse. So the hidden webview talks to a tiny
//! proxy on 127.0.0.1 (random port, this app only) that forwards to the portal over HTTPS and trusts the portal's
//! certificate for that host alone, pinned on first use (a changed certificate stops it, like Android). The page
//! reports back to the proxy with a same-origin POST, so the portal page never gets access to the app itself.
use sha2::{Digest, Sha256};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};

pub const PORT: u16 = 4443;
const DEADLINE: Duration = Duration::from_secs(28);
const REPORT: &str = "/__college_wifi__/report";

pub struct Pin {
    pub known: Option<String>,
    pub seen: Option<String>,
    pub mismatch: bool,
}

fn sha256_hex(der: &[u8]) -> String {
    Sha256::digest(der).iter().map(|b| format!("{:02x}", b)).collect()
}

/// Set-Cookie for the portal → a cookie the webview keeps on http://127.0.0.1 (drop Secure / Domain / SameSite=None).
pub fn rewrite_cookie(sc: &str) -> String {
    sc.split(';')
        .map(|p| p.trim())
        .filter(|p| {
            let l = p.to_ascii_lowercase();
            !(l == "secure" || l.starts_with("domain=") || l == "samesite=none" || l == "partitioned")
        })
        .collect::<Vec<_>>()
        .join("; ")
}

/// Absolute portal URLs in Location headers → the proxy.
pub fn rewrite_location(loc: &str, host: &str, local: &str) -> String {
    for p in [format!("https://{}:{}", host, PORT), format!("https://{}", host)] {
        if let Some(rest) = loc.strip_prefix(&p) { return format!("{}{}", local, rest); }
    }
    loc.to_string()
}

struct Proxy {
    host: String,
    local: String,
    pin: Arc<Mutex<Pin>>,
    tx: Sender<(String, String)>,
    client: reqwest::blocking::Client,
}

fn read_request(s: &mut BufReader<TcpStream>) -> Option<(String, String, Vec<(String, String)>, Vec<u8>)> {
    let mut line = String::new();
    s.read_line(&mut line).ok()?;
    let mut parts = line.split_whitespace();
    let (method, path) = (parts.next()?.to_string(), parts.next()?.to_string());
    let mut headers = Vec::new();
    let mut len = 0usize;
    loop {
        let mut h = String::new();
        if s.read_line(&mut h).ok()? == 0 { break; }
        let h = h.trim_end();
        if h.is_empty() { break; }
        if let Some((k, v)) = h.split_once(':') {
            if k.eq_ignore_ascii_case("content-length") { len = v.trim().parse().unwrap_or(0); }
            headers.push((k.trim().to_string(), v.trim().to_string()));
        }
        if headers.len() > 100 { return None; }
    }
    let mut body = vec![0u8; len.min(4 << 20)];
    s.read_exact(&mut body).ok()?;
    Some((method, path, headers, body))
}

fn respond(out: &mut TcpStream, code: u16, headers: &[(String, String)], body: &[u8]) {
    let mut head = format!("HTTP/1.1 {} X\r\n", code);
    for (k, v) in headers { head.push_str(&format!("{}: {}\r\n", k, v)); }
    head.push_str(&format!("Content-Length: {}\r\nConnection: close\r\n\r\n", body.len()));
    let _ = out.write_all(head.as_bytes());
    let _ = out.write_all(body);
    let _ = out.flush();
}

impl Proxy {
    fn handle(&self, stream: TcpStream) {
        let _ = stream.set_read_timeout(Some(Duration::from_secs(15)));
        let mut out = match stream.try_clone() { Ok(s) => s, Err(_) => return };
        let mut r = BufReader::new(stream);
        let Some((method, path, headers, body)) = read_request(&mut r) else { return };
        if path.starts_with(REPORT) {
            let kind = path.split("k=").nth(1).unwrap_or("").to_string();
            let _ = self.tx.send((kind, String::from_utf8_lossy(&body).into_owned()));
            respond(&mut out, 204, &[], b"");
            return;
        }
        let target = format!("https://{}:{}{}", self.host, PORT, path);
        let m = reqwest::Method::from_bytes(method.as_bytes()).unwrap_or(reqwest::Method::GET);
        let mut req = self.client.request(m, &target);
        for (k, v) in &headers {
            let l = k.to_ascii_lowercase();
            if matches!(l.as_str(), "host" | "connection" | "accept-encoding" | "content-length" | "upgrade-insecure-requests" | "keep-alive") { continue; }
            let v = match l.as_str() {
                "origin" => format!("https://{}:{}", self.host, PORT),
                "referer" => v.replace(&self.local, &format!("https://{}:{}", self.host, PORT)),
                _ => v.clone(),
            };
            req = req.header(k.as_str(), v);
        }
        if !body.is_empty() { req = req.body(body); }
        let resp = match req.send() {
            Ok(r) => r,
            Err(_) => { respond(&mut out, 502, &[], b"portal unreachable"); return; }
        };
        // certificate pin: trust this portal's own certificate on first use, refuse a different one later
        let fp = resp.extensions().get::<reqwest::tls::TlsInfo>().and_then(|t| t.peer_certificate()).map(sha256_hex);
        {
            let mut pin = self.pin.lock().unwrap();
            match (&pin.known, &fp) {
                (_, None) => { respond(&mut out, 502, &[], b"no certificate"); return; }
                (Some(k), Some(f)) if k != f => {
                    pin.mismatch = true;
                    let _ = self.tx.send(("CERT".into(), String::new()));
                    respond(&mut out, 502, &[], b"certificate changed");
                    return;
                }
                (_, Some(f)) => { pin.seen = Some(f.clone()); }
            }
        }
        let code = resp.status().as_u16();
        let mut hs: Vec<(String, String)> = Vec::new();
        for (k, v) in resp.headers() {
            let l = k.as_str().to_ascii_lowercase();
            if matches!(l.as_str(), "transfer-encoding" | "connection" | "content-length" | "strict-transport-security" | "keep-alive" | "alt-svc") { continue; }
            let v = v.to_str().unwrap_or("").to_string();
            let v = match l.as_str() {
                "set-cookie" => rewrite_cookie(&v),
                "location" => rewrite_location(&v, &self.host, &self.local),
                _ => v,
            };
            hs.push((k.as_str().to_string(), v));
        }
        let mut buf = Vec::new();
        let _ = resp.take(8 << 20).read_to_end(&mut buf);
        respond(&mut out, code, &hs, &buf);
    }
}

fn js_string(s: &str) -> String {
    serde_json::to_string(s).unwrap_or_else(|_| "\"\"".into())
}

/// One look at the page (the Android step script), reporting through the proxy instead of evaluateJavascript.
fn step_js(submitted: bool, navigated: bool, user: &str, pass: &str) -> String {
    format!(r#"(function(){{function rep(k,v){{try{{fetch('{rep}?k='+k,{{method:'POST',body:v||''}});}}catch(e){{}}}}
function vis(e){{var r=e.getBoundingClientRect();return e.type!=='hidden'&&!e.disabled&&(r.width>0||r.height>0);}}
var t=(document.body&&document.body.innerText||'').toLowerCase().replace(/[^a-z]/g,'');
if(t.indexOf('cycletotalnetworktraffic')>=0){{rep('DATA',document.documentElement.outerHTML);return;}}
var all=[...document.querySelectorAll('input')];var p=all.find(e=>e.type==='password'&&vis(e));
if(p){{if({submitted}){{rep('LOGIN',[...document.querySelectorAll('.error,.alert,.message,.msg,#msg,#message,[role=alert],.errorMessage,#errorMessage')].map(e=>(e.textContent||'').trim()).find(Boolean)||'');return;}}
 var before=all.slice(0,all.indexOf(p)).filter(e=>vis(e)&&/^(text|email|tel|number|)$/.test(e.type));var re=/user|login|uname|email|uid|id|roll|name/i;
 var u=before.reverse().find(e=>re.test(e.name+' '+e.id+' '+e.placeholder))||before[0];if(!u){{rep('WAIT');return;}}
 function set(e,v){{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v);e.dispatchEvent(new Event('input',{{bubbles:true}}));e.dispatchEvent(new Event('change',{{bubbles:true}}));}}
 set(u,{user});set(p,{pass});
 var f=p.form;var b=(f?[...f.querySelectorAll('button,input[type=submit],input[type=button],input[type=image]')]:[]).find(vis)||[...document.querySelectorAll('button,input[type=submit],input[type=button],a,[role=button]')].find(e=>vis(e)&&/log\s*in|sign\s*in|submit|login/i.test(e.textContent||e.value||''));
 if(b){{b.click();rep('SUBMITTED');return;}}if(f){{f.requestSubmit?f.requestSubmit():f.submit();rep('SUBMITTED');return;}}rep('WAIT');return;}}
if(!{navigated}){{var m=[...document.querySelectorAll('a,li,span,div,td,button')].filter(e=>vis(e)&&/^\s*internet\s*usage\s*$/i.test(e.textContent||''));
 if(m.length){{var e=m[m.length-1];var a=e.closest('a')||e;a.click();rep('NAV');return;}}}}
rep('WAIT');}})()"#, rep = REPORT, submitted = submitted, navigated = navigated, user = js_string(user), pass = js_string(pass))
}

pub struct Outcome {
    pub page: Option<String>,
    pub error: Option<String>,
    pub pin_seen: Option<String>,
}

/// Blocking: run from a background thread. `host` is the portal host (e.g. 172.16.16.16).
pub fn fetch(app: &AppHandle, host: &str, user: &str, pass: &str, known_pin: Option<String>) -> Outcome {
    let listener = match TcpListener::bind("127.0.0.1:0") {
        Ok(l) => l,
        Err(_) => return Outcome { page: None, error: Some("couldn't start the usage reader".into()), pin_seen: None },
    };
    let port = listener.local_addr().map(|a| a.port()).unwrap_or(0);
    let local = format!("http://127.0.0.1:{}", port);
    let (tx, rx): (Sender<(String, String)>, Receiver<(String, String)>) = channel();
    let pin = Arc::new(Mutex::new(Pin { known: known_pin, seen: None, mismatch: false }));
    let client = reqwest::blocking::Client::builder()
        .danger_accept_invalid_certs(true) // the certificate is checked by its pin above, for this one host
        .tls_info(true)
        .redirect(reqwest::redirect::Policy::none())
        .no_proxy()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(15))
        .build();
    let Ok(client) = client else { return Outcome { page: None, error: Some("couldn't start the usage reader".into()), pin_seen: None } };
    let proxy = Arc::new(Proxy { host: host.to_string(), local: local.clone(), pin: pin.clone(), tx, client });
    let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
    {
        let (proxy, stop) = (proxy.clone(), stop.clone());
        let _ = listener.set_nonblocking(true);
        std::thread::spawn(move || {
            while !stop.load(std::sync::atomic::Ordering::Relaxed) {
                match listener.accept() {
                    Ok((s, _)) => {
                        let _ = s.set_nonblocking(false);
                        let p = proxy.clone();
                        std::thread::spawn(move || p.handle(s));
                    }
                    Err(_) => std::thread::sleep(Duration::from_millis(30)),
                }
            }
        });
    }
    let start_url: tauri::Url = match format!("{}/userportal/", local).parse() { Ok(u) => u, Err(_) => return Outcome { page: None, error: Some("bad portal address".into()), pin_seen: None } };
    let allowed = local.clone();
    if let Some(old) = app.get_webview_window("userportal") { let _ = old.destroy(); }
    let win = WebviewWindowBuilder::new(app, "userportal", WebviewUrl::External(start_url))
        .title("College WiFi · usage")
        .visible(false)
        .skip_taskbar(true)
        .inner_size(1100.0, 900.0)
        .on_navigation(move |u| { let s = u.as_str(); s.starts_with(&allowed) || s.starts_with("about:") || s.starts_with("data:") })
        .build();
    let win = match win { Ok(w) => w, Err(_) => { stop.store(true, std::sync::atomic::Ordering::Relaxed); return Outcome { page: None, error: Some("couldn't open the user portal".into()), pin_seen: None }; } };

    let began = Instant::now();
    let (mut submitted, mut navigated, mut submitted_at) = (false, false, Instant::now());
    let mut creds = Some((user.to_string(), pass.to_string()));
    let mut result: (Option<String>, Option<String>) = (None, None);
    let mut polls = 0u32;
    std::thread::sleep(Duration::from_millis(1200));
    loop {
        if began.elapsed() > DEADLINE {
            result = (None, Some(if submitted { "signed in, but the usage table never showed".into() } else { "the user portal didn't load".into() }));
            break;
        }
        polls += 1;
        let (u, p) = creds.clone().unwrap_or_default();
        let _ = win.eval(&step_js(submitted, navigated, &u, &p));
        let deadline = Instant::now() + Duration::from_millis(700);
        let mut done = false;
        while let Ok((kind, payload)) = rx.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
            match kind.as_str() {
                "DATA" => { result = (Some(payload), None); done = true; break; }
                "SUBMITTED" => { submitted = true; submitted_at = Instant::now(); creds = None; }
                "NAV" => navigated = true,
                "CERT" => { result = (None, Some("the user portal's certificate changed · not sending your login".into())); done = true; break; }
                "LOGIN" if submitted_at.elapsed() > Duration::from_secs(4) && submitted => {
                    let m: String = payload.chars().take(80).collect();
                    result = (None, Some(format!("the user portal refused the login{}", if m.is_empty() { String::new() } else { format!(": {}", m) })));
                    done = true;
                    break;
                }
                _ => {}
            }
        }
        if done { break; }
        if submitted && !navigated && polls > 200 { result = (None, Some("stuck".into())); break; }
    }
    let _ = win.destroy();
    stop.store(true, std::sync::atomic::Ordering::Relaxed);
    let seen = pin.lock().unwrap().seen.clone();
    Outcome { page: result.0, error: result.1, pin_seen: seen }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cookie_and_location_rewrites() {
        assert_eq!(rewrite_cookie("JSESSIONID=abc; Path=/userportal; Secure; HttpOnly; SameSite=None; Domain=172.16.16.16"), "JSESSIONID=abc; Path=/userportal; HttpOnly");
        assert_eq!(rewrite_location("https://172.16.16.16:4443/userportal/webpages/myaccount/index.jsp", "172.16.16.16", "http://127.0.0.1:5000"), "http://127.0.0.1:5000/userportal/webpages/myaccount/index.jsp");
        assert_eq!(rewrite_location("/userportal/x", "172.16.16.16", "http://127.0.0.1:5000"), "/userportal/x");
    }
    #[test]
    fn step_script_quotes_credentials() {
        let js = step_js(false, false, "21bcs\"001", "p'a\\ss</script>");
        assert!(js.contains(r#"set(u,"21bcs\"001")"#));
        assert!(js.contains(r#"set(p,"p'a\\ss</script>")"#));
        assert!(js.contains("if(false)"));
    }
}
