package com.promenar.nexara.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.workspace.WorkspaceFolder
import com.promenar.nexara.domain.storage.ExternalFolderEntry
import com.promenar.nexara.ui.chat.components.ExternalTextPreview
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences
import java.io.File

private val Volume = File("/storage/emulated/0")
private val Project = WorkspaceEntity("ws-project", WorkspaceEntity.KIND_PROJECT, "nexara-android", "nexara-android", 1)
private val Shared = WorkspaceEntity(
    "ws-shared", WorkspaceEntity.KIND_SHARED, "毕业设计-多模态检索系统",
    "/storage/emulated/0/Documents/Projects/graduation-thesis/multimodal-retrieval-system", 2,
)

@Composable
private fun WorkspaceTheme(mode: NexaraThemeMode = NexaraThemeMode.DARK, content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = mode, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** 与 NexaraBottomSheet 一致的标题与左右留白。 */
@Composable
private fun SheetFrame(
    title: String,
    mode: NexaraThemeMode = NexaraThemeMode.DARK,
    content: @Composable () -> Unit,
) = WorkspaceTheme(mode) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 32.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp))
        content()
    }
}

@PreviewTest
@Preview(name = "选择器 项目根 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Preview(name = "选择器 项目根 深中360 大字", widthDp = 360, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun workspacePickerProjectsPreview() = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    WorkspacePickerContent(
        WorkspacePickerUiState(
            page = WorkspacePickerPage.Projects,
            folders = listOf("demo", "my-app", "nexara-android", "scratch_2026.10").map { WorkspaceFolder(it, File("/projects/$it")) },
            selectedProject = "my-app",
        ),
    )
}

private val PermissionState = WorkspacePickerUiState(
    registered = listOf(Project, Shared),
    currentWorkspaceId = Project.id,
    sharedSupported = true,
    sharedPermission = false,
    sharedRoots = listOf(Volume),
)

@PreviewTest
@Preview(name = "选择器 共享未授权 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Composable
fun workspacePickerPermissionPreview() = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    WorkspacePickerContent(PermissionState)
}

@PreviewTest
@Preview(name = "选择器 共享未授权 浅英360", widthDp = 360, heightDp = 720, locale = "en")
@Composable
fun workspacePickerPermissionLightPreview() =
    SheetFrame(stringResource(R.string.workspace_picker_title), NexaraThemeMode.LIGHT) {
        WorkspacePickerContent(PermissionState)
    }

@PreviewTest
@Preview(name = "选择器 Play 起始页 深中411", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable
fun workspacePickerPlayHomePreview() = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    WorkspacePickerContent(WorkspacePickerUiState(registered = listOf(Project), sharedSupported = false))
}

@PreviewTest
@Preview(name = "选择器 共享目录列表 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Preview(name = "选择器 共享目录列表 深中360", widthDp = 360, heightDp = 720, locale = "zh-rCN")
@Composable
fun workspacePickerSharedListPreview() = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    val path = File(Volume, "Documents/Projects/graduation-thesis")
    WorkspacePickerContent(
        WorkspacePickerUiState(
            page = WorkspacePickerPage.Shared(Volume, path),
            sharedSupported = true,
            sharedPermission = true,
            sharedRoots = listOf(Volume, File("/storage/1A2B-3C4D")),
            folders = listOf("data", "multimodal-retrieval-system", "论文初稿与参考文献整理（第三版）", "slides")
                .map { WorkspaceFolder(it, File(path, it)) },
        ),
    )
}

@PreviewTest
@Preview(name = "资源管理器工作区栏 多种状态 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Preview(name = "资源管理器工作区栏 多种状态 深中360 大字", widthDp = 360, heightDp = 1300, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sessionWorkspaceStatesPreview() = WorkspaceTheme {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SessionWorkspaceBar(SessionWorkspaceUi.Private, busy = false)
        SessionWorkspaceBar(
            SessionWorkspaceUi.Directory(Project.id, Project.displayName, Project.kind, Project.location),
            busy = false,
            footnote = stringResource(R.string.resource_explorer_directory_notice),
        )
        SessionWorkspaceBar(
            SessionWorkspaceUi.Directory(Shared.id, Shared.displayName, Shared.kind, Shared.location),
            busy = true,
        )
        SessionWorkspaceBar(
            SessionWorkspaceUi.Unavailable(Shared.id, Shared.displayName, Shared.kind, Shared.location, "请先授予所有文件访问权限"),
            busy = false,
            switchFailed = true,
        )
    }
}

