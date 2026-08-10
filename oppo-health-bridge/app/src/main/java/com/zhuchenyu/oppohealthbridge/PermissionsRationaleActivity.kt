package com.zhuchenyu.oppohealthbridge

import android.app.Activity
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val body = TextView(this).apply {
            textSize = 16f
            text = """
                OPPO Health Bridge 隐私说明

                本应用仅用于把你已授权的 OPPO 健康数据写入本机 Health Connect。

                处理的数据可能包括：心率、静息心率、血氧、步数、总消耗与睡眠。

                数据默认只在你的手机上的 OPPO 健康与 Health Connect 之间传输。本版本不会把健康数据上传到开发者服务器，也不会用于广告或出售。

                你可以随时在 OPPO 健康的“数据共享与授权”以及系统 Health Connect 的“应用权限”中撤销权限。
            """.trimIndent()
            setPadding(padding, padding, padding, padding)
            movementMethod = LinkMovementMethod.getInstance()
        }
        val root = ScrollView(this).apply {
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
    }
}
