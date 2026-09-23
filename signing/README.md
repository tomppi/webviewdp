# signing

`release.jks` is the **private** key the published APK is signed with. It is not
in this repository, and neither is `keystore.properties`, which carries its
passwords. Both are gitignored.

Locally, signing reads `keystore.properties`:

    storeFile=signing/release.jks
    storePassword=<password>
    keyAlias=webviewdp
    keyPassword=<password>

CI reads the same four values from repository secrets - `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` - and decodes the keystore to
`signing/release.jks` before the build. A release build without a usable key stops
rather than producing an unsigned APK. The debug build type uses the SDK's own
throwaway `~/.android/debug.keystore`: a debug APK is a local artifact, so its
identity only has to outlive the machine that built it.

**Back the key up, with its passwords, off this machine.** Losing it means no
future build can install over the ones it signed - Android refuses an update
signed by a different key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and the only way
past that uninstalls the app, taking its data with it.

Certificate SHA-256:
`B6:51:D1:93:11:01:F5:7B:85:5C:D1:19:0B:5B:7E:14:0D:16:BD:A7:69:11:0D:15:00:06:C7:E3:1E:CF:BC:55`

Verify a downloaded APK against it (`apksigner` is in the Android SDK's
build-tools; the APK carries a v2 signature, so `keytool -printcert -jarfile`
cannot read it):

    apksigner verify --print-certs app-release.apk

## The key this replaced

Every build up to and including 1.4 was signed with a debug key committed here, so
its certificate is **public**: anyone who cloned the repository has it and can sign
an APK Android installs as an update over 1.4, keeping its data. An install signed
with it has to be uninstalled once before a `release.jks` build will install.
