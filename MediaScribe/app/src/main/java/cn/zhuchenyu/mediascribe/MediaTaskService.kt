package cn.zhuchenyu.mediascribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaTaskService : Service() {
    companion object {
        const val OP_EXTRACT = "cn.zhuchenyu.mediascribe.EXTRACT"
        const val OP_TRANSCRIBE = "cn.zhuchenyu.mediascribe.TRANSCRIBE"
        const val OP_DOWNLOAD = "cn.zhuchenyu.mediascribe.DOWNLOAD"
        const val OP_CANCEL = "cn.zhuchenyu.mediascribe.CANCEL"
        const val ACTION_UPDATE = "cn.zhuchenyu.mediascribe.UPDATE"
        const val EXTRA_URI = "media_uri"
        const val EXTRA_MODEL = "model"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_DONE = "done"
        const val EXTRA_OUTPUT_URI = "output_uri"
        const val EXTRA_OUTPUT_TYPE = "output_type"
        private const val CHANNEL_ID = "media_tasks"
        private const val NOTIFY_ID = 67
    }

    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastPercent = -2

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "音频处理任务", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        if (action == OP_CANCEL) {
            job?.cancel()
            try { FFmpegKit.cancel() } catch (t: Throwable) { Log.w("MediaScribe", "Cancel failed", t) }
            publish("取消请求已发送，正在结束当前片段", -1)
            return START_NOT_STICKY
        }
        if (job?.isActive == true) {
            publish("当前已有任务，请先完成或取消", -1)
            return START_NOT_STICKY
        }
        lastPercent = -2
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(NOTIFY_ID, notification("准备处理文件"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else {
                startForeground(NOTIFY_ID, notification("准备处理文件"))
            }
        } catch (t: Throwable) {
            Log.e("MediaScribe", "Unable to start media worker", t)
            val message = "处理服务启动失败：" + (t.message ?: t.javaClass.simpleName)
            getSharedPreferences("task_state", MODE_PRIVATE).edit()
                .putString("last_message", message).putString("last_action", action).commit()
            sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, message).putExtra(EXTRA_PROGRESS, 0).putExtra(EXTRA_DONE, true))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        getSharedPreferences("task_state", MODE_PRIVATE).edit()
            .putString("last_action", action).commit()
        val uriString = intent.getStringExtra(EXTRA_URI)
        val model = intent.getStringExtra(EXTRA_MODEL) ?: ModelCatalog.BASE
        job = worker.launch {
            try {
                when (action) {
                    OP_EXTRACT -> extract(requireUri(uriString))
                    OP_TRANSCRIBE -> transcribe(requireUri(uriString), model)
                    OP_DOWNLOAD -> downloadModel(model)
                    else -> throw IllegalArgumentException("不支持的任务类型")
                }
            } catch (_: CancellationException) {
                publish("任务已取消", 0, done = true)
            } catch (t: Throwable) {
                Log.e("MediaScribe", "Task failed", t)
                publish("处理失败：" + t.javaClass.simpleName + " · " +
                    (t.message ?: "底层处理错误").take(200), 0, done = true)
            } finally {
                try {
                    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
                    else @Suppress("DEPRECATION") stopForeground(true)
                } catch (t: Throwable) { Log.w("MediaScribe", "stopForeground failed", t) }
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun requireUri(source: String?): Uri =
        if (source.isNullOrBlank()) throw IOException("未选择文件") else Uri.parse(source)

    private fun extract(uri: Uri) {
        val temp = File.createTempFile("extract_", ".mp3", cacheDir)
        try {
            publish("正在提取音频，转换为 192 kbps MP3", 0)
            val input = FFmpegKitConfig.getSafParameterForRead(this, uri)
            val durationMs = duration(uri)
            FFmpegKitConfig.enableStatisticsCallback { stats ->
                if (durationMs > 0) {
                    val percent = (stats.time.toDouble() * 100.0 / durationMs).toInt().coerceIn(0, 95)
                    if (percent != lastPercent) publish("正在提取 MP3", percent)
                }
            }
            val arguments = arrayOf(
                "-hide_banner", "-nostdin", "-y", "-i", input,
                "-map", "0:a:0", "-vn", "-c:a", "libmp3lame",
                "-b:a", "192k",
                temp.absolutePath
            )
            runFFmpeg(arguments)
            if (!temp.isFile || temp.length() < 1000) throw IOException("未生成有效的 MP3")
            publish("正在保存 MP3 到音乐目录", 96)
            val name = "Audio_${stamp()}.mp3"
            val saved = saveToMediaStore(temp, name, "audio/mpeg", false)
            publish("完成：Music/MediaScribe/$name", 100, done = true,
                outputUri = saved.toString(), outputType = "mp3")
        } finally {
            FFmpegKitConfig.enableStatisticsCallback(null)
            temp.delete()
        }
    }

    private suspend fun transcribe(uri: Uri, modelName: String) {
        val modelFile = ModelCatalog.file(this, modelName)
        if (!modelFile.isFile || modelFile.length() < 10_000_000) throw IOException("语音模型未下载")
        val dir = File(cacheDir, "scribe_${System.currentTimeMillis()}")
        if (!dir.mkdirs()) throw IOException("无法建立临时目录")
        try {
            publish("正在将音频切分为每段约 60 秒", 0)
            val input = FFmpegKitConfig.getSafParameterForRead(this, uri)
            val durationMs = duration(uri)
            FFmpegKitConfig.enableStatisticsCallback { stats ->
                if (durationMs > 0) {
                    val percent = (stats.time.toDouble() * 25.0 / durationMs).toInt().coerceIn(0, 24)
                    if (percent != lastPercent) publish("正在切分长音频", percent)
                }
            }
            runFFmpeg(arrayOf(
                "-hide_banner", "-nostdin", "-y", "-i", input,
                "-map", "0:a:0", "-vn",
                "-af", "aformat=sample_rates=16000:channel_layouts=mono",
                "-c:a", "pcm_s16le", "-f", "segment", "-segment_time", "60",
                "-segment_format", "wav", "-reset_timestamps", "1",
                File(dir, "chunk_%04d.wav").absolutePath
            ))
            FFmpegKitConfig.enableStatisticsCallback(null)
            val chunks = dir.listFiles()?.filter { it.extension == "wav" }?.sortedBy { it.name }.orEmpty()
            if (chunks.isEmpty()) throw IOException("音频切分失败；请确认所选文件含有声音")
            publish("正在载入本地 Whisper 模型", 25)
            currentCoroutineContext().ensureActive()

            val text = StringBuilder()
            val subtitles = StringBuilder()
            var subtitleNumber = 1
            val whisperModel = Whisper.loadModel(this, modelFile.absolutePath)
            try {
                chunks.forEachIndexed { index, chunk ->
                    currentCoroutineContext().ensureActive()
                    publish("正在识别第 ${index + 1}/${chunks.size} 段", 25 + index * 70 / chunks.size)
                    val result = Whisper.transcribe(whisperModel, chunk.absolutePath, WhisperConfig(language = "zh"))
                    val recognized = result.text.trim()
                    if (recognized.isNotEmpty()) text.append(recognized).append("\n")
                    val offset = index * 60_000L
                    for (segment in result.segments) {
                        val content = segment.text.trim()
                        if (content.isEmpty()) continue
                        subtitles.append(subtitleNumber++).append("\n")
                            .append(srtTime(offset + segment.startMs))
                            .append(" --> ")
                            .append(srtTime(offset + segment.endMs))
                            .append("\n")
                            .append(content).append("\n\n")
                    }
                    chunk.delete()
                }
            } finally {
                Whisper.releaseModel(whisperModel)
            }
            if (text.isBlank()) throw IOException("未检测到可识别的语音")
            publish("正在保存文字与字幕", 96)
            val name = "Transcript_${stamp()}"
            val txtUri = saveBytes(text.toString().toByteArray(Charsets.UTF_8), "$name.txt", "text/plain")
            saveBytes(subtitles.toString().toByteArray(Charsets.UTF_8), "$name.srt", "application/x-subrip")
            publish("完成：Download/MediaScribe/$name.txt 和 .srt", 100, done = true,
                outputUri = txtUri.toString(), outputType = "txt")
        } finally {
            FFmpegKitConfig.enableStatisticsCallback(null)
            dir.deleteRecursively()
        }
    }

    private suspend fun downloadModel(modelName: String) {
        val destination = ModelCatalog.file(this, modelName)
        destination.parentFile?.mkdirs()
        val temp = File(destination.absolutePath + ".part")
        try {
            publish("正在下载 ${ModelCatalog.display(modelName)}", 0)
            val connection = URL(ModelCatalog.url(modelName)).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "MediaScribe/1.0")
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) throw IOException("下载服务返回 HTTP ${connection.responseCode}")
                val length = connection.contentLengthLong
                var received = 0L
                var prevPercent = -1
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            received += read
                            val percent = if (length > 0) (received * 100 / length).toInt().coerceIn(0, 99) else -1
                            if (percent != prevPercent) {
                                publish("下载模型：${received / (1024 * 1024)} MB / ${if (length > 0) "${length / (1024 * 1024)} MB" else "未知大小"}", percent)
                                prevPercent = percent
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (received < 10_000_000 || (length > 0 && received != length)) throw IOException("模型下载不完整")
                if (destination.exists()) destination.delete()
                if (!temp.renameTo(destination)) throw IOException("模型保存失败")
                publish("已下载 ${ModelCatalog.display(modelName)}，可离线使用", 100, done = true)
            } finally {
                connection.disconnect()
            }
        } finally {
            temp.delete()
        }
    }

    private fun runFFmpeg(args: Array<String>) {
        val session = FFmpegKit.executeWithArguments(args)
        currentJobCheck()
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val problem = session.output?.takeLast(550) ?: session.failStackTrace ?: "未知 FFmpeg 错误"
            throw IOException(problem.replace("\n", " ").take(350))
        }
    }

    private fun currentJobCheck() {
        if (job?.isCancelled == true) throw CancellationException("已取消")
    }

    private fun duration(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun saveBytes(bytes: ByteArray, name: String, mime: String): Uri {
        val temp = File.createTempFile("output_", ".tmp", cacheDir)
        try {
            FileOutputStream(temp).use { it.write(bytes) }
            return saveToMediaStore(temp, name, mime, true)
        } finally { temp.delete() }
    }

    private fun saveToMediaStore(source: File, name: String, mime: String, downloads: Boolean): Uri {
        if (Build.VERSION.SDK_INT < 29) {
            val folder = File(getExternalFilesDir(null) ?: filesDir, "MediaScribe")
            folder.mkdirs()
            val target = File(folder, name)
            FileInputStream(source).use { src -> FileOutputStream(target).use { src.copyTo(it) } }
            return Uri.fromFile(target)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (downloads) "Download/MediaScribe/" else "Music/MediaScribe/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (downloads) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val destination = contentResolver.insert(collection, values) ?: throw IOException("手机储存空间不可写")
        try {
            val output = contentResolver.openOutputStream(destination) ?: throw IOException("无法写入输出文件")
            FileInputStream(source).use { input -> output.use { input.copyTo(it) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(destination, done, null, null)
            return destination
        } catch (e: Exception) {
            contentResolver.delete(destination, null, null)
            throw e
        }
    }

    private fun publish(message: String, percent: Int, done: Boolean = false,
                        outputUri: String? = null, outputType: String? = null) {
        lastPercent = percent
        getSharedPreferences("task_state", MODE_PRIVATE).edit()
            .putString("last_message", message)
            .commit()
        val update = Intent(ACTION_UPDATE).setPackage(packageName)
            .putExtra(EXTRA_MESSAGE, message)
            .putExtra(EXTRA_PROGRESS, percent)
            .putExtra(EXTRA_DONE, done)
            .putExtra(EXTRA_OUTPUT_URI, outputUri)
            .putExtra(EXTRA_OUTPUT_TYPE, outputType)
        sendBroadcast(update)
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification(message))
        } catch (t: Throwable) {
            Log.w("MediaScribe", "Notification failed", t)
        }
    }

    private fun notification(content: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("声文工坊")
            .setContentText(content.take(70))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun srtTime(timeMs: Long): String {
        val ms = timeMs.coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d,%03d",
            ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }
}
