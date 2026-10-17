package com.phonesurvey.core

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

data class NtripConfig(
    val host: String,
    val port: Int = 2101,
    val mountpoint: String,
    val username: String = "",
    val password: String = "",
)

data class NtripMountpoint(
    val name: String,
    val identifier: String,
    val format: String,
    val latitude: Double?,
    val longitude: Double?,
    val requiresGga: Boolean,
)

sealed interface NtripStatus {
    data object Connecting : NtripStatus
    data class Streaming(val bytesReceived: Long) : NtripStatus
    data class Error(val message: String) : NtripStatus
    data object Stopped : NtripStatus
}

class NtripException(message: String) : IOException(message)

/**
 * Minimal NTRIP client. Requests the stream in NTRIP 1.0 style, which every caster
 * (including the national VRS service) answers with a plain "ICY 200 OK" byte stream,
 * and also understands NTRIP 2.0 chunked replies.
 */
class NtripClient(
    private val config: NtripConfig,
    private val ggaProvider: () -> String?,
    private val onRtcm: (ByteArray, Int) -> Unit,
    private val onStatus: (NtripStatus) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null

    /** Blocks, reconnecting after errors until [stop] is called. */
    fun run() {
        running = true
        var backoffMs = 2000L
        while (running) {
            try {
                onStatus(NtripStatus.Connecting)
                streamOnce()
                backoffMs = 2000L
            } catch (e: NtripException) {
                onStatus(NtripStatus.Error(e.message ?: "NTRIP 오류"))
                // Authentication and mountpoint errors won't fix themselves quickly.
                backoffMs = 30_000L
            } catch (e: IOException) {
                if (running) onStatus(NtripStatus.Error(e.message ?: "연결 끊김"))
            }
            if (!running) break
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        onStatus(NtripStatus.Stopped)
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: IOException) {}
    }

    private fun streamOnce() {
        Socket().use { s ->
            socket = s
            s.connect(InetSocketAddress(config.host, config.port), 10_000)
            s.soTimeout = 20_000
            val out = s.getOutputStream()
            out.write(buildRequest(config, ggaProvider()).toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val input = BufferedInputStream(s.getInputStream())
            val status = readLine(input) ?: throw IOException("응답 없음")
            val body: InputStream = when {
                status.startsWith("ICY 200") -> input
                status.startsWith("HTTP/1.") && status.contains(" 200") -> {
                    val headers = readHeaders(input)
                    if (headers["transfer-encoding"]?.contains("chunked", true) == true) ChunkedInputStream(input) else input
                }
                status.startsWith("SOURCETABLE") -> throw NtripException("마운트포인트를 찾을 수 없습니다: ${config.mountpoint}")
                status.contains(" 401") -> throw NtripException("아이디 또는 비밀번호가 틀렸습니다")
                else -> throw NtripException("캐스터 응답: $status")
            }
            val buf = ByteArray(4096)
            var total = 0L
            var lastGga = 0L
            onStatus(NtripStatus.Streaming(0))
            while (running) {
                // VRS casters send nothing until they know where we are, so report position first.
                val now = System.currentTimeMillis()
                if (now - lastGga > 10_000) {
                    ggaProvider()?.let { out.write((it + "\r\n").toByteArray(Charsets.US_ASCII)); out.flush() }
                    lastGga = now
                }
                val n = body.read(buf)
                if (n < 0) throw IOException("캐스터가 연결을 닫았습니다")
                if (n > 0) {
                    onRtcm(buf, n)
                    total += n
                    onStatus(NtripStatus.Streaming(total))
                }
            }
        }
    }

    companion object {
        fun buildRequest(config: NtripConfig, gga: String?): String {
            val sb = StringBuilder()
            sb.append("GET /").append(config.mountpoint).append(" HTTP/1.0\r\n")
            sb.append("User-Agent: NTRIP PhoneSurvey/1.0\r\n")
            sb.append("Accept: */*\r\n")
            if (config.username.isNotEmpty()) {
                val token = Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))
                sb.append("Authorization: Basic ").append(token).append("\r\n")
            }
            if (gga != null) sb.append("Ntrip-GGA: ").append(gga).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            return sb.toString()
        }

        /** Downloads the caster's source table (list of mountpoints). */
        fun fetchSourceTable(host: String, port: Int, username: String = "", password: String = ""): List<NtripMountpoint> {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 10_000)
                s.soTimeout = 15_000
                s.getOutputStream().write(
                    buildRequest(NtripConfig(host, port, "", username, password), null).toByteArray(Charsets.ISO_8859_1),
                )
                val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                return parseSourceTable(text)
            }
        }

        fun parseSourceTable(text: String): List<NtripMountpoint> = text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("STR;") }
            .map { line ->
                val f = line.split(';')
                NtripMountpoint(
                    name = f.getOrElse(1) { "" },
                    identifier = f.getOrElse(2) { "" },
                    format = f.getOrElse(3) { "" },
                    latitude = f.getOrNull(9)?.toDoubleOrNull(),
                    longitude = f.getOrNull(10)?.toDoubleOrNull(),
                    requiresGga = f.getOrNull(11) == "1",
                )
            }
            .toList()

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
                if (sb.length > 1024) throw IOException("잘못된 응답")
            }
        }

        private fun readHeaders(input: InputStream): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            return headers
        }
    }

    /** Decodes HTTP chunked transfer encoding (NTRIP 2.0). */
    private class ChunkedInputStream(private val input: InputStream) : InputStream() {
        private var remaining = 0

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining == 0) {
                var line = readLine(input) ?: return -1
                if (line.isEmpty()) line = readLine(input) ?: return -1
                remaining = line.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("잘못된 chunk")
                if (remaining == 0) return -1
            }
            val n = input.read(b, off, minOf(len, remaining))
            if (n > 0) remaining -= n
            return n
        }
    }
}
