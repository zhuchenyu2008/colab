package com.zhuchenyu.oppowatchprobe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), SensorEventListener {
    companion object {
        private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val SAMPLE_DURATION_MS = 30_000L
        private val HEALTH_KEYWORDS = listOf(
            "heart", "heartrate", "cardio", "bpm", "pulse", "ppg", "oxygen", "spo2",
            "oximeter", "blood", "ecg", "sleep", "health", "oppo", "oplus", "heytap",
            "step", "fitness", "sport"
        )
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var statusView: TextView
    private lateinit var reportView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val liveValues = linkedMapOf<String, String>()

    @Volatile private var staticReport = ""
    @Volatile private var healthServicesReport = "Health Services：尚未探测"
    @Volatile private var liveReport = "实时采样：尚未运行"
    @Volatile private var serverStatus = "局域网报告服务：未启动"

    private var server: ProbeReportServer? = null
    private var sampling = false
    private var pendingLiveStart = false
    private var lastUiRefreshMs = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshStaticReport()
        if (pendingLiveStart) {
            pendingLiveStart = false
            startLiveSampling()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        setContentView(buildUi())
        refreshStaticReport()
        probeHealthServices()
        startReportServer()
    }

    override fun onDestroy() {
        stopLiveSampling(updateUi = false)
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
            text = "Watch 3 Pro 传感器探针"
            textSize = 21f
            setPadding(0, 0, 0, gap)
        })

        content.addView(TextView(this).apply {
            text = "目标：确认第三方 APK 真正能访问的心率、计步、OPPO/OPlus 私有传感器和 Wear Health Services。"
            textSize = 13f
            setPadding(0, 0, 0, gap)
        })

        statusView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, gap)
        }
        content.addView(statusView)

        content.addView(actionButton("1. 请求传感器权限") {
            requestProbePermissions()
        })

        content.addView(actionButton("2. 刷新完整静态扫描") {
            refreshStaticReport()
            probeHealthServices()
        })

        content.addView(actionButton("3. 开始 30 秒实时采样") {
            if (missingRuntimePermissions().isNotEmpty()) {
                pendingLiveStart = true
                requestProbePermissions()
            } else {
                startLiveSampling()
            }
        })

        content.addView(actionButton("停止实时采样") {
            stopLiveSampling(updateUi = true)
        })

        content.addView(actionButton("4. 启动/刷新局域网报告地址") {
            startReportServer()
        })

        content.addView(actionButton("复制完整报告") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OPPO Watch Sensor Probe", fullReport()))
            Toast.makeText(this, "报告已复制", Toast.LENGTH_SHORT).show()
        })

        reportView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, gap, 0, 0)
        }
        content.addView(reportView)

        return ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun requestProbePermissions() {
        val missing = missingRuntimePermissions()
        if (missing.isEmpty()) {
            Toast.makeText(this, "所需运行时权限已具备", Toast.LENGTH_SHORT).show()
            refreshStaticReport()
            return
        }
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun missingRuntimePermissions(): List<String> {
        val requested = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requested += Manifest.permission.BODY_SENSORS
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            requested += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (Build.VERSION.SDK_INT >= 36 &&
            ContextCompat.checkSelfPermission(this, READ_HEART_RATE) != PackageManager.PERMISSION_GRANTED
        ) {
            requested += READ_HEART_RATE
        }
        return requested.distinct()
    }

    private fun refreshStaticReport() {
        staticReport = buildStaticReport()
        updateUi()
    }

    private fun buildStaticReport(): String = buildString {
        appendLine("=== OPPO WATCH SENSOR PROBE ===")
        appendLine("Generated: ${nowText()}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine()
        appendLine("[DEVICE]")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Brand: ${Build.BRAND}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Product: ${Build.PRODUCT}")
        appendLine("Hardware: ${Build.HARDWARE}")
        appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("FEATURE_WATCH: ${packageManager.hasSystemFeature("android.hardware.type.watch")}")
        appendLine()

        appendLine("[PERMISSIONS]")
        appendLine("BODY_SENSORS: ${permissionState(Manifest.permission.BODY_SENSORS)}")
        appendLine("ACTIVITY_RECOGNITION: ${permissionState(Manifest.permission.ACTIVITY_RECOGNITION)}")
        if (Build.VERSION.SDK_INT >= 36) {
            appendLine("READ_HEART_RATE: ${permissionState(READ_HEART_RATE)}")
        }
        appendLine()

        appendLine("[STANDARD SENSOR AVAILABILITY]")
        appendDefaultSensor("HEART_RATE", Sensor.TYPE_HEART_RATE)
        if (Build.VERSION.SDK_INT >= 24) appendDefaultSensor("HEART_BEAT", Sensor.TYPE_HEART_BEAT)
        appendDefaultSensor("STEP_COUNTER", Sensor.TYPE_STEP_COUNTER)
        appendDefaultSensor("STEP_DETECTOR", Sensor.TYPE_STEP_DETECTOR)
        appendDefaultSensor("ACCELEROMETER", Sensor.TYPE_ACCELEROMETER)
        appendDefaultSensor("GYROSCOPE", Sensor.TYPE_GYROSCOPE)
        appendDefaultSensor("PRESSURE", Sensor.TYPE_PRESSURE)
        appendDefaultSensor("AMBIENT_TEMPERATURE", Sensor.TYPE_AMBIENT_TEMPERATURE)
        appendLine()

        val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL).sortedWith(compareBy<Sensor> { it.type }.thenBy { it.name })
        val healthSensors = allSensors.filter(::looksHealthRelated)

        appendLine("[HEALTH / VENDOR SENSOR CANDIDATES] count=${healthSensors.size}")
        if (healthSensors.isEmpty()) {
            appendLine("(none matched keywords)")
        } else {
            healthSensors.forEach { appendSensor(it) }
        }
        appendLine()

        appendLine("[ALL SENSORS] count=${allSensors.size}")
        allSensors.forEach { appendSensor(it) }
        appendLine()

        appendLine("[HEALTH-RELATED INSTALLED PACKAGES / COMPONENTS]")
        append(scanHealthPackages())
    }

    private fun StringBuilder.appendDefaultSensor(label: String, type: Int) {
        val sensor = sensorManager.getDefaultSensor(type)
        if (sensor == null) {
            appendLine("$label: NO")
        } else {
            appendLine("$label: YES | ${sensor.name} | vendor=${sensor.vendor} | type=${sensor.type} | stringType=${sensor.stringType}")
        }
    }

    private fun StringBuilder.appendSensor(sensor: Sensor) {
        appendLine(
            "type=${sensor.type} | name=${sensor.name} | vendor=${sensor.vendor} | stringType=${sensor.stringType} | " +
                "ver=${sensor.version} | power=${sensor.power}mA | minDelay=${sensor.minDelay}us | maxDelay=${sensor.maxDelay}us | " +
                "range=${sensor.maximumRange} | resolution=${sensor.resolution} | wakeUp=${sensor.isWakeUpSensor} | " +
                "reportingMode=${sensor.reportingMode} | fifoMax=${sensor.fifoMaxEventCount}"
        )
    }

    private fun looksHealthRelated(sensor: Sensor): Boolean {
        val haystack = "${sensor.name} ${sensor.vendor} ${sensor.stringType}".lowercase(Locale.ROOT)
        return HEALTH_KEYWORDS.any { it in haystack } || sensor.type in setOf(
            Sensor.TYPE_HEART_RATE,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_STEP_DETECTOR,
            if (Build.VERSION.SDK_INT >= 24) Sensor.TYPE_HEART_BEAT else -1
        )
    }

    @Suppress("DEPRECATION")
    private fun scanHealthPackages(): String {
        return try {
            val flags = PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS
            val matches = packageManager.getInstalledPackages(flags)
                .mapNotNull { info ->
                    val appInfo = info.applicationInfo ?: return@mapNotNull null
                    val label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }.getOrDefault("")
                    val haystack = "${info.packageName} $label".lowercase(Locale.ROOT)
                    if (HEALTH_KEYWORDS.none { it in haystack }) return@mapNotNull null
                    Triple(info, label, haystack)
                }
                .sortedBy { it.first.packageName }

            buildString {
                appendLine("Matched packages: ${matches.size}")
                matches.forEach { (info, label, _) ->
                    appendLine("PACKAGE ${info.packageName} | label=$label | version=${info.versionName}")
                    info.services.orEmpty().take(30).forEach {
                        appendLine("  SERVICE ${it.name} | exported=${it.exported} | permission=${it.permission ?: "-"}")
                    }
                    info.providers.orEmpty().take(30).forEach {
                        appendLine("  PROVIDER ${it.name} | authority=${it.authority ?: "-"} | exported=${it.exported}")
                    }
                    info.receivers.orEmpty().take(30).forEach {
                        appendLine("  RECEIVER ${it.name} | exported=${it.exported} | permission=${it.permission ?: "-"}")
                    }
                }
            }
        } catch (t: Throwable) {
            "Package scan failed: ${t.javaClass.simpleName}: ${t.message}\n"
        }
    }

    private fun probeHealthServices() {
        healthServicesReport = "Health Services：探测中……"
        updateUi()
        try {
            val measureClient = HealthServices.getClient(this).measureClient
            val future = measureClient.getCapabilitiesAsync()
            future.addListener({
                healthServicesReport = try {
                    val capabilities = future.get()
                    val supported = capabilities.supportedDataTypesMeasure
                    buildString {
                        appendLine("[WEAR HEALTH SERVICES / MEASURE]")
                        appendLine("API reachable: YES")
                        appendLine("HEART_RATE_BPM supported: ${DataType.HEART_RATE_BPM in supported}")
                        appendLine("Supported measure data types (${supported.size}):")
                        supported.sortedBy { it.toString() }.forEach { appendLine("- $it") }
                    }.trimEnd()
                } catch (t: Throwable) {
                    "[WEAR HEALTH SERVICES / MEASURE]\nAPI reachable: NO\n${t.javaClass.simpleName}: ${rootMessage(t)}"
                }
                updateUi()
            }, ContextCompat.getMainExecutor(this))
        } catch (t: Throwable) {
            healthServicesReport = "[WEAR HEALTH SERVICES / MEASURE]\nAPI reachable: NO\n${t.javaClass.simpleName}: ${rootMessage(t)}"
            updateUi()
        }
    }

    private fun startLiveSampling() {
        if (sampling) return
        sampling = true
        liveValues.clear()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager.unregisterListener(this)

        val candidates = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .filter { sensor ->
                looksHealthRelated(sensor) || sensor.type in setOf(
                    Sensor.TYPE_ACCELEROMETER,
                    Sensor.TYPE_GYROSCOPE,
                    Sensor.TYPE_PRESSURE
                )
            }
            .distinctBy { "${it.type}:${it.name}:${it.vendor}" }

        var registered = 0
        val failed = mutableListOf<String>()
        candidates.forEach { sensor ->
            try {
                if (sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                    registered++
                } else {
                    failed += "${sensor.name}: registerListener=false"
                }
            } catch (t: Throwable) {
                failed += "${sensor.name}: ${t.javaClass.simpleName}:${t.message}"
            }
        }

        liveReport = buildString {
            appendLine("[LIVE SENSOR SAMPLE]")
            appendLine("Started: ${nowText()}")
            appendLine("Duration: 30s")
            appendLine("Registered sensors: $registered/${candidates.size}")
            if (failed.isNotEmpty()) {
                appendLine("Registration failures:")
                failed.forEach { appendLine("- $it") }
            }
            appendLine("Waiting for events…")
        }.trimEnd()
        updateUi()

        mainHandler.removeCallbacks(stopSamplingRunnable)
        mainHandler.postDelayed(stopSamplingRunnable, SAMPLE_DURATION_MS)
    }

    private val stopSamplingRunnable = Runnable { stopLiveSampling(updateUi = true) }

    private fun stopLiveSampling(updateUi: Boolean) {
        mainHandler.removeCallbacks(stopSamplingRunnable)
        sensorManager.unregisterListener(this)
        if (!sampling && !updateUi) return
        sampling = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        liveReport = buildString {
            appendLine("[LIVE SENSOR SAMPLE]")
            appendLine("Finished: ${nowText()}")
            appendLine("Sensors with events: ${liveValues.size}")
            if (liveValues.isEmpty()) appendLine("(no events received)")
            liveValues.forEach { (name, value) -> appendLine("$name => $value") }
        }.trimEnd()
        if (updateUi) updateUi()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sensor = event.sensor ?: return
        val key = "type=${sensor.type} ${sensor.name} [${sensor.vendor}]"
        val values = event.values.joinToString(prefix = "[", postfix = "]", limit = 12) { "%.4f".format(Locale.US, it) }
        liveValues[key] = "values=$values accuracy=${event.accuracy} timestampNs=${event.timestamp}"

        val now = System.currentTimeMillis()
        if (now - lastUiRefreshMs >= 500L) {
            lastUiRefreshMs = now
            liveReport = buildString {
                appendLine("[LIVE SENSOR SAMPLE] RUNNING")
                appendLine("Updated: ${nowText()}")
                appendLine("Sensors with events: ${liveValues.size}")
                liveValues.forEach { (name, value) -> appendLine("$name => $value") }
            }.trimEnd()
            updateUi()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startReportServer() {
        if (server == null) server = ProbeReportServer(reportProvider = ::fullReport)
        val url = server?.start() ?: server?.localUrl()
        serverStatus = if (url != null) {
            "局域网报告：$url\n手机与手表连同一 Wi‑Fi/热点后，用手机浏览器打开。"
        } else {
            "局域网报告：启动失败或当前没有可用 IPv4 地址。"
        }
        updateUi()
    }

    private fun fullReport(): String = listOf(
        staticReport,
        healthServicesReport,
        liveReport,
        "[LOCAL REPORT SERVER]\n$serverStatus"
    ).joinToString("\n\n")

    private fun updateUi() {
        runOnUiThread {
            statusView.text = buildString {
                appendLine("${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                appendLine("权限缺失：${missingRuntimePermissions().ifEmpty { listOf("无") }.joinToString()}")
                append(serverStatus)
            }
            reportView.text = fullReport()
        }
    }

    private fun permissionState(permission: String): String =
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun rootMessage(t: Throwable): String {
        var current = t
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }
}
