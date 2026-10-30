package com.promenar.infinity.data.secret

import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import java.net.URLEncoder
import java.util.Base64

/** 所有替换在持久化或交给模型之前完成；任务输入只保留可清零的内存副本。 */
class SecretOutputRedactor(private val vault: SessionSecretVault) {
    private class Pattern(val value: String, val replacement: String) {
        fun matches(text: String, offset: Int): Boolean {
            if (text.startsWith(value, offset)) return true
            if (offset + value.length > text.length) return false
            var index = 0
            while (index < value.length) {
                if (value[index] == '%' && index + 2 < value.length && value[index + 1].isHex() && value[index + 2].isHex()) {
                    if (text[offset + index] != '%' || !value[index + 1].equals(text[offset + index + 1], ignoreCase = true) ||
                        !value[index + 2].equals(text[offset + index + 2], ignoreCase = true)) return false
                    index += 3
                } else {
                    if (text[offset + index] != value[index]) return false
                    index++
                }
            }
            return true
        }

        private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
    private val lock = Any()
    private val jobs = mutableMapOf<String, MutableMap<String, MutableList<ByteArray>>>()

    fun registerJobInput(sessionId: String, jobId: String, value: ByteArray) = synchronized(lock) {
        require(value.isNotEmpty()) { "SECRET_EMPTY" }
        require(sessionId.isNotBlank() && jobId.isNotBlank()) { "SECRET_JOB_INVALID" }
        jobs.getOrPut(sessionId, ::mutableMapOf).getOrPut(jobId, ::mutableListOf).add(value.copyOf())
        Unit
    }

    fun clearJob(sessionId: String, jobId: String) = synchronized(lock) {
        val session = jobs[sessionId] ?: return@synchronized
        session.remove(jobId)?.forEach { it.fill(0) }
        if (session.isEmpty()) jobs.remove(sessionId)
    }

    fun clearSession(sessionId: String) = synchronized(lock) {
        jobs.remove(sessionId)?.values?.flatten()?.forEach { it.fill(0) }
    }

    fun redact(sessionId: String, text: String): String {
        if (text.isEmpty()) return text
        val stream = stream(sessionId)
        return stream.accept(text) + stream.finish()
    }

    fun stream(sessionId: String): Stream = Stream(sessionId)

    /** 每个命令结束时调用 [finish] 刷出普通短尾；stdout、stderr 的缓冲互相隔离。 */
    fun sink(sessionId: String, delegate: ToolLiveOutputSink): ToolLiveOutputSink = RedactingSink(sessionId, delegate)

    fun finish(sink: ToolLiveOutputSink) { (sink as? RedactingSink)?.finish() }

    inner class Stream internal constructor(private val sessionId: String) {
        private val known = linkedMapOf<String, Pattern>()
        private var pending = ""
        private var finished = false

        init { refresh() }

        @Synchronized fun accept(chunk: String): String {
            check(!finished) { "SECRET_STREAM_CLOSED" }
            refresh()
            pending += chunk
            return drain(final = false)
        }

        @Synchronized fun finish(): String {
            if (finished) return ""
            refresh()
            finished = true
            return drain(final = true).also { known.clear() }
        }

        private fun refresh() {
            patterns(sessionId).forEach { known.putIfAbsent(it.value, it) }
        }

        private fun drain(final: Boolean): String {
            val patterns = known.values.sortedByDescending { it.value.length }
            val longest = patterns.firstOrNull()?.value?.length ?: 1
            val safe = if (final) pending.length else (pending.length - longest + 1).coerceAtLeast(0)
            val output = StringBuilder()
            var offset = 0
            while (offset < safe) {
                val match = patterns.firstOrNull { it.matches(pending, offset) }
                if (match == null) output.append(pending[offset++]) else {
                    output.append(match.replacement)
                    offset += match.value.length
                }
            }
            pending = pending.substring(offset)
            return output.toString()
        }
    }

    private inner class RedactingSink(sessionId: String, private val delegate: ToolLiveOutputSink) : ToolLiveOutputSink by delegate {
        private val streams = ToolOutputStream.entries.associateWith { stream(sessionId) }.toMutableMap()
        private val session = sessionId
        private var finished = false

        @Synchronized override fun emit(stream: ToolOutputStream, text: String) {
            check(!finished) { "SECRET_STREAM_CLOSED" }
            streams.getValue(stream).accept(text).takeIf { it.isNotEmpty() }?.let { delegate.emit(stream, it) }
        }

        @Synchronized override fun replace(stream: ToolOutputStream, text: String) {
            check(!finished) { "SECRET_STREAM_CLOSED" }
            // 快照不是增量；丢弃旧快照的短尾，避免覆盖后把旧内容追加到界面。
            streams.getValue(stream).finish()
            val replacement = this@SecretOutputRedactor.stream(session)
            streams[stream] = replacement
            delegate.replace(stream, replacement.accept(text))
        }

        @Synchronized fun finish() {
            if (finished) return
            finished = true
            streams.forEach { (stream, redactor) -> redactor.finish().takeIf { it.isNotEmpty() }?.let { delegate.emit(stream, it) } }
        }
    }

    private fun patterns(sessionId: String): List<Pattern> {
        val values = vault.redactionValues(sessionId).map { (name, value) -> "‹保密:$name›" to value }.toMutableList()
        synchronized(lock) {
            jobs[sessionId]?.values?.forEach { inputs -> inputs.forEach { values += "‹保密›" to it.copyOf() } }
        }
        return try {
            values.flatMap { (replacement, bytes) ->
                if (bytes.size < 4) emptyList() else variants(bytes).map { Pattern(it, replacement) }
            }.distinctBy { it.value }
        } finally {
            values.forEach { it.second.fill(0) }
        }
    }

    private fun variants(bytes: ByteArray): Set<String> = buildSet {
        val decoded = bytes.toString(Charsets.UTF_8)
        if (decoded.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            add(decoded)
            val encoded = URLEncoder.encode(decoded, "UTF-8")
            add(encoded)
            add(encoded.replace("+", "%20"))
            add(encoded.replace(Regex("%[0-9A-F]{2}")) { it.value.lowercase() })
        }
        add(Base64.getEncoder().encodeToString(bytes))
        add(Base64.getEncoder().withoutPadding().encodeToString(bytes))
        add(Base64.getUrlEncoder().encodeToString(bytes))
        add(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        add(buildString {
            bytes.forEach { byte ->
                val value = byte.toInt() and 255
                val char = value.toChar()
                if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") {
                    append(char)
                } else {
                    append('%')
                    append(HEX[value ushr 4].uppercaseChar())
                    append(HEX[value and 15].uppercaseChar())
                }
            }
        })
        val hex = bytes.joinToString("") { HEX[(it.toInt() and 255) ushr 4].toString() + HEX[it.toInt() and 15] }
        add(hex)
        add(hex.chunked(2).joinToString("") { "%$it" })
        add(hex.uppercase().chunked(2).joinToString("") { "%$it" })
    }

    private companion object { const val HEX = "0123456789abcdef" }
}
