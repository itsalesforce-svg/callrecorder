package com.example.callrecorder.auth

import android.content.Context
import android.content.Intent
import com.example.callrecorder.debug.AppSigning
import com.example.callrecorder.debug.CallRecorderLog
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.util.Collections

/**
 * Builds an authenticated [Drive] client for the account the user linked on the Settings screen.
 *
 * Differences from the first version of this class, all of them required for the call to work:
 *
 *  * one preference file/key for the account name ([AppPrefs]) instead of two mismatching ones;
 *  * the account is resolved with [GoogleAccountRepository] and handed to the credential as a real
 *    `Account` object (`setSelectedAccount`) rather than as a string that the library matches with
 *    a case-sensitive `equals()` against accounts our app may not even be allowed to list;
 *  * failures are returned as typed [Result] values with an actionable message, never as `null`;
 *  * `getToken()` / every Drive call needs a background thread, so callers keep the build step on
 *    the UI thread but the request itself inside `Dispatchers.IO`.
 */
class GoogleAuthManager(private val context: Context) {

    companion object {
        val SCOPES: List<String> = Collections.singletonList(DriveScopes.DRIVE_FILE)

        /** Human-readable summary of the scope, used in the UI. */
        const val SCOPE_LABEL = "https://www.googleapis.com/auth/drive.file"

        /**
         * Logs the values Google Cloud Console needs for the (implicit) Android OAuth client.
         * `UnregisteredOnApiConsole` means this pair is not registered in the Cloud project.
         */
        fun logSigningIdentity(context: Context) {
            CallRecorderLog.d(
                "AUTH",
                "OAuth Android client needs package=${context.packageName} sha1=${AppSigning.sha1(context)}"
            )
        }
    }

    /** Why we could not build a Drive client, if that is the case. */
    sealed class CredentialResult {
        data class Ready(
            val credential: GoogleAccountCredential,
            val accountName: String,
            /** true = the address was not visible through AccountManager, we used the saved string. */
            val usedSyntheticAccount: Boolean
        ) : CredentialResult()

        data class Problem(
            val kind: Kind,
            val message: String,
            val accounts: List<GoogleAccountRepository.VisibleAccount>
        ) : CredentialResult() {
            enum class Kind { NO_ACCOUNT_SAVED, ACCOUNT_NOT_ON_DEVICE }
        }
    }

    private val repository = GoogleAccountRepository(context)

    // -----------------------------------------------------------------------------------------
    // Credential / service construction
    // -----------------------------------------------------------------------------------------

    fun credentialResult(): CredentialResult {
        val saved = AppPrefs.accountName(context)
        CallRecorderLog.d("AUTH", "credentialResult: saved=$saved synthetic=${AppPrefs.accountIsSynthetic(context)}")
        if (saved == null) {
            return CredentialResult.Problem(
                kind = CredentialResult.Problem.Kind.NO_ACCOUNT_SAVED,
                message = "No Google account is linked yet. Pick one from the list on this screen, " +
                    "or type the address shown in Settings > Accounts on this phone.",
                accounts = repository.googleAccounts()
            )
        }

        return when (val resolution = repository.resolve(saved)) {
            is GoogleAccountRepository.Resolution.Exact -> {
                CallRecorderLog.d("AUTH", "resolve=$saved -> EXACT device account ${resolution.account.name} (${resolution.account.type})")
                CredentialResult.Ready(buildCredential(resolution.account), resolution.account.name, false)
            }

            is GoogleAccountRepository.Resolution.Synthetic -> {
                CallRecorderLog.w(
                    "AUTH",
                    "resolve=$saved -> SYNTHETIC. AccountManager returned no accounts, so a fabricated " +
                        "Account($saved, com.google) is passed to GoogleAuthUtil. If the token step fails " +
                        "with UnregisteredOnApiConsole the app's SHA-1 is missing in Cloud Console."
                )
                CredentialResult.Ready(buildCredential(resolution.account), resolution.account.name, true)
            }

            is GoogleAccountRepository.Resolution.NotFound -> {
                CallRecorderLog.e("AUTH", "resolve=$saved -> NOT FOUND. Visible: ${resolution.visible.map { it.name }}")
                CredentialResult.Problem(
                    kind = CredentialResult.Problem.Kind.ACCOUNT_NOT_ON_DEVICE,
                    message = "\"$saved\" is not one of the Google accounts this app can use. " +
                        "Accounts on the phone: " +
                        (if (resolution.visible.isEmpty()) "none visible (permission denied?)"
                        else resolution.visible.joinToString { it.name }) +
                        ". Fix: grant 'Find accounts on the device', or pick the account from the list.",
                    accounts = resolution.visible
                )
            }

            GoogleAccountRepository.Resolution.NoneSaved ->
                CredentialResult.Problem(
                    kind = CredentialResult.Problem.Kind.NO_ACCOUNT_SAVED,
                    message = "No Google account is linked yet.",
                    accounts = repository.googleAccounts()
                )
        }
    }

