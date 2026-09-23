# signing

`debug.keystore` is the Android debug key **both build types are signed with**,
committed on purpose. It is not a secret: the password and alias are the standard
debug ones (`android` / `androiddebugkey`).

The published APK is the **release** variant - `android:debuggable` off, so no
`adb run-as`, no heap dumps and no debug-only certificate trust - signed with this
key so an install keeps its identity across upgrades.

**What that means.** Anyone with this repository has the key, so anyone can sign a
modified APK that Android installs as an update over the published one and keeps
its data. Trust a build for where it came from, not for the fact that it installs.
Closing that would mean a private release key: one uninstall - and its data - on
every existing install, because Android refuses an update signed by a different
key.

The key is here because a sideload build has to keep **one** identity. Left to
itself, every CI run generates a throwaway debug key of its own, so each build
would have been a dead end for the last one.

Certificate SHA-256:
`BF:50:95:37:AB:DE:00:BD:22:B8:FE:F7:9C:D4:4B:FE:CD:A6:77:0D:65:3F:38:03:0F:47:9F:63:A1:A3:48:76`

Verify a downloaded APK against it (`apksigner` is in the Android SDK's
build-tools; the APK carries a v2 signature, so `keytool -printcert -jarfile`
cannot read it):

    apksigner verify --print-certs app-release.apk
