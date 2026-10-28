package com.promenar.infinity.ui.linux

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.domain.linux.LinuxEnvironmentInfo
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxTerminalInfo
import com.promenar.infinity.domain.linux.LinuxTerminalLocation
import com.promenar.infinity.domain.tool.CommandToolResult
import com.promenar.infinity.ui.linux.output.CommandOutputContent
import com.promenar.infinity.ui.linux.output.CommandOutputParser
import com.promenar.infinity.ui.linux.output.CommandOutputPhase
import com.promenar.infinity.ui.linux.output.CommandOutputUiState
import com.promenar.infinity.ui.linux.output.OutputSearch
import com.promenar.infinity.ui.linux.output.OutputSegment
import com.promenar.infinity.ui.linux.output.OutputSegments
import com.promenar.infinity.ui.linux.terminal.LinuxTerminalContent
import com.promenar.infinity.ui.linux.terminal.LinuxTerminalUiState
import com.promenar.infinity.ui.linux.terminal.LiveTail
import com.promenar.infinity.ui.linux.terminal.TerminalModifiers
import com.promenar.infinity.ui.linux.terminal.TerminalTab
import com.promenar.infinity.ui.linux.terminal.TerminalTextPreviewPane
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

// ── 命令完整输出 ──

@PreviewTest
@Preview(name = "命令输出 合并 深色", widthDp = 390, heightDp = 844, locale = "zh-rCN")
@Composable
fun commandOutputMergedDarkPreview() = Frame(dark = true) { OutputPreview(OutputSegment.MERGED, query = "") }

@PreviewTest
@Preview(name = "Command output stdout light", widthDp = 390, heightDp = 844, locale = "en")
@Composable
fun commandOutputStdoutLightPreview() = Frame(dark = false) { OutputPreview(OutputSegment.STDOUT, query = "") }