    private fun buildCredential(account: android.accounts.Account): GoogleAccountCredential =
        GoogleAccountCredential.usingOAuth2(context, SCOPES)
            // Recorded on every attempt: these two values are exactly what Play services checks when
            // it answers "UnregisteredOnApiConsole".
            .also { logSigningIdentity(context) }
            // setSelectedAccount() instead of setSelectedAccountName(): the latter silently nulls
            // the credential when the name does not match a *visible* account byte-for-byte.
            .setSelectedAccount(account)
            // Retry transport-level failures the way the Google samples recommend.
            .setBackOff(ExponentialBackOff())

    /**
     * Kept for existing callers: returns null when nothing usable is configured. New code should
     * use [credentialResult] so the reason can be shown to the user.
     */
    fun getGoogleDriveService(): Drive? =
        (credentialResult() as? CredentialResult.Ready)?.let { driveService(it) }

    fun driveService(result: CredentialResult.Ready): Drive =
        Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            result.credential
        )
            .setApplicationName("CallRecorderApp")
            .build()

    /** Convenience: throw if unconfigured, so upload code can stay readable. */
    fun requireDriveService(): Drive = when (val r = credentialResult()) {
        is CredentialResult.Ready -> driveService(r)
        is CredentialResult.Problem -> throw IllegalStateException(r.message)
    }

    // -----------------------------------------------------------------------------------------
    // Linking an account
    // -----------------------------------------------------------------------------------------

    fun saveAccountName(accountName: String) {
        val synthetic = repository.resolve(accountName) is GoogleAccountRepository.Resolution.Synthetic
        AppPrefs.saveAccount(context, accountName, synthetic)
    }

    fun unlinkAccount() = AppPrefs.clearAccount(context)

    fun selectedAccountName(): String? = AppPrefs.accountName(context)

    /**
     * Play services account chooser. Picking an account this way also makes the account *visible*
     * to us, so it is the most reliable path. Wrapped defensively because the chooser activity has
     * been observed to close the calling app on some OEM builds (Play services missing/disabled,
     * or GET_ACCOUNTS not granted on Android 13+), which is why the in-app list stays available.
     */
    /**
     * The chooser that actually grants visibility on Android 8+ (see GoogleAccountRepository's
     * frameworkChooserIntent doc). Preferred over [chooseAccountIntent] because it is part of the
     * platform, not of Play services.
     */
    fun frameworkChooserIntent(): Intent? = repository.frameworkChooserIntent()

    fun chooseAccountIntent(): Intent? = runCatching {
        val credential = GoogleAccountCredential.usingOAuth2(context, SCOPES)
        AppPrefs.accountName(context)?.let { name ->
            repository.resolve(name).let { if (it is GoogleAccountRepository.Resolution.Exact) credential.setSelectedAccount(it.account) }
        }
        credential.newChooseAccountIntent()
    }.getOrNull()?.apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** True when the last attempt ended in a consent prompt the user has not answered yet. */
    fun consentPending(): Boolean = AppPrefs.driveConsentNeeded(context)

    /** Whether a throwable means "ask the user for consent" (used to persist consentPending). */
    fun isConsentFailure(t: Throwable): Boolean = t is UserRecoverableAuthIOException

    // -----------------------------------------------------------------------------------------
    // Diagnostics shown on the Settings screen
    // -----------------------------------------------------------------------------------------

    data class DriveDiagnostics(
        val accountsPermissionGranted: Boolean,
        val googleAccountsVisible: List<GoogleAccountRepository.VisibleAccount>,
        val totalAccountsOnDevice: Int,
        val savedAccount: String?,
        val scope: String
    )

    fun diagnostics(): DriveDiagnostics = DriveDiagnostics(
        accountsPermissionGranted = repository.hasAccountsPermission(),
        googleAccountsVisible = repository.googleAccounts(),
        totalAccountsOnDevice = repository.totalAccountCount(),
        savedAccount = AppPrefs.accountName(context),
        scope = SCOPE_LABEL
    )

    fun accountsRepository(): GoogleAccountRepository = repository
}
