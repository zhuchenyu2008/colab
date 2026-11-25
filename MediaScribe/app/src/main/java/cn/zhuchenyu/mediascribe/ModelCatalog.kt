package cn.zhuchenyu.mediascribe

import android.content.Context
import java.io.File

object ModelCatalog {
    const val TINY = "tiny"
    const val BASE = "base"

    fun file(context: Context, model: String): File {
        val name = if (model == TINY) "ggml-tiny.bin" else "ggml-base.bin"
        return File(File(context.filesDir, "models"), name)
    }

    fun url(model: String) =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" +
            (if (model == TINY) "ggml-tiny.bin" else "ggml-base.bin") + "?download=true"

    fun display(model: String) = if (model == TINY) "轻量模型 Tiny（约 75 MB）" else "标准模型 Base（约 142 MB）"
}
