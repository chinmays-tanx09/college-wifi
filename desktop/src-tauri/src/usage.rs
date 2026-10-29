//! Data usage: a faithful port of the Android app's `Usage.bytes` and `PortalUsage` (Sophos User Portal
//! "Current Daily Cycle usage information" table). Pure Rust, no I/O, unit-tested below.
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::sync::OnceLock;

pub const KB: i64 = 1024;
pub const MB: i64 = KB * 1024;
pub const GB: i64 = MB * 1024;
pub const NA: i64 = -1;
pub const UNLIMITED: i64 = i64::MAX;

fn re(cell: &'static OnceLock<Regex>, pat: &str) -> &'static Regex {
    cell.get_or_init(|| Regex::new(pat).expect("regex"))
}

/// "7.8 GB", "13 GB", "512 MB", "12 KB", "0 B" (binary units, as the Sophos portal shows them).
pub fn bytes(b: i64) -> String {
    let b = b.max(0);
    if b >= GB {
        let g = b as f64 / GB as f64;
        let s = if g >= 100.0 { format!("{:.0} GB", g) } else { format!("{:.1} GB", g) };
        return s.replace(".0 GB", " GB");
    }
    if b >= MB {
        let m = b as f64 / MB as f64;
        let s = if m >= 100.0 { format!("{:.0} MB", m) } else { format!("{:.1} MB", m) };
        return s.replace(".0 MB", " MB");
    }
    if b >= KB {
        return format!("{:.0} KB", b as f64 / KB as f64);
    }
    format!("{} B", b)
}

/// Like `bytes`, but in GB once the allowance is GB-sized and the amount is 100 MB or more ("0.8 GB left").
pub fn bytes_scaled(b: i64, scale: i64) -> String {
    if scale >= GB && b >= 100 * MB && b < GB {
        return format!("{:.1} GB", b as f64 / GB as f64);
    }
    bytes(b)
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Quota {
    pub allotted: i64,
    pub up_to_last: i64,
    pub current: i64,
    pub total: i64,
    pub remaining: i64,
    pub down: i64,
    pub up: i64,
    pub period: String,
}

impl Default for Quota {
    fn default() -> Self {
        Quota { allotted: NA, up_to_last: NA, current: NA, total: NA, remaining: NA, down: NA, up: NA, period: "daily".into() }
    }
}

impl Quota {
    pub fn ok(&self) -> bool {
        self.total >= 0 || (self.allotted > 0 && self.allotted != UNLIMITED && self.remaining >= 0)
    }
    /// Used in this cycle (total, else allotted - remaining).
    pub fn used(&self) -> i64 {
        if self.total >= 0 {
            return self.total;
        }
        if self.allotted > 0 && self.allotted != UNLIMITED && self.remaining >= 0 {
            return (self.allotted - self.remaining).max(0);
        }
        NA
    }
    /// The cycle's allowance, or 0 when none/unlimited.
    pub fn cap(&self) -> i64 {
        if self.allotted > 0 && self.allotted != UNLIMITED { self.allotted } else { 0 }
    }
    pub fn left(&self) -> i64 {
        if self.remaining >= 0 && self.remaining != UNLIMITED {
            return self.remaining;
        }
        let (c, u) = (self.cap(), self.used());
        if c > 0 && u >= 0 { (c - u).max(0) } else { NA }
    }
    pub fn daily(&self) -> bool {
        self.period == "daily"
    }
    /// 0.0 ..= 1.0 of the allowance used (0 when there is no cap).
    pub fn fraction_used(&self) -> f64 {
        let c = self.cap();
        if c <= 0 {
            return 0.0;
        }
        (self.used().max(0) as f64 / c as f64).clamp(0.0, 1.0)
    }
}

pub fn norm(s: &str) -> String {
    s.to_lowercase().chars().filter(|c| c.is_ascii_lowercase() || c.is_ascii_digit()).collect()
}

/// "13312 MB" → bytes; "N.A." / "-" / "" → NA; "Unlimited" → UNLIMITED. A bare number is MB (the portal's unit).
pub fn value(s: &str) -> i64 {
    static R: OnceLock<Regex> = OnceLock::new();
    let t = s.trim().to_lowercase().replace(',', "").replace('\u{a0}', " ");
    if t.starts_with("unlimited") {
        return UNLIMITED;
    }
    let r = re(&R, r"([0-9]*\.?[0-9]+)\s*(bytes|byte|b|kb|kib|k|mb|mib|m|gb|gib|g|tb|tib|t)?\b");
    let Some(c) = r.captures(&t) else { return NA };
    let v: f64 = match c[1].parse() { Ok(v) => v, Err(_) => return NA };
    let unit = c.get(2).map(|m| m.as_str()).unwrap_or("mb");
    let mult: f64 = match unit.as_bytes()[0] {
        b'k' => KB as f64,
        b'm' => MB as f64,
        b'g' => GB as f64,
        b't' => 1024.0 * GB as f64,
        _ => 1.0,
    };
    (v * mult).round() as i64
}

pub fn cell_text(h: &str) -> String {
    static BR: OnceLock<Regex> = OnceLock::new();
    static TAG: OnceLock<Regex> = OnceLock::new();
    static WS: OnceLock<Regex> = OnceLock::new();
    let t = re(&BR, r"(?is)<br\s*/?>").replace_all(h, " ");
    let t = re(&TAG, r"(?s)<[^>]*>").replace_all(&t, " ");
    let t = t
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'");
    re(&WS, r"\s+").replace_all(&t, " ").trim().to_string()
}

/// Java's `(?is)<x[^>]*>(.*?)(?=stop|$)` without look-ahead: the text after each start tag up to the first stop.
fn segments<'a>(hay: &'a str, start: &Regex, stop: &Regex) -> Vec<&'a str> {
    let mut out = Vec::new();
    for m in start.find_iter(hay) {
        let from = m.end();
        let to = stop.find_at(hay, from).map(|s| s.start()).unwrap_or(hay.len());
        out.push(&hay[from..to.max(from)]);
    }
    out
}

