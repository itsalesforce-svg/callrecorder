package com.example.callrecorder.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.BackoffPolicy
import com.example.callrecorder.auth.AppPrefs
import java.util.concurrent.TimeUnit

/**
 * Entry points for queueing [GoogleDriveSyncWorker].
 *
 * Network constraints are now read from [AppPrefs] so the "Sync over Wi-Fi only" switch actually
 * means something, and the periodic job is cancelled when automatic sync is switched off.
 */
object SyncScheduler {

    private const val IMMEDIATE_SYNC_WORK_NAME = "immediate_drive_sync_work"
    private const val PERIODIC_SYNC_WORK_NAME = "periodic_drive_sync_work"

    /**
     * @param requireWifi null = honour the user's "Sync over Wi-Fi only" preference.
     */
    fun scheduleImmediateSync(context: Context, requireWifi: Boolean? = null) {
        val needsWifi = requireWifi ?: AppPrefs.syncWifiOnly(context)
        val request = OneTimeWorkRequestBuilder<GoogleDriveSyncWorker>()
            .setConstraints(networkConstraints(needsWifi))
            // Transient network failures back off instead of hammering Google.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun schedulePeriodicSync(context: Context) {
        if (!AppPrefs.autoSync(context)) {
            cancelPeriodicSync(context)
            return
        }
        schedulePeriodicSync(
            context = context,
            intervalHours = AppPrefs.syncIntervalHours(context),
            requireWifi = AppPrefs.syncWifiOnly(context)
        )
    }

    fun schedulePeriodicSync(context: Context, intervalHours: Long, requireWifi: Boolean) {
        val request = PeriodicWorkRequestBuilder<GoogleDriveSyncWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(networkConstraints(requireWifi))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPeriodicSync(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
        }
    }

    fun cancelAll(context: Context) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(IMMEDIATE_SYNC_WORK_NAME)
            wm.cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
        }
    }

    /** True when a sync run is currently queued or executing. */
    fun isSyncPending(context: Context): Boolean {
        val states = runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(IMMEDIATE_SYNC_WORK_NAME).get()
        }.getOrDefault(emptyList())
        return states.any { !it.state.isFinished }
    }

    private fun networkConstraints(requireWifi: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()
}
