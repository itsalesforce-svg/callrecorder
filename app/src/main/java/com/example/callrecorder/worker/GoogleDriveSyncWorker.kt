package com.example.callrecorder.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.callrecorder.auth.GoogleAuthManager
import com.example.callrecorder.cloud.GoogleDriveUploader
import com.example.callrecorder.data.local.AppDatabase

class GoogleDriveSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val db = AppDatabase.getDatabase(context)
        val dao = db.callRecordDao()

        val unsyncedList = dao.getUnsyncedRecords()
        if (unsyncedList.isEmpty()) {
            return Result.success()
        }

        val authManager = GoogleAuthManager(context)
        val driveService = authManager.getGoogleDriveService() ?: return Result.retry()
        val uploader = GoogleDriveUploader(driveService)

        for (record in unsyncedList) {
            val success = uploader.uploadCallAudio(record.filePath, record.phoneNumber, record.timestamp)
            if (success) {
                dao.updateSyncStatus(record.id, true)
            }
        }

        return Result.success()
    }
}