/// Rows of cells, in document order, from HTML tables; or from plain text (tab / 2+ spaces / "|" separated).
pub fn rows(page: &str) -> Vec<Vec<String>> {
    static HAS_CELL: OnceLock<Regex> = OnceLock::new();
    static TR: OnceLock<Regex> = OnceLock::new();
    static TR_STOP: OnceLock<Regex> = OnceLock::new();
    static TD: OnceLock<Regex> = OnceLock::new();
    static TD_STOP: OnceLock<Regex> = OnceLock::new();
    static SCRIPT: OnceLock<Regex> = OnceLock::new();
    static STYLE: OnceLock<Regex> = OnceLock::new();
    static BLOCK: OnceLock<Regex> = OnceLock::new();
    static ANYTAG: OnceLock<Regex> = OnceLock::new();
    static NL: OnceLock<Regex> = OnceLock::new();
    static SEP: OnceLock<Regex> = OnceLock::new();
    let mut out: Vec<Vec<String>> = Vec::new();
    if re(&HAS_CELL, r"(?i)<t[dh][\s>]").is_match(page) {
        let tr = re(&TR, r"(?is)<tr[^>]*>");
        let tr_stop = re(&TR_STOP, r"(?is)<tr[\s>]|</table");
        let td = re(&TD, r"(?is)<t[dh][^>]*>");
        let td_stop = re(&TD_STOP, r"(?is)<t[dh][\s>]|</tr");
        for row in segments(page, tr, tr_stop) {
            let cells: Vec<String> = segments(row, td, td_stop).into_iter().map(cell_text).collect();
            if !cells.is_empty() {
                out.push(cells);
            }
        }
        if !out.is_empty() {
            return out;
        }
    }
    let text = re(&SCRIPT, r"(?is)<script[^>]*>.*?</script>").replace_all(page, " ");
    let text = re(&STYLE, r"(?is)<style[^>]*>.*?</style>").replace_all(&text, " ");
    let text = re(&BLOCK, r"(?i)</(p|div|tr|li|h[1-6])>|<br\s*/?>").replace_all(&text, "\n");
    let text = re(&ANYTAG, r"(?s)<[^>]*>").replace_all(&text, "\t");
    let sep = re(&SEP, r"\t|\s{2,}|\|");
    for line in re(&NL, r"\r?\n").split(&text) {
        let cells: Vec<String> = sep.split(line).map(cell_text).filter(|c| !c.is_empty()).collect();
        if !cells.is_empty() {
            out.push(cells);
        }
    }
    out
}

fn is_header(r: &[String]) -> bool {
    let (mut a, mut rem) = (false, false);
    for c in r {
        let n = norm(c);
        if n == "allotted" {
            a = true;
        }
        if n == "remaining" {
            rem = true;
        }
    }
    a && rem
}

