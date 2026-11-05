package com.example.callrecorder.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.callrecorder.auth.AppPrefs
import com.example.callrecorder.auth.GoogleAuthManager
import com.example.callrecorder.cloud.GoogleDriveUploader
import com.example.callrecorder.debug.CallRecorderLog
import com.example.callrecorder.data.local.AppDatabase
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Uploads every not-yet-synced recording to the linked Google account.
 *
 * The old implementation treated "everything is fine" and "we could not even build a Drive client"
 * the same way (`?: return Result.retry()`), which is why a mis-configured account looked like an
 * endless silent retry loop. Now each outcome is distinct and its reason is persisted in
 * [AppPrefs] so the Settings screen can show it without logcat.
 */
class GoogleDriveSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val dao = AppDatabase.getDatabase(context).callRecordDao()
        val authManager = GoogleAuthManager(context)

        val unsyncedList = dao.getUnsyncedRecords()
        CallRecorderLog.d("SYNC", "worker started: ${unsyncedList.size} unsynced record(s)")
        if (unsyncedList.isEmpty()) {
            AppPrefs.recordSyncResult(context, "idle", 0, 0, "Nothing to upload - all records are synced.")
            return Result.success()
        }

        AppPrefs.recordSyncResult(context, "running", 0, 0, "Uploading ${unsyncedList.size} recording(s)…")

        when (val credential = authManager.credentialResult()) {
            is GoogleAuthManager.CredentialResult.Problem -> {
                CallRecorderLog.e("SYNC", "no usable account (${credential.kind}): ${credential.message}")
                val noAccountSaved =
                    credential.kind == GoogleAuthManager.CredentialResult.Problem.Kind.NO_ACCOUNT_SAVED
                AppPrefs.recordSyncResult(context, if (noAccountSaved) "not_linked" else "needs_auth", 0, unsyncedList.size, credential.message)
                AppPrefs.setDriveConsentNeeded(context, !noAccountSaved)
                // Missing configuration is not something a retry can fix: stop and tell the user.
                return Result.failure()
            }

            is GoogleAuthManager.CredentialResult.Ready -> {
                val drive = try {
                    authManager.driveService(credential)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    AppPrefs.recordSyncResult(context, "error", 0, unsyncedList.size, t.message ?: t.javaClass.simpleName)
                    return Result.retry()
                }

                val uploader = GoogleDriveUploader(context, drive)
                var uploaded = 0
                var failed = 0
                var retryableFailure: String? = null
                var authFailure: String? = null

                for (record in unsyncedList) {
                    if (isStopped) break

                    val localFile = File(record.filePath)
                    if (!localFile.exists() || localFile.length() == 0L) {
                        // Nothing will ever be uploadable for this row; drop it out of the queue.
                        dao.updateSyncStatus(record.id, true)
                        failed++
                        continue
                    }

                    when (val result = // Voice recordings have no phone number; their label is what makes the Drive file
                    // recognisable ("Meeting_..."), so fall back to it.
                    uploader.uploadCallAudio(
                        record.filePath,
                        record.phoneNumber.ifBlank { record.contactName },
                        record.timestamp
                    )) {
                        is GoogleDriveUploader.UploadResult.Success -> {
                            dao.updateSyncStatus(record.id, true)
                            uploaded++
                        }

                        is GoogleDriveUploader.UploadResult.Failure -> {
                            CallRecorderLog.e(
                                "SYNC",
                                "upload failed for ${record.filePath}: " +
                                    "${result.failure.headline} - ${result.failure.detail}"
                            )
                            failed++
                            val failure = result.failure
                            if (failure.needsAccountSetup) {
                                authFailure = failure.headline + " — " + failure.detail
                                AppPrefs.setDriveConsentNeeded(context, failure.recoverIntent != null)
                            } else if (failure.retryable) {
                                retryableFailure = failure.headline + " — " + failure.detail
                            } else {
                                retryableFailure = retryableFailure ?: (failure.headline + " — " + failure.detail)
                            }
                        }
                    }
                }

                val message = when {
                    authFailure != null -> authFailure
                    retryableFailure != null -> retryableFailure
                    uploaded > 0 && failed == 0 -> "Uploaded $uploaded recording(s) as ${credential.accountName}."
                    uploaded > 0 -> "Uploaded $uploaded, $failed failed. Will retry."
                    else -> "All $failed upload(s) failed. Check 'Test Drive connection'."
                }

                CallRecorderLog.d("SYNC", "finished: uploaded=$uploaded failed=$failed message=$message")
                AppPrefs.recordSyncResult(
                    context = context,
                    state = if (failed == 0) "success" else if (authFailure != null) "needs_auth" else "error",
                    uploaded = uploaded,
                    failed = failed,
                    message = message
                )

                return when {
                    authFailure != null -> Result.failure()
                    failed > 0 -> Result.retry()
                    else -> Result.success()
                }
            }
        }
    }
}
