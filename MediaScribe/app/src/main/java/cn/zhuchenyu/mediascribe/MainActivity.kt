package cn.zhuchenyu.mediascribe

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val ink = Color.rgb(18, 18, 18)
    private val muted = Color.rgb(113, 113, 113)
    private val line = Color.rgb(226, 226, 226)
    private val canvas = Color.rgb(250, 250, 249)
    private val white = Color.WHITE

    private var selectedVideo: Uri? = null
    private var selectedAudio: Uri? = null
    private var selectedModel = ModelCatalog.BASE
    private var activeTab = 0

    private lateinit var tabVideo: TextView
    private lateinit var tabText: TextView
    private lateinit var actionTitle: TextView
    private lateinit var actionDetail: TextView
    private lateinit var chosenFile: TextView
    private lateinit var chooseButton: Button
    private lateinit var runButton: Button
    private lateinit var modelPanel: LinearLayout
    private lateinit var modelBase: TextView
    private lateinit var modelTiny: TextView
    private lateinit var modelStatus: TextView
    private lateinit var taskStatus: TextView
    private lateinit var taskProgress: ProgressBar

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(MediaTaskService.EXTRA_MESSAGE) ?: return
            taskStatus.text = message
            val progress = intent.getIntExtra(MediaTaskService.EXTRA_PROGRESS, -1)
            taskProgress.isIndeterminate = progress < 0
            if (progress >= 0) taskProgress.progress = progress.coerceIn(0, 100)
            if (intent.getBooleanExtra(MediaTaskService.EXTRA_DONE, false)) {
                taskProgress.isIndeterminate = false
                taskProgress.progress = if (progress == 100) 100 else 0
                updateModelStatus()
                val uri = intent.getStringExtra(MediaTaskService.EXTRA_OUTPUT_URI)
                if (uri != null && intent.getStringExtra(MediaTaskService.EXTRA_OUTPUT_TYPE) == "mp3") {
                    selectedAudio = Uri.parse(uri)
                    selectTab(1)
                    chosenFile.text = "刚刚生成的 MP3 · 可以直接转文字"
                    toast("MP3 已保存，可以直接转文字")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = canvas
        window.navigationBarColor = canvas
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        selectedVideo = savedInstanceState?.getString("video")?.let(Uri::parse)
        selectedAudio = savedInstanceState?.getString("audio")?.let(Uri::parse)
        selectedModel = savedInstanceState?.getString("model") ?: ModelCatalog.BASE
        activeTab = savedInstanceState?.getInt("tab") ?: 0

        val scroll = ScrollView(this).apply {
            setBackgroundColor(canvas)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(48))
        }
        scroll.addView(content)
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(text("M", 17f, white, true, gravity = Gravity.CENTER).apply {
            background = rounded(ink, 12)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandText.addView(text("MEDIASCRIBE", 13f, ink, true, tracking = 0.15f))
        brandText.addView(text("LOCAL AUDIO WORKSPACE", 10f, muted, false, tracking = 0.11f))
        brand.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        brand.addView(text("v1.1", 12f, muted, true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = bordered(white, line, 10)
        })
        content.addView(brand)
        content.addView(text("声音，\n值得被记录。", 36f, ink, true).apply {
            setPadding(0, dp(35), 0, dp(10))
            setLineSpacing(dp(1).toFloat(), 1f)
        })
        content.addView(text("从视频中提取声音，或把一小时录音整理成文字。\n一部手机，离线完成。", 14f, muted, false).apply {
            setLineSpacing(dp(5).toFloat(), 1f)
        })
        content.addView(text("WORKSPACE", 11f, muted, true, tracking = 0.22f).apply {
            setPadding(0, dp(30), 0, dp(11))
        })

        val switcher = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bordered(white, line, 15)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        tabVideo = tab("视频 → MP3") { selectTab(0) }
        tabText = tab("音频 → 文字") { selectTab(1) }
        switcher.addView(tabVideo, LinearLayout.LayoutParams(0, dp(48), 1f))
        switcher.addView(tabText, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(switcher)

        val panel = column().apply {
            setPadding(dp(20), dp(25), dp(20), dp(22))
            background = bordered(white, line, 19)
        }
        actionTitle = text("", 24f, ink, true)
        actionDetail = text("", 13f, muted, false).apply {
            setPadding(0, dp(8), 0, dp(18))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        panel.addView(actionTitle)
        panel.addView(actionDetail)
        val pickerBox = column().apply {
            background = dashed()
            setPadding(dp(17), dp(18), dp(17), dp(16))
        }
        pickerBox.addView(text("已选择文件  /  SELECTED FILE", 10f, muted, true, tracking = 0.1f))
        chosenFile = text("尚未选择文件", 14f, ink, true).apply {
            setPadding(0, dp(9), 0, dp(12))
            maxLines = 2
        }
        pickerBox.addView(chosenFile)
        chooseButton = button("选择本地文件", false) { openPicker() }
        pickerBox.addView(chooseButton)
        panel.addView(pickerBox)

        modelPanel = column().apply {
            setPadding(0, dp(23), 0, 0)
        }
        modelPanel.addView(text("离线语音模型", 16f, ink, true))
        modelPanel.addView(text("首次下载后保存在本机，无需上传录音。", 12f, muted, false).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        modelBase = modelChoice("BASE · 准确") { selectedModel = ModelCatalog.BASE; updateModelStatus() }
        modelTiny = modelChoice("TINY · 快速") { selectedModel = ModelCatalog.TINY; updateModelStatus() }
        modelRow.addView(modelBase, LinearLayout.LayoutParams(0, dp(45), 1f).apply { marginEnd = dp(8) })
        modelRow.addView(modelTiny, LinearLayout.LayoutParams(0, dp(45), 1f))
        modelPanel.addView(modelRow)
        modelStatus = text("", 12f, muted, false).apply {
            setPadding(0, dp(10), 0, dp(4))
        }
        modelPanel.addView(modelStatus)
        modelPanel.addView(button("下载所选模型", false) {
            val f = ModelCatalog.file(this, selectedModel)
            if (f.isFile && f.length() > 10_000_000L) toast("模型已下载")
            else startTask(MediaTaskService.OP_DOWNLOAD, null, selectedModel)
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) } })
        panel.addView(modelPanel)

        runButton = button("", true) { runCurrent() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(22) }
        }
        panel.addView(runButton)
        content.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val progressPanel = column().apply {
            background = bordered(white, line, 19)
            setPadding(dp(20), dp(20), dp(20), dp(18))
        }
        val progressHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progressHeader.addView(text("任务状态", 17f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        progressHeader.addView(text("PROCESS", 10f, muted, true, tracking = 0.11f))
        progressPanel.addView(progressHeader)
        taskStatus = text("准备就绪", 14f, muted, false).apply {
            setPadding(0, dp(12), 0, dp(13))
        }
        progressPanel.addView(taskStatus)
        taskProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(ink)
            progressBackgroundTintList = ColorStateList.valueOf(line)
            indeterminateTintList = ColorStateList.valueOf(ink)
        }
        progressPanel.addView(taskProgress, LinearLayout.LayoutParams(-1, dp(5)))
        val utility = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        utility.addView(button("取消任务", false) {
            try { startService(Intent(this, MediaTaskService::class.java).setAction(MediaTaskService.OP_CANCEL)) }
            catch (t: Throwable) { toast("取消失败：" + (t.message ?: "未知错误")) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        utility.addView(button("复制诊断", false) { copyDiagnostic() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        progressPanel.addView(utility)
        content.addView(progressPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(15) })

        content.addView(text("PRIVATE BY DEFAULT", 11f, ink, true, tracking = 0.16f).apply {
            setPadding(0, dp(25), 0, dp(6))
        })
        content.addView(text("所有音频处理均在本机进行。\nMP3 保存至 Music/MediaScribe；文字保存至 Download/MediaScribe。", 12f, muted, false).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        selectTab(activeTab)
        updateModelStatus()
        val last = getSharedPreferences("task_state", Context.MODE_PRIVATE).getString("last_message", null)
        if (last != null) taskStatus.text = last
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, IntentFilter(MediaTaskService.ACTION_UPDATE), Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, IntentFilter(MediaTaskService.ACTION_UPDATE))
        }
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("video", selectedVideo?.toString())
        outState.putString("audio", selectedAudio?.toString())
        outState.putString("model", selectedModel)
        outState.putInt("tab", activeTab)
        super.onSaveInstanceState(outState)
    }

    private fun selectTab(tab: Int) {
        activeTab = tab
        val isVideo = tab == 0
        for ((view, active) in listOf(tabVideo to isVideo, tabText to !isVideo)) {
            view.setTextColor(if (active) white else muted)
            view.background = rounded(if (active) ink else white, 11)
        }
        actionTitle.text = if (isVideo) "提取音频" else "转写文字"
        actionDetail.text = if (isVideo)
            "从视频中提取 MP3 音频 · 192 kbps\n支持长视频，无需联网。"
        else "MP3 / M4A / WAV 等格式 · 自动切片\n生成纯文字 TXT 和时间轴字幕 SRT。"
        val uri = if (isVideo) selectedVideo else selectedAudio
        chosenFile.text = if (uri == null) "尚未选择文件" else displayName(uri)
        modelPanel.visibility = if (isVideo) View.GONE else View.VISIBLE
        runButton.text = if (isVideo) "开始提取 MP3  →" else "开始识别文字  →"
    }

    private fun updateModelStatus() {
        if (!::modelStatus.isInitialized) return
        val base = selectedModel == ModelCatalog.BASE
        for ((view, selected) in listOf(modelBase to base, modelTiny to !base)) {
            view.setTextColor(if (selected) white else ink)
            view.background = bordered(if (selected) ink else white, if (selected) ink else line, 10)
        }
        val file = ModelCatalog.file(this, selectedModel)
        modelStatus.text = if (file.isFile && file.length() > 10_000_000L)
            "✓ 已就绪 · " + ModelCatalog.display(selectedModel)
        else "未下载 · " + ModelCatalog.display(selectedModel)
    }

    private fun runCurrent() {
        val isVideo = activeTab == 0
        val uri = (if (isVideo) selectedVideo else selectedAudio)
            ?: return toast("请先选择文件")
        if (!isVideo) {
            val file = ModelCatalog.file(this, selectedModel)
            if (!file.isFile || file.length() <= 10_000_000L) return toast("请先下载语音模型")
        }
        startTask(if (isVideo) MediaTaskService.OP_EXTRACT else MediaTaskService.OP_TRANSCRIBE,
            uri, if (isVideo) null else selectedModel)
    }

    private fun startTask(action: String, uri: Uri?, model: String?) {
        val intent = Intent(this, MediaTaskService::class.java)
            .setAction(action)
            .putExtra(MediaTaskService.EXTRA_URI, uri?.toString())
            .putExtra(MediaTaskService.EXTRA_MODEL, model)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            taskStatus.text = "任务已提交，正在启动处理进程"
            taskProgress.isIndeterminate = true
        } catch (t: Throwable) {
            taskStatus.text = "启动失败：" + (t.message ?: t.javaClass.simpleName)
            taskProgress.isIndeterminate = false
        }
    }

    private fun openPicker() {
        val isVideo = activeTab == 0
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (isVideo) "video/*" else "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, if (isVideo) 101 else 102)
    }

    @Deprecated("Legacy document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode != 101 && requestCode != 102) || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: Exception) {}
        if (requestCode == 101) selectedVideo = uri else selectedAudio = uri
        selectTab(if (requestCode == 101) 0 else 1)
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else "已选择文件" } ?: "已选择文件"
    } catch (_: Exception) { "已选择文件" }

    private fun copyDiagnostic() {
        val prefs = getSharedPreferences("task_state", Context.MODE_PRIVATE)
        val report = StringBuilder()
        report.append("MediaScribe 1.1.0 diagnostic\n")
            .append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append("\n")
            .append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE).append(" API ").append(Build.VERSION.SDK_INT).append("\n")
            .append("Last task: ").append(prefs.getString("last_action", "none")).append("\n")
            .append("Last message: ").append(prefs.getString("last_message", "none")).append("\n")
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = getSystemService(ActivityManager::class.java)
                val exits = am.getHistoricalProcessExitReasons(packageName, 0, 12)
                for (info in exits.filter { it.processName.endsWith(":media_worker") }.take(4)) {
                    report.append("Worker exit: reason=").append(info.reason)
                        .append(" status=").append(info.status)
                        .append(" time=").append(info.timestamp).append("\n")
                    try {
                        info.traceInputStream?.bufferedReader()?.use {
                            report.append(it.readText().take(3000)).append("\n")
                        }
                    } catch (_: Exception) {}
                }
            } catch (t: Throwable) {
                report.append("Exit info: ").append(t.javaClass.simpleName).append("\n")
            }
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("MediaScribe diagnostic", report.toString()))
        toast("诊断信息已复制，可粘贴给我分析")
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun tab(label: String, onTap: () -> Unit) =
        text(label, 14f, ink, true, gravity = Gravity.CENTER).apply { setOnClickListener { onTap() } }

    private fun modelChoice(label: String, onTap: () -> Unit) =
        text(label, 13f, ink, true, gravity = Gravity.CENTER).apply { setOnClickListener { onTap() } }

    private fun button(label: String, primary: Boolean, onTap: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            elevation = 0f
            setTextColor(if (primary) white else ink)
            background = bordered(if (primary) ink else white, if (primary) ink else line, 11)
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
        }

    private fun text(label: String, size: Float, color: Int, bold: Boolean,
                     tracking: Float = 0f, gravity: Int = Gravity.START): TextView =
        TextView(this).apply {
            text = label
            textSize = size
            letterSpacing = tracking
            setTextColor(color)
            this.gravity = gravity
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            includeFontPadding = false
        }

    private fun rounded(bg: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(radius).toFloat()
        }

    private fun bordered(bg: Int, stroke: Int, radius: Int): GradientDrawable =
        rounded(bg, radius).apply { setStroke(dp(1), stroke) }

    private fun dashed(): GradientDrawable =
        rounded(canvas, 12).apply { setStroke(dp(1), line, dp(5).toFloat(), dp(4).toFloat()) }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value + 0.5f).toInt()
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
