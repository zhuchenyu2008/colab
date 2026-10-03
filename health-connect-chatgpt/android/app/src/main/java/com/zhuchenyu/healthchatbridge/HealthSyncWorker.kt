package com.zhuchenyu.healthchatbridge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HealthSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = SecurePrefs(applicationContext)
        val url = prefs.serverUrl
        val token = prefs.getToken()
        if (url.isBlank() || token.isBlank()) return Result.failure()

        return runCatching {
            val repo = HealthConnectRepository(applicationContext)
            check(repo.isAvailable()) { "Health Connect 不可用" }
            val granted = repo.grantedPermissions()
            check(HealthPermissionNames.backgroundReadPermission() in granted) {
                "未授予 Health Connect 后台读取权限"
            }
            val days = repo.readRecentDays(3)
            val json = days.toUploadJson(prefs.deviceId)
            withContext(Dispatchers.IO) { ApiClient().upload(url, token, json) }
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

private object HealthPermissionNames {
    fun backgroundReadPermission(): String = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
}
