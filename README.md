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

## Why the app cannot see the Google accounts that Gmail and Drive use

This is **documented platform behaviour**, not a bug in the phone or in the permission:

- `AccountManager.getAccountsByType` reference: *"Caller targeting API level O and above, will get
  list of accounts made visible to it by user (see `newChooseAccountIntent`) or by
  `AbstractAccountAuthenticator` using `setAccountVisibility`."*
- Android Developers Blog (Android O changes): *"the `GET_ACCOUNTS` permission is no longer
  sufficient to gain access to the list of accounts registered on the device."*

Gmail, Drive and the Play Store are signed by Google, i.e. by the app that owns the `com.google`
account type, so they see every account on the device. Call Recorder targets SDK 35, so the OS returns
an **empty list** to it regardless of the granted permission. The old message ("no Google account found
on this phone") reported that platform rule as a problem with the phone.

### The one action that lifts it

Tap **Choose account (system picker)** — `AccountManager.newChooseAccountIntent()`. From the reference:
*"Chosen account is marked as `VISIBILITY_USER_MANAGED_VISIBLE` to the caller … and will be returned to
it in consequent `getAccountsByType(String)` calls."* The pick is persisted for this package by the
system, so it is needed **once**; afterwards the in-app list works normally. Typing the address still
works without it, because the credential is built from an `Account` object rather than by enumerating.

The Settings screen now states which of the two situations you are in: permission missing, accounts
hidden by Android 8+ visibility rules, or accounts genuinely visible.

**Verified on device** (OPPO CPH1859, Android 9): after one pick in the system chooser the log reads

```
permission=true visible=0 ... hiddenByOs=true      <- before
accounts visible to this app: 1
  - itsalesforce@gmail.com (com.google)            <- after
resolve=itsalesforce@gmail.com -> EXACT device account itsalesforce@gmail.com (com.google)
```

So the account problem is closed; the remaining `UnregisteredOnApiConsole` is a separate gate.

### Android 9 (API 28) specifics

The confirmed test device runs **Android 9 / API 28**. Everything in the two sections above applies in
full, because the account visibility rule keys off the *app's* targetSdk (26+), not the OS version —
and three API-28 details are worth knowing:

- **The call-state broadcast does arrive.** `ACTION_PHONE_STATE_CHANGED` is one of the documented
  implicit-broadcast exemptions, so a manifest-declared receiver still fires on Android 9 after the
  app has been swiped away. Before Android 12 there is also no restriction on starting a foreground
  service from that receiver, so the recording pipeline is not blocked by background limits here.
- **`READ_CALL_LOG` supplies the phone number.** On API 28 the number in `EXTRA_INCOMING_NUMBER` is
  empty without it; the app falls back to `Unknown` rather than skipping the recording.
- **Silent captures are the real Android 9 failure mode.** A third-party app may open
  `VOICE_COMMUNICATION` with no error and record pure silence, leaving a valid MP4 with nothing
  audible. Because `prepare()`/`start()` succeed, a start/fail fallback can never notice. The service
  therefore samples `getMaxAmplitude()` after a 1.2 s warm-up and accepts a source only if it captured
  sound, promoting whatever worked. `MIC` is now the default; on Android 9 that normally captures the
  near side of the conversation only.

## The one remaining gate: registering the signing key

`UnregisteredOnApiConsole` means Google Play services found **no Android OAuth client** matching this
APK's package name + signing SHA-1. Nothing in the app can bypass it: the legacy `GoogleAuthUtil` flow
carries no client id, so Play services does the lookup itself and refuses before the account, the scope
or any app setting is considered. This is the only step that has to happen outside the code.

### It is now a one-time step

The workflow keeps `~/.android/debug.keystore` in the GitHub Actions cache (`android-debug-keystore-v1`).
The first run with this workflow creates the key and caches it; every later run restores the same file,
so **the SHA-1 stops changing**. Register it once and it stays valid for every future download.

1. Install the newest APK.
2. **Settings > Debug & Diagnostics**: read `package:` and `SHA-1:`, or tap **Copy package + SHA-1**,
   or tap **Open Cloud Console credentials** to go straight to the right page.
3. In the **same project** where *Google Drive API* is enabled: *Create credentials > OAuth client ID >
   Android*, paste the package name and the SHA-1.
4. **Test Drive connection** — step 2 should now report the token as issued.

If the cache is ever evicted, the run prints a warning and a new SHA-1; registering the new value takes
a minute. To make it independent of the cache entirely, store a keystore as the secret
`DEBUG_KEYSTORE_BASE64` (commands below) and it always wins.

The cached keystore was introduced after the build that produced `9A:D1:4B:E4:...`, so the APK reports
a **new** SHA-1 once. That value is now pinned and published by every build:

```
Package name: com.example.callrecorder
SHA-1:        69:D8:18:E2:B3:EB:B4:18:02:04:8D:70:20:3A:01:75:99:CE:3F:ED
```

Each run emits it as a Checks annotation (`Signing SHA-1 register this in Cloud Console`) and writes it
to the run summary, and **Settings > Debug & Diagnostics** shows the value read back from the installed
APK - all three must agree. Register that one pair once and Drive sync stops being blocked.

## OPPO / ColorOS (and other aggressive OEM skins)

The test device is an OPPO CPH1859 running ColorOS, which kills background components far more
eagerly than stock Android 9. If calls are not recorded while the app is not open, enable for this app:

- **Settings > Battery > App battery management / Auto-start**: allow auto-start and background
  activity (`com.example.callrecorder`).
- **Recent apps > lock the card** so ColorOS does not purge it.
- Do not use "Force stop" or a cleaner app on it — that cancels the `PHONE_STATE` receiver until the
  app is opened again.

The debug log makes this visible: if a call happened and there is no `CALLS: PHONE_STATE=...` line at
all, the receiver never ran, which points at the OEM restriction rather than at the recorder.
