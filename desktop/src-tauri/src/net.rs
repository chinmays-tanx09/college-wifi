//! The current Wi-Fi name, read with each OS's own tool (no extra permissions; nothing leaves the machine).
use std::process::Command;
use std::time::Duration;

fn run(cmd: &str, args: &[&str]) -> Option<String> {
    let mut c = Command::new(cmd);
    c.args(args);
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let child = c.stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::null()).spawn().ok()?;
    // these tools answer in milliseconds; never let one hold the engine
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || { let _ = tx.send(child.wait_with_output()); });
    let out = rx.recv_timeout(Duration::from_secs(4)).ok()?.ok()?;
    Some(String::from_utf8_lossy(&out.stdout).into_owned())
}

/// `netsh wlan show interfaces` → the "SSID : name" line (not BSSID).
pub fn parse_netsh(out: &str) -> Option<String> {
    out.lines().find_map(|l| {
        let t = l.trim();
        let (k, v) = t.split_once(':')?;
        if k.trim().eq_ignore_ascii_case("SSID") { let v = v.trim(); if v.is_empty() { None } else { Some(v.to_string()) } } else { None }
    })
}

/// `nmcli -t -f ACTIVE,SSID dev wifi` → the active line ("yes:Name"; colons in names are escaped as "\:").
pub fn parse_nmcli(out: &str) -> Option<String> {
    out.lines().find_map(|l| {
        let rest = l.strip_prefix("yes:").or_else(|| l.strip_prefix("ja:"))?;
        let v = rest.replace("\\:", ":");
        if v.is_empty() { None } else { Some(v) }
    })
}

/// macOS `ipconfig getsummary en0` → " SSID : name" (redacted on some versions without Location access).
pub fn parse_ipconfig(out: &str) -> Option<String> {
    out.lines().find_map(|l| {
        let t = l.trim();
        let v = t.strip_prefix("SSID : ")?.trim();
        if v.is_empty() || v.contains("<redacted>") { None } else { Some(v.to_string()) }
    })
}

pub fn current_ssid() -> Option<String> {
    #[cfg(target_os = "windows")]
    { return run("netsh", &["wlan", "show", "interfaces"]).and_then(|o| parse_netsh(&o)); }
    #[cfg(target_os = "macos")]
    {
        for dev in ["en0", "en1"] {
            if let Some(s) = run("/usr/sbin/ipconfig", &["getsummary", dev]).and_then(|o| parse_ipconfig(&o)) { return Some(s); }
        }
        return run("/usr/sbin/networksetup", &["-getairportnetwork", "en0"])
            .and_then(|o| o.split_once("Network: ").map(|(_, v)| v.trim().to_string()))
            .filter(|s| !s.is_empty());
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        if let Some(s) = run("nmcli", &["-t", "-f", "ACTIVE,SSID", "dev", "wifi"]).and_then(|o| parse_nmcli(&o)) { return Some(s); }
        return run("iwgetid", &["-r"]).map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    }
    #[allow(unreachable_code)]
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parsers() {
        let netsh = "There is 1 interface on the system:\r\n\r\n    Name                   : Wi-Fi\r\n    State                  : connected\r\n    SSID                   : IIIT DHARWAD-STUDENT\r\n    BSSID                  : aa:bb:cc:dd:ee:ff\r\n";
        assert_eq!(parse_netsh(netsh).as_deref(), Some("IIIT DHARWAD-STUDENT"));
        assert_eq!(parse_netsh("    State : disconnected"), None);
        assert_eq!(parse_nmcli("no:Other\nyes:IIIT DHARWAD-STUDENT\n").as_deref(), Some("IIIT DHARWAD-STUDENT"));
        assert_eq!(parse_nmcli("yes:a\\:b").as_deref(), Some("a:b"));
        assert_eq!(parse_ipconfig("<dictionary> {\n  SSID : IIIT DHARWAD-STUDENT\n}").as_deref(), Some("IIIT DHARWAD-STUDENT"));
        assert_eq!(parse_ipconfig("  SSID : <redacted>"), None);
    }
}
