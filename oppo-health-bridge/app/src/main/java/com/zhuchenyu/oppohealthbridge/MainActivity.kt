package com.zhuchenyu.oppohealthbridge

import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var reader: OppoHealthReader
    private lateinit var writer: HealthConnectWriter
    private lateinit var status: TextView
    private var oppoAuthorizationInFlight = false
    private var oppoAuthorizationStartedAtMs = 0L

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
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (!::reader.isInitialized || !oppoAuthorizationInFlight) return
        if (System.currentTimeMillis() - oppoAuthorizationStartedAtMs < 500L) return

        // Some OPPO Health versions return to our Activity before reliably delivering the request
        // callback. Re-check valid scopes on resume so a real authorization is not mistaken for a hang.
        lifecycleScope.launch {
            delay(700L)
            val scopes = runCatching { reader.authorizedScopes() }.getOrNull().orEmpty()
            if (scopes.isNotEmpty()) {
                oppoAuthorizationInFlight = false
                setStatus("OPPO 健康授权已确认。当前 scope：\n${scopes.joinToString("\n")}")
            }
        }
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
            text = "把国行 OPPO 健康中已同步的心率、静息心率、血氧、步数、消耗与睡眠写入系统 Health Connect。数据默认不上传服务器。"
            textSize = 15f
            setPadding(0, 0, 0, gap)
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, gap, 0, gap)
        }
        content.addView(status)

        content.addView(actionButton("1. 授权 OPPO 健康", {
            lifecycleScope.launch {
                try {
                    if (!reader.isHealthInstalled()) {
                        setStatus("未检测到国行 OPPO 健康（com.heytap.health）。")
                        return@launch
                    }
                    oppoAuthorizationInFlight = true
                    oppoAuthorizationStartedAtMs = System.currentTimeMillis()
                    setStatus("正在打开 OPPO 健康授权页……")
                    reader.requestAuthorization(this@MainActivity)
                    val scopes = reader.authorizedScopes()
                    oppoAuthorizationInFlight = false
                    setStatus("OPPO 健康授权完成。当前 scope：\n${scopes.joinToString("\n").ifBlank { "（没有返回 scope）" }}")
                } catch (t: Throwable) {
                    oppoAuthorizationInFlight = false
                    setStatus("OPPO 健康授权失败：${friendlyError(t)}")
                }
            }
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
                    setStatus("已开启后台同步。WorkManager 最短周期约 15 分钟；系统省电策略可能延后实际执行。每次同步最近 3 个完整自然日（含今天），避免部分日期覆盖完整历史。")
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
            text = "当前排障顺序：先固定包名/签名、确认 SDK 与授权回调、修正数据同步；只有这些都排除后，才把 100006 作为 OPPO 官方预申请/白名单问题处理。"
            textSize = 13f
            setPadding(0, gap, 0, 0)
        })

        return ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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

            try {
                val scopes = reader.authorizedScopes()
                lines += "OPPO 健康授权：${if (scopes.isNotEmpty()) "已授权（${scopes.size} 个 scope）" else "未授权"}"
            } catch (t: Throwable) {
                lines += "OPPO 健康授权状态：${friendlyError(t)}"
            }

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

            lines += "版本：${BuildConfig.VERSION_NAME}"
            setStatus(lines.joinToString("\n"))
        }
    }

    private fun syncNow() {
        lifecycleScope.launch {
            try {
                setStatus("正在检查授权……")
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
                100006 -> "OPPO 健康拒绝权限：先核对当前 APK 固定签名；最后再考虑官方预申请/白名单"
                100007 -> "未安装 OPPO 健康"
                100008 -> "OPPO 健康版本过低"
                100012 -> "OPPO 健康授权失败或授权结果未确认"
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
