package com.promenar.infinity.ui.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.ssh.SshActiveConnection
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshConnectionKind
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.linux.terminal.TerminalTextPreviewPane
import com.promenar.infinity.ui.settings.SshActiveConnectionsSection
import com.promenar.infinity.ui.ssh.SshReadOnlyTab
import com.promenar.infinity.ui.ssh.SshTerminalContent
import com.promenar.infinity.ui.ssh.SshTerminalUiState
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

// 截图只消费展示参数：分段内容用占位，不创建 SSH 运行时、终端控制器或设置页状态。

// ── 远程面板外壳 ──

@PreviewTest
@Preview(name = "远程 窄窗口整页 深色中文 智能体使用中", widthDp = 390, heightDp = 844, locale = "zh-rCN")
@Composable
fun remotePageNarrowDarkPreview() = Frame(dark = true) {
    RemoteShellPreview(RemoteSegment.SSH, agentActive = true, tailscaleAttention = false, linuxMode = false, page = true)
}

@PreviewTest
@Preview(name = "远程 检视器 浅色英文 Tailscale 需处理", widthDp = 400, heightDp = 760, locale = "en")
@Composable
fun remoteInspectorLightPreview() = Frame(dark = false) {
    RemoteShellPreview(RemoteSegment.TAILSCALE, agentActive = true, tailscaleAttention = true, linuxMode = false, page = false)
}

@PreviewTest
@Preview(name = "远程 Linux 会话 Git 提示 深色中文 字号130", widthDp = 360, heightDp = 900, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun remoteLinuxGitLargeTextPreview() = Frame(dark = true) {
    RemoteShellPreview(RemoteSegment.GIT, agentActive = false, tailscaleAttention = false, linuxMode = true, page = true)
}

@PreviewTest
@Preview(name = "远程 Linux 会话 网络提示 浅色中文", widthDp = 390, heightDp = 844, locale = "zh-rCN")
@Composable
fun remoteLinuxNetworkLightPreview() = Frame(dark = false) {
    RemoteShellPreview(RemoteSegment.NETWORK, agentActive = false, tailscaleAttention = false, linuxMode = true, page = true)
}

// ── 活动连接 ──

@PreviewTest
@Preview(name = "SSH 活动连接 深色中文", widthDp = 390, heightDp = 420, locale = "zh-rCN")
@Composable
fun sshActiveConnectionsDarkPreview() = Frame(dark = true) { ConnectionsPreview() }

@PreviewTest
@Preview(name = "SSH 活动连接 浅色英文 字号130", widthDp = 360, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable
fun sshActiveConnectionsLargeTextPreview() = Frame(dark = false) { ConnectionsPreview() }

// ── SSH 终端 ──

@PreviewTest
@Preview(name = "SSH 终端 已连接 带只读页签 深色中文", widthDp = 390, heightDp = 844, locale = "zh-rCN")
@Composable
fun sshTerminalConnectedDarkPreview() = Frame(dark = true) {
    TerminalPreview(SshTerminalUiState(connecting = false, sessionId = "pty-1", host = HOST), READ_ONLY_TABS, selectedTab = "mine")
}

@PreviewTest
@Preview(name = "SSH 终端 智能体只读 浅色英文", widthDp = 390, heightDp = 844, locale = "en")
@Composable
fun sshTerminalAgentLightPreview() = Frame(dark = false) {
    TerminalPreview(SshTerminalUiState(connecting = false, sessionId = "pty-1", host = HOST), READ_ONLY_TABS, selectedTab = "exec:s1")
}

@PreviewTest
@Preview(name = "SSH 终端 已断开 深色中文 字号130", widthDp = 360, heightDp = 800, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sshTerminalClosedLargeTextPreview() = Frame(dark = true) {
    TerminalPreview(
        SshTerminalUiState(connecting = false, closed = true, error = "HOST_UNREACHABLE", host = HOST),
        readOnlyTabs = emptyList(),
        selectedTab = "mine",
    )
}

@Composable
private fun RemoteShellPreview(
    segment: RemoteSegment,
    agentActive: Boolean,
    tailscaleAttention: Boolean,
    linuxMode: Boolean,
    page: Boolean,
) {
    RemotePageContent(
        segment = segment,
        onSelectSegment = {},
        agentActive = agentActive,
        tailscaleAttention = tailscaleAttention,
        linuxMode = linuxMode,
        onBack = if (page) ({}) else null,
        onOpenSettings = {},
    ) { current ->
        Column(Modifier.fillMaxSize().padding(InfinitySpacing.Large)) {
            Text("${current.name} · 设置页内容占位", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ConnectionsPreview() {
    Column(Modifier.padding(InfinitySpacing.Large)) {
        SshActiveConnectionsSection(
            connections = listOf(
                connection("nas", SshConnectionKind.TERMINAL, 1_000L),
                connection("pi", SshConnectionKind.FORWARD, 2_000L),
                connection("build-server", SshConnectionKind.AGENT, 3_000L),
                connection("nas", SshConnectionKind.JOB, 4_000L),
            ),
            enabled = true,
            onOpenTerminal = {},
            onDisconnect = {},
        )
    }
}

@Composable
private fun TerminalPreview(state: SshTerminalUiState, readOnlyTabs: List<SshReadOnlyTab>, selectedTab: String) {
    SshTerminalContent(
        alias = "nas",
        state = state,
        readOnlyTabs = readOnlyTabs,
        selectedTab = selectedTab,
        atBottom = true,
        onSelectTab = {},
        onBack = {},
        onDisconnect = {},
        onReconnect = {},
        onKey = {},
        onToggleCtrl = {},
        onToggleAlt = {},
        onToggleKeyboard = {},
        onScrollToBottom = {},
        onStopExec = {},
        shellPane = { modifier -> TerminalTextPreviewPane(SHELL_SAMPLE, modifier) },
        readOnlyPane = { _, _, _, _, modifier -> TerminalTextPreviewPane(AGENT_SAMPLE, modifier) },
        imeVisible = false,
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

private fun connection(alias: String, kind: SshConnectionKind, at: Long) =
    SshActiveConnection(owner = "o:$alias:$kind", alias = alias, kind = kind, sessionId = null, jobId = null, connectedAtMillis = at)

private val HOST = SshHost(alias = "nas", hostname = "10.0.0.2", port = 22, username = "root", authType = SshAuthType.KEY)

private val READ_ONLY_TABS = listOf(
    SshReadOnlyTab("exec:s1", SshReadOnlyTab.Kind.EXEC, "s1", "journalctl -u nginx -n 200"),
    SshReadOnlyTab("pty:p1", SshReadOnlyTab.Kind.SESSION, "ssh_session:p1", null),
)

private val SHELL_SAMPLE = buildString {
    appendLine("Last login: Thu Oct 30 09:12:41 2026 from 100.64.0.7")
    appendLine("root@nas:~# df -h /srv")
    appendLine("Filesystem      Size  Used Avail Use% Mounted on")
    appendLine("/dev/md0        7.3T  4.1T  3.2T  57% /srv")
    append("root@nas:~# ")
}

private val AGENT_SAMPLE = buildString {
    appendLine("Oct 30 09:20:01 nas nginx[812]: 2026/10/30 09:20:01 [warn] upstream response is buffered")
    appendLine("Oct 30 09:20:07 nas nginx[812]: 2026/10/30 09:20:07 [error] connect() failed (111: Connection refused)")
    appendLine("Oct 30 09:20:09 nas systemd[1]: Reloading nginx.service")
}
