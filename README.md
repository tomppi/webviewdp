# webviewdp

A minimal Android WebView app for your DeepSeek Harness, reached over a Tailscale
tailnet: a dedicated app instead of the phone browser, and one the harness treats
as an operator.

On first launch it shows a setup screen where you paste your launch URL (the
Tailscale `https://<machine>.<tailnet>.ts.net/?token=…` address printed by
[`dsh-launch.sh`](dsh-launch.sh) or [`dsh-url.sh`](dsh-url.sh)). The app signs in
with it, keeps the session in an encrypted jar of its own, and from then on serves
the harness to its own WebView from `http://127.0.0.1:<port>`.

That loopback origin is the point. The harness decides what a page may do by
where its document came from: a page the harness serves itself over the tailnet is
not loopback, and gets no durable settings - every preference resets when the page
reloads. A page from `127.0.0.1` is, so the harness treats this app exactly as it
treats the browser on the machine itself, and settings stick. The listener accepts
connections only from this device and relays to the real harness with the session
attached, so the WebView never holds a credential and never loads a remote page.

## Requirements

- The [Tailscale](https://tailscale.com/) app installed and connected on the
  phone. The WebView relies on the Tailscale VPN for MagicDNS and tailnet
  routing; no exit node is needed.
- A device running Android 8.0 (API 26) or newer.

## Download a build

GitHub Actions builds the release APK on every push to `main` (and on manual
`workflow_dispatch` runs):

1. Go to the repo's **Actions** tab and open the latest **Build APK** run.
2. Download the **webviewdp-apk** artifact.
3. Unzip it and sideload `app-release.apk` onto the phone.

The published APK is the release variant, signed with the private release key
described in [`signing/README.md`](signing/README.md): not `android:debuggable`,
and the same identity the previous builds had, so it installs over one in place.

## Build locally (optional)

1. Open this folder in Android Studio and let Gradle sync.
2. `Build > Build App Bundle(s) / APK(s) > Build APK(s)`.

## Change or reset the URL

- Clear the app's data (Settings > Apps > WebView DP > Clear data) to show the
  setup screen again and forget the session.
- The app also returns to the setup screen when the harness refuses its session
  (the 30-day cookie expired, or the harness restarted with a new secret): paste a
  fresh launch URL there.

## Authentication (harness 0.1.2-alpha.1 and newer)

The harness's browser session gate answers the page with 401 until the browser
exchanges the server's per-launch token. The token is a login for a machine the
harness can run code on, so nothing publishes it: you paste the launch URL once.

1. [`dsh-launch.ps1`](dsh-launch.ps1) starts the server and catches the
   `?token=` URL it prints, one per authority (tailnet name, loopback).
2. Paste that URL into this app. The app - not the WebView - exchanges it for the
   303's `Set-Cookie`, a 30-day signed session it keeps encrypted at rest in the
   Android Keystore and attaches to every relayed request. The token and the
   cookie never enter the page, its history or its script context.
3. When that session is gone (30 days, app data cleared, or a harness restart with
   a new signing secret) the app shows the setup screen and says so, instead of a
   confusing 401 page. The plain address is enough while the session lasts; the
   `?token=` URL is needed only to get one.

Upgrading from 1.4 needs no new paste: on first start the app adopts the cookie
the old version left in the WebView's jar.

Earlier versions read the token from `auth.json` in the harness's served dist.
Every static asset is public, so that file was a working sign-in for anything
that could reach the port; the launcher no longer writes it and this app no
longer reads it. `-PublishAuthJson` restores the old behaviour, which WebView
DP 1.4 and earlier still needs.

## Running the harness

The harness itself is [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness).
Build a checkout, then start it through the launcher, which prints the launch
URLs:

```powershell
powershell -ExecutionPolicy Bypass -File dsh-launch.ps1 `
    -TailHost machine.tailnet-name.ts.net -Repo C:\src\deepseek-harness
```

- `-TailHost` is the authority clients reach the harness by, and also becomes
  the server's `--trusted-host`. Use the tailnet name so TLS and MagicDNS line
  up; a bare address works for a local test.
- `-Repo` is a built checkout (`apps/cli/lib/bin.js` must exist). `node` is
  taken from `PATH`.
- It refuses to start a second server on a port that is already serving, and
  waits up to ten minutes for the token before giving up with the log tail.
- `-PublishAuthJson` additionally writes `<Repo>\apps\web\dist\auth.json` - a
  public sign-in file - for a client too old to paste the URL. `-DistRoot` and
  `-Port` pick a throwaway instance.
- [`dsh-url.ps1`](dsh-url.ps1) prints the current launch URLs again, read from
  the launcher's log, for when the console has scrolled away or the server runs
  as a scheduled task. It needs no arguments and warns when the token it found
  is stale.

On Debian (or any POSIX shell) [`dsh-launch.sh`](dsh-launch.sh) is the same
wrapper with the same job, taking its settings from the environment instead of
parameters - `DSH_REPO`, `DSH_PORT`, `DSH_TAIL_HOST`, `DSH_PUBLISH_AUTH_JSON`,
`DSH_FOREGROUND` - and printing the same three URLs, with
[`dsh-url.sh`](dsh-url.sh) to print them again later. Its log is
`$XDG_STATE_HOME/dsh/web.<port>.out.log`, which both scripts read; when the
server runs as root under a systemd unit, that file is root-only and
`dsh-url.sh` has to be run with sudo.
- It stays alive alongside the server, which is what makes it usable as a
  scheduled task.

## Notes

- Keeps all navigation inside the WebView and supports file uploads.
- One dependency, [OkHttp](https://square.github.io/okhttp/), for the relay:
  connection pooling, a cookie jar, and streaming uploads and downloads. No
  analytics.
- The relay forwards every path unchanged and caches nothing; only the authority
  markers the harness's request fence inspects are rewritten to the tailnet name.
- Cleartext HTTP is permitted for `127.0.0.1` alone
  (`app/src/main/res/xml/network_security_config.xml`); the harness hop stays
  HTTPS with the system trust store and hostname verification.
- **Background recovery:** Android can reclaim the WebView's renderer process
  while the app is in the background (memory pressure; heavier harness pages
  make this likely). The app notices via `onRenderProcessGone` and reloads the
  current page automatically instead of leaving a blank screen; after repeated
  rapid failures it returns to the setup screen. Logcat (tag `WebViewDP`)
  records every reload and its cause.

## License

MIT - see [`LICENSE`](LICENSE).