package com.example.callrecorder.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * SINGLE SOURCE OF TRUTH for every persisted setting.
 *
 * Why this class exists: the Drive layer used to read
 * `call_recorder_prefs/google_account_name` while the Settings screen read
 * `call_recorder_settings/google_account` with a *hard-coded* fallback of
 * "itsalesforce@gmail.com". The result: the UI always showed a linked account while
 * `GoogleAuthManager` saw `null`, so `getGoogleDriveService()` returned null, the worker
 * bailed out with `Result.retry()` forever and the user saw "account is null" /
 * "not available on this phone" even though an address was on screen.
 *
 * Everything now lives in one file, under one key, with no fake defaults: a null value here
 * really means "nothing linked yet".
 */
object AppPrefs {

    private const val FILE_NAME = "call_recorder_prefs"
    private const val LEGACY_FILE_NAME = "call_recorder_settings"

    const val KEY_ACCOUNT_NAME = "google_account_name"
    private const val KEY_ACCOUNT_SYNTHETIC = "google_account_synthetic"
    private const val KEY_AUTO_RECORD = "auto_record"
    private const val KEY_AUDIO_SOURCE = "audio_source"
    private const val KEY_AUTO_SYNC = "auto_sync"
    private const val KEY_SYNC_WIFI_ONLY = "sync_wifi_only"
    private const val KEY_SYNC_INTERVAL_HOURS = "sync_interval_hours"
    private const val KEY_DRIVE_CONSENT_NEEDED = "drive_consent_needed"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val KEY_LAST_SYNC_UPLOADED = "last_sync_uploaded"
    private const val KEY_LAST_SYNC_FAILED = "last_sync_failed"
    private const val KEY_LAST_SYNC_MESSAGE = "last_sync_message"
    private const val KEY_LAST_SYNC_STATE = "last_sync_state"
    private const val KEY_LAST_RECORDING_ISSUE = "last_call_recording_issue"
    private const val KEY_DRIVE_FOLDER_ID = "drive_folder_id"
    private const val KEY_MIGRATED = "prefs_migrated_v2"
    private const val KEY_STARTUP_PERMISSIONS_ASKED = "startup_permissions_asked"

    /** Value written by the previous SettingsViewModel as a *default*, never by a real user choice. */
    private const val LEGACY_PLACEHOLDER_ACCOUNT = "itsalesforce@gmail.com"

    private val migrationLock = Any()

    /** Cached handle; SharedPreferences.getInstance-like semantics. */
    @Volatile
    private var cached: SharedPreferences? = null

    fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(migrationLock) {
            cached?.let { return it }
            val app = context.applicationContext
            val p = app.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            migrateLegacyValues(app, p)
            cached = p
            return p
        }
    }

    private fun migrateLegacyValues(app: Context, target: SharedPreferences) {
        if (target.getBoolean(KEY_MIGRATED, false)) return
        try {
            val legacy = app.getSharedPreferences(LEGACY_FILE_NAME, Context.MODE_PRIVATE)
            val editor = target.edit()
            if (legacy.contains(KEY_AUTO_RECORD)) {
                editor.putBoolean(KEY_AUTO_RECORD, legacy.getBoolean(KEY_AUTO_RECORD, true))
            }
            if (legacy.contains(KEY_AUDIO_SOURCE)) {
                legacy.getString(KEY_AUDIO_SOURCE, null)?.let { editor.putString(KEY_AUDIO_SOURCE, it) }
            }
            if (legacy.contains(KEY_SYNC_WIFI_ONLY)) {
                editor.putBoolean(KEY_SYNC_WIFI_ONLY, legacy.getBoolean(KEY_SYNC_WIFI_ONLY, true))
            }
            if (legacy.contains(KEY_SYNC_INTERVAL_HOURS)) {
                editor.putLong(KEY_SYNC_INTERVAL_HOURS, legacy.getLong(KEY_SYNC_INTERVAL_HOURS, 4L))
            }
            // Older builds called this "auto_sync_enabled" and kept the recording issue here.
            if (legacy.contains("auto_sync_enabled")) {
                editor.putBoolean(KEY_AUTO_SYNC, legacy.getBoolean("auto_sync_enabled", true))
            }
            if (legacy.contains(KEY_LAST_RECORDING_ISSUE)) {
                legacy.getString(KEY_LAST_RECORDING_ISSUE, null)
                    ?.let { editor.putString(KEY_LAST_RECORDING_ISSUE, it) }
            }
            // Only carry the old account over when the user actually saved one. The legacy screen
            // returned "itsalesforce@gmail.com" as a *default* even when nothing was ever saved,
            // and migrating that phantom value is exactly what produced the confusing error.
            if (legacy.contains(KEY_ACCOUNT_NAME)) {
                legacy.getString(KEY_ACCOUNT_NAME, null)?.let { editor.putString(KEY_ACCOUNT_NAME, it) }
            } else if (legacy.contains("google_account")) {
                val saved = legacy.getString("google_account", null)?.trim().orEmpty()
                if (saved.isNotEmpty() && !saved.equals(LEGACY_PLACEHOLDER_ACCOUNT, ignoreCase = true)) {
                    editor.putString(KEY_ACCOUNT_NAME, saved)
                }
            }
            editor.putBoolean(KEY_MIGRATED, true)
            editor.apply()
        } catch (t: Throwable) {
            // Migration must never take the app down; a fresh (empty) preference set is fine.
            target.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Google account
    // -----------------------------------------------------------------------------------------

    fun accountName(context: Context): String? =
        prefs(context).getString(KEY_ACCOUNT_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * True when the account could not be found through [android.accounts.AccountManager] and we
     * are talking to the `com.google` authenticator with a synthetic [android.accounts.Account]
     * built from the e-mail address (used when account enumeration is blocked for this app).
     */
    fun accountIsSynthetic(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACCOUNT_SYNTHETIC, false)

    fun saveAccount(context: Context, name: String?, synthetic: Boolean) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        prefs(context).edit().apply {
            if (trimmed == null) remove(KEY_ACCOUNT_NAME) else putString(KEY_ACCOUNT_NAME, trimmed)
            putBoolean(KEY_ACCOUNT_SYNTHETIC, synthetic)
            putBoolean(KEY_DRIVE_CONSENT_NEEDED, false)
        }.apply()
    }

    fun clearAccount(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_ACCOUNT_SYNTHETIC)
            .putBoolean(KEY_DRIVE_CONSENT_NEEDED, false)
            .apply()
    }

    // -----------------------------------------------------------------------------------------
    // Recording / sync preferences
    // -----------------------------------------------------------------------------------------

    /**
     * True once the app has asked for its runtime permissions on its own. Asking on first launch is
     * what prevents the "account is null" state without the user having to discover that
     * GET_ACCOUNTS was never granted.
     */
    fun startupPermissionsAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STARTUP_PERMISSIONS_ASKED, false)

    fun setStartupPermissionsAsked(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_STARTUP_PERMISSIONS_ASKED, value).apply()

    fun autoRecord(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_RECORD, true)

    fun setAutoRecord(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_RECORD, value).apply()

    /**
     * Default audio source.
     *
     * `VOICE_COMMUNICATION` used to be the default because it is the call-tuned stream. On Android 9
     * (API 28) and older, a third-party app can open it without any error and still receive pure
     * silence, so the recording looked successful while containing nothing audible. `MIC` starts on
     * essentially every build and captures at least the near side of the conversation, and the
     * service now *verifies* audio with `getMaxAmplitude()` and promotes whichever source actually
     * produced sound (see CallRecordService.startRecorderWithFallback).
     */
    fun audioSource(context: Context): String =
        prefs(context).getString(KEY_AUDIO_SOURCE, "MIC") ?: "MIC"

    fun setAudioSource(context: Context, value: String) =
        prefs(context).edit().putString(KEY_AUDIO_SOURCE, value).apply()

    /** Why the last recording attempt failed (shown on the Settings screen), null = all good. */
    fun lastRecordingIssue(context: Context): String? =
        prefs(context).getString(KEY_LAST_RECORDING_ISSUE, null)

    fun setLastRecordingIssue(context: Context, issue: String?) {
        prefs(context).edit().apply {
            if (issue.isNullOrBlank()) remove(KEY_LAST_RECORDING_ISSUE)
            else putString(KEY_LAST_RECORDING_ISSUE, issue)
        }.apply()
    }

    /** Auto-sync (background upload) on/off. Default true so a fresh install actually syncs. */
    fun autoSync(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_SYNC, true)

    fun setAutoSync(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    fun syncWifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_SYNC_WIFI_ONLY, true)

    fun setSyncWifiOnly(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SYNC_WIFI_ONLY, value).apply()

    /** Hours between background sync runs; WorkManager enforces a 15 minute minimum anyway. */
    fun syncIntervalHours(context: Context): Long =
        prefs(context).getLong(KEY_SYNC_INTERVAL_HOURS, 4L).coerceAtLeast(1L)

    fun setSyncIntervalHours(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_SYNC_INTERVAL_HOURS, value.coerceAtLeast(1L)).apply()

    // -----------------------------------------------------------------------------------------
    // Drive status (written by GoogleDriveSyncWorker, read by the Settings screen)
    // -----------------------------------------------------------------------------------------

    fun driveConsentNeeded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DRIVE_CONSENT_NEEDED, false)

    fun setDriveConsentNeeded(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_DRIVE_CONSENT_NEEDED, value).apply()

    fun lastSyncState(context: Context): String? = prefs(context).getString(KEY_LAST_SYNC_STATE, null)

    fun lastSyncAt(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC_AT, 0L)

    fun lastSyncMessage(context: Context): String? = prefs(context).getString(KEY_LAST_SYNC_MESSAGE, null)

    fun lastSyncUploaded(context: Context): Int = prefs(context).getInt(KEY_LAST_SYNC_UPLOADED, 0)

    fun lastSyncFailed(context: Context): Int = prefs(context).getInt(KEY_LAST_SYNC_FAILED, 0)

    fun recordSyncResult(
        context: Context,
        state: String,
        uploaded: Int,
        failed: Int,
        message: String?
    ) {
        prefs(context).edit().apply {
            putString(KEY_LAST_SYNC_STATE, state)
            putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
            putInt(KEY_LAST_SYNC_UPLOADED, uploaded)
            putInt(KEY_LAST_SYNC_FAILED, failed)
            if (message == null) remove(KEY_LAST_SYNC_MESSAGE) else putString(KEY_LAST_SYNC_MESSAGE, message)
        }.apply()
    }

    // -----------------------------------------------------------------------------------------
    // Drive folder cache (avoids a files.list() round-trip per recording)
    // -----------------------------------------------------------------------------------------

    fun driveFolderId(context: Context): String? =
        prefs(context).getString(KEY_DRIVE_FOLDER_ID, null)?.takeIf { it.isNotBlank() }

    fun setDriveFolderId(context: Context, id: String) =
        prefs(context).edit().putString(KEY_DRIVE_FOLDER_ID, id).apply()

    fun clearDriveFolderId(context: Context) =
        prefs(context).edit().remove(KEY_DRIVE_FOLDER_ID).apply()
}
