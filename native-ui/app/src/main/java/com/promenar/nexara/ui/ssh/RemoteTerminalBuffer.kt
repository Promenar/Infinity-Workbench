package com.promenar.nexara.ui.ssh

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** 仅桥接远端 PTY；不创建 Termux 本地会话或本地进程。 */
internal class RemoteTerminalBuffer(columns: Int = 80, rows: Int = 24) {
    var onInput: (String) -> Unit = {}
    var onChanged: (() -> Unit)? = null

    val emulator = TerminalEmulator(
        object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) {
                onInput(String(data, offset, count, Charsets.UTF_8))
            }

            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit

            // 远端 OSC 52 不获得系统剪贴板权限。
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() { onChanged?.invoke() }
        },
        columns,
        rows,
        1_500,
        SilentTerminalClient,
    )

    fun append(output: String) {
        val bytes = output.toByteArray(Charsets.UTF_8)
        try {
            emulator.append(bytes, bytes.size)
            onChanged?.invoke()
            emulator.clearScrollCounter()
        } finally {
            bytes.fill(0)
        }
    }

    fun clear() {
        onInput = {}
        onChanged = null
        emulator.reset()
        emulator.doDecSetOrReset(true, 1049)
        clearScreen()
        emulator.doDecSetOrReset(false, 1049)
        clearScreen()
        emulator.screen.clearTranscript()
    }

    private fun clearScreen() {
        emulator.screen.blockSet(0, 0, emulator.mColumns, emulator.mRows, ' '.code, emulator.screen.getStyleAt(0, 0))
    }
}

/** 库的诊断回调可能包含终端文本，因此全部静默。 */
private object SilentTerminalClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession?) = Unit
    override fun onTitleChanged(changedSession: TerminalSession?) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession?) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession?) = Unit
    override fun onColorsChanged(session: TerminalSession?) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) = Unit
    override fun logStackTrace(tag: String?, error: Exception?) = Unit
}

/** 缓冲仅驻留进程内；保留会话重进时恢复画面，最多保留四份。 */
internal object RetainedTerminalBuffers {
    private data class Entry(val buffer: RemoteTerminalBuffer, val retainedAt: Long)
    private val entries = LinkedHashMap<String, Entry>()

    fun take(id: String): RemoteTerminalBuffer? {
        prune()
        return entries.remove(id)?.buffer
    }

    fun retain(id: String, buffer: RemoteTerminalBuffer) {
        prune()
        entries.remove(id)?.buffer?.takeUnless { it === buffer }?.clear()
        entries[id] = Entry(buffer, android.os.SystemClock.elapsedRealtime())
        while (entries.size > 4) {
            val first = entries.entries.first()
            entries.remove(first.key)
            first.value.buffer.clear()
        }
    }

    private fun prune() {
        val now = android.os.SystemClock.elapsedRealtime()
        val expired = entries.filterValues { now - it.retainedAt >= 10 * 60 * 1_000L }.keys.toList()
        expired.forEach { entries.remove(it)?.buffer?.clear() }
    }
}
