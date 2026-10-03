package com.zhuchenyu.healthchatbridge

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SecurePrefs
    private lateinit var repo: HealthConnectRepository
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var status: TextView

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        status.text = "Health Connect 已授权 ${granted.size} 项。可以执行首次同步。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SecurePrefs(this)
        repo = HealthConnectRepository(this)
        setContentView(buildUi())
        refreshStatus()
    }

    private fun buildUi(): ScrollView {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val gap = (10 * density).toInt()

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        body.addView(TextView(this).apply {
            text = "Health Connect → ChatGPT"
            textSize = 24f
        })
        body.addView(TextView(this).apply {
            text = "只读取 Health Connect 的日汇总，并通过 HTTPS 上传到你自己的私有桥接服务。自然日固定使用北京时间。"
            textSize = 15f
            setPadding(0, gap, 0, gap)
        })

        serverInput = EditText(this).apply {
            hint = "服务器，例如 https://health.example.com"
            setText(prefs.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        body.addView(serverInput)

        tokenInput = EditText(this).apply {
            hint = "INGEST_TOKEN"
            setText(prefs.getToken())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        body.addView(tokenInput)

        body.addView(button("保存服务器配置") { saveConfig() })
        body.addView(button("授权 Health Connect") {
            if (!repo.isAvailable()) {
                status.text = "Health Connect 当前不可用，请先安装或更新。"
            } else {
                permissionLauncher.launch(repo.requestedPermissions())
            }
        })
        body.addView(button("立即同步最近 7 天") { syncNow() })
        body.addView(button("开启每小时后台同步") { enableBackgroundSync() })
        body.addView(button("停止后台同步") {
            WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
            status.text = "已停止后台同步。"
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, gap * 2, 0, gap)
        }
        body.addView(status)

        return ScrollView(this).apply { addView(body) }
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 }
    }

    private fun saveConfig(): Boolean {
        val url = serverInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()
        if (!url.startsWith("https://")) {
            status.text = "服务器必须是 HTTPS 地址。"
            return false
        }
        if (token.length < 16) {
            status.text = "同步令牌过短，请使用服务端生成的随机令牌。"
            return false
        }
        prefs.serverUrl = url
        prefs.setToken(token)
        status.text = "配置已保存，令牌已由 Android Keystore 加密。"
        return true
    }

    private fun syncNow() {
        if (!saveConfig()) return
        lifecycleScope.launch {
            status.text = "正在读取 Health Connect…"
            runCatching {
                check(repo.isAvailable()) { "Health Connect 不可用" }
                val snapshots = repo.readRecentDays(7)
                val payload = snapshots.toUploadJson(prefs.deviceId)
                val response = withContext(Dispatchers.IO) {
                    ApiClient().upload(prefs.serverUrl, prefs.getToken(), payload)
                }
                "同步完成：${snapshots.size} 天。服务器：$response"
            }.onSuccess { status.text = it }
                .onFailure { status.text = "同步失败：${it.message}" }
        }
    }

    private fun enableBackgroundSync() {
        if (!saveConfig()) return
        lifecycleScope.launch {
            val granted = runCatching { repo.grantedPermissions() }.getOrDefault(emptySet())
            val backgroundPermission = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
            if (backgroundPermission !in granted) {
                status.text = "尚未授予 Health Connect 后台读取权限。请先点“授权 Health Connect”并允许后台读取。"
                return@launch
            }
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(this@MainActivity).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            status.text = "已开启每小时后台同步；每次回补最近 3 个北京时间自然日。"
        }
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            status.text = if (!repo.isAvailable()) {
                "Health Connect：不可用"
            } else {
                val granted = runCatching { repo.grantedPermissions().size }.getOrDefault(0)
                "Health Connect：可用；当前已授权 $granted 项。"
            }
        }
    }

    companion object {
        private const val WORK_NAME = "health-connect-chatgpt-hourly"
    }
}
