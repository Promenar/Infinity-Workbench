package com.promenar.nexara.ui.chat.components

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.domain.storage.ExternalFolderEntry
import com.promenar.nexara.ui.common.FolderBreadcrumbsBar
import com.promenar.nexara.ui.common.FolderCrumb
import com.promenar.nexara.ui.common.NexaraIdentifierText
import com.promenar.nexara.ui.common.bettboxListGroup
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.forReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 由规范路径生成面包屑；crumb id 即该级目录的路径。 */
internal fun directoryCrumbs(path: String): List<FolderCrumb> {
    val segments = path.split('/').filter(String::isNotEmpty)
    return segments.mapIndexed { index, name -> FolderCrumb("/" + segments.take(index + 1).joinToString("/"), name) }
}

/**
 * 目录工作区的穿透式浏览：每次一级，面包屑以工作区名为根，系统返回先关闭文件再回到上级。
 * 文件只做有界文本预览，或经私有只读副本交给其它应用打开；不在此处写入或删除。
 */
@Composable
internal fun DirectoryWorkspaceBrowser(
    rootTitle: String,
    state: DirectoryBrowserState,
    onNavigate: (String) -> Unit,
    onOpenFile: (ExternalFolderEntry) -> Unit,
    onCloseFile: () -> Unit,
    onRefresh: () -> Unit,
    readFile: suspend (path: String, maxBytes: Long) -> ByteArray,
    modifier: Modifier = Modifier,
    rootHeader: @Composable () -> Unit = {},
) {
    val selected = state.selectedFile
    val parent = parentDirectoryPath(state.path)
    BackHandler(enabled = selected != null || parent != null) {
        if (selected != null) onCloseFile() else parent?.let(onNavigate)
    }
    if (selected != null) {
        DirectoryFileView(
            entry = selected,
            preview = state.preview ?: ExternalTextPreview.Loading,
            previewError = state.previewError,
            onBack = onCloseFile,
            readFile = readFile,
            modifier = modifier,
        )
    } else {
        DirectoryListView(
            rootTitle = rootTitle,
            state = state,
            onNavigate = onNavigate,
            onOpenFile = onOpenFile,
            onRefresh = onRefresh,
            modifier = modifier,
            rootHeader = rootHeader,
        )
    }
}

