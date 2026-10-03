package com.zhuchenyu.healthchatbridge

import java.net.URL
import javax.net.ssl.HttpsURLConnection

class ApiClient {
    fun upload(serverUrl: String, token: String, json: String): String {
        require(serverUrl.startsWith("https://")) { "服务器地址必须使用 HTTPS" }
        require(token.isNotBlank()) { "同步令牌为空" }

        val connection = URL("${serverUrl.trimEnd('/')}/api/v1/ingest").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "服务器返回 HTTP $code：$body" }
            return body
        } finally {
            connection.disconnect()
        }
    }
}
