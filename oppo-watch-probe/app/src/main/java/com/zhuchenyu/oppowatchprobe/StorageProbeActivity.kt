package com.zhuchenyu.oppowatchprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StorageProbeActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var reportView: TextView
    private var report: String = "尚未扫描"
    private var server: ProbeReportServer? = null

    companion object {
        private val KEYWORDS = listOf(
            "health", "heart", "heartrate", "sleep", "spo", "oxygen", "blood", "step",
            "sport", "fitness", "workout", "wear", "watch", "ppg", "ecg",
            "oppo", "oplus", "coloros", "heytap"
        )
        private val KNOWN_PACKAGES = listOf(
            "com.coloros.healthservice",
            "com.oplus.healthservice",
            "com.heytap.health",
            "com.heytap.health.international",
            "com.oppo.health",
            "com.oppo.wearable",
            "com.heytap.wearable"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        scan()
        startServer()
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (14 * density).toInt()
        val gap = (8 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = "Watch 3 Pro 历史数据存储探针"
            textSize = 20f
        })
        content.addView(TextView(this).apply {
            text = "目标：定位 OPPO 手表已保存的心率、血氧、睡眠、步数数据所在系统包 / Provider / Service / 数据目录。不会主动读取传感器。"
            textSize = 13f
            setPadding(0, gap, 0, gap)
        })
        statusView = TextView(this).apply { textSize = 13f }
        content.addView(statusView)
        content.addView(button("重新扫描系统健康组件") { scan() })
        content.addView(button("启动/刷新局域网报告地址") { startServer() })
        content.addView(button("复制完整报告") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Watch storage probe", report))
            Toast.makeText(this, "报告已复制", Toast.LENGTH_SHORT).show()
        })
        reportView = TextView(this).apply {
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, gap, 0, 0)
        }
        content.addView(reportView)
        return ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    @Suppress("DEPRECATION")
    private fun scan() {
        statusView.text = "正在扫描全部系统包与组件……"
        Thread {
            report = buildReport()
            runOnUiThread {
                reportView.text = report
                statusView.text = "扫描完成。${server?.localUrl()?.let { "手机可打开：$it" } ?: "可复制报告。"}"
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun buildReport(): String {
        val pm = packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
        val packages = try {
            pm.getInstalledPackages(flags).sortedBy { it.packageName }
        } catch (t: Throwable) {
            return "PACKAGE SCAN FAILED: ${t.javaClass.simpleName}: ${t.message}"
        }

        val candidateSet = packages.filter { isCandidate(it) }
        return buildString {
            appendLine("=== OPPO WATCH HISTORY STORAGE PROBE ===")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("Device: ${android.os.Build.BRAND} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()

            appendLine("[KNOWN PACKAGE CHECK]")
            KNOWN_PACKAGES.forEach { name ->
                val p = packages.firstOrNull { it.packageName == name }
                appendLine("$name => ${if (p == null) "NOT FOUND" else "FOUND version=${p.versionName} source=${p.applicationInfo?.sourceDir}"}")
            }
            appendLine()

            appendLine("[SUMMARY]")
            appendLine("Installed packages visible: ${packages.size}")
            appendLine("Candidate packages: ${candidateSet.size}")
            appendLine()

            appendLine("[CANDIDATE PACKAGE DETAILS]")
            if (candidateSet.isEmpty()) appendLine("(none)")
            candidateSet.forEach { appendPackageDetails(it) }

            appendLine()
            appendLine("[ALL PACKAGE NAMES + APK PATHS]")
            packages.forEach { p ->
                val ai = p.applicationInfo
                val system = ai?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } == true
                appendLine("${p.packageName} | v=${p.versionName} | system=$system | uid=${ai?.uid} | source=${ai?.sourceDir}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isCandidate(info: PackageInfo): Boolean {
        val ai = info.applicationInfo
        val label = runCatching { ai?.let { packageManager.getApplicationLabel(it).toString() } ?: "" }.getOrDefault("")
        val componentNames = buildList {
            info.activities.orEmpty().forEach { add(it.name ?: "") }
            info.services.orEmpty().forEach { add(it.name ?: "") }
            info.providers.orEmpty().forEach { add("${it.name} ${it.authority}") }
            info.receivers.orEmpty().forEach { add(it.name ?: "") }
            info.requestedPermissions.orEmpty().forEach { add(it) }
        }.joinToString(" ")
        val haystack = "${info.packageName} $label $componentNames".lowercase(Locale.ROOT)
        return KEYWORDS.any { it in haystack }
    }

    private fun StringBuilder.appendPackageDetails(info: PackageInfo) {
        val ai = info.applicationInfo
        val label = runCatching { ai?.let { packageManager.getApplicationLabel(it).toString() } ?: "" }.getOrDefault("")
        val source = ai?.sourceDir
        val data = ai?.dataDir
        val sourceFile = source?.let(::File)
        val dataFile = data?.let(::File)
        val system = ai?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } == true

        appendLine("PACKAGE ${info.packageName}")
        appendLine("  label=$label version=${info.versionName} system=$system uid=${ai?.uid}")
        appendLine("  sourceDir=$source exists=${sourceFile?.exists()} readable=${sourceFile?.canRead()} size=${sourceFile?.takeIf { it.exists() }?.length()}")
        appendLine("  dataDir=$data exists=${dataFile?.exists()} readable=${dataFile?.canRead()} listable=${runCatching { dataFile?.list()?.size }.getOrNull()}")

        val requested = info.requestedPermissions.orEmpty()
        if (requested.isNotEmpty()) {
            appendLine("  requestedPermissions:")
            requested.forEach { appendLine("    - $it") }
        }

        info.providers.orEmpty().forEach { p ->
            appendLine("  PROVIDER ${p.name} | authority=${p.authority} | exported=${p.exported} | readPerm=${p.readPermission ?: "-"} | writePerm=${p.writePermission ?: "-"}")
        }
        info.services.orEmpty().forEach { s ->
            appendLine("  SERVICE ${s.name} | exported=${s.exported} | permission=${s.permission ?: "-"}")
        }
        info.receivers.orEmpty().forEach { r ->
            appendLine("  RECEIVER ${r.name} | exported=${r.exported} | permission=${r.permission ?: "-"}")
        }
        info.activities.orEmpty().forEach { a ->
            appendLine("  ACTIVITY ${a.name} | exported=${a.exported} | permission=${a.permission ?: "-"}")
        }
        appendLine()
    }

    private fun startServer() {
        server?.stop()
        server = ProbeReportServer(reportProvider = { report })
        val url = server?.start()
        statusView.text = if (url != null) "局域网报告：$url" else "局域网报告服务启动失败；可以直接复制报告。"
    }
}
