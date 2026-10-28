package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.ui.chat.components.ExternalMountFormContent
import com.promenar.infinity.ui.chat.components.ExternalMountManagerContent
import com.promenar.infinity.ui.chat.components.ExternalMountSource
import com.promenar.infinity.ui.chat.components.KnowledgeMountOption
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private val Mounts = listOf(
    ExternalMount("m1", "termux-home", "saf_tree", "tree-1", "s1", createdAt = 1),
    ExternalMount("m2", "documents", "real_path", "/storage/emulated/0/Documents", "s1", createdAt = 2),
    ExternalMount("m3", "sdcard-backup", "saf_tree", "tree-3", "s1", createdAt = 3, unavailableReason = "revoked"),
)

/** 与 InfinityBottomSheet 全高面板一致的底色与左右留白；页头由内容自带。 */
@Composable
private fun MountSheetFrame(mode: InfinityThemeMode = InfinityThemeMode.DARK, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 32.dp)) { content() }
        }
    }
}

@Composable
private fun ManagerPreview(
    mounts: List<ExternalMount>,
    mode: InfinityThemeMode = InfinityThemeMode.DARK,
    knowledgeMount: KnowledgeMountOption? = null,
) = MountSheetFrame(mode) {
    ExternalMountManagerContent(
        mounts = mounts,
        sources = listOf(ExternalMountSource.Saf, ExternalMountSource.RealPath),
        loading = false,
        busy = false,
        notice = null,
        onRefresh = {},
        onAdd = {},
        onBrowse = {},
        onReauthorize = {},
        onRemove = {},
        onGrantRealPath = {},
        onRecycle = {},
        knowledgeMount = knowledgeMount,
    )
}

@PreviewTest
@Preview(name = "外部文件夹 已挂载 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "外部文件夹 已挂载 浅英360 大字", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f)
@Composable
fun externalMountManagerPreview() = ManagerPreview(Mounts)

@PreviewTest
@Preview(name = "外部文件夹 空 浅中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun externalMountManagerEmptyPreview() = ManagerPreview(emptyList(), InfinityThemeMode.LIGHT)

@PreviewTest
@Preview(name = "外部文件夹 添加存储路径 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Composable
fun externalMountFormPreview() = MountSheetFrame {
    ExternalMountFormContent(
        source = ExternalMountSource.RealPath,
        alias = "documents",
        sourcePath = "/storage/emulated/0/Documents",
        hasCurrentSession = true,
        realPathPermission = false,
        busy = false,
        notice = null,
        onAliasChanged = {},
        onPathChanged = {},
        onGrantAccess = {},
        onBack = {},
        onConfirm = {},
    )
}

// 知识库挂载：已挂载行带“知识库 · 只读”信息色标签；添加组末尾的“挂载知识库”在已挂载后置灰。
@PreviewTest
@Preview(name = "外部文件夹 知识库只读 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Preview(name = "外部文件夹 知识库只读 浅英360 大字", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun externalMountKnowledgePreview() = ManagerPreview(
    Mounts.take(1) + ExternalMount("k1", "knowledge", "knowledge_base", "root-1", "s1", createdAt = 4, readOnly = true),
    knowledgeMount = KnowledgeMountOption.Mounted,
)

@PreviewTest
@Preview(name = "外部文件夹 可挂载知识库 浅中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun externalMountKnowledgeAvailablePreview() = ManagerPreview(
    emptyList(),
    InfinityThemeMode.LIGHT,
    knowledgeMount = KnowledgeMountOption.Available(listOf("/mnt/knowledge")),
)