/// Column index of Allotted, Up to last session, Current session, Total, Remaining, plus the header width.
fn columns(h: &[String]) -> [i64; 6] {
    let mut c = [-1i64; 5];
    for (i, cell) in h.iter().enumerate() {
        let n = norm(cell);
        let i = i as i64;
        if n == "allotted" {
            c[0] = i
        } else if n.starts_with("uptolast") {
            c[1] = i
        } else if n.starts_with("current") {
            c[2] = i
        } else if n == "total" {
            c[3] = i
        } else if n == "remaining" {
            c[4] = i
        }
    }
    let def = [1, 2, 3, 4, 5];
    for i in 0..5 {
        if c[i] < 0 {
            c[i] = def[i];
        }
    }
    [c[0], c[1], c[2], c[3], c[4], h.len() as i64]
}

/// A header without the empty corner cell is one short of the data rows: shift right.
fn shift(col: &[i64; 6], row_size: usize) -> [i64; 5] {
    let hdr = col[5];
    let rs = row_size as i64;
    let s = if rs > hdr { rs - hdr } else { 0 };
    [col[0] + s, col[1] + s, col[2] + s, col[3] + s, col[4] + s]
}

fn at(r: &[String], i: i64) -> i64 {
    if i >= 0 && (i as usize) < r.len() { value(&r[i as usize]) } else { NA }
}

/// The page → this cycle's quota, or None when the page doesn't carry the cycle table.
pub fn parse(page: &str) -> Option<Quota> {
    if page.is_empty() {
        return None;
    }
    let flat = norm(page);
    let mut q = Quota::default();
    q.period = if flat.contains("dailycycle") {
        "daily"
    } else if flat.contains("weeklycycle") {
        "weekly"
    } else if flat.contains("monthlycycle") {
        "monthly"
    } else if flat.contains("yearlycycle") {
        "yearly"
    } else {
        "daily"
    }
    .into();
    let rows = rows(page);
    let mut col: Option<[i64; 6]> = None;
    let mut any = false;
    for r in &rows {
        let first = norm(&r[0]);
        if is_header(r) {
            col = Some(columns(r));
            continue;
        }
        let kind = if first.starts_with("cycletotal") {
            "total"
        } else if first.starts_with("cycledownload") {
            "down"
        } else if first.starts_with("cycleupload") {
            "up"
        } else {
            continue;
        };
        let c = match &col {
            None => [1, 2, 3, 4, 5],
            Some(cc) => shift(cc, r.len()),
        };
        let (allotted, upto, cur, tot, rem) = (at(r, c[0]), at(r, c[1]), at(r, c[2]), at(r, c[3]), at(r, c[4]));
        match kind {
            "total" => {
                q.allotted = allotted;
                q.up_to_last = upto;
                q.current = cur;
                q.total = tot;
                q.remaining = rem;
                any = true;
            }
            "down" => q.down = tot,
            _ => q.up = tot,
        }
    }
    if !any && q.down >= 0 && q.up >= 0 {
        q.total = q.down + q.up;
        any = true;
    }
    if !any {
        return None;
    }
    if q.total < 0 && q.up_to_last >= 0 && q.current >= 0 {
        q.total = q.up_to_last + q.current;
    }
    fixup(&mut q);
    if q.ok() { Some(q) } else { None }
}

/// v5.3: a real portal read put the cap in Total and the used amount in Allotted ("Used 13 GB of 1.7 GB").
/// The cap is always the largest of allotted/total/remaining and used + left = cap, so re-seat them when they disagree.
pub fn fixup(q: &mut Quota) {
    let (a, t, r) = (q.allotted, q.total, q.remaining);
    if a > 0 && a != UNLIMITED && t >= 0 && r >= 0 && r != UNLIMITED {
        let cap = a.max(t.max(r));
        let tol = (5 * MB).max(cap / 50);
        if (a - (t + r)).abs() > tol {
            let left = if r != cap { r } else { a.min(t) };
            q.allotted = cap;
            q.remaining = left;
            q.total = (cap - left).max(0);
        }
    } else if a > 0 && a != UNLIMITED && t > a {
        q.allotted = t;
        q.total = a;
    }
    if q.down <= 0 && q.up <= 0 {
        q.down = NA;
        q.up = NA;
    }
}

