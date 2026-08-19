# webviewdp

A minimal Android WebView app that opens your DeepSeek Harness web UI over a
Tailscale tailnet, giving you a dedicated app instead of the phone browser.

## Requirements

- Android Studio (with JDK 17+).
- The [Tailscale](https://tailscale.com/) app installed and connected on the
  phone. The WebView relies on the Tailscale VPN for MagicDNS and tailnet
  routing; no exit node is needed.
- A device running Android 8.0 (API 26) or newer.

## Build and install

1. Open this folder in Android Studio and let Gradle sync.
2. `Build > Build App Bundle(s) / APK(s) > Build APK(s)`, or run on a
   connected device.
3. Install the APK and open it with Tailscale connected.

## Change the target URL

Edit `app/src/main/res/values/strings.xml` and set `target_url` to your
harness's `https://<machine>.<tailnet>.ts.net/` URL.

## Notes

- Keeps all navigation inside the WebView and supports file uploads.
- No dependencies or analytics; it is a plain `WebView` with JavaScript and
  DOM storage enabled.
