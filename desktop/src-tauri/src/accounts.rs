//! Several portal logins with automatic failover: a faithful port of the Android app's `Accounts` (v5/v5.1).
//! Order = the user's order (first = primary). A wrong password or an expired account is parked until the user
//! edits it (one attempt, never a lockout loop). A device/session limit is retried on the next Wi-Fi connect (or
//! after 15 min); a data/time quota after the next local midnight (campus quotas are daily). Pure Rust, no I/O.
use crate::portal::Kind;
use serde::{Deserialize, Serialize};

pub const SESSION_RETRY_MS: i64 = 15 * 60_000;
/// Signed in, but the portal intercepts the internet (data used up while the session stays "live").
pub const BLOCKED_RETRY_MS: i64 = 15 * 60_000;

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Account {
    pub id: String,
    pub user: String,
    /// "" | WRONG_PASSWORD | EXPIRED | LIMIT_SESSION | LIMIT_QUOTA | REFUSED
    pub state: String,
    /// What the portal said last time it refused this account (never contains the password).
    pub message: String,
    pub state_at: i64,
    pub until: i64,
}

impl Account {
    pub fn new(id: &str, user: &str) -> Account {
        Account { id: id.into(), user: user.into(), ..Default::default() }
    }
    pub fn parked(&self) -> bool {
        self.state == "WRONG_PASSWORD" || self.state == "EXPIRED"
    }
    pub fn limited(&self, now: i64, fresh_connect: bool) -> bool {
        match self.state.as_str() {
            "LIMIT_SESSION" => !fresh_connect && now < self.until,
            "LIMIT_QUOTA" | "REFUSED" => now < self.until,
            _ => false,
        }
    }
    /// The user edited this login: whatever the portal said about the old one no longer applies.
    pub fn clear(&mut self) {
        self.state.clear();
        self.message.clear();
        self.until = 0;
        self.state_at = 0;
    }
}

/// Accounts to try, in order, for one sign-in chain. Primary first unless it is parked or still limited.
/// A manual tap ("Log in") tries every account that isn't parked once, limits included (the user asked).
pub fn candidates(ordered: &[Account], now: i64, fresh_connect: bool, manual: bool) -> Vec<Account> {
    ordered.iter().filter(|a| !a.parked() && (manual || !a.limited(now, fresh_connect))).cloned().collect()
}

/// Record what the portal said about this account. `next_reset` = the next local midnight after `now`.
pub fn mark(a: &mut Account, kind: Kind, message: &str, now: i64, next_reset: i64) {
    a.state_at = now;
    a.message = crate::portal::short_msg(message);
    match kind {
        Kind::Ok => { a.clear(); }
        Kind::WrongPassword => { a.state = "WRONG_PASSWORD".into(); a.until = i64::MAX; }
        Kind::Expired => { a.state = "EXPIRED".into(); a.until = i64::MAX; }
        Kind::LimitSession => { a.state = "LIMIT_SESSION".into(); a.until = now + SESSION_RETRY_MS; }
        Kind::LimitQuota => { a.state = "LIMIT_QUOTA".into(); a.until = next_reset.max(now + SESSION_RETRY_MS); }
        // unknown non-success: try the next one, come back later
        Kind::Refused => { a.state = "REFUSED".into(); a.until = now + SESSION_RETRY_MS; }
        Kind::Unreadable => {}
    }
}

/// Short label for the account list chip ("Wrong password", "Limit reached 9:41 pm", "Active", "").
pub fn chip(a: &Account, active: bool, now: i64, time: &dyn Fn(i64) -> String) -> String {
    match a.state.as_str() {
        "WRONG_PASSWORD" => return "Wrong password".into(),
        "EXPIRED" => return "Expired".into(),
        "LIMIT_SESSION" | "LIMIT_QUOTA" | "REFUSED" if now < a.until => {
            return format!("{} {}", if a.state == "REFUSED" { "Refused" } else { "Limit reached" }, time(a.state_at)).trim().to_string();
        }
        _ => {}
    }
    if active { "Active".into() } else { String::new() }
}

pub fn index_of(list: &[Account], id: &str) -> Option<usize> {
    list.iter().position(|a| a.id == id)
}

/// Move one account to the front (it becomes the primary, tried first on every connect).
pub fn make_primary(list: &mut Vec<Account>, id: &str) -> bool {
    match index_of(list, id) {
        Some(i) => { let a = list.remove(i); list.insert(0, a); true }
        None => false,
    }
}

/// Move one account up (-1) or down (+1) in the order.
pub fn shift(list: &mut [Account], id: &str, by: i32) -> bool {
    let Some(i) = index_of(list, id) else { return false };
    let j = i as i64 + by as i64;
    if j < 0 || j >= list.len() as i64 { return false; }
    list.swap(i, j as usize);
    true
}

/// Is every saved account parked or at a data limit right now? (Android `allRefused` wording.)
pub fn all_quota(list: &[Account], now: i64) -> bool {
    !list.is_empty() && list.iter().all(|a| a.state == "LIMIT_QUOTA" && now < a.until)
}

/// Same login twice is one account (usernames are the keychain key).
pub fn has_user(list: &[Account], user: &str, except_id: Option<&str>) -> bool {
    list.iter().any(|a| a.user.eq_ignore_ascii_case(user) && Some(a.id.as_str()) != except_id)
}

