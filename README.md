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
2. Download the **webviewdp-debug-apk** artifact.
3. Unzip it and sideload `app-debug.apk` onto the phone.

## Build locally (optional)

1. Open this folder in Android Studio and let Gradle sync.
2. `Build > Build App Bundle(s) / APK(s) > Build APK(s)`.

## Change or reset the URL

- Clear the app's data (Settings > Apps > WebView DP > Clear data) to show the
  setup screen again.
- The app also returns to the setup screen if the saved URL fails to load.

## Authentication (harness 0.1.2-alpha.1 and newer)

The harness's browser session gate answers the page with 401 until the browser
exchanges the server's per-launch token. This app handles that automatically:

1. The harness server writes `auth.json` (the per-launch ?token= URLs for the
   tailnet and loopback authorities) into its served dist every start - the
   server's own launcher script (`dsh-launch.ps1`) does this.
2. On a 401 the app fetches `<origin>/auth.json` (a public static asset), loads
   the matching `?token=` URL, and the 303 redirect back to `/` stores the
   30-day signed cookie. From then on the app needs nothing - including across
   harness restarts.

If the harness was started without `dsh-launch.ps1` (so `auth.json` is missing),
the app shows the setup screen with a message instead of a confusing 401 page.

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