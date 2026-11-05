# Android Automatic Call Recorder (Total Recall Clone)

Complete Native Android implementation based on Developer Specification (Target SDK: API 34/35).

## Prerequisites
- Android Studio Ladybug (2024.2+) or Hedgehog/Iguana
- JDK 17
- Android Device or Emulator with API 34+ (Android 14/15)

## Architecture
- **UI**: Jetpack Compose with Material 3 Design System (BOM 2024.09.00)
- **Local DB**: Room 2.6.1 with KSP (Kotlin Symbol Processing)
- **Background Engine**: Foreground Service with `FOREGROUND_SERVICE_MICROPHONE`
- **Telephony Receiver**: `CallStateReceiver` triggers on `EXTRA_STATE_OFFHOOK` and stops on `EXTRA_STATE_IDLE`
- **Cloud Sync**: WorkManager 2.9.1 uploading to Google Drive REST API v3

## Google Drive sync

Recordings are uploaded to the **`CallRecordings`** folder of the Google account you link on the
Settings screen, using the Drive REST API v3 with the `drive.file` scope (the app can only ever see
files it created itself).

### Link an account (do this in order)

1. **Settings > Accounts** on the phone must contain the Google account. The app cannot add one for you.
   It must also be the account the phone is signed in to Play services with, because that is the only
   account Play services will mint an OAuth token for.
2. `GET_ACCOUNTS` must be granted. The app asks for its runtime permissions on **first launch**; if you
   dismissed that prompt, open the Permissions screen (or the Drive card on Settings) and tap
   **Grant accounts permission**. Android hides the account list from apps without this permission, so
   `AccountManager.getAccountsByType("com.google")` returns an empty array and every account lookup
   fails with "account is null" / "not available on this phone".
3. Tap **Choose from this device** and pick the address (preferred), or type it exactly as
   `Account.name` (the *e-mail*, not the display name, no trailing spaces).
4. Tap **Test Drive connection**. Four checks run and each one reports pass/fail:
   1. `GET_ACCOUNTS` granted (reported as a warning, not a hard stop, because the address can still
      be handed straight to the `com.google` authenticator),
   2. saved address resolves to an account on this device,
   3. Google Play services mints an OAuth token for `drive.file`,
   4. the Drive API accepts that token (`about` round-trip).
5. If Google shows a consent screen ("Grant access on Google's screen"), complete it — the test re-runs
   automatically when you come back.

### Reading the error

| Message on the Settings screen | What it actually means | Fix |
|---|---|---|
| `No chosen account found, or the chosen account is no longer installed on the device` / `name must not be empty` | the saved string did not resolve to an account | grant `GET_ACCOUNTS`, then re-pick the account from the list |
| `Android hides the account list…` | runtime permission missing | tap **Grant accounts permission** (or Settings > Apps > Call Recorder > Permissions > Accounts) |
| `Google needs your permission for this account` | OAuth consent not granted / revoked | tap **Grant access on Google's screen**, then test again |
| `Google Play services cannot issue a token (status …)` | Play services missing, disabled, or the account is paused | update/enable Play services, open Gmail once, sign in again |
| `Google Drive auth library is missing from the APK` | `com.google.android.gms:play-services-auth` not on the runtime classpath | keep the dependency in `app/build.gradle.kts` — `google-api-client-android` declares it as `provided`, it is **not** pulled in for you |
| `accessNotConfigured` | Drive API disabled for the Cloud project whose OAuth client you use | enable *Google Drive API* in Cloud Console |
| `Another Google account is visible to this app` (warning row after a failure) | the phone is signed in to Play services with a *different* mailbox than the saved address | tap **Use … instead** under the e-mail field, then test again |
| `TLS handshake with Google failed` | wrong clock, VPN, proxy or Private DNS | fix device date/time, disable the VPN/proxy, retry |

If you create your own OAuth client in Google Cloud (needed only if you switch away from
`GoogleAccountCredential`), the **SHA-1 of the signing key you are installing** must be registered for
`com.example.callrecorder`: `./gradlew signingReport` prints it. A debug-signed APK and a
Play-signed APK therefore behave differently.

### Notes on the implementation

- `auth/AppPrefs.kt` is the only place that touches `SharedPreferences`; there is one key per setting
  and no hard-coded default accounts. Values from the two old preference files are migrated once.
- `auth/GoogleAccountRepository.kt` resolves the account tolerantly (trim, case-insensitive, Gmail
  dots, both `com.google` and `com.google.android.gm` types) and falls back to a synthetic
  `Account(email, "com.google")` when enumeration is blocked, so the picker is not the only path.
