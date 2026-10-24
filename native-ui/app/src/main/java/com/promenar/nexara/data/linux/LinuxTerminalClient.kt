package com.promenar.nexara.data.linux

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** 无界面时禁止剪贴板操作，也不把终端内容写入日志。 */
internal object LinuxTerminalSilentClient : TerminalSessionClient {
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

/** 内部生命周期回调始终存在，界面客户端可随 Activity 重建切换。诊断回调始终静默。 */
internal class LinuxTerminalClient(
    private val titleChanged: () -> Boolean,
    private val finished: () -> Unit,
) : TerminalSessionClient by LinuxTerminalSilentClient {
    @Volatile var delegate: TerminalSessionClient = LinuxTerminalSilentClient

    override fun onTextChanged(changedSession: TerminalSession?) { delegate.onTextChanged(changedSession) }
    override fun onTitleChanged(changedSession: TerminalSession?) {
        if (titleChanged()) delegate.onTitleChanged(changedSession)
    }
    override fun onSessionFinished(finishedSession: TerminalSession?) {
        finished()
        delegate.onSessionFinished(finishedSession)
    }
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) { delegate.onCopyTextToClipboard(session, text) }
    override fun onPasteTextFromClipboard(session: TerminalSession?) { delegate.onPasteTextFromClipboard(session) }
    override fun onBell(session: TerminalSession?) { delegate.onBell(session) }
    override fun onColorsChanged(session: TerminalSession?) { delegate.onColorsChanged(session) }
    override fun onTerminalCursorStateChange(state: Boolean) { delegate.onTerminalCursorStateChange(state) }
    override fun getTerminalCursorStyle(): Int = delegate.terminalCursorStyle
}