@Composable
private fun DirectoryListView(
    rootTitle: String,
    state: DirectoryBrowserState,
    onNavigate: (String) -> Unit,
    onOpenFile: (ExternalFolderEntry) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier,
    rootHeader: @Composable () -> Unit,
) = Column(modifier.fillMaxSize()) {
    val context = LocalContext.current
    val crumbs = directoryCrumbs(state.path)
    FolderBreadcrumbsBar(
        folderStack = crumbs,
        rootTitle = rootTitle,
        onNavigateBack = { onNavigate(crumbs.dropLast(1).lastOrNull()?.id ?: "/") },
        onNavigateToCrumb = { index -> onNavigate(if (index <= 0) "/" else crumbs[index - 1].id) },
        modifier = Modifier
            .padding(horizontal = NexaraSpacing.Small)
            .testTag(UiTags.RESOURCE_EXPLORER_BREADCRUMBS),
        backButtonTag = UiTags.RESOURCE_EXPLORER_FOLDER_BACK,
    )
    if (crumbs.isEmpty()) rootHeader()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        TextButton(
            onClick = onRefresh,
            enabled = !state.isLoading,
            modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.resource_explorer_directory_refresh),
                modifier = Modifier.padding(start = NexaraSpacing.XSmall),
            )
        }
    }
    if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    val error = state.error
    when {
        error != null -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = NexaraSpacing.Medium)
                .semantics { liveRegion = LiveRegionMode.Assertive }
                .testTag(UiTags.RESOURCE_EXPLORER_DIRECTORY_STATUS),
        ) {
            Text(
                text = error.reason?.let { stringResource(R.string.resource_explorer_directory_load_failed, it) }
                    ?: stringResource(R.string.resource_explorer_directory_load_failed_generic),
                style = MaterialTheme.typography.bodyMedium.forReading(),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = onRefresh,
                modifier = Modifier
                    .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                    .testTag(UiTags.RESOURCE_EXPLORER_RETRY_LOAD),
            ) { Text(stringResource(R.string.shared_btn_retry)) }
        }
        !state.isLoading && state.entries.isEmpty() -> Text(
            stringResource(R.string.resource_explorer_directory_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(NexaraSpacing.Large)
                .testTag(UiTags.RESOURCE_EXPLORER_DIRECTORY_STATUS),
        )
        else -> LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(UiTags.RESOURCE_EXPLORER_DIRECTORY_LIST),
            contentPadding = PaddingValues(bottom = NexaraSpacing.Large),
        ) {
            items(state.entries, key = { it.path }) { entry ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            if (entry.isDirectory) onNavigate(entry.path) else onOpenFile(entry)
                        },
                    headlineContent = {
                        NexaraIdentifierText(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = if (entry.isDirectory) null else ({
                        Text(Formatter.formatShortFileSize(context, entry.size), style = NexaraTypography.bodySmall)
                    }),
                    leadingContent = {
                        Icon(
                            if (entry.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                            contentDescription = null,
                            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = if (entry.isDirectory) ({ Icon(Icons.Rounded.ChevronRight, null) }) else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun DirectoryFileView(
    entry: ExternalFolderEntry,
    preview: ExternalTextPreview,
    previewError: String?,
    onBack: () -> Unit,
    readFile: suspend (path: String, maxBytes: Long) -> ByteArray,
    modifier: Modifier,
) = Column(modifier.fillMaxSize()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.mount_open_external)
    var opening by remember(entry.path) { mutableStateOf(false) }
    var openNotice by remember(entry.path) { mutableStateOf<Int?>(null) }
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp))
        Text(stringResource(R.string.resource_explorer_directory_back), modifier = Modifier.padding(start = NexaraSpacing.XSmall))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall)) {
        NexaraIdentifierText(entry.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        NexaraIdentifierText(
            entry.path + " · " + Formatter.formatShortFileSize(context, entry.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
        TextButton(
            enabled = !opening,
            modifier = Modifier
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .testTag(UiTags.RESOURCE_EXPLORER_DIRECTORY_OPEN_EXTERNAL),
            onClick = {
                if (entry.size > EXTERNAL_OPEN_BYTES) {
                    openNotice = R.string.mount_open_limit
                    return@TextButton
                }
                opening = true
                openNotice = null
                scope.launch {
                    try {
                        val intent = prepareExternalFileOpen(context, entry.name) { maxBytes -> readFile(entry.path, maxBytes) }
                        context.startActivity(
                            Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        openNotice = R.string.mount_open_failed
                    } finally {
                        opening = false
                    }
                }
            },
        ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(chooserTitle, modifier = Modifier.padding(start = NexaraSpacing.XSmall))
        }
    }
    if (opening) LinearProgressIndicator(Modifier.fillMaxWidth())
    openNotice?.let { notice ->
        Text(
            stringResource(notice),
            style = NexaraTypography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(top = NexaraSpacing.Medium)
            .bettboxListGroup(),
    ) {
        when (preview) {
            ExternalTextPreview.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            ExternalTextPreview.Failed -> DirectoryPreviewMessage(
                stringResource(R.string.workspace_kb_preview_failed, previewError.orEmpty()),
                error = true,
            )
            ExternalTextPreview.Binary -> DirectoryPreviewMessage(stringResource(R.string.mount_preview_binary))
            ExternalTextPreview.TooLarge -> DirectoryPreviewMessage(stringResource(R.string.workspace_kb_preview_too_large))
            is ExternalTextPreview.Text -> SelectionContainer(Modifier.fillMaxSize()) {
                Text(
                    preview.content,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(NexaraSpacing.Medium)
                        .testTag(UiTags.RESOURCE_EXPLORER_DIRECTORY_PREVIEW),
                    style = NexaraTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun DirectoryPreviewMessage(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.forReading(),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(NexaraSpacing.Medium),
    )
}
