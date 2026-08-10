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
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.heytap.databaseengine.HeytapHealthApi
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

    override fun onPause() {
        if (oppoAuthPending) {
            oppoAuthLeftApp = true
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (oppoAuthPending && oppoAuthLeftApp) {
            oppoAuthPending = false
            oppoAuthLeftApp = false
            lifecycleScope.launch {
                delay(350)
                validateOppoAuthorizationAfterReturn()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        lastOppoActivityResult = "requestCode=$requestCode, resultCode=$resultCode"
        super.onActivityResult(requestCode, resultCode, data)
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
            text = "v0.3.0 授权链路改为 OPPO 官方 healthsdk_demo 的 Activity 授权方式：request(Activity) → 返回 App → valid() 校验。"
            textSize = 15f
            setPadding(0, 0, 0, gap)
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, gap, 0, gap)
        }
        content.addView(status)

        content.addView(actionButton("1. 授权 OPPO 健康（官方 Demo 模式）", {
            try {
                if (!reader.isHealthInstalled()) {
                    setStatus("未检测到国行 OPPO 健康（com.heytap.health）。")
                    return@actionButton
                }

                reader.initialize()
                oppoAuthPending = true
                oppoAuthLeftApp = false
                lastOppoActivityResult = null
                setStatus(
                    "正在按 OPPO 官方 Demo 调用 authorityApi().request(Activity)…\n" +
                        "如果授权页被成功拉起，返回本 App 后会自动调用 valid() 校验 scope。"
                )

                // Strictly mirror OPPO-OpenPlatform/healthsdk_demo:
                // authorityApi().request(Activity) with no AuthResult callback.
                HeytapHealthApi.getInstance().authorityApi().request(this@MainActivity)
            } catch (t: Throwable) {
                oppoAuthPending = false
                oppoAuthLeftApp = false
                setStatus("OPPO 官方模式授权调用失败：${friendlyError(t)}")
            }
        }, gap))

        content.addView(actionButton("2. 授权 Health Connect 写入", {
            try {
                healthPermissionLauncher.launch(HealthConnectWriter.PERMISSIONS)
            } catch (t: Throwable) {
                setStatus("无法打开 Health Connect 授权：${t.message ?: t.javaClass.simpleName}")
            }
        }, gap))

        content.addView(actionButton("3. 立即同步最近 7 天", {
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
                    if (!writer.hasAllPermissions()) {
                        setStatus("请先完成 Health Connect 写入授权。")
                        return@launch
                    }
                    SyncWorker.schedule(applicationContext)
                    setStatus("已开启后台同步。Android WorkManager 会以约 15 分钟为最短周期调度；系统省电策略可能让实际执行时间稍有延迟。")
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
            text = "本版用于排除授权调用方式差异。如果仍然在返回后得到 100006，则能更有把握地判断问题位于 OPPO 侧的应用身份/预申请 scope，而不是 callback 授权写法。"
            textSize = 13f
            setPadding(0, gap, 0, 0)
        })

        return ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private suspend fun validateOppoAuthorizationAfterReturn() {
        val activityResultText = lastOppoActivityResult?.let { "\nActivityResult：$it" } ?: "\nActivityResult：未收到（仅通过 onResume 检测到已返回）"
        try {
            val scopes = reader.authorizedScopes()
            val scopeText = scopes.joinToString("\n").ifBlank { "（没有返回 scope）" }
            setStatus(
                "已从 OPPO 健康返回。$activityResultText\n" +
                    "valid() 成功，当前 scope：\n$scopeText"
            )
        } catch (t: Throwable) {
            setStatus(
                "已从 OPPO 健康返回。$activityResultText\n" +
                    "随后 valid() 失败：${friendlyError(t)}"
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

            try {
                val scopes = reader.authorizedScopes()
                lines += "OPPO 健康授权：${if (scopes.isNotEmpty()) "已授权（${scopes.size} 个 scope）" else "未授权"}"
                if (scopes.isNotEmpty()) lines += "scope：${scopes.joinToString(", ")}"
            } catch (t: Throwable) {
                lines += "OPPO 健康授权状态：${friendlyError(t)}"
            }

            try {
                val granted = writer.grantedPermissions()
                val count = HealthConnectWriter.PERMISSIONS.count { it in granted }
                lines += "Health Connect：已授权 $count/${HealthConnectWriter.PERMISSIONS.size} 项写入权限"
            } catch (t: Throwable) {
                lines += "Health Connect：${t.message ?: t.javaClass.simpleName}"
            }

            lines += "OPPO 授权方式：官方 Demo request(Activity)"
            lines += "SDK：OPPO Health SDK 2.1.7"
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
                if (!writer.hasAllPermissions()) {
                    setStatus("请先点击“授权 Health Connect 写入”。")
                    return@launch
                }

                setStatus("正在读取 OPPO 健康最近 7 天的数据……")
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
                    睡眠：${result.sleepRecords}
                    共写入/更新：${result.totalRecords} 条 Health Connect Record
                    OPPO 数据源警告：$warningText
                    """.trimIndent()
                )
            } catch (t: Throwable) {
                setStatus("同步失败：${friendlyError(t)}")
            }
        }
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
