package com.promenar.nexara.ui.startup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.startup.StartupIssue
import com.promenar.nexara.startup.StartupIssueAction
import com.promenar.nexara.startup.StartupIssueScope
import com.promenar.nexara.startup.StartupScopeKind
import com.promenar.nexara.startup.StartupSeverity
import com.promenar.nexara.startup.StartupStep
import com.promenar.nexara.startup.StartupSubsystem
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

private val SampleRows = listOf(
    StartupIssueRow(
        StartupIssue(
            id = "issue-journal", step = StartupStep.WORKSPACE_JOURNAL, severity = StartupSeverity.ISOLATED,
            scope = StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "root-1", "工作环境能力探测"),
            summary = "WorkspaceMutationRecoveryException: 移动类 journal 文件布局冲突",
            actions = setOf(StartupIssueAction.RETRY, StartupIssueAction.ABANDON), occurredAt = 0,
        ),
        scopeName = "工作环境能力探测",
    ),
    StartupIssueRow(
        StartupIssue(
            id = "issue-vectors", step = StartupStep.VECTOR_QUEUE, severity = StartupSeverity.ISOLATED,
            scope = StartupIssueScope.subsystem(StartupSubsystem.VECTOR_QUEUE),
            summary = "向量队列恢复失败",
            actions = setOf(StartupIssueAction.RETRY), occurredAt = 0,
        ),
        scopeName = "记忆与索引",
    ),
)

@Composable
private fun StartupPreview(mode: NexaraThemeMode, content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = mode, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) { Column { content() } }
    }
}

// 会话列表与会话顶栏下方的提示条，以及问题面板：一条可重试和放弃，一条只能重试。
@PreviewTest
@Preview(name = "启动问题 深中360", widthDp = 360, heightDp = 900, locale = "zh-rCN")
@Composable
fun startupIssuesDarkPreview() = StartupPreview(NexaraThemeMode.DARK) {
    StartupIssuesBanner(count = 2, onOpen = {})
    Spacer(Modifier.height(8.dp))
    StartupWritePausedBanner(onOpen = {})
    Spacer(Modifier.height(16.dp))
    // 面板外壳给出 24dp 水平留白。
    Column(Modifier.padding(horizontal = 24.dp)) {
        StartupIssuesSheetContent(
            SampleRows, busyIssueId = null, message = "问题仍未解决。文件 journal 无法安全放弃", messageIssueId = "issue-journal",
            canDiagnose = true, onAction = { _, _ -> }, onDiagnose = {}, onViewRecords = {}, onExport = {},
        )
    }
}

@PreviewTest
@Preview(name = "启动问题 浅英 大字体", widthDp = 411, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun startupIssuesLightLargeFontPreview() = StartupPreview(NexaraThemeMode.LIGHT) {
    StartupIssuesBanner(count = 1, onOpen = {})
    Spacer(Modifier.height(16.dp))
    Column(Modifier.padding(horizontal = 24.dp)) {
        StartupIssuesSheetContent(SampleRows, busyIssueId = "issue-vectors", message = null, canDiagnose = false, onAction = { _, _ -> }, onDiagnose = {}, onViewRecords = {}, onExport = {})
    }
}