/// The first saved login (v1.0 kept exactly one) becomes account 1.
pub fn migrate(list: &mut Vec<Account>, legacy_user: &str, new_id: &str) -> bool {
    if !list.is_empty() || legacy_user.trim().is_empty() { return false; }
    list.push(Account::new(new_id, legacy_user.trim()));
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    fn t(_: i64) -> String { "9:41".into() }
    fn three() -> Vec<Account> { vec![Account::new("a", "u1"), Account::new("b", "u2"), Account::new("c", "u3")] }

    #[test]
    fn primary_first_and_parked_skipped() {
        let mut l = three();
        assert_eq!(candidates(&l, 0, false, false).iter().map(|a| a.id.as_str()).collect::<Vec<_>>(), ["a", "b", "c"]);
        mark(&mut l[0], Kind::WrongPassword, "Invalid user name/password", 1000, 0);
        assert!(l[0].parked());
        assert_eq!(candidates(&l, 2000, false, false).iter().map(|a| a.id.as_str()).collect::<Vec<_>>(), ["b", "c"]);
        // even a manual tap never retries a wrong password (no lockout loop)
        assert_eq!(candidates(&l, 2000, true, true).len(), 2);
        l[0].clear();
        assert_eq!(candidates(&l, 2000, false, false).len(), 3);
    }

    #[test]
    fn session_limit_retried_on_fresh_connect_or_after_15_min() {
        let mut l = three();
        mark(&mut l[0], Kind::LimitSession, "You have reached Maximum Login Limit.", 1000, 0);
        assert_eq!(candidates(&l, 2000, false, false)[0].id, "b");
        assert_eq!(candidates(&l, 2000, true, false)[0].id, "a", "a new Wi-Fi connect tries it again");
        assert_eq!(candidates(&l, 1000 + SESSION_RETRY_MS, false, false)[0].id, "a");
    }

    #[test]
    fn quota_waits_for_midnight_manual_tap_overrides() {
        let mut l = three();
        let midnight = 10_000_000;
        mark(&mut l[0], Kind::LimitQuota, "Your data transfer has been exceeded", 1000, midnight);
        assert_eq!(l[0].until, midnight);
        assert_eq!(candidates(&l, midnight - 1, true, false)[0].id, "b", "a reconnect does not retry a used-up quota");
        assert_eq!(candidates(&l, midnight, false, false)[0].id, "a");
        assert_eq!(candidates(&l, 5000, false, true)[0].id, "a", "a manual tap tries it once");
        // the reset is never sooner than 15 min
        mark(&mut l[1], Kind::LimitQuota, "quota", 1000, 1500);
        assert_eq!(l[1].until, 1000 + SESSION_RETRY_MS);
    }

    #[test]
    fn ok_clears_and_refused_comes_back() {
        let mut a = Account::new("a", "u");
        mark(&mut a, Kind::Refused, "Something odd", 0, 0);
        assert!(a.limited(1, false));
        assert!(!a.limited(SESSION_RETRY_MS, false));
        mark(&mut a, Kind::Ok, "You are signed in as u", 5, 0);
        assert_eq!(a.state, "");
        assert_eq!(a.message, "");
        // an unreadable reply says nothing about the account
        mark(&mut a, Kind::Unreadable, "?", 6, 0);
        assert_eq!(a.state, "");
    }

    #[test]
    fn chips() {
        let mut a = Account::new("a", "u");
        assert_eq!(chip(&a, true, 0, &t), "Active");
        assert_eq!(chip(&a, false, 0, &t), "");
        mark(&mut a, Kind::LimitSession, "limit", 100, 0);
        assert_eq!(chip(&a, true, 200, &t), "Limit reached 9:41");
        assert_eq!(chip(&a, true, 100 + SESSION_RETRY_MS, &t), "Active");
        mark(&mut a, Kind::Refused, "x", 100, 0);
        assert_eq!(chip(&a, false, 200, &t), "Refused 9:41");
        mark(&mut a, Kind::Expired, "Your account has expired", 100, 0);
        assert_eq!(chip(&a, true, 200, &t), "Expired");
        mark(&mut a, Kind::WrongPassword, "Login failed", 100, 0);
        assert_eq!(chip(&a, true, 200, &t), "Wrong password");
    }

    #[test]
    fn ordering_and_migration() {
        let mut l = three();
        assert!(make_primary(&mut l, "c"));
        assert_eq!(l.iter().map(|a| a.id.as_str()).collect::<Vec<_>>(), ["c", "a", "b"]);
        assert!(shift(&mut l, "b", -1));
        assert_eq!(l.iter().map(|a| a.id.as_str()).collect::<Vec<_>>(), ["c", "b", "a"]);
        assert!(!shift(&mut l, "c", -1));
        assert!(!make_primary(&mut l, "zz"));
        assert!(has_user(&l, "U2", None));
        assert!(!has_user(&l, "u2", Some("b")));
        let mut m = Vec::new();
        assert!(migrate(&mut m, " 21bcs001 ", "x1"));
        assert_eq!(m, vec![Account::new("x1", "21bcs001")]);
        assert!(!migrate(&mut m, "other", "x2"), "only ever migrates into an empty list");
        assert!(!migrate(&mut Vec::new(), "", "x3"));
    }

    #[test]
    fn all_quota_wording() {
        let mut l = three();
        assert!(!all_quota(&l, 0));
        for a in l.iter_mut() { mark(a, Kind::LimitQuota, "quota", 0, 1_000_000_000); }
        assert!(all_quota(&l, 10));
        assert!(!all_quota(&[], 10));
    }

    #[test]
    fn settings_json_round_trip_and_old_files() {
        let a = Account { id: "a".into(), user: "u".into(), state: "LIMIT_QUOTA".into(), message: "m".into(), state_at: 1, until: 2 };
        let j = serde_json::to_string(&a).unwrap();
        assert_eq!(serde_json::from_str::<Account>(&j).unwrap(), a);
        let partial: Account = serde_json::from_str(r#"{"id":"z","user":"q"}"#).unwrap();
        assert_eq!(partial, Account::new("z", "q"));
    }
}