- `cloud/DriveErrorMapper.kt` converts the cryptic client exceptions into the text above and extracts
  the recovery `Intent` when Google offers one.
- `GoogleDriveSyncWorker` persists its outcome (state, counts, message) so the Settings screen can show
  "Last run" without logcat; auth problems fail fast instead of retrying forever.

## The "UnregisteredOnApiConsole" failure (why Drive never worked)

The screenshots of the running app showed the decisive line:

```
OAuth access token (https://www.googleapis.com/auth/drive.file)
Unexpected error: com.google.android.gms.auth.GoogleAuthException.UnregisteredOnApiConsole
```

`GoogleAccountCredential` + `GoogleAuthUtil` is the **legacy** Play services flow. There is no client
id in the code: Play services resolves the OAuth client implicitly from the requesting app's
**package name + signing certificate SHA-1**. If no *Android* OAuth client in the Cloud project has
that exact pair, the token request is refused with `UnregisteredOnApiConsole` — before the account,
the scope or the “share with other Google apps” switch is even considered. That is why the account
could be perfectly present on the phone and still produce "account is null" style errors.

Worse, **every GitHub Actions run generates a brand-new debug keystore**, so the SHA-1 of a
downloaded APK differs from the previous download. Registering a SHA-1 is therefore only useful if
the keystore is stable.

### Fix it once

1. Install the APK from the latest Actions run.
2. Open **Settings > Debug & Diagnostics**. The card shows `package:` and `SHA-1:` for the APK that is
   actually installed. Tap **Copy package + SHA-1** (or read the SHA-1 under the run's Summary page,
   printed by the "Show signing SHA-1" step).
3. In <https://console.cloud.google.com> pick the **same project** where *Google Drive API* is enabled
   (APIs & Services > Enabled APIs). Then **APIs & Services > Credentials > Create credentials >
   OAuth client ID > Android** and paste:
   - Package name: `com.example.callrecorder`
   - SHA-1: the value from step 2
4. Back in the app tap **Test Drive connection**. Step 2 must now report the token as issued.

### Make the SHA-1 permanent (so the next download keeps working)

Create a keystore once, on any machine with a JDK:

```bash
keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
base64 -w0 debug.keystore      # use `base64 -i debug.keystore` on macOS
```

Add the printed text as the repository secret **`DEBUG_KEYSTORE_BASE64`**
(Settings > Secrets and variables > Actions). The workflow restores it before building, so every
future APK is signed with the same key and the SHA-1 registered in Cloud Console stays valid. Register
that stable SHA-1 once and this whole failure class disappears.

## Debug & Diagnostics card

Everything needed to diagnose a failure without a USB cable:

| Button | What it does |
|---|---|
| **Run self-test** | Dumps every account the app can see, requests a token **directly** from `GoogleAuthUtil` (surfacing the real message instead of a wrapped `IOException`), then records a 2 second microphone sample and reports which `AudioSource` actually produced audio |
| **Share debug log** | `ACTION_SEND` with the log file (via `FileProvider`) — mail it, or send it in chat |
| **Copy log** | Same content to the clipboard |
| **Signing identity** | `package:` + `SHA-1:` / `SHA-256:` of the installed APK, copyable |

The log (`filesDir/callrecorder-debug.log`, last 500 lines, mirrored to `logcat` under the tag
`CallRecorder`) records: app version, Android version, device model, signing fingerprints, every
telephony state transition, whether the recording service started, which audio source was tried and
how many bytes it produced, every Drive test step with pass/fail detail, the raw exception chain from
Play services, and every sync outcome. **OAuth tokens are recorded as a length only, never the value.**

## Recording: why calls produced no files

The recording pipeline had no diagnostics at all, so failure looked like a removed feature. Each of
these now writes to the debug log *and* to the Settings card:

- `Automatic Call Recording` being off made the service `stopSelf()` silently;
- a missing `RECORD_AUDIO` permission;
- `startForegroundService` throwing `ForegroundServiceStartNotAllowedException` (Android 12+) from the
  broadcast receiver — the exception used to be lost with the receiver;
- `EXTRA_INCOMING_NUMBER` arriving as a String on some OEM builds where the typed getter returns null;
- the recorder failing on every audio source, or producing a 0-byte file because the OS blocks call
  audio capture.

Use **Run self-test** (it records straight to the microphone) to find out whether this Android build
can capture audio at all, and with which source. If `MIC` works but the call is silent, the OS is
blocking call audio — that is a platform limitation, not a bug in the app.