/// "Used 13.6 MB of 13 GB today · 13 GB left" (daily) or "… this cycle".
pub fn line(q: &Quota) -> String {
    let (u, c, l) = (q.used(), q.cap(), q.left());
    let when = if q.daily() { "today" } else { "this cycle" };
    if c <= 0 {
        return format!("Used {} {}", bytes(u), when);
    }
    format!("Used {} of {} {} · {} left", bytes_scaled(u, c), bytes(c), when, bytes_scaled(l.max(0), c))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The fixture the Android tests rebuilt from a real screenshot of the portal's "Internet usage" page.
    pub const FIXTURE: &str = include_str!("../tests/fixtures/userportal_internet_usage.html");

    #[test]
    fn bytes_words() {
        assert_eq!(bytes(0), "0 B");
        assert_eq!(bytes(-5), "0 B");
        assert_eq!(bytes(512), "512 B");
        assert_eq!(bytes(12 * KB), "12 KB");
        assert_eq!(bytes(13 * MB + 600 * KB), "13.6 MB");
        assert_eq!(bytes(512 * MB), "512 MB");
        assert_eq!(bytes(13 * GB), "13 GB");
        assert_eq!(bytes((7.8 * GB as f64) as i64), "7.8 GB");
        assert_eq!(bytes(150 * GB), "150 GB");
        assert_eq!(bytes_scaled(819 * MB, 13 * GB), "0.8 GB");
        assert_eq!(bytes_scaled(50 * MB, 13 * GB), "50 MB");
        assert_eq!(bytes_scaled(819 * MB, 500 * MB), "819 MB");
    }

    #[test]
    fn values() {
        assert_eq!(value("13312 MB"), 13312 * MB);
        assert_eq!(value("13.59 MB"), (13.59 * MB as f64).round() as i64);
        assert_eq!(value("N.A."), NA);
        assert_eq!(value("-"), NA);
        assert_eq!(value(""), NA);
        assert_eq!(value("Unlimited"), UNLIMITED);
        assert_eq!(value("2 GB"), 2 * GB);
        assert_eq!(value("1,024 KB"), MB);
        assert_eq!(value("100"), 100 * MB, "a bare number is MB");
        assert_eq!(value("1 TB"), 1024 * GB);
        assert_eq!(value("5 bytes"), 5);
    }

    fn mb(x: f64) -> i64 {
        (x * MB as f64).round() as i64
    }

    // Ported from the Android UsageTest (same fixtures, same expectations).
    #[test]
    fn parses_fixture() {
        let q = parse(FIXTURE).expect("portal fixture parses");
        assert_eq!(q.allotted, 13312 * MB);
        assert_eq!(q.up_to_last, 0);
        assert_eq!(q.current, mb(13.59));
        assert_eq!(q.total, mb(13.59));
        assert_eq!(q.remaining, mb(13298.41));
        assert_eq!(q.down, mb(12.57));
        assert_eq!(q.up, mb(1.02));
        assert_eq!(q.period, "daily");
        assert_eq!(line(&q), "Used 13.6 MB of 13 GB today · 13 GB left");
        let back: Quota = serde_json::from_str(&serde_json::to_string(&q).unwrap()).unwrap();
        assert_eq!(back, q);
    }

    #[test]
    fn parses_inner_text_fixture() {
        let q = parse(include_str!("../tests/fixtures/userportal_internet_usage.txt")).expect("innerText parses");
        assert_eq!(q.remaining, mb(13298.41));
        assert_eq!(q.allotted, 13312 * MB);
    }

    #[test]
    fn android_variants() {
        let v = "<table><TR><TH>ALLOTTED</TH><TH>UP TO LAST SESSION</TH><TH>CURRENT SESSION</TH><TH>TOTAL</TH><TH>REMAINING</TH></TR><tr><td>Cycle Total Network Traffic</td><td>2 GB</td><td>1,024.00 MB</td><td>0.5 GB</td><td>1.5 GB</td><td>N.A.</td></tr></table><p>CurrentWeeklyCycle usage information</p>";
        let q = parse(v).expect("variant parses");
        assert_eq!(q.total, (1.5 * GB as f64) as i64);
        assert_eq!(q.left(), (0.5 * GB as f64) as i64);
        assert_eq!(q.period, "weekly");
        assert_eq!(line(&q), "Used 1.5 GB of 2 GB this cycle · 0.5 GB left");
        let un = "<table><tr><th></th><th>Allotted</th><th>Up to last session</th><th>Current session</th><th>Total</th><th>Remaining</th></tr><tr><td>Cycle total network traffic</td><td>Unlimited</td><td>100 MB</td><td>20 MB</td><td>120 MB</td><td>Unlimited</td></tr></table>CurrentDailyCycle";
        let qu = parse(un).expect("unlimited parses");
        assert_eq!(qu.cap(), 0);
        assert_eq!(qu.used(), 120 * MB);
        assert_eq!(line(&qu), "Used 120 MB today");
        assert!(parse("<html><form><input name=username><input type=password></form>Sign in</html>").is_none());
    }

    #[test]
    fn swapped_columns_are_reseated() {
        // the real Oct 29 read: cap in Total, used in Allotted ("Used 13 GB of 1.7 GB")
        let mut q = Quota { allotted: (1.7 * GB as f64) as i64, total: 13 * GB, remaining: (11.3 * GB as f64) as i64, ..Default::default() };
        fixup(&mut q);
        assert_eq!(q.allotted, 13 * GB);
        assert_eq!(q.remaining, (11.3 * GB as f64) as i64);
        assert_eq!(q.total, 13 * GB - (11.3 * GB as f64) as i64);
        let mut q2 = Quota { allotted: 2 * GB, total: 13 * GB, ..Default::default() };
        fixup(&mut q2);
        assert_eq!((q2.allotted, q2.total), (13 * GB, 2 * GB));
        // consistent numbers are left alone
        let mut q3 = Quota { allotted: 13 * GB, total: 5 * GB, remaining: 8 * GB, ..Default::default() };
        let before = q3.clone();
        fixup(&mut q3);
        assert_eq!(q3, before);
    }

    #[test]
    fn plain_text_layout() {
        let page = "<div>Current Daily Cycle usage information</div><pre>\nAllotted  Up to last session  Current session  Total  Remaining\nCycle total network traffic  2048 MB  100 MB  24 MB  124 MB  1924 MB\n</pre>";
        let q = parse(page).expect("quota from text");
        assert_eq!(q.allotted, 2048 * MB);
        assert_eq!(q.total, 124 * MB);
        assert_eq!(q.remaining, 1924 * MB);
        assert_eq!(q.left(), 1924 * MB);
    }

    #[test]
    fn header_without_corner_cell_shifts() {
        let page = "<table><tr><th>Allotted</th><th>Up to last session</th><th>Current session</th><th>Total</th><th>Remaining</th></tr>\
<tr><td>Cycle total network traffic</td><td>1024 MB</td><td>10 MB</td><td>5 MB</td><td>15 MB</td><td>1009 MB</td></tr></table>";
        let q = parse(page).unwrap();
        assert_eq!(q.allotted, 1024 * MB);
        assert_eq!(q.total, 15 * MB);
        assert_eq!(q.remaining, 1009 * MB);
    }

    #[test]
    fn down_plus_up_when_no_total_row() {
        let page = "<table><tr><td></td><td>Allotted</td><td>Up to last session</td><td>Current session</td><td>Total</td><td>Remaining</td></tr>\
<tr><td>Cycle download network traffic</td><td>N.A.</td><td>N.A.</td><td>N.A.</td><td>300 MB</td><td>N.A.</td></tr>\
<tr><td>Cycle upload network traffic</td><td>N.A.</td><td>N.A.</td><td>N.A.</td><td>20 MB</td><td>N.A.</td></tr></table>";
        let q = parse(page).unwrap();
        assert_eq!(q.total, 320 * MB);
        assert_eq!(q.cap(), 0);
        assert_eq!(line(&q), "Used 320 MB today");
    }

    #[test]
    fn not_a_usage_page() {
        assert!(parse("").is_none());
        assert!(parse("<html><body><form><input type=password></form></body></html>").is_none());
    }

    #[test]
    fn weekly_and_fraction() {
        let page = "<p>Current Weekly Cycle usage information</p><table><tr><td></td><td>Allotted</td><td>Up to last session</td><td>Current session</td><td>Total</td><td>Remaining</td></tr>\
<tr><td>Cycle total network traffic</td><td>10 GB</td><td>N.A.</td><td>N.A.</td><td>2.5 GB</td><td>7.5 GB</td></tr></table>";
        let q = parse(page).unwrap();
        assert_eq!(q.period, "weekly");
        assert!((q.fraction_used() - 0.25).abs() < 1e-9);
        assert_eq!(line(&q), "Used 2.5 GB of 10 GB this cycle · 7.5 GB left");
    }
}
