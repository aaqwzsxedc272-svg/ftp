# PhoneFTP 📂

An Android app that turns your phone into an **always-on FTP server**, so other
devices (PC, another phone, a TV box, …) can browse / upload / download files
on your phone over your local network.

> ⚠️ Status: first working version (v0.1). FTP logic, auto-start and
> network-change handling are implemented; the build has not yet been compiled
> in CI — open it in Android Studio for the first build (see below).
> Intended platform: **Android** (iOS does not allow apps to run a permanent
> background server like this).

## What it does

- **Real FTP server** on the phone (default port **9999**, passive mode):
  login with username/password (or optional anonymous), browse, download
  (`RETR`), upload (`STOR`/`APPE`), delete, rename, create folders, resume
  downloads (`REST`).
- **Runs permanently** as a foreground service:
  - starts automatically when the phone **boots** / the app is updated,
  - **START_STICKY** + alarm restart if the system kills or the user swipes
    the app away,
  - persistent notification showing the current `ftp://…` address(es) and a
    Stop button.
- **Survives network changes automatically:**
  - the server binds `0.0.0.0`, so it works on whatever interface is up;
  - a `ConnectivityManager` network callback + Wi-Fi/hotspot state listener
    watches for changes (new Wi-Fi joined, Wi-Fi changed, **hotspot turned
    on/off**), makes sure the server (re)starts and refreshes the shown IP.
  - **Hotspot case:** connect other devices to your phone's hotspot and use
    the address shown in the app/notification — typically
    `ftp://192.168.43.1:9999` (varies by brand).
- Serves the **whole shared storage** if you grant *All files access*,
  otherwise the app-private folder on external storage. You can also type a
  custom folder path in the app.

## Target device (current setup)

- **Samsung SM-A127F** (Galaxy A12 family), **Android 13**, One UI Core 5.1,
  Knox 3.9, security patch 2024-10-01.
- Current Wi-Fi address seen: `192.168.1.38` → once installed, the server will
  be at **`ftp://192.168.1.38:9999`** on that network (address changes with the
  network; the app/notification always shows the current one).
- The app UI is in **English** and shows Samsung-specific keep-alive steps
  on Samsung devices. The shared folder is chosen in the app with the
  **“Pick shared folder”** button (system folder picker), and the FTP
  username/password are set in the app's Settings section.
- **Samsung setup for true 24/7 running (important on One UI):**
  1. Settings → Apps → PhoneFTP → **Battery → Unrestricted**
  2. Settings → Battery → Background usage limits → **Never sleeping apps →
     add PhoneFTP**
  3. Lock PhoneFTP in Recents (⋮ → Lock this app)
  4. In the app: grant *All files access* + *Ignore battery optimizations*

## Build & install

1. Install **Android Studio** (it brings the JDK + Android SDK).
2. Open this folder in Android Studio, let Gradle sync, then
   **Run ▶** on your phone (USB debugging) — or build an APK with
   `./gradlew assembleDebug` (APK appears in
   `app/build/outputs/apk/debug/`).
3. First launch checklist in the app:
   - set a **username/password** (default `mou` / `mou`),
   - tap **Grant all-files access** (to serve your whole storage),
   - tap **Ignore battery optimizations**,
   - on Xiaomi / Samsung / Oppo etc., also set the app battery mode to
     **“No restrictions”** and/or lock it in Recents — those brands kill
     background apps aggressively, and no app can fully override that.
4. From another device on the same Wi-Fi (or connected to your hotspot),
   open the shown address in any FTP client (FileZilla, Windows Explorer
   address bar, FE File Explorer, `curl ftp://…`, …):
   `ftp://192.168.x.x:9999`, passive mode.

## Notes / limits

- **Plain FTP is not encrypted** — passwords and files travel in clear text.
  Use it on networks you trust (home Wi-Fi / your own hotspot). Don't expose
  port 9999 to the internet. (SFTP/FTPS can be added later if needed.)
- Passive data ports: `50000–50100`. Clients on the same LAN/hotspot need no
  router configuration.
- Android itself can still stop any app in extreme cases (force-stop from
  Settings, some OEM “deep sleep” features). Foreground-service + boot +
  network-restart covers everything an app is legitimately allowed to do.

## Project layout

```
app/src/main/java/com/phoneftp/
├── FtpSettings.kt          # persisted settings (port, login, root folder, auto-start)
├── ftp/
│   ├── FtpServer.kt        # listening socket (0.0.0.0), session pool
│   ├── FtpSession.kt       # FTP protocol per client, sandboxed to the root folder
│   └── LocalIp.kt          # current LAN/hotspot addresses of the phone
├── service/
│   ├── FtpService.kt       # foreground service, network-change watcher, auto-restart
│   └── BootReceiver.kt     # start on boot / after app update
└── ui/MainActivity.kt      # status, addresses, settings, permission shortcuts
```

## Roadmap (tell me what you want next)

Ideas for the next iterations: folder picker (SAF) instead of typed path,
multiple users, SFTP/FTPS, start/stop widget + quick-settings tile,
QR code with the connection address, transfer log in the app, read-only mode.
