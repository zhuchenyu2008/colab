package cn.zhuchenyu.mediascribe

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private var selectedVideo: Uri? = null
    private var selectedAudio: Uri? = null
    private var pickerKind = ""
    private var selectedModel = ModelCatalog.BASE

    private lateinit var videoName: TextView
    private lateinit var audioName: TextView
    private lateinit var modelStatus: TextView
    private lateinit var taskStatus: TextView
    private lateinit var taskProgress: ProgressBar

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(MediaTaskService.EXTRA_MESSAGE) ?: return
            taskStatus.text = message
            val pct = intent.getIntExtra(MediaTaskService.EXTRA_PROGRESS, -1)
            if (pct >= 0) {
                taskProgress.isIndeterminate = false
                taskProgress.progress = pct.coerceIn(0, 100)
            } else {
                taskProgress.isIndeterminate = true
            }
            if (intent.getBooleanExtra(MediaTaskService.EXTRA_DONE, false)) {
                taskProgress.isIndeterminate = false
                taskProgress.progress = 100
                refreshModelStatus()
                val uri = intent.getStringExtra(MediaTaskService.EXTRA_OUTPUT_URI)
                if (uri != null && intent.getStringExtra(MediaTaskService.EXTRA_OUTPUT_TYPE) == "mp3") {
                    selectedAudio = Uri.parse(uri)
                    audioName.text = "已生成 MP3，可直接转文字"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        selectedVideo = savedInstanceState?.getString("video")?.let(Uri::parse)
        selectedAudio = savedInstanceState?.getString("audio")?.let(Uri::parse)
        selectedModel = savedInstanceState?.getString("model") ?: ModelCatalog.BASE

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 16, 32))
            clipToPadding = false
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(25), dp(20), dp(35))
        }
        scroll.addView(root)
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }

        root.addView(label("MEDIA SCRIBE  /  声文工坊", 13f, "#4CE0C5", true))
        root.addView(label("声音，整理成文字。", 29f, "#F5F7FF", true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        root.addView(label("视频提取 MP3 · 中文离线转写 · 长录音分段处理", 14f, "#A7B0C8", false).apply {
            setPadding(0, 0, 0, dp(22))
        })

        card(root, "01  视频 → MP3", "选择本地视频，提取真正的 MP3（16 kHz / 128 kbps 单声道）。") { group ->
            videoName = label(selectedVideo?.let { "已选择视频" } ?: "尚未选择视频", 14f, "#BAC5DC", false)
            group.addView(videoName)
            group.addView(button("选择视频文件") { openPicker("video") })
            group.addView(button("开始提取 MP3", false) {
                val uri = selectedVideo ?: return@button toast("请先选择视频")
                startTask(MediaTaskService.OP_EXTRACT, uri, null)
            })
        }

        card(root, "02  语音识别模型", "模型只下载一次；下载完成后，无需联网即可识别。中文建议 Base，速度优先可用 Tiny。") { group ->
            val spinner = Spinner(this)
            val options = listOf("标准 Base · 中文更准确 · 142 MB", "轻量 Tiny · 速度更快 · 75 MB")
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
            spinner.adapter = adapter
            spinner.setSelection(if (selectedModel == ModelCatalog.TINY) 1 else 0)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedModel = if (position == 1) ModelCatalog.TINY else ModelCatalog.BASE
                    refreshModelStatus()
                }
            }
            group.addView(spinner)
            modelStatus = label("", 14f, "#BAC5DC", false)
            group.addView(modelStatus)
            group.addView(button("下载所选模型") {
                if (ModelCatalog.file(this, selectedModel).isFile && ModelCatalog.file(this, selectedModel).length() > 10_000_000) {
                    return@button toast("模型已经下载完成")
                }
                startTask(MediaTaskService.OP_DOWNLOAD, null, selectedModel)
            })
        }

        card(root, "03  MP3 → 文字", "支持 MP3、M4A、WAV 等音频；自动切片，输出 TXT 和带时间轴的 SRT。") { group ->
            audioName = label(selectedAudio?.let { "已选择音频" } ?: "尚未选择音频", 14f, "#BAC5DC", false)
            group.addView(audioName)
            group.addView(button("选择音频文件") { openPicker("audio") })
            group.addView(button("开始转文字", false) {
                val uri = selectedAudio ?: return@button toast("请先选择音频")
                val model = ModelCatalog.file(this, selectedModel)
                if (!model.isFile || model.length() < 10_000_000) return@button toast("请先下载所选模型")
                startTask(MediaTaskService.OP_TRANSCRIBE, uri, selectedModel)
            })
        }

        card(root, "处理进度", "任务在前台服务运行；锁屏时可继续处理，请为手机保留足够电量和储存空间。") { group ->
            taskStatus = label("准备就绪", 14f, "#F5F7FF", false)
            group.addView(taskStatus)
            taskProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            group.addView(taskProgress, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(14) })
            group.addView(button("取消当前任务", false) {
                val intent = Intent(this, MediaTaskService::class.java).setAction(MediaTaskService.OP_CANCEL)
                startService(intent)
            })
        }

        root.addView(label("音频和识别结果保存在手机的 Music/MediaScribe 与 Download/MediaScribe 文件夹。", 12f, "#7C88A5", false).apply {
            setPadding(dp(3), dp(8), dp(3), 0)
        })
        refreshModelStatus()
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, IntentFilter(MediaTaskService.ACTION_UPDATE), Context.RECEIVER_NOT_EXPORTED)
        } else {
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
        super.onSaveInstanceState(outState)
    }

    private fun refreshModelStatus() {
        if (!::modelStatus.isInitialized) return
        val file = ModelCatalog.file(this, selectedModel)
        modelStatus.text = if (file.isFile && file.length() > 10_000_000) {
            "已就绪：${ModelCatalog.display(selectedModel)}"
        } else {
            "尚未下载：${ModelCatalog.display(selectedModel)}"
        }
    }

    private fun startTask(action: String, uri: Uri?, model: String?) {
        val intent = Intent(this, MediaTaskService::class.java)
            .setAction(action)
            .putExtra(MediaTaskService.EXTRA_URI, uri?.toString())
            .putExtra(MediaTaskService.EXTRA_MODEL, model)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            taskStatus.text = "任务已提交"
            taskProgress.isIndeterminate = true
        } catch (e: Exception) {
            toast("无法启动任务：${e.localizedMessage}")
        }
    }

    private fun openPicker(kind: String) {
        pickerKind = kind
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (kind == "video") "video/*" else "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 101)
    }

    @Deprecated("Legacy document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 101 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        if (pickerKind == "video") {
            selectedVideo = uri
            videoName.text = "视频已选择：${displayName(uri)}"
        } else {
            selectedAudio = uri
            audioName.text = "音频已选择：${displayName(uri)}"
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "已授权文件"
        } catch (_: Exception) { "已授权文件" }
    }

    private fun card(parent: LinearLayout, title: String, subtitle: String, content: (LinearLayout) -> Unit) {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = shape("#182138", 20)
        }
        group.addView(label(title, 19f, "#FFFFFF", true))
        group.addView(label(subtitle, 13f, "#9EAEC9", false).apply {
            setPadding(0, dp(8), 0, dp(15))
        })
        content(group)
        parent.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(15) })
    }

    private fun button(caption: String, primary: Boolean = true, handler: () -> Unit): Button {
        return Button(this).apply {
            text = caption
            isAllCaps = false
            textSize = 14f
            setTextColor(if (primary) Color.rgb(12, 29, 38) else Color.WHITE)
            background = shape(if (primary) "#4CE0C5" else "#293753", 12)
            setOnClickListener { handler() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) }
        }
    }

    private fun label(t: String, size: Float, color: String, bold: Boolean): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun shape(color: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value + 0.5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
