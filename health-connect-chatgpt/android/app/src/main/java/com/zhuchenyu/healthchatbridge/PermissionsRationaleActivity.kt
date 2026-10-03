package com.zhuchenyu.healthchatbridge

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            textSize = 18f
            setPadding(48, 48, 48, 48)
            text = "本应用只读取你主动授权的 Health Connect 健康数据，并按北京时间汇总后发送到你配置的私有服务器。应用不写入、修改或删除 Health Connect 数据。"
        })
    }
}
