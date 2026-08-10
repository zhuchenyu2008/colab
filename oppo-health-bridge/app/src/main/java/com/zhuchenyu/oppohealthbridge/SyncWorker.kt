package com.zhuchenyu.oppohealthbridge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val reader = OppoHealthReader(applicationContext)
            val writer = HealthConnectWriter(applicationContext)
            reader.initialize()

            if (reader.authorizedScopes().isEmpty()) {
                return Result.failure(workDataOf("error" to "OPPO Health not authorized"))
            }
            if (!writer.hasAllPermissions()) {
                return Result.failure(workDataOf("error" to "Health Connect not authorized"))
            }

            val snapshot = reader.readSnapshot(days = 2)
            val stats = writer.write(snapshot)
            Result.success(
                workDataOf(
                    "records" to stats.totalRecords,
                    "syncedAt" to System.currentTimeMillis()
                )
            )
        } catch (t: Throwable) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure(workDataOf("error" to (t.message ?: t.javaClass.simpleName)))
            }
        }
    }

    companion object {
        private const val UNIQUE_WORK = "oppo-health-to-health-connect"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
