package com.promenar.nexara.data.script

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 隔离进程内合并脚本的运行中输出，由调用方定时 [flush] 成一条 [ScriptWire.OUTPUT] 消息。
 * 每个窗口只保留尾部 [windowChars] 个字符：输出过快时丢弃的只是实时展示，最终结果另行收集。
 */
internal class ScriptOutputForwarder(
    private val windowChars: Int = WINDOW_CHARS,
    private val send: (String) -> Unit,
) {
    private val pieces = ArrayDeque<Pair<Int, String>>()
    private var chars = 0

    @Synchronized
    fun add(fd: Int, text: String) {
        if (text.isEmpty()) return
        val kept = tail(text, windowChars)
        // 相邻同一路输出合并成一段，避免大量短片段的 JSON 开销撑大单条消息。
        val last = pieces.lastOrNull()
        if (last != null && last.first == fd) pieces[pieces.lastIndex] = fd to last.second + kept
        else pieces.addLast(fd to kept)
        chars += kept.length
        while ((chars > windowChars || pieces.size > MAX_PIECES) && pieces.size > 1) chars -= pieces.removeFirst().second.length
        if (chars > windowChars) {
            val only = pieces.removeFirst()
            val trimmed = tail(only.second, windowChars)
            pieces.addLast(only.first to trimmed)
            chars = trimmed.length
        }
    }

    fun flush() {
        val batch = synchronized(this) {
            if (pieces.isEmpty()) return
            pieces.toList().also { pieces.clear(); chars = 0 }
        }
        send(JsonArray(batch.map { (fd, text) -> buildJsonObject { put("fd", JsonPrimitive(fd)); put("d", JsonPrimitive(text)) } }).toString())
    }

    /** 取尾部最多 [limit] 个字符，不从代理对中间切开。 */
    private fun tail(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var start = text.length - limit
        if (Character.isLowSurrogate(text[start])) start++
        return text.substring(start)
    }

    companion object {
        const val INTERVAL_MILLIS = 100L
        /** stdout 与 stderr 交替输出时的片段上限。 */
        const val MAX_PIECES = 256
        /** 远低于 Binder 消息上限，转义后仍有余量。 */
        const val WINDOW_CHARS = 16 * 1024
    }
}