@PreviewTest
@Preview(name = "资源管理器工作区栏 登记已删除 浅英411", widthDp = 411, heightDp = 360, locale = "en")
@Composable
fun sessionWorkspaceMissingPreview() = WorkspaceTheme(NexaraThemeMode.LIGHT) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SessionWorkspaceBar(SessionWorkspaceUi.Unavailable("gone", null, null, null, "Workspace registration was deleted"), busy = false)
    }
}

@PreviewTest
@Preview(name = "Agent 默认工作区 深中411", widthDp = 411, heightDp = 620, locale = "zh-rCN")
@Preview(name = "Agent 默认工作区 深中360 大字", widthDp = 360, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun agentDefaultWorkspacePreview() = WorkspaceTheme {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        AgentDefaultWorkspaceSection(workspaceId = null, workspace = null, onChoose = {}, onClear = {})
        AgentDefaultWorkspaceSection(workspaceId = Shared.id, workspace = Shared, onChoose = {}, onClear = {})
        AgentDefaultWorkspaceSection(workspaceId = "gone", workspace = null, onChoose = {}, onClear = {})
    }
}

@PreviewTest
@Preview(name = "知识库工作区 分组 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Composable
fun knowledgeWorkspaceListPreview() = KnowledgeListPreview(NexaraThemeMode.DARK)

@PreviewTest
@Preview(name = "知识库工作区 分组 浅英360", widthDp = 360, heightDp = 720, locale = "en")
@Composable
fun knowledgeWorkspaceListLightPreview() = KnowledgeListPreview(NexaraThemeMode.LIGHT)

/** 知识库文件页顶部的“工作区”入口，以及点开后底部弹层中的登记列表。 */
@Composable
private fun KnowledgeListPreview(mode: NexaraThemeMode) = WorkspaceTheme(mode) {
    Column(Modifier.fillMaxSize()) {
        KnowledgeWorkspacesEntry(count = 2, onClick = {}, modifier = Modifier.padding(16.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(top = 24.dp)) {
                Text(stringResource(R.string.workspace_kb_entry), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 16.dp))
                KnowledgeWorkspaceListContent(
                    workspaces = listOf(Project, Shared),
                    notice = stringResource(R.string.workspace_kb_delete_referenced, 2, 1),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "知识库工作区 浏览 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun knowledgeWorkspaceBrowserPreview() = SheetFrame(stringResource(R.string.workspace_kb_entry)) {
    KnowledgeWorkspaceBrowserContent(
        workspace = Shared,
        path = "/docs/design",
        entries = listOf(
            ExternalFolderEntry("/docs/design/assets", "assets", true, 0, 0),
            ExternalFolderEntry("/docs/design/架构说明-检索与重排流程.md", "架构说明-检索与重排流程.md", false, 18_432, 0),
            ExternalFolderEntry("/docs/design/benchmark.csv", "benchmark.csv", false, 2_048_000, 0),
        ),
        loading = false,
        error = null,
    )
}

@PreviewTest
@Preview(name = "知识库工作区 文件预览 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Preview(name = "知识库工作区 文件预览 深中360 大字", widthDp = 360, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun knowledgeWorkspaceFilePreview() = SheetFrame(stringResource(R.string.workspace_kb_entry)) {
    KnowledgeWorkspaceFileContent(
        entry = ExternalFolderEntry("/docs/design/架构说明-检索与重排流程.md", "架构说明-检索与重排流程.md", false, 18_432, 0),
        preview = ExternalTextPreview.Text("# 检索与重排\n\n1. 向量召回 Top-50\n2. 关键词补召回\n3. 交叉编码器重排\n\n> 只读预览，不会修改源文件。\n"),
        notice = KnowledgeImportNotice.Imported,
    )
}
