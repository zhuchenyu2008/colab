package com.zhuchenyu.oppohealthbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.security.MessageDigest

class DeveloperInfoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "开发者信息"
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val gap = (12 * density).toInt()

        val info = buildDeveloperInfo()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(this).apply {
            text = "开发者信息 / OPPO 授权排障"
            textSize = 23f
            setPadding(0, 0, 0, gap)
        })

        content.addView(TextView(this).apply {
            text = "以下包名和签名摘要均从当前实际安装 APK 动态读取。先用它确认每次安装的签名是否保持一致；只有技术项全部排除后，再用于 OPPO 官方预申请/白名单。"
            textSize = 14f
            setPadding(0, 0, 0, gap)
        })

        content.addView(TextView(this).apply {
            text = info
            textSize = 14f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, gap)
        })

        content.addView(Button(this).apply {
            text = "复制全部开发者信息"
            isAllCaps = false
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OPPO Health Bridge developer info", info))
                Toast.makeText(this@DeveloperInfoActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        content.addView(TextView(this).apply {
            text = "\n当前排障目标：如果同一固定签名 APK 仍稳定返回 100006，再把包名、SHA-1/SHA-256 和所需 scopes 交给 OPPO 官方处理。不要在签名尚未固定时提交白名单资料。"
            textSize = 13f
        })

        return ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun buildDeveloperInfo(): String {
        val signatures = signingDigests()
        return buildString {
            appendLine("App: OPPO Health Bridge")
            appendLine("Package: $packageName")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("OPPO Health SDK: 2.1.7（HeyTap 官方 Maven）")
            appendLine("OPPO Health package: com.heytap.health")
            appendLine("Callback Host: 未显式指定，使用 OPPO Health SDK 默认回调")
            appendLine()
            appendLine("Signing SHA-1:")
            appendLine(signatures.first)
            appendLine()
            appendLine("Signing SHA-256:")
            appendLine(signatures.second)
            appendLine()
            appendLine("希望获得的只读健康 scopes（由 OPPO 侧预配置，不是 App 自行声明即可获得）:")
            appendLine("- READ_HEART_RATE")
            appendLine("- READ_BLOOD_OXYGEN_DATA")
            appendLine("- READ_SLEEP_DATA")
            appendLine("- READ_DAILY_ACTIVITY")
            appendLine()
            appendLine("用途:")
            appendLine("读取用户本人已授权的 OPPO 健康数据，并写入 Android Health Connect；不向 OPPO 健康写入数据。")
            appendLine()
            appendLine("当前重点观察错误:")
            appendLine("100006 / ERR_PERMISSION_DENY")
        }.trim()
    }

    @Suppress("DEPRECATION")
    private fun signingDigests(): Pair<String, String> {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signer = packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                ?: return "无法读取" to "无法读取"
            digest(signer, "SHA-1") to digest(signer, "SHA-256")
        } catch (t: Throwable) {
            "读取失败：${t.javaClass.simpleName}" to "读取失败：${t.javaClass.simpleName}"
        }
    }

    private fun digest(bytes: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm)
            .digest(bytes)
            .joinToString(":") { "%02X".format(it) }
}
