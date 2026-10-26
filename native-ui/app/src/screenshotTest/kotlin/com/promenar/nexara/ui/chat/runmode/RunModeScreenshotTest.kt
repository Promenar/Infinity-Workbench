package com.promenar.nexara.ui.chat.runmode

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.linux.LinuxResetLossSummary
import com.promenar.nexara.data.linux.LinuxRunModeExportFailure
import com.promenar.nexara.data.linux.LinuxRunModeExportFailureReason
import com.promenar.nexara.data.linux.LinuxRunModeExportReport
import com.promenar.nexara.domain.linux.LinuxFailure
import com.promenar.nexara.domain.linux.LinuxFailureCode
import com.promenar.nexara.domain.model.SessionRunMode
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.ui.chat.ChatTopBar
import com.promenar.nexara.ui.workspace.SessionWorkspaceUi
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@Composable
private fun RunModePreview(mode: NexaraThemeMode, content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = mode, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}

private val Loss = LinuxResetLossSummary(rootfsBytes = 420L shl 20, rootHomeBytes = 96L shl 20, otherHomeBytes = 0,
    installedPackages = List(38) { "pkg$it" }, unreadablePaths = listOf("/var/cache"))

private val PreviewGit = com.promenar.nexara.ui.git.GitEntrySummary.Repository(
    branch = "master", detached = false, headShortSha = "cf2a667", unborn = false,
    changeCount = 1, truncated = false, ahead = null, behind = null,
)

// 顶栏状态行（长标题 + Linux + Git、内置无 Git、Linux 不可用 + 长分支）与 C1 修复检查点。
@PreviewTest
@Preview(name = "运行模式 顶栏与修复 深中360", widthDp = 360, heightDp = 720, locale = "zh-rCN")
@Composable
fun runModeTopBarAndUnavailableDarkPreview() = RunModePreview(NexaraThemeMode.DARK) {
    ChatTopBar("在会话目录中创建文件并返回完整路径与说明", {}, {}, {}, onClearHistory = {}, onRename = {}, onDeleteSession = {},
        runMode = RunModeBadge(SessionRunMode.LINUX, unavailable = false), git = PreviewGit,
        workspace = SessionWorkspaceUi.Directory("w1", "nexara-app", WorkspaceEntity.KIND_PROJECT, "nexara-app"))
    ChatTopBar("新对话", {}, {}, {}, onClearHistory = {}, onRename = {}, onDeleteSession = {},
        runMode = RunModeBadge(SessionRunMode.BUILTIN, unavailable = false), workspace = SessionWorkspaceUi.Private)
    ChatTopBar("工作环境能力探测", {}, {}, {}, onClearHistory = {}, onRename = {}, onDeleteSession = {},
        runMode = RunModeBadge(SessionRunMode.LINUX, unavailable = true),
        git = PreviewGit.copy(branch = "feature/very-long-branch-name-for-layout", changeCount = 0, ahead = 2, behind = null),
        workspace = SessionWorkspaceUi.Directory("w2", "multimodal-retrieval-system", WorkspaceEntity.KIND_SHARED, "/storage/emulated/0/multimodal-retrieval-system"))
    // Play 版没有运行模式标识：状态行只有工作区；工作区不可用时显示警示色。
    ChatTopBar("整理论文参考文献", {}, {}, {}, onClearHistory = {}, onRename = {}, onDeleteSession = {},
        workspace = SessionWorkspaceUi.Unavailable("w3", "论文初稿", WorkspaceEntity.KIND_SHARED, "/storage/emulated/0/论文初稿", "需要所有文件访问权限"))
    Spacer(Modifier.height(16.dp))
    RunModeCheckpointContent(RunModeCheckpointStage.Unavailable(RunModeUnavailableReason.BROKEN), canExport = true,
        message = null, actions = RunModeCheckpointActions())
}

// C2 无法无损修复：重置损失摘要、导出与改用内置；以及重置也失败后只剩两项。
@PreviewTest
@Preview(name = "运行模式 修复失败 浅英 大字体", widthDp = 411, heightDp = 1100, locale = "en", fontScale = 1.3f)
@Composable
fun runModeRepairFailedLightPreview() = RunModePreview(NexaraThemeMode.LIGHT) {
    RunModeCheckpointContent(RunModeCheckpointStage.RepairFailed(LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED), Loss, canReset = true),
        canExport = true, message = null, actions = RunModeCheckpointActions())
    RunModeCheckpointContent(RunModeCheckpointStage.RepairFailed(null, null, canReset = false),
        canExport = true, message = null, actions = RunModeCheckpointActions())
}

// 旧会话首次选择与部分导出结果。
@PreviewTest
@Preview(name = "运行模式 选择与导出 深中360", widthDp = 360, heightDp = 900, locale = "zh-rCN")
@Composable
fun runModeSelectAndExportDarkPreview() = RunModePreview(NexaraThemeMode.DARK) {
    RunModeCheckpointContent(RunModeCheckpointStage.Select(canChooseLinux = true), canExport = false, message = null,
        actions = RunModeCheckpointActions())
    RunModeCheckpointContent(
        RunModeCheckpointStage.Exported(
            LinuxRunModeExportReport("session", filesCopied = 128, bytesCopied = 12L shl 20,
                failures = listOf(LinuxRunModeExportFailure("/root/app/node_modules/.bin/tsc", LinuxRunModeExportFailureReason.LINK_SKIPPED))),
            privateTarget = true,
        ),
        canExport = false, message = null, actions = RunModeCheckpointActions(),
    )
    Row { RunModeChip(SessionRunMode.BUILTIN, unavailable = false, onClick = {}); Spacer(Modifier.width(8.dp)); RunModeChip(null, false, {}) }
}
