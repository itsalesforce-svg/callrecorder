package com.example.callrecorder.cloud

import android.content.Context
import android.content.Intent
import com.example.callrecorder.auth.AppPrefs
import com.example.callrecorder.auth.GoogleAuthManager
import com.example.callrecorder.auth.GoogleAccountRepository
import com.example.callrecorder.debug.AppSigning
import com.example.callrecorder.debug.CallRecorderLog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Step-by-step "does Drive actually work on this phone?" probe.
 *
 * A single boolean "connected: false" is useless to debug from a phone screen, so every stage of
 * the chain is tested and reported separately:
 *
 *   1. Android allows this app to see device accounts (GET_ACCOUNTS runtime permission).
 *   2. The saved e-mail address resolves to an account on this device.
 *   3. Google Play services can mint an OAuth access token for the `drive.file` scope
 *      (this is the step that produced "account is null" and "no chosen account found").
 *   4. The Drive REST API accepts that token (proves scope + API access + network).
 */
class DriveConnectionTester(private val context: Context) {

    enum class StepStatus { PASS, FAIL, SKIP }

    data class TestStep(
        val label: String,
        val status: StepStatus,
        val detail: String,
        val recoverIntent: Intent? = null
    )

    data class Report(
        val ok: Boolean,
        val headline: String,
        val steps: List<TestStep>,
        val consentIntent: Intent?,
        /** False when no on-device action can fix it (Cloud Console configuration problem). */
        val failureFixableOnDevice: Boolean = true
    ) {
        val failedStep: TestStep? get() = steps.firstOrNull { it.status == StepStatus.FAIL }
        val detail: String get() = failedStep?.detail ?: "All checks passed."
    }

    private val authManager = GoogleAuthManager(context)
    private val repository = GoogleAccountRepository(context)

