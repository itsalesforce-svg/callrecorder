package com.example.callrecorder.cloud

import android.content.Intent
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.example.callrecorder.debug.AppSigning
import com.example.callrecorder.debug.CallRecorderLog
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.android.gms.auth.GoogleAuthException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns the very cryptic Google API client exceptions into text that actually tells a human what
 * to do. The old code did `catch (e: Exception) { e.printStackTrace(); false }`, which is precisely
 * why this app reported a generic "Drive connection failed" with no way to know that the real
 * problem was a missing runtime permission / a missing consent dialog.
 */
data class DriveFailure(
    /** One-line summary, safe to show in the UI. */
    val headline: String,
    /** Longer explanation + fix. */
    val detail: String,
    /**
     * Intent that must be launched to unblock the sync (OAuth consent screen, Play Services
     * resolution, …), or null when there is nothing the app can launch for you.
     */
    val recoverIntent: Intent? = null,
    /** True when the linked Google account itself is unusable and must be re-selected. */
    val needsAccountSetup: Boolean = false,
    /** True when the failure is transient (network) and a retry makes sense. */
    val retryable: Boolean = false,
    /**
     * False when no amount of retrying or re-testing on this phone can help (e.g. the signing key is
     * not registered in Cloud Console). The UI then stops nagging and shows setup instructions.
     */
    val fixableOnDevice: Boolean = true
)

object DriveErrorMapper {

    /**
     * Rethrow coroutine cancellation, then map everything else.
     *
     * [context] is optional; when supplied, configuration errors can quote this APK's real package
     * name and signing SHA-1 instead of telling the user to "check the console".
     */
    fun describe(throwable: Throwable, context: android.content.Context? = null): DriveFailure {
        if (throwable is CancellationException) throw throwable
        val failure = map(throwable, context)
        CallRecorderLog.d("DRIVE", "mapped ${throwable.javaClass.name} -> ${failure.headline}")
        return failure
    }

