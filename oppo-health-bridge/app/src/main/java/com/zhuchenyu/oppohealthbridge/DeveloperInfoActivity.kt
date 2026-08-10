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
            text = "开发者信息 / OPPO 白名单资料"
            textSize = 23f
            setPadding(0, 0, 0, gap)
        })

        content.addView(TextView(this).apply {
            text = "以下签名摘要从当前实际安装的 APK 动态计算，可直接截图或复制给 OPPO 开放平台/技术支持。"
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
            text = "\n已知真机现象：authorityApi().request() 可拉起 OPPO 健康，但授权 Activity 会立即退出，随后 authorityApi().valid() 返回 100006（ERR_PERMISSION_DENY）。这通常需要 OPPO 侧核对包名、签名、预申请权限、Host/白名单。"
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
            appendLine("OPPO Health SDK: 2.1.7")
            appendLine("OPPO Health package: com.heytap.health")
            appendLine("Callback Host: 未显式指定，使用 OPPO Health SDK 默认回调")
            appendLine()
            appendLine("Signing SHA-1:")
            appendLine(signatures.first)
            appendLine()
            appendLine("Signing SHA-256:")
            appendLine(signatures.second)
            appendLine()
            appendLine("申请的只读健康权限 / scopes:")
            appendLine("- READ_HEART_RATE")
            appendLine("- READ_BLOOD_OXYGEN_DATA")
            appendLine("- READ_SLEEP_DATA")
            appendLine("- READ_DAILY_ACTIVITY")
            appendLine()
            appendLine("用途:")
            appendLine("读取用户本人已授权的 OPPO 健康数据，并写入 Android Health Connect；不向 OPPO 健康写入数据。")
            appendLine()
            appendLine("当前已知错误:")
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