    suspend fun run(): Report = withContext(Dispatchers.IO) {
        val steps = mutableListOf<TestStep>()
        CallRecorderLog.d("TEST", "--- Drive connection test started ---")
        CallRecorderLog.d("TEST", "package=${context.packageName} sha1=${AppSigning.sha1(context)}")
        CallRecorderLog.d("TEST", "scope=${GoogleAuthManager.SCOPE_LABEL}")

        // ---- Step 1: runtime permission -----------------------------------------------------
        // Missing GET_ACCOUNTS is reported as advisory (SKIP) rather than a hard failure: the
        // address can still be handed straight to the com.google authenticator, and if that works
        // the sync works too. But without the permission the in-app account list stays empty, so
        // the user cannot pick an account - which is worth saying out loud.
        val permissionGranted = repository.hasAccountsPermission()
        val visible = repository.googleAccounts()
        val hiddenByOs = repository.visibilityRulesApply(context) && visible.isEmpty() &&
            repository.hasGoogleAuthenticator()
        steps += TestStep(
            label = "Android 'Find accounts on this device' (GET_ACCOUNTS)",
            status = if (permissionGranted) StepStatus.PASS else StepStatus.SKIP,
            detail = if (permissionGranted && hiddenByOs) {
                "Granted - but Android 8+ still withholds the account list from apps targeting API 26+, " +
                    "so 0 accounts is the expected result even though Gmail and Drive see them all. The " +
                    "platform only makes an account visible after you pick it in the system chooser; " +
                    "typing the address is the alternative."
            } else if (permissionGranted) {
                "Granted. The app can see ${visible.size} Google account(s) on this phone."
            } else {
                "Not granted: AccountManager returns an empty list, so 'Choose from this device' will " +
                    "show nothing and GoogleAuthUtil may fail to resolve the account. Grant it with the " +
                    "button below (Settings > Apps > Call Recorder > Permissions > Accounts also works). " +
                    "Continuing with the address you typed as a fallback."
            }
        )

        // ---- Step 2: the saved address resolves to a device account -------------------------
        val credentialResult = authManager.credentialResult()
        val ready = credentialResult as? GoogleAuthManager.CredentialResult.Ready
        if (ready == null) {
            val problem = credentialResult as GoogleAuthManager.CredentialResult.Problem
            steps += TestStep(
                label = "Linked Google account",
                status = StepStatus.FAIL,
                detail = problem.message
            )
            return@withContext Report(
                ok = false,
                headline = "No usable Google account",
                steps = steps,
                consentIntent = null
            )
        }
        steps += TestStep(
            label = "Linked Google account",
            status = StepStatus.PASS,
            detail = if (ready.usedSyntheticAccount) {
                "${ready.accountName} is not in the list this app may see, so its address is being " +
                    "passed straight to the com.google authenticator. If the next step fails, the " +
                    "account really is missing from this phone (Settings > Accounts) or its visibility " +
                    "is blocked for third-party apps."
            } else {
                "${ready.accountName} found on this device."
            }
        )

        // ---- Step 3: OAuth token for drive.file --------------------------------------------
        var consent: Intent? = null
        val token: String? = try {
            CallRecorderLog.d("TEST", "requesting token for ${ready.accountName} (synthetic=${ready.usedSyntheticAccount})")
            ready.credential.getToken().also {
                CallRecorderLog.d("TEST", "token issued, ${it.length} chars")
            }
        } catch (t: Throwable) {
            // Log the raw type/message/cause chain: this is the line that explains the failure.
            val chain = generateSequence(t as Throwable?) { it.cause }
                .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
            CallRecorderLog.e("TEST", "getToken FAILED: $chain", t)
            val failure = DriveErrorMapper.describe(t, context)
            consent = failure.recoverIntent
            steps += TestStep(
                label = "OAuth access token (${GoogleAuthManager.SCOPE_LABEL})",
                status = StepStatus.FAIL,
                detail = "${failure.headline}. ${failure.detail}",
                recoverIntent = failure.recoverIntent
            )
            AppPrefs.setDriveConsentNeeded(context, consent != null)
            null
        }

        if (token == null && steps.none { it.label.startsWith("OAuth access token") }) {
            steps += TestStep(
                label = "OAuth access token (${GoogleAuthManager.SCOPE_LABEL})",
                status = StepStatus.FAIL,
                detail = "Google Play services returned no token and no error for this account. That " +
                    "usually means the account is paused on the phone: open Gmail or Drive once and " +
                    "sign in again, then retry."
            )
        }

        if (token != null) {
            steps += TestStep(
                label = "OAuth access token (${GoogleAuthManager.SCOPE_LABEL})",
                status = StepStatus.PASS,
                detail = "Token issued (${token.length} chars). Consent is in place for this account."
            )

            // ---- Step 4: Drive REST API round-trip -----------------------------------------
            try {
                val drive = authManager.driveService(ready)
                drive.about().get().setFields("kind").execute()
                steps += TestStep(
                    label = "Google Drive API round-trip",
                    status = StepStatus.PASS,
                    detail = "drive.files/about answered, so uploads can proceed now."
                )
            } catch (t: Throwable) {
                CallRecorderLog.e("TEST", "Drive about().get() failed", t)
                val failure = DriveErrorMapper.describe(t, context)
                steps += TestStep(
                    label = "Google Drive API round-trip",
                    status = StepStatus.FAIL,
                    detail = "${failure.headline}. ${failure.detail}",
                    recoverIntent = failure.recoverIntent
                )
                consent = consent ?: failure.recoverIntent
            }
        }

        val failed = steps.firstOrNull { it.status == StepStatus.FAIL }

        // When the address cannot be turned into a token, show the accounts the app *can* see: the
        // usual cause is that the phone is signed in with a different mailbox.
        if (failed != null && !failed.detail.contains("UnregisteredOnApiConsole", true)) {
            val alternative = repository.anotherGoogleAccount(ready.accountName)
            if (alternative != null) {
                steps += TestStep(
                    label = "Another Google account is visible to this app",
                    status = StepStatus.SKIP,
                    detail = "${alternative.name} is on this phone and can be used by the app. If " +
                        "that is the account you signed in to the Play Store with, save that address " +
                        "above instead of ${ready.accountName}."
                )
            }
        }

        val ok = failed == null
        val advisory = steps.firstOrNull { it.status == StepStatus.SKIP }
        steps.forEach { step -> CallRecorderLog.d("TEST", "${step.status} ${step.label}: ${step.detail}") }
        CallRecorderLog.d("TEST", "--- test finished: ok=$ok ---")
        AppPrefs.setDriveConsentNeeded(context, !ok && consent != null)
        val deviceFixable = failed?.let { step ->
            // A configuration failure is the one case where re-testing cannot possibly help.
            !step.detail.contains("UnregisteredOnApiConsole", ignoreCase = true)
        } ?: true
        Report(
            ok = ok,
            headline = when {
                !ok && !deviceFixable -> "Configuration needed in Google Cloud Console"
                !ok -> failed?.label ?: "Drive connection failed"
                advisory != null -> "Drive works, but grant the accounts permission too"
                else -> "Drive is ready - recordings will upload to /CallRecordings"
            },
            steps = steps,
            consentIntent = consent,
            failureFixableOnDevice = deviceFixable
        )
    }
}
