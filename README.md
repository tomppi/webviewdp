# webviewdp

A minimal Android WebView app that opens your DeepSeek Harness web UI over a
Tailscale tailnet, giving you a dedicated app instead of the phone browser.

On first launch it shows a setup screen where you paste your harness URL
(the Tailscale `https://<machine>.<tailnet>.ts.net/` address). The URL is
stored on the device, so subsequent launches go straight to the harness.

## Requirements

- The [Tailscale](https://tailscale.com/) app installed and connected on the
  phone. The WebView relies on the Tailscale VPN for MagicDNS and tailnet
  routing; no exit node is needed.
- A device running Android 8.0 (API 26) or newer.

## Download a build

GitHub Actions builds a debug APK on every push to `main` (and on manual
`workflow_dispatch` runs):

1. Go to the repo's **Actions** tab and open the latest **Build APK** run.
2. Download the **webviewdp-apk** artifact.
3. Unzip it and sideload `app-release.apk` onto the phone.

The published APK is the release variant, signed with the committed
[`signing/debug.keystore`](signing/README.md): not `android:debuggable`, and the
same identity the previous builds had, so it installs over one in place.

## Build locally (optional)

1. Open this folder in Android Studio and let Gradle sync.
2. `Build > Build App Bundle(s) / APK(s) > Build APK(s)`.

## Change or reset the URL

- Clear the app's data (Settings > Apps > WebView DP > Clear data) to show the
  setup screen again.
- The app also returns to the setup screen if the saved URL fails to load.

## Authentication (harness 0.1.2-alpha.1 and newer)

The harness's browser session gate answers the page with 401 until the browser
exchanges the server's per-launch token. The token is a login for a machine the
harness can run code on, so nothing publishes it: you paste the launch URL once.

1. [`dsh-launch.ps1`](dsh-launch.ps1) starts the server and catches the
   `?token=` URL it prints, one per authority (tailnet name, loopback).
2. Paste that URL into this app. Loading it answers a 303 whose `Set-Cookie` is
   a 30-day signed session, which the WebView keeps: after that the app opens the
   plain address and needs no token - including across harness restarts.
3. When the cookie is gone (30 days, or app data cleared) the app shows the setup
   screen and says so, instead of a confusing 401 page.

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
- No dependencies or analytics; it is a plain `WebView` with JavaScript and
  DOM storage enabled.
- **Background recovery:** Android can reclaim the WebView's renderer process
  while the app is in the background (memory pressure; heavier harness pages
  make this likely). The app notices via `onRenderProcessGone` and reloads the
  current page automatically instead of leaving a blank screen; after repeated
  rapid failures it returns to the setup screen. Logcat (tag `WebViewDP`)
  records every reload and its cause.

## License

MIT - see [`LICENSE`](LICENSE).