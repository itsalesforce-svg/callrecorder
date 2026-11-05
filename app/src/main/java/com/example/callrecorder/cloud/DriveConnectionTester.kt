package com.example.callrecorder.cloud

import android.content.Context
import android.content.Intent
import com.example.callrecorder.auth.AppPrefs
import com.example.callrecorder.auth.GoogleAuthManager
import com.example.callrecorder.auth.GoogleAccountRepository
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
        val consentIntent: Intent?
    ) {
        val failedStep: TestStep? get() = steps.firstOrNull { it.status == StepStatus.FAIL }
        val detail: String get() = failedStep?.detail ?: "All checks passed."
    }

    private val authManager = GoogleAuthManager(context)
    private val repository = GoogleAccountRepository(context)

    suspend fun run(): Report = withContext(Dispatchers.IO) {
        val steps = mutableListOf<TestStep>()

        // ---- Step 1: runtime permission -----------------------------------------------------
        val permissionGranted = repository.hasAccountsPermission()
        steps += TestStep(
            label = "Android 'Find accounts on this device' (GET_ACCOUNTS)",
            status = if (permissionGranted) StepStatus.PASS else StepStatus.FAIL,
            detail = if (permissionGranted) {
                "Granted. The app can list ${repository.totalAccountCount()} account(s) on this phone."
            } else {
                "Not granted, so AccountManager returns an empty list and GoogleAuthUtil cannot " +
                    "resolve ANY account. Grant it with the button below (Settings > Apps > " +
                    "Call Recorder > Permissions > Accounts also works)."
            }
        )
        if (!permissionGranted) {
            return@withContext Report(
                ok = false,
                headline = "Grant the accounts permission first",
                steps = steps,
                consentIntent = null
            )
        }

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
            ready.credential.getToken()
        } catch (t: Throwable) {
            val failure = DriveErrorMapper.describe(t)
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
                val failure = DriveErrorMapper.describe(t)
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
        val ok = failed == null
        AppPrefs.setDriveConsentNeeded(context, !ok && consent != null)
        Report(
            ok = ok,
            headline = if (ok) "Drive is ready - recordings will upload to /CallRecordings"
            else failed!!.headline,
            steps = steps,
            consentIntent = consent
        )
    }
}
