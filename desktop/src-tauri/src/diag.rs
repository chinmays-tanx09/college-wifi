//! The connection log: what the app did and what the portal answered, newest last. Kept in memory (shown under
//! Settings → Connection log) and, when the user turns it on, appended to college-wifi.log in the app's log folder.
//! Passwords never reach it: callers log usernames and portal words only, and `redact` scrubs a password anyway.
use std::collections::VecDeque;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;

const KEEP: usize = 300;
const FILE_CAP: u64 = 1_000_000;

pub struct Log {
    lines: Mutex<VecDeque<String>>,
    file: Mutex<Option<PathBuf>>,
}

impl Default for Log {
    fn default() -> Self { Log { lines: Mutex::new(VecDeque::new()), file: Mutex::new(None) } }
}

pub fn stamp(now_ms: i64) -> String {
    use chrono::TimeZone;
    match chrono::Local.timestamp_millis_opt(now_ms).single() {
        Some(t) => t.format("%Y-%m-%d %H:%M:%S").to_string(),
        None => now_ms.to_string(),
    }
}

/// Never let a secret into a log line.
pub fn redact(line: &str, secret: Option<&str>) -> String {
    match secret { Some(s) if s.len() >= 3 => line.replace(s, "[redacted]"), _ => line.to_string() }
}

impl Log {
    pub fn add(&self, now_ms: i64, line: &str) {
        let l = format!("{}  {}", stamp(now_ms), line.replace(['\r', '\n'], " "));
        {
            let mut q = self.lines.lock().unwrap();
            q.push_back(l.clone());
            while q.len() > KEEP { q.pop_front(); }
        }
        if let Some(p) = self.file.lock().unwrap().clone() {
            if std::fs::metadata(&p).map(|m| m.len() > FILE_CAP).unwrap_or(false) {
                let _ = std::fs::rename(&p, p.with_extension("old.log"));
            }
            if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&p) { let _ = writeln!(f, "{}", l); }
        }
    }
    pub fn lines(&self) -> Vec<String> {
        self.lines.lock().unwrap().iter().cloned().collect()
    }
    pub fn text(&self) -> String {
        self.lines().join("\n")
    }
    /// Some(path) starts writing the log file (with everything kept so far); None stops.
    pub fn set_file(&self, path: Option<PathBuf>) {
        if let Some(p) = &path {
            if let Some(d) = p.parent() { let _ = std::fs::create_dir_all(d); }
            let was = self.file.lock().unwrap().clone();
            if was.as_ref() != Some(p) {
                if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(p) {
                    for l in self.lines() { let _ = writeln!(f, "{}", l); }
                }
            }
        }
        *self.file.lock().unwrap() = path;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ring_file_and_redaction() {
        let log = Log::default();
        for i in 0..(KEEP + 5) { log.add(0, &format!("line {}", i)); }
        let l = log.lines();
        assert_eq!(l.len(), KEEP);
        assert!(l[0].ends_with("line 5"));
        assert!(!log.text().contains('\r'));
        let dir = std::env::temp_dir().join(format!("cw-log-{}", std::process::id()));
        let p = dir.join("college-wifi.log");
        log.set_file(Some(p.clone()));
        log.add(0, "after\nfile");
        let s = std::fs::read_to_string(&p).unwrap();
        assert!(s.contains("line 5") && s.contains("after file"));
        log.set_file(None);
        log.add(0, "not in file");
        assert!(!std::fs::read_to_string(&p).unwrap().contains("not in file"));
        let _ = std::fs::remove_dir_all(dir);
        assert_eq!(redact("pw=hunter2!", Some("hunter2!")), "pw=[redacted]");
        assert_eq!(redact("ab", Some("ab")), "ab");
    }
}
