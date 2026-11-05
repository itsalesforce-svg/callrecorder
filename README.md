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
2. On the app's Settings screen tap **Grant accounts permission**. Android hides the account list from
   apps unless the runtime permission `GET_ACCOUNTS` is granted, and without it
   `AccountManager.getAccountsByType("com.google")` returns an empty array — every account lookup then
   fails with "account is null" / "not available on this phone".
3. Tap **Choose from this device** and pick the address (preferred), or type it exactly as
   `Account.name` (the *e-mail*, not the display name, no trailing spaces).
4. Tap **Test Drive connection**. Four checks run and each one reports pass/fail:
   1. `GET_ACCOUNTS` granted,
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