@PreviewTest
@Preview(name = "命令输出 stderr 搜索 截断 深色", widthDp = 390, heightDp = 844, locale = "zh-rCN")
@Preview(name = "Command output stderr search truncated 360dp font130", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Composable
fun commandOutputStderrSearchPreview() = Frame(dark = true) { OutputPreview(OutputSegment.STDERR, query = "error", current = 1, truncated = true) }

@PreviewTest
@Preview(name = "命令输出 stderr 浅色", widthDp = 390, heightDp = 844, locale = "zh-rCN")
@Composable
fun commandOutputStderrLightPreview() = Frame(dark = false) { OutputPreview(OutputSegment.STDERR, query = "") }

// ── 本地终端 ──

@PreviewTest
@Preview(name = "终端 未安装 深色", widthDp = 390, heightDp = 844, locale = "zh-rCN")
@Composable
fun terminalNotInstalledDarkPreview() = Frame(dark = true) { TerminalPreview(LinuxTerminalUiState(runtime = LinuxRuntimeState.NotInstalled(2_000_000_000L))) }

@PreviewTest
@Preview(name = "Terminal not installed light", widthDp = 390, heightDp = 844, locale = "en")
@Composable
fun terminalNotInstalledLightPreview() = Frame(dark = false) { TerminalPreview(LinuxTerminalUiState(runtime = LinuxRuntimeState.NotInstalled(2_000_000_000L))) }

/** TerminalView 依赖真实 PTY 会话，预览中以同样配色的示例文本占位。 */
@PreviewTest
@Preview(name = "终端 页签 按键行 Ctrl 选中 深色", widthDp = 390, heightDp = 844, locale = "zh-rCN")
@Preview(name = "Terminal tabs keys 360dp font130", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Composable
fun terminalReadyDarkPreview() = Frame(dark = true) { TerminalPreview(readyState(selectedKey = TerminalTab.shellKey("t1"))) }

@PreviewTest
@Preview(name = "终端 页签 按键行 浅色", widthDp = 390, heightDp = 844, locale = "zh-rCN")
@Preview(name = "Terminal wide keys light", widthDp = 600, heightDp = 900, locale = "en")
@Composable
fun terminalReadyLightPreview() = Frame(dark = false) { TerminalPreview(readyState(selectedKey = TerminalTab.shellKey("t1"))) }

@PreviewTest
@Preview(name = "终端 Agent 任务只读页签 深色", widthDp = 390, heightDp = 844, locale = "zh-rCN")
@Composable
fun terminalJobTabDarkPreview() = Frame(dark = true) { TerminalPreview(readyState(selectedKey = TerminalTab.jobKey("job-3"))) }

@PreviewTest
@Preview(name = "终端 已退出 浅色", widthDp = 390, heightDp = 844, locale = "zh-rCN")
@Composable
fun terminalExitedLightPreview() = Frame(dark = false) {
    TerminalPreview(readyState(selectedKey = TerminalTab.shellKey("t2"), secondExited = true), atBottom = false)
}

private const val ESC = "\u001B"

private val sampleTerminal = buildString {
    val prompt = "$ESC[32mroot@infinity$ESC[0m:$ESC[34m/workspace$ESC[0m# "
    appendLine("${prompt}ls")
    appendLine("$ESC[34mdata$ESC[0m  $ESC[34mnotes$ESC[0m  report.md  $ESC[32mrun.sh$ESC[0m")
    appendLine("${prompt}./run.sh")
    appendLine("Requirement already satisfied: pandas in /root/projects/.venv/lib/python3.13/site-packages (2.3.3)")
    appendLine("clean raw/2026-09.csv … ok (18,224 行)")
    appendLine("clean raw/2026-10.csv … ok (21,907 行)")
    appendLine("done: 2 files")
    appendLine("${prompt}git status -s")
    appendLine("$ESC[31m M$ESC[0m report.md")
    appendLine("$ESC[31m??$ESC[0m data/out/")
    append(prompt)
}

private val sampleJobLog = buildString {
    appendLine("$ESC[1mCollecting$ESC[0m lxml>=5.0")
    appendLine("  Downloading lxml-5.3.0.tar.gz (3.7 MB)")
    appendLine("$ESC[33mWARNING$ESC[0m: Retrying (Retry(total=4)) after connection broken")
    appendLine("Building wheels for collected packages: lxml")
}

private fun readyState(selectedKey: String, secondExited: Boolean = false): LinuxTerminalUiState {
    val info = LinuxEnvironmentInfo("Alpine", "3.24.3", "aarch64", "5.4.0", 0L, null)
    val workspace = LinuxTerminalLocation.SessionWorkspace("chat-1")
    val job = BackgroundJob(
        id = "job-3",
        sessionId = "chat-1",
        runner = BackgroundJobRunner.SHELL,
        summary = "pip install -r requirements.txt",
        state = BackgroundJobState.RUNNING,
        exitCode = null,
        startedAtMillis = 0L,
        endedAtMillis = null,
        maxRuntimeMinutes = 120,
        onExit = BackgroundJobExitAction.NOTIFY,
        logWorkspacePath = "jobs/job-3.log",
        liveOutputKey = "job:job-3",
    )
    return LinuxTerminalUiState(
        runtime = LinuxRuntimeState.Ready(info),
        tabs = listOf(
            TerminalTab.Shell(LinuxTerminalInfo("t1", 1, null, workspace, 0L, running = true)),
            TerminalTab.Shell(
                LinuxTerminalInfo("t2", 2, null, workspace, 0L, running = !secondExited, exitCode = if (secondExited) 0 else null),
            ),
            TerminalTab.AgentJob(job, ordinal = 3),
        ),
        selectedKey = selectedKey,
        modifiers = TerminalModifiers(ctrl = true),
        jobTails = mapOf("job-3" to LiveTail(sampleJobLog, truncated = false)),
        sessionTitles = mapOf("chat-1" to "数据清洗"),
    )
}

@Composable
private fun TerminalPreview(state: LinuxTerminalUiState, atBottom: Boolean = true) {
    LinuxTerminalContent(
        state = state,
        atBottom = atBottom,
        onBack = {},
        onOpenEnvironment = {},
        onNewTerminal = {},
        onRetry = {},
        onSelect = {},
        onClose = {},
        onKey = {},
        onToggleCtrl = {},
        onToggleAlt = {},
        onToggleKeyboard = {},
        onScrollToBottom = {},
        shellPane = { _, modifier -> TerminalTextPreviewPane(sampleTerminal, modifier) },
        imeVisible = false,
    )
}

private val sampleStdout = buildString {
    appendLine("Collecting lxml (from -r requirements.txt (line 3))")
    appendLine("  Using cached lxml-5.3.0.tar.gz (3.7 MB)")
    appendLine("$ESC[32mSuccessfully$ESC[0m installed pandas-2.3.3 numpy-2.3.4")
}

private val sampleStderr = buildString {
    appendLine("  $ESC[33mWARNING$ESC[0m: Retrying (Retry(total=4)) after connection broken")
    appendLine("  Building wheel for lxml (pyproject.toml) ... $ESC[31merror$ESC[0m")
    appendLine("  $ESC[31merror$ESC[0m: subprocess-exited-with-error")
    appendLine()
    appendLine("  × Building wheel for lxml did not run successfully.")
    appendLine("  │ exit code: 1")
    appendLine("  ╰─> Error: Please make sure the libxml2 and libxslt development packages are installed.")
    appendLine()
    appendLine("$ESC[1;31mERROR$ESC[0m: Failed building wheel for lxml")
    appendLine("$ESC[1;31mERROR$ESC[0m: Could not build wheels for lxml, which is required to install pyproject.toml-based projects")
}

@Composable
private fun OutputPreview(segment: OutputSegment, query: String, current: Int = 0, truncated: Boolean = false) {
    val result = CommandToolResult(
        exitCode = 1,
        stdout = sampleStdout,
        stderr = sampleStderr,
        stdoutOriginalLength = if (truncated) 49_152 else sampleStdout.length.toLong(),
        stdoutTruncated = truncated,
        backendFields = kotlinx.serialization.json.JsonObject(mapOf("runtime" to kotlinx.serialization.json.JsonPrimitive("linux"))),
    ).toJson().toString()
    val output = CommandOutputParser.parse("shell_exec", """{"command":"pip install -r requirements.txt"}""", result, 12_400)
    val document = OutputSegments.build(output, segment, "── stderr ──")
    val search = OutputSearch.find(document.text, query).let { if (it.matches.isEmpty()) it else it.copy(current = current.coerceAtMost(it.matches.lastIndex)) }
    CommandOutputContent(
        state = CommandOutputUiState(
            phase = CommandOutputPhase.LOADED,
            output = output,
            segment = segment,
            document = document,
            search = search,
        ),
        onBack = {},
        onSegmentSelected = {},
        onQueryChange = {},
        onPreviousMatch = {},
        onNextMatch = {},
        onCopy = {},
        onShare = {},
        onSave = {},
        onRerun = {},
        onRetry = {},
    )
}

@Composable
private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}
