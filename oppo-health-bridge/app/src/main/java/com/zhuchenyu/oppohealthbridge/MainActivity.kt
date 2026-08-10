package com.zhuchenyu.oppohealthbridge

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.heytap.databaseengine.HeytapHealthApi
import com.heytap.databaseengine.apiv2.HResponse
import com.heytap.databaseengine.apiv2._HeytapHealth
import com.heytap.databaseengine.apiv2.auth.AuthResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var reader: OppoHealthReader
    private lateinit var writer: HealthConnectWriter
    private lateinit var status: TextView

    private var oppoAuthPending = false
    private var oppoAuthLeftApp = false
    private var lastOppoActivityResult: String? = null
    @Volatile private var lastAuthCallback: String = "尚未触发"

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        setStatus("Health Connect 已授权 ${granted.size}/${HealthConnectWriter.PERMISSIONS.size} 项。")
        refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = OppoHealthReader(applicationContext)
        writer = HealthConnectWriter(applicationContext)
        setContentView(buildUi())

        lifecycleScope.launch {
            setStatus("正在初始化 OPPO Health SDK 2.1.7，并等待内部 hasInit=true……")
            val ready = awaitOppoSdkReady()
            if (ready) {
                refreshState()
            } else {
                setStatus("OPPO Health SDK 初始化超时：_HeytapHealth.hasInit() 5 秒内仍为 false。")
            }
        }
    }

    override fun onPause() {
        if (oppoAuthPending) oppoAuthLeftApp = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (oppoAuthPending && oppoAuthLeftApp) {
            oppoAuthPending = false
            oppoAuthLeftApp = false
            lifecycleScope.launch {
                delay(500L)
                validateOppoAuthorization("从 OPPO 健康返回（onResume）")
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        lastOppoActivityResult = "requestCode=$requestCode, resultCode=$resultCode"
        super.onActivityResult(requestCode, resultCode, data)
    }

    private suspend fun awaitOppoSdkReady(timeoutMs: Long = 5_000L): Boolean {
        reader.initialize()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!_HeytapHealth.hasInit() && SystemClock.elapsedRealtime() < deadline) {
            delay(25L)
        }
        return _HeytapHealth.hasInit()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val gap = (10 * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(this).apply {
            text = "OPPO Health → Health Connect"
            textSize = 24f
            setPadding(0, 0, 0, gap)
        })

        content.addView(TextView(this).apply {
            text = "v0.3.1：按 SDK 2.1.7 的真实 API 重新实现授权，并吸收 OPPO 官方 Demo 的初始化顺序。"
            textSize = 15f
            setPadding(0, 0, 0, gap)
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, gap, 0, gap)
        }
        content.addView(status)

        content.addView(actionButton("1. 授权 OPPO 健康（2.1.7 重写版）", {
            requestOppoAuthorization()
        }, gap))

        content.addView(actionButton("2. 授权 Health Connect 写入", {
            try {
                if (!writer.isAvailable()) {
                    setStatus(healthConnectAvailabilityText())
                    return@actionButton
                }
                healthPermissionLauncher.launch(HealthConnectWriter.PERMISSIONS)
            } catch (t: Throwable) {
                setStatus("无法打开 Health Connect 授权：${t.message ?: t.javaClass.simpleName}")
            }
        }, gap))

        content.addView(actionButton("3. 立即同步最近 7 个自然日", {
            syncNow()
        }, gap))

        content.addView(actionButton("开启 15 分钟后台同步", {
            lifecycleScope.launch {
                try {
                    if (!awaitOppoSdkReady()) {
                        setStatus("OPPO Health SDK 初始化失败。")
                        return@launch
                    }
                    val scopes = reader.authorizedScopes()
                    if (scopes.isEmpty()) {
                        setStatus("请先完成 OPPO 健康授权。")
                        return@launch
                    }
                    if (!writer.isAvailable()) {
                        setStatus(healthConnectAvailabilityText())
                        return@launch
                    }
                    if (!writer.hasAllPermissions()) {
                        setStatus("请先完成 Health Connect 写入授权。")
                        return@launch
                    }
                    SyncWorker.schedule(applicationContext)
                    setStatus("已开启后台同步。WorkManager 最短周期约 15 分钟；系统省电策略可能延后实际执行。")
                } catch (t: Throwable) {
                    setStatus("开启后台同步失败：${friendlyError(t)}")
                }
            }
        }, gap))

        content.addView(actionButton("关闭后台同步", {
            SyncWorker.cancel(applicationContext)
            setStatus("后台同步已关闭。")
        }, gap))

        content.addView(actionButton("刷新状态", {
            refreshState()
        }, gap))

        content.addView(actionButton("开发者信息 / OPPO 白名单资料", {
            startActivity(Intent(this, DeveloperInfoActivity::class.java))
        }, gap))

        content.addView(TextView(this).apply {
            text = "诊断重点：SDK 2.1.7 编译期只提供带 HResponse<AuthResult> 的 request 重载，因此不再照搬旧版 1.1.5 Demo 的 request(Activity)。本版只保留官方 Demo 中仍适用于 2.1.7 的初始化等待、Manifest queries 与 singleTop 生命周期处理。"
            textSize = 13f
            setPadding(0, gap, 0, 0)
        })

        return ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun requestOppoAuthorization() {
        lifecycleScope.launch {
            try {
                if (!reader.isHealthInstalled()) {
                    setStatus("未检测到国行 OPPO 健康（com.heytap.health）。")
                    return@launch
                }

                setStatus("正在等待 OPPO Health SDK 真正初始化完成……")
                if (!awaitOppoSdkReady()) {
                    setStatus("SDK 初始化超时，没有发起授权。")
                    return@launch
                }

                oppoAuthPending = true
                oppoAuthLeftApp = false
                lastOppoActivityResult = null
                lastAuthCallback = "等待 SDK 回调"
                setStatus(
                    "SDK hasInit=true。\n" +
                        "现在调用 SDK 2.1.7 的 authorityApi().request(Activity, HResponse<AuthResult>)……"
                )

                HeytapHealthApi.getInstance().authorityApi().request(
                    this@MainActivity,
                    object : HResponse<AuthResult> {
                        override fun onSuccess(result: AuthResult) {
                            lastAuthCallback = "onSuccess(errorCode=${result.errorCode})"
                            runOnUiThread {
                                oppoAuthPending = false
                                oppoAuthLeftApp = false
                                lifecycleScope.launch {
                                    delay(200L)
                                    validateOppoAuthorization("授权回调 onSuccess，errorCode=${result.errorCode}")
                                }
                            }
                        }

                        override fun onFailure(code: Int) {
                            lastAuthCallback = "onFailure(code=$code)"
                            runOnUiThread {
                                oppoAuthPending = false
                                oppoAuthLeftApp = false
                                lifecycleScope.launch {
                                    delay(200L)
                                    validateOppoAuthorization("授权回调 onFailure，code=$code")
                                }
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                oppoAuthPending = false
                oppoAuthLeftApp = false
                setStatus("OPPO 2.1.7 授权调用失败：${friendlyError(t)}")
            }
        }
    }

    private suspend fun validateOppoAuthorization(trigger: String) {
        val activityResultText = lastOppoActivityResult ?: "未收到 onActivityResult"
        try {
            if (!awaitOppoSdkReady()) {
                setStatus(
                    "$trigger\n" +
                        "SDK hasInit=false。\n" +
                        "授权回调：$lastAuthCallback\n" +
                        "ActivityResult：$activityResultText"
                )
                return
            }

            val scopes = reader.authorizedScopes()
            val scopeText = scopes.joinToString("\n").ifBlank { "（没有返回 scope）" }
            setStatus(
                "$trigger\n" +
                    "SDK hasInit=true\n" +
                    "授权回调：$lastAuthCallback\n" +
                    "ActivityResult：$activityResultText\n" +
                    "valid() 成功，当前 scope：\n$scopeText"
            )
        } catch (t: Throwable) {
            setStatus(
                "$trigger\n" +
                    "SDK hasInit=${_HeytapHealth.hasInit()}\n" +
                    "授权回调：$lastAuthCallback\n" +
                    "ActivityResult：$activityResultText\n" +
                    "valid()：${friendlyError(t)}"
            )
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit, bottomMargin: Int): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, bottomMargin) }
        }

    private fun refreshState() {
        lifecycleScope.launch {
            val lines = mutableListOf<String>()
            lines += "OPPO 健康安装：${if (reader.isHealthInstalled()) "是" else "否"}"

            val sdkReady = runCatching { awaitOppoSdkReady() }.getOrDefault(false)
            lines += "OPPO Health SDK hasInit：$sdkReady"

            if (sdkReady) {
                try {
                    val scopes = reader.authorizedScopes()
                    lines += "OPPO 健康授权：${if (scopes.isNotEmpty()) "已授权（${scopes.size} 个 scope）" else "未授权"}"
                    if (scopes.isNotEmpty()) lines += "scope：${scopes.joinToString(", ")}"
                } catch (t: Throwable) {
                    lines += "OPPO 健康授权状态：${friendlyError(t)}"
                }
            }

            lines += "最近授权回调：$lastAuthCallback"

            if (!writer.isAvailable()) {
                lines += healthConnectAvailabilityText()
            } else {
                try {
                    val granted = writer.grantedPermissions()
                    val count = HealthConnectWriter.PERMISSIONS.count { it in granted }
                    lines += "Health Connect：已授权 $count/${HealthConnectWriter.PERMISSIONS.size} 项写入权限"
                } catch (t: Throwable) {
                    lines += "Health Connect：${t.message ?: t.javaClass.simpleName}"
                }
            }

            lines += "OPPO 授权方式：SDK 2.1.7 request(Activity, HResponse<AuthResult>)"
            lines += "SDK 初始化：等待 _HeytapHealth.hasInit()=true"
            lines += "版本：${BuildConfig.VERSION_NAME}"
            setStatus(lines.joinToString("\n"))
        }
    }

    private fun syncNow() {
        lifecycleScope.launch {
            try {
                setStatus("正在检查授权……")
                if (!awaitOppoSdkReady()) {
                    setStatus("OPPO Health SDK 初始化失败。")
                    return@launch
                }
                val scopes = reader.authorizedScopes()
                if (scopes.isEmpty()) {
                    setStatus("请先点击“授权 OPPO 健康”。")
                    return@launch
                }
                if (!writer.isAvailable()) {
                    setStatus(healthConnectAvailabilityText())
                    return@launch
                }
                if (!writer.hasAllPermissions()) {
                    setStatus("请先点击“授权 Health Connect 写入”。")
                    return@launch
                }

                setStatus("正在读取 OPPO 健康最近 7 个自然日的数据……")
                val snapshot = reader.readSnapshot(days = 7)
                setStatus("读取完成，正在写入 Health Connect……")
                val result = writer.write(snapshot)
                val nowText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val warningText = if (result.sourceWarnings.isEmpty()) "无" else result.sourceWarnings.joinToString("\n")
                setStatus(
                    """
                    同步完成：$nowText
                    心率记录组：${result.heartRateRecords}
                    静息心率：${result.restingHeartRateRecords}
                    血氧：${result.spo2Records}
                    步数：${result.stepsRecords}
                    消耗：${result.caloriesRecords}
                    睡眠会话：${result.sleepRecords}
                    共写入/更新：${result.totalRecords} 条 Health Connect Record
                    OPPO 数据源警告：$warningText
                    """.trimIndent()
                )
            } catch (t: Throwable) {
                setStatus("同步失败：${friendlyError(t)}")
            }
        }
    }

    private fun healthConnectAvailabilityText(): String = when (writer.sdkStatus()) {
        HealthConnectClient.SDK_UNAVAILABLE -> "Health Connect：当前设备/系统不支持"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect：未安装或需要更新提供程序"
        HealthConnectClient.SDK_AVAILABLE -> "Health Connect：可用"
        else -> "Health Connect：未知状态 ${writer.sdkStatus()}"
    }

    private fun friendlyError(t: Throwable): String {
        return if (t is OppoSdkException) {
            val hint = when (t.errorCode) {
                100004 -> "OPPO 健康账号未登录"
                100006 -> "OPPO 健康拒绝权限"
                100007 -> "未安装 OPPO 健康"
                100008 -> "OPPO 健康版本过低"
                100012 -> "OPPO 健康授权失败"
                100014 -> "授权被取消"
                100015 -> "绑定 OPPO 健康服务失败"
                101002 -> "读取健康数据失败"
                101004 -> "OPPO 健康正在同步数据，请稍后重试"
                101005 -> "查询结果为空"
                101006 -> "检查授权 scope 失败"
                else -> "请把错误码发给我"
            }
            "错误码 ${t.errorCode}（$hint）"
        } else {
            t.message ?: t.javaClass.simpleName
        }
    }

    private fun setStatus(text: String) {
        status.text = text
    }
}
