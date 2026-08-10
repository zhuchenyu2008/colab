package com.zhuchenyu.oppowatchprobe

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class ProbeReportServer(
    private val port: Int = 8787,
    private val reportProvider: () -> String
) {
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    fun start(): String? {
        if (running.get()) return localUrl()
        return try {
            val socket = ServerSocket(port)
            socket.reuseAddress = true
            serverSocket = socket
            running.set(true)
            thread = Thread({ serveLoop(socket) }, "watch-probe-http").apply {
                isDaemon = true
                start()
            }
            localUrl()
        } catch (_: Throwable) {
            running.set(false)
            null
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        thread = null
    }

    fun localUrl(): String? = localIpv4()?.let { "http://$it:$port/" }

    private fun serveLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                client.soTimeout = 3000
                handle(client)
            } catch (_: SocketException) {
                break
            } catch (_: Throwable) {
            }
        }
    }

    private fun handle(client: java.net.Socket) {
        client.use { socket ->
            runCatching {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val body = reportProvider().toByteArray(Charsets.UTF_8)
                val out = socket.getOutputStream()
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/plain; charset=utf-8\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)
                out.write(header)
                out.write(body)
                out.flush()
            }
        }
    }

    private fun localIpv4(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val candidates = interfaces
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }

            candidates.firstOrNull { it.isSiteLocalAddress }?.hostAddress
                ?: candidates.firstOrNull()?.hostAddress
        } catch (_: Throwable) {
            null
        }
    }
}
