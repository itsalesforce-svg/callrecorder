package com.example.callrecorder.ui.viewmodel

import android.app.Application
import android.accounts.AccountManager
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.callrecorder.auth.AppPrefs
import com.example.callrecorder.auth.GoogleAccountRepository
import com.example.callrecorder.auth.GoogleAuthManager
import com.example.callrecorder.cloud.DriveConnectionTester
import com.example.callrecorder.debug.AppSigning
import com.example.callrecorder.debug.CallRecorderLog
import com.example.callrecorder.debug.SelfTest
import com.example.callrecorder.worker.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How far the Drive link got; drives the colour/icon of the Drive status card. */
enum class DriveLinkState {
    NO_ACCOUNT,
    LINKED_NOT_TESTED,
    TESTING,
    READY,
    FAILED,
    CONSENT_PENDING
}

data class SettingsUiState(
    val autoRecord: Boolean = true,
    /** Open the microphone while the phone rings; see AppPrefs.recordFromRing. */
    val recordFromRing: Boolean = true,
    val audioSource: String = "VOICE_COMMUNICATION",
    val autoSync: Boolean = true,
    val syncWifiOnly: Boolean = true,
    val syncIntervalHours: Long = 4,
    val googleAccountName: String? = null,
    val accountDraft: String = "",
    val draftLooksValid: Boolean = true,
    val accountsPermissionGranted: Boolean = false,
    val deviceAccounts: List<GoogleAccountRepository.VisibleAccount> = emptyList(),
    val totalAccounts: Int = 0,
    val linkState: DriveLinkState = DriveLinkState.NO_ACCOUNT,
    val headline: String? = null,
    val detail: String? = null,
    val steps: List<DriveConnectionTester.TestStep> = emptyList(),
    val busy: Boolean = false,
    val showAccountPicker: Boolean = false,
    val consentIntent: Intent? = null,
    val syncMessage: String? = null,
    val callRecordingIssue: String? = null,
    val lastSyncSummary: String? = null,
    // ---- diagnostics ----
    val signingSha1: String = "",
    val signingSha256: String = "",
    val logLineCount: Int = 0,
    val diagnosticsRunning: Boolean = false,
    val diagnosticsResult: String? = null,
    /**
     * True when the last failure cannot be fixed on this phone (the build's signing key is not
     * registered in Google Cloud Console). The card then stops offering "save/test again".
     */
    val needsConsoleSetup: Boolean = false,
    /**
     * Android 8+ hides the account list from apps targeting O+ until the user picks an account with
     * the system chooser. When true, an empty device list is expected - it is not a broken phone.
     */
    val accountsHiddenByOs: Boolean = false,
    /** The com.google authenticator is installed, i.e. accounts exist even if we cannot list them. */
    val googleAuthenticatorPresent: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val authManager = GoogleAuthManager(application)
    private val repository = GoogleAccountRepository(application)
    private val tester = DriveConnectionTester(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * One-shot emissions of Google's consent/re-auth prompt. The screen launches these with an
     * Activity result launcher and re-runs the test when the user comes back. A SharedFlow (not a
     * field in the state) is used so a recomposition cannot launch the same prompt twice.
     */
    private val _driveAuthorizationRequests = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val driveAuthorizationRequests: SharedFlow<Intent> = _driveAuthorizationRequests.asSharedFlow()

    init {
        refresh()
    }

    /** Re-read the recording failure note written by CallRecordService. */
    fun refreshCallRecordingIssue() {
        _uiState.update {
            it.copy(callRecordingIssue = AppPrefs.lastRecordingIssue(getApplication()))
        }
    }

    /**
     * Re-reads the permission state, the account list and the last sync result.
     * AccountManager is a binder call, so it runs on [Dispatchers.IO].
     */
    fun refresh() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                val previous = _uiState.value
                val saved = AppPrefs.accountName(ctx)
                val permission = repository.hasAccountsPermission()
                val accounts = repository.googleAccounts()
                val total = repository.totalAccountCount()
                val authenticator = repository.hasGoogleAuthenticator()
                val hiddenByOs = repository.visibilityRulesApply(ctx) && accounts.isEmpty()
                CallRecorderLog.d(
                    "ACCOUNTS",
                    "permission=$permission visible=${accounts.size} total=$total " +
                        "googleAuthenticator=$authenticator hiddenByOs=$hiddenByOs"
                )
                val consentPending = saved != null && AppPrefs.driveConsentNeeded(ctx)
                val stillSameAccount = saved != null && saved == previous.googleAccountName
                val linkState = when {
                    previous.busy || previous.linkState == DriveLinkState.TESTING -> DriveLinkState.TESTING
                    saved == null -> DriveLinkState.NO_ACCOUNT
                    consentPending -> DriveLinkState.CONSENT_PENDING
                    stillSameAccount && previous.linkState == DriveLinkState.READY -> DriveLinkState.READY
                    stillSameAccount && previous.linkState == DriveLinkState.FAILED -> DriveLinkState.FAILED
                    else -> DriveLinkState.LINKED_NOT_TESTED
                }
                _uiState.update {
                    it.copy(
                        autoRecord = AppPrefs.autoRecord(ctx),
                        recordFromRing = AppPrefs.recordFromRing(ctx),
                        audioSource = AppPrefs.audioSource(ctx),
                        autoSync = AppPrefs.autoSync(ctx),
                        syncWifiOnly = AppPrefs.syncWifiOnly(ctx),
                        syncIntervalHours = AppPrefs.syncIntervalHours(ctx),
                        googleAccountName = saved,
                        accountDraft = it.accountDraft.ifBlank { saved.orEmpty() },
                        draftLooksValid = saved?.let { name -> repository.looksLikeEmail(name) } ?: true,
                        accountsPermissionGranted = permission,
                        deviceAccounts = accounts,
                        totalAccounts = total,
                        accountsHiddenByOs = hiddenByOs,
                        googleAuthenticatorPresent = authenticator,
                        linkState = linkState,
                        headline = headlineFor(linkState, saved, permission, accounts.size),
                        detail = blockerDetail(permission, accounts.size, total, saved),
                        callRecordingIssue = AppPrefs.lastRecordingIssue(ctx),
                        lastSyncSummary = syncSummary(),
                        signingSha1 = AppSigning.sha1(ctx),
                        signingSha256 = AppSigning.sha256(ctx),
                        logLineCount = if (CallRecorderLog.hasEntries()) 1 else 0
                    )
                }
            }
        }
    }

    private fun headlineFor(
        state: DriveLinkState,
        saved: String?,
        permission: Boolean,
        visibleCount: Int
    ): String = when (state) {
        DriveLinkState.NO_ACCOUNT -> when {
            !permission -> "Grant the accounts permission to see your Google accounts"
            // Do NOT say "no Google account found": on Android 8+ an empty list is what the OS
            // returns for apps targeting O+ until the user picks an account with the chooser.
            visibleCount == 0 -> "Android is hiding your Google accounts from this app"
            else -> "Pick the Google account that should receive your recordings"
        }
        DriveLinkState.LINKED_NOT_TESTED -> "Linked: $saved (not tested yet)"
        DriveLinkState.TESTING -> "Checking Drive access…"
        DriveLinkState.READY -> "Drive is ready — $saved"
        DriveLinkState.CONSENT_PENDING -> "Google is still waiting for your permission for $saved"
        DriveLinkState.FAILED -> "Drive connection failed for $saved"
    }

    /** Non-null only while something structurally blocks the link, so the UI can nag once. */
    private fun blockerDetail(
        permission: Boolean,
        visible: Int,
        total: Int,
        saved: String?
    ): String? = when {
        !permission ->
            "Android hides the account list from apps unless GET_ACCOUNTS (a runtime permission) is " +
                "granted. Tap 'Grant accounts permission'."
        visible == 0 && saved == null && _uiState.value.googleAuthenticatorPresent ->
            "The Google account is on this phone - Gmail and Drive can see it - but Android 8 and " +
                "newer deliberately hide the account list from apps that target API 26 or later. " +
                "GET_ACCOUNTS no longer grants visibility; the platform releases it only after the " +
                "user picks an account in the system chooser. Tap 'Choose account (system picker)' " +
                "once: that pick marks the account visible to this app permanently, including after " +
                "reinstalls of the app data. Typing the address below also works."
        visible == 0 && saved == null ->
            "This app can see $total account(s) on the device, but none of type com.google. On ROMs " +
                "without Play services also enable 'Allow all apps to find accounts'."
        else -> null
    }

    private fun syncSummary(): String? {
        val ctx = getApplication<Application>()
        val at = AppPrefs.lastSyncAt(ctx)
        if (at == 0L) return null
        val stamp = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(at))
        val state = AppPrefs.lastSyncState(ctx) ?: "idle"
        val uploaded = AppPrefs.lastSyncUploaded(ctx)
        val failed = AppPrefs.lastSyncFailed(ctx)
        val message = AppPrefs.lastSyncMessage(ctx)
        return "$stamp · $state · $uploaded uploaded, $failed failed" +
            (message?.let { " · $it" } ?: "")
    }

    // -----------------------------------------------------------------------------------------
    // Account linking
    // -----------------------------------------------------------------------------------------

    fun onAccountDraftChanged(value: String) {
        _uiState.update {
            it.copy(
                accountDraft = value,
                draftLooksValid = value.isBlank() || repository.looksLikeEmail(value)
            )
        }
    }

    /** Save a typed address. Also the escape hatch when the system picker is unavailable. */
    fun saveDraftAccount() {
        val draft = _uiState.value.accountDraft.trim()
        if (draft.isEmpty()) {
            _uiState.update {
                it.copy(
                    linkState = DriveLinkState.FAILED,
                    headline = "Type the Google address first",
                    detail = "Use the account name shown in Settings > Accounts on this phone (spaces " +
                        "are trimmed; the local part is matched case-insensitively).",
                    steps = emptyList()
                )
            }
            return
        }
        if (!repository.looksLikeEmail(draft)) {
            _uiState.update {
                it.copy(
                    linkState = DriveLinkState.FAILED,
                    headline = "\"$draft\" is not a usable Google account name",
                    detail = "The Drive client needs the account *name* (an e-mail address such as " +
                        "name@gmail.com), not the display name shown in the contacts app.",
                    steps = emptyList()
                )
            }
            return
        }
        linkAccount(draft)
    }

    fun linkDeviceAccount(name: String) {
        setShowAccountPicker(false)
        linkAccount(name)
    }

    private fun linkAccount(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val synthetic = repository.resolve(name) is GoogleAccountRepository.Resolution.Synthetic
                AppPrefs.saveAccount(getApplication(), name, synthetic)
            }
            _uiState.update {
                it.copy(
                    googleAccountName = name,
                    accountDraft = name,
                    linkState = DriveLinkState.TESTING,
                    headline = "Linked: $name",
                    detail = null,
                    steps = emptyList(),
                    consentIntent = null,
                    needsConsoleSetup = false
                )
            }
            runConnectionTest()
        }
    }

    fun unlinkAccount() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppPrefs.clearAccount(getApplication()) }
            _uiState.update {
                it.copy(
                    googleAccountName = null,
                    accountDraft = "",
                    linkState = DriveLinkState.NO_ACCOUNT,
                    steps = emptyList(),
                    consentIntent = null,
                    detail = null
                )
            }
            refresh()
        }
    }

    /**
     * System account chooser. The framework chooser comes first because it is the platform's own
     * documented way of granting account visibility on Android 8+; the Play services AccountPicker is
     * the fallback for ROMs where the framework one is absent.
     */
    fun systemChooserIntent(): Intent? {
        val framework = runCatching { authManager.frameworkChooserIntent() }.getOrNull()
        if (framework != null) {
            CallRecorderLog.d("ACCOUNTS", "using framework AccountManager.newChooseAccountIntent chooser")
            return framework
        }
        CallRecorderLog.w("ACCOUNTS", "framework chooser unavailable, falling back to the Play services picker")
        return runCatching { authManager.chooseAccountIntent() }.getOrNull()
    }

    /**
     * Handle the chooser result. Play services' AccountPicker answers with `selected_account`,
     * while the framework's AccountManager chooser answers with `account_name`; accept either so a
     * picked account is never silently dropped (that is what made the picker "return null").
     */
    fun onSystemChooserResult(data: Intent?) {
        val extras = data?.extras
        val picked = extras?.getString(GoogleAccountRepository.PICKER_SELECTED_ACCOUNT_EXTRA)
            ?: extras?.getString(AccountManager.KEY_ACCOUNT_NAME)
        if (picked.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    linkState = DriveLinkState.FAILED,
                    headline = "The system picker returned no account",
                    detail = "It was closed without a selection - on some ROMs it also crashes when " +
                        "Google Play services is restricted. Use 'Choose from this device' or type the " +
                        "address instead."
                )
            }
            refresh()
            return
        }
        linkAccount(picked)
    }

    fun setShowAccountPicker(show: Boolean) {
        viewModelScope.launch {
            if (!show) {
                _uiState.update { it.copy(showAccountPicker = false) }
                return@launch
            }
            val accounts = withContext(Dispatchers.IO) { repository.googleAccounts() }
            _uiState.update { it.copy(deviceAccounts = accounts, showAccountPicker = true) }
        }
    }

    /** Opens the system "add account" screen (works even without GET_ACCOUNTS). */
    fun openDeviceAccountSettings() {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_ADD_ACCOUNT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Test / sync actions
    // -----------------------------------------------------------------------------------------

    fun runConnectionTest() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    linkState = DriveLinkState.TESTING,
                    headline = "Checking Drive access…",
                    detail = null,
                    steps = emptyList()
                )
            }
            val report = try {
                tester.run()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                DriveConnectionTester.Report(
                    ok = false,
                    headline = "The test crashed: ${t.javaClass.simpleName}",
                    steps = listOf(
                        DriveConnectionTester.TestStep(
                            label = "Unexpected error",
                            status = DriveConnectionTester.StepStatus.FAIL,
                            detail = t.message ?: "no message"
                        )
                    ),
                    consentIntent = null
                )
            }
            _uiState.update {
                it.copy(
                    busy = false,
                    linkState = when {
                        report.ok -> DriveLinkState.READY
                        report.consentIntent != null -> DriveLinkState.CONSENT_PENDING
                        else -> DriveLinkState.FAILED
                    },
                    headline = report.headline,
                    detail = if (report.ok) null else report.failedStep?.detail,
                    steps = report.steps,
                    consentIntent = report.consentIntent,
                    needsConsoleSetup = !report.ok && !report.failureFixableOnDevice,
                    lastSyncSummary = syncSummary()
                )
            }
            report.consentIntent?.let { _driveAuthorizationRequests.emit(it) }
        }
    }

    /** After the user answers Google's consent screen, re-run the test automatically. */
    fun onConsentScreenReturned() {
        _uiState.update { it.copy(consentIntent = null) }
        runConnectionTest()
    }

    fun syncNow() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (AppPrefs.accountName(ctx).isNullOrBlank()) {
                _uiState.update {
                    it.copy(syncMessage = "Link a Google account before syncing - there is nowhere to upload to yet.")
                }
                return@launch
            }
            withContext(Dispatchers.IO) { SyncScheduler.scheduleImmediateSync(ctx) }
            _uiState.update {
                it.copy(
                    linkState = DriveLinkState.TESTING,
                    headline = "Upload queued - the sync is running now",
                    detail = null,
                    syncMessage = null
                )
            }
            delay(3_000)
            refresh()
        }
    }

    fun openAppPermissionSettings() {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Diagnostics (the "Debug" card)
    // -----------------------------------------------------------------------------------------

    /** Full log + build + signing identity, ready to paste into a chat. */
    fun debugSnapshot(): String = CallRecorderLog.snapshot(getApplication())

    /** The log as a file, for ACTION_SEND. */
    fun debugFile(): java.io.File =
        CallRecorderLog.exportToFile(getApplication())

    fun clearDebugLog() {
        CallRecorderLog.clear(getApplication())
        _uiState.update { it.copy(diagnosticsResult = "Log cleared.") }
    }

    /**
     * Runs the three checks that actually matter for this app on this phone:
     * account visibility, token issuance (surfacing the real Play services message) and whether the
     * microphone can capture audio at all.
     */
    fun runSelfTest(recordSample: Boolean = false) {
        if (_uiState.value.diagnosticsRunning) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _uiState.update { it.copy(diagnosticsRunning = true, diagnosticsResult = "Running…") }
            CallRecorderLog.d("SELFTEST", "self test requested (recordSample=$recordSample)")
            val accounts = try {
                SelfTest.diagnoseAccounts(ctx)
            } catch (t: Throwable) {
                CallRecorderLog.e("SELFTEST", "account diagnosis failed", t)
                "account diagnosis failed: ${t.javaClass.simpleName}: ${t.message}"
            }
            val token = try {
                SelfTest.probeToken(ctx)
            } catch (t: Throwable) {
                CallRecorderLog.e("SELFTEST", "token probe failed", t)
                "token probe failed: ${t.javaClass.simpleName}: ${t.message}"
            }
            var audio = "skipped"
            if (recordSample) {
                val outcome = SelfTest.recordSample(ctx, seconds = 2)
                audio = (if (outcome.ok) "PASS - " else "FAIL - ") + outcome.message
                if (!outcome.ok) {
                    AppPrefs.setLastRecordingIssue(ctx, "Microphone self-test failed: ${outcome.message}")
                }
            }
            val report = buildString {
                appendLine("ACCOUNTS")
                appendLine(accounts.trim())
                appendLine()
                appendLine("TOKEN")
                appendLine(token)
                if (recordSample) {
                    appendLine()
                    appendLine("MICROPHONE")
                    appendLine(audio)
                }
            }
            _uiState.update {
                it.copy(
                    diagnosticsRunning = false,
                    diagnosticsResult = report.trim(),
                    callRecordingIssue = AppPrefs.lastRecordingIssue(ctx)
                )
            }
            // Refresh the Drive card so a successful token shows up there too.
            if (token.startsWith("TOKEN OK")) {
                runConnectionTest()
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Toggles
    // -----------------------------------------------------------------------------------------

    fun setAutoRecord(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppPrefs.setAutoRecord(getApplication(), enabled) }
            _uiState.update { it.copy(autoRecord = enabled) }
        }
    }

    fun setRecordFromRing(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppPrefs.setRecordFromRing(getApplication(), enabled) }
            _uiState.update { it.copy(recordFromRing = enabled) }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) {
                AppPrefs.setAutoSync(ctx, enabled)
                if (enabled) SyncScheduler.schedulePeriodicSync(ctx) else SyncScheduler.cancelPeriodicSync(ctx)
            }
            _uiState.update { it.copy(autoSync = enabled) }
        }
    }

    fun setSyncWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) {
                AppPrefs.setSyncWifiOnly(ctx, wifiOnly)
                if (AppPrefs.autoSync(ctx)) SyncScheduler.schedulePeriodicSync(ctx)
            }
            _uiState.update { it.copy(syncWifiOnly = wifiOnly) }
        }
    }

    fun setAudioSource(source: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppPrefs.setAudioSource(getApplication(), source) }
            _uiState.update { it.copy(audioSource = source) }
        }
    }
}