    private fun map(t: Throwable, context: android.content.Context? = null): DriveFailure {
        // 0) The Android OAuth client is not registered for this package+SHA-1. This is by far the
        //    most common real cause of "the account is there but no token arrives", it is permanent
        //    until the console is fixed, and the generic messages below hide it completely.
        val rawMessage = generateSequence(t) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        if (rawMessage.contains("UnregisteredOnApiConsole", ignoreCase = true) ||
            rawMessage.contains("unregistered", ignoreCase = true) ||
            (t is GoogleAuthException && rawMessage.contains("ApplicationIdentity", ignoreCase = true))
        ) {
            val sha1 = context?.let { AppSigning.sha1(it) } ?: "(open the Debug card to see it)"
            val pkg = context?.packageName ?: "com.example.callrecorder"
            return DriveFailure(
                headline = "This build's signing key is not registered in Google Cloud Console",
                detail = "Google Play services refused the token: 'UnregisteredOnApiConsole'. The legacy " +
                    "GoogleAuthUtil flow has no client id in the code - Play services looks the app up " +
                    "implicitly by package name + signing SHA-1, and no Android OAuth client matches this " +
                    "build.\n\nRegister these two values in the SAME Cloud project where 'Google Drive API' " +
                    "is enabled (APIs & Services > Credentials > Create credentials > OAuth client ID > " +
                    "Android):\n\nPackage name: $pkg\nSHA-1: $sha1\n\n" +
                    "IMPORTANT: every GitHub Actions build is signed with a freshly generated debug keystore, " +
                    "so its SHA-1 changes on each run and any value registered for a previous download stops " +
                    "working. See 'Stable signing key' in the README - after that the SHA-1 stays the same.",
                needsAccountSetup = false,
                retryable = false,
                fixableOnDevice = false
            )
        }

        // 1) Play Services needs the user to do something (consent, re-login). Its intent is the fix.
        (t as? GooglePlayServicesAvailabilityIOException)?.let { e ->
            return DriveFailure(
                headline = "Google Play services cannot issue a token (status ${e.connectionStatusCode})",
                detail = "Install / update Google Play services, make sure the Google account is " +
                    "enabled on this phone, then tap 'Grant access' and run the test again. " +
                    "On ROMs without Play services (MicroG) enable 'Allow all apps to find accounts' " +
                    "in the account settings.",
                recoverIntent = runCatching { e.intent }.getOrNull(),
                needsAccountSetup = false,
                retryable = true
            )
        }

        (t as? UserRecoverableAuthIOException)?.let { e ->
            val message = runCatching { e.cause?.message }.getOrNull()
            return DriveFailure(
                headline = "Google needs your permission for this account",
                detail = "The Drive scope has not been granted yet (or was revoked). Tap 'Grant access' " +
                    "to open Google's consent screen, then run the test again." +
                    (message?.let { " Details: $it" } ?: ""),
                recoverIntent = runCatching { e.intent }.getOrNull(),
                needsAccountSetup = false,
                retryable = true
            )
        }

        (t as? GoogleAuthIOException)?.let { e ->
            val cause = runCatching { e.cause }.getOrNull()
            val text = cause?.message ?: e.message ?: "no message"
            val accountProblem = text.contains("no chosen account", true) ||
                text.contains("no longer installed", true) ||
                text.contains("account not found", true) ||
                text.contains("google accounts api", true)
            return if (accountProblem) {
                DriveFailure(
                    headline = "The saved Google account is not usable on this phone",
                    detail = "Google Play services could not resolve the account for this app. Grant " +
                        "'Find accounts on the device' (GET_ACCOUNTS) for Call Recorder, or pick the " +
                        "account with the system picker so the app becomes visible to it. " +
                        "Saved address: check the spelling against Settings > Accounts. Details: $text",
                    recoverIntent = null,
                    needsAccountSetup = true
                )
            } else {
                DriveFailure(
                    headline = "Google authentication failed",
                    detail = "The account exists but no Drive token could be obtained. Usually this means " +
                        "the account is in a 'needs re-login' state (open Gmail/Drive once and sign in " +
                        "again) or the consent was denied. Details: $text",
                    recoverIntent = null,
                    needsAccountSetup = true
                )
            }
        }

        // 2) Drive API answered with an HTTP error.
        (t as? GoogleJsonResponseException)?.let { e ->
            val status = runCatching { e.statusCode }.getOrDefault(0)
            val body = runCatching { e.details?.message }.getOrNull() ?: e.content?.take(400) ?: ""
            val headline = when {
                status == 401 || status == 403 -> "Google rejected the access token (HTTP $status)"
                status == 429 -> "Drive rate limit reached (HTTP 429)"
                status >= 500 -> "Google Drive is having problems (HTTP $status)"
                else -> "Drive API error (HTTP $status)"
            }
            val hint = when {
                body.contains("accessNotConfigured", true) || body.contains("Drive API has not been used", true) ->
                    "Enable 'Google Drive API' in the Google Cloud project whose OAuth client this app uses."
                body.contains("userRateLimitExceeded", true) -> "Wait a minute and tap 'Sync now' again."
                status == 403 -> "The consent granted to this app does not include Drive file access. " +
                    "Revoke it (myaccount.google.com/permissions) and run 'Test Drive connection'."
                else -> "Full response: $body"
            }
            return DriveFailure(headline = headline, detail = hint, retryable = status >= 500 || status == 429)
        }

        // 3) The Play Services auth classes are not in the APK at all.
        if (t is NoClassDefFoundError || t is ClassNotFoundException || t is LinkageError) {
            return DriveFailure(
                headline = "Google Play services auth library is missing from the APK",
                detail = "com.google.android.gms.auth.GoogleAuthUtil could not be loaded. Add " +
                    "implementation(\"com.google.android.gms:play-services-auth:21.3.0\") to " +
                    "app/build.gradle.kts - google-api-client-android declares Play services as a " +
                    "'provided' dependency, so it is NOT pulled in for you. " +
                    "If this only happens in the release build, also keep the rules in proguard-rules.pro." +
                    (t.message?.let { " Details: $it" } ?: "")
            )
        }

        // 4) Permission model / platform problems.
        if (t is SecurityException) {
            return DriveFailure(
                headline = "Android denied access to the account list",
                detail = "Call Recorder is not allowed to read device accounts (GET_ACCOUNTS). " +
                    "Grant it on the Permissions screen, or under Settings > Apps > Call Recorder > " +
                    "Permissions > Accounts. Details: ${t.message}",
                needsAccountSetup = true
            )
        }

        // 5) Transport problems.
        when (t) {
            is UnknownHostException -> return DriveFailure(
                headline = "No route to googleapis.com",
                detail = "DNS/network failure. Check Wi-Fi/mobile data, 'Sync over Wi-Fi only' state, " +
                    "VPN or Private DNS (hostname verification) settings. Details: ${t.message}",
                retryable = true
            )
            is SSLException -> return DriveFailure(
                headline = "TLS handshake with Google failed",
                detail = "Wrong device date/time, a corporate proxy, or a firewall blocking " +
                    "www.googleapis.com. Details: ${t.message}",
                retryable = true
            )
            is SocketTimeoutException -> return DriveFailure(
                headline = "Google Drive timed out",
                detail = "Slow/unstable connection; the upload will be retried automatically. " +
                    "Large recordings upload faster over Wi-Fi.",
                retryable = true
            )
        }

        if (t is java.io.IOException) {
            val msg = t.message.orEmpty()
            val accountProblem = msg.contains("no chosen account", true) ||
                msg.contains("no longer installed", true)
            return if (accountProblem) {
                DriveFailure(
                    headline = "No Google account selected for this app",
                    detail = "The Drive client could not resolve the saved address to an account it may " +
                        "use. Pick the account in the list on this screen (or grant 'Find accounts on the " +
                        "device'), then tap 'Test Drive connection'. Details: $msg",
                    needsAccountSetup = true
                )
            } else {
                DriveFailure(
                    headline = "Google Drive request failed",
                    detail = msg.ifBlank { t.javaClass.simpleName },
                    retryable = true
                )
            }
        }

        return DriveFailure(
            headline = "Unexpected error: ${t.javaClass.name}",
            detail = t.message ?: "no details",
            retryable = true
        )
    }
}
