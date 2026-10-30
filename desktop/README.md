<p align="center"><img src="app-icon.png" width="96" alt="College WiFi"></p>

# College WiFi for Windows, macOS and Linux

The desktop version of the College WiFi Android app. It signs you in to the campus Wi-Fi captive portal by itself (IIIT Dharwad's Sophos portal out of the box), shows how much of today's data you have left, and sits in your tray / menu bar.

<p align="center"><img src="docs/light_home.png" width="440" alt="Light"> <img src="docs/dark_sidebar.png" width="440" alt="Dark, sidebar open"></p>

## Download (v1.1.0)

Every installer is on the [desktop-v1.1.0 release](https://github.com/chinmays-tanx09/college-wifi/releases/tag/desktop-v1.1.0), built by GitHub Actions:

| OS | File | Notes |
|---|---|---|
| Windows 10/11 (x64) | `College.WiFi_1.1.0_x64-setup.exe` (or the `.msi`) | Installs for your user only, no admin needed. Not code-signed, so SmartScreen may say "Windows protected your PC": click **More info → Run anyway**. Updating from 1.0.0 keeps your saved login (it becomes account 1). |
| Linux | `.AppImage` (any distro) or `.deb` (Debian/Ubuntu) | `chmod +x` the AppImage and run it, or `sudo apt install ./….deb`. |
| macOS 10.15+ (Apple silicon + Intel) | Universal `.dmg` | Not notarized: the first time, right-click the app → **Open**. |

## What's new in 1.1.0

- **Several accounts, like the Android app.** Accounts page in the sidebar: add, edit, remove, reorder. The first account is your primary and is tried first; if the portal refuses it or it hits a limit, the next one signs in by itself. A wrong password is tried once and then parked until you edit it (no lockout loop). A device/login limit is retried on the next Wi-Fi connect or after 15 min, and a data limit after midnight. "Use now" signs the current account out and signs in with the one you picked. If the portal says you're signed in but still blocks the internet (data used up), it moves on to the next account.
- **Sign-in fixes.** It no longer shows "Connected" just because the portal says your username is live (for example, signed in on your phone): it checks that this computer really has the internet, and signs in if not. If a VPN, phone hotspot or Ethernet owns the default route, it retries the portal from the Wi-Fi adapter's own address. It re-checks every minute, so a missed network event can't leave it stuck on "Not on college Wi-Fi". If the portal moves, it finds the new address from the connectivity check's redirect.
- **Clear errors.** "Couldn't reach the portal: <why>" (timed out, refused, no route…) is now separate from "Portal rejected the login" with the portal's own words. When login.xml answers with a block page or a redirect, the page's text is read (as on Android).
- **Connection log.** Settings → Troubleshooting → Connection log (Copy button), and an optional log file. It never contains passwords.

## What it does

- **One big button.** The ring shows today's data used against your allowance; the button in the middle logs you in or out of the portal.
- **Auto-login.** It reacts to network changes (the OS tells it when an interface comes up), checks whether you're on the college network, and signs in if the portal is blocking you. While you're on the college network it also looks once a minute (a tiny "still signed in?" request plus the standard connectivity check). Off campus the minute check is only the Wi-Fi name and a TCP knock on the portal; nothing is sent.
- **Recognises the college network** by its Wi-Fi name (default `IIIT DHARWAD-STUDENT`; editable) or, when the OS won't share the name, by whether the portal (`172.16.16.16:8090`) answers.
- **Data left today**, read from the campus Sophos User Portal ("Internet usage", the daily cycle table), the same way the Android app reads it.
- **Tray / menu bar icon** with Log in, Log out, Open and Quit. Closing the window keeps it running in the tray.
- **Start at login** (optional), light / dark / system theme, ChatGPT-style collapsible sidebar (Ctrl/Cmd+B).

## Privacy

- Your password goes into the OS keychain: **Windows Credential Manager**, **macOS Keychain**, or the **Secret Service** keyring on Linux (GNOME Keyring / KWallet). It is never written to a file.
- No telemetry, no analytics, no servers of ours. The app only talks to the campus portal and to the standard connectivity checks (`connectivitycheck.gstatic.com` / `captive.apple.com`) to see whether the portal is blocking you.
- The User Portal's self-signed HTTPS certificate is trusted only for the portal host and pinned the first time it's seen; if it changes, the app stops and won't send your login.

## How it works

| Piece | File |
|---|---|
| Sophos protocol (login `mode=191`, keep-alive `/live?mode=192`, logout `mode=193`), byte-for-byte the same requests as the Android app | `src-tauri/src/portal.rs` |
| Usage table parser + the v5.3 used/limit swap fix (`PortalUsage.fixup`) | `src-tauri/src/usage.rs` |
| User Portal reader (hidden webview through a local pinned-certificate proxy) | `src-tauri/src/userportal.rs` |
| Wi-Fi name (`netsh` / `ipconfig getsummary` / `nmcli`) | `src-tauri/src/net.rs` |
| Engine, tray, network events, commands | `src-tauri/src/lib.rs` |
| UI (plain HTML/CSS/JS, monochrome) | `ui/` |

Built with [Tauri 2](https://tauri.app) (Rust + the system webview), so the installers are a few MB.

## Build it yourself

You need Rust (stable), Node 18+, and on Linux `libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev libdbus-1-dev`.

```sh
cd desktop
npm install
cargo test --manifest-path src-tauri/Cargo.toml   # portal + usage unit tests (Android fixtures)
npx tauri dev                                      # run it
npx tauri build                                    # installers for the OS you're on
```

GitHub Actions builds all three OSes and publishes a release: see [`ci/desktop.yml`](ci/desktop.yml) (also at `.github/workflows/desktop.yml` when present). Push a tag like `desktop-v1.0.0`, or run the workflow by hand.

## Status and honesty

- The portal requests are a straight port of the Android code and are unit-tested against the same fixtures, but **this desktop build has not yet signed in on the real campus network.** If something's off, open an issue with what the status line says.
- Login, logout and the usage reader can't be tested away from campus.

MIT licence, same as the Android app.
