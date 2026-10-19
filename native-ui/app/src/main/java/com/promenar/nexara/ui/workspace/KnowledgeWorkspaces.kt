package com.promenar.nexara.ui.workspace

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.workspace.WorkspaceDeleteResult
import com.promenar.nexara.data.workspace.WorkspaceLibrary
import com.promenar.nexara.domain.storage.ExternalFolderEntry
import com.promenar.nexara.ui.chat.components.ExternalTextPreview
import com.promenar.nexara.ui.chat.components.decodeExternalTextPreview
import com.promenar.nexara.ui.common.ConfirmDialog
import com.promenar.nexara.ui.common.FolderBreadcrumbsBar
import com.promenar.nexara.ui.common.FolderCrumb
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraDropdownMenu
import com.promenar.nexara.ui.common.NexaraIdentifierText
import com.promenar.nexara.ui.common.bettboxListGroup
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.forReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TEXT_PREVIEW_BYTES = 256 * 1024

/** 知识库文件页根目录下的“工作区”分组入口；点开后列出已登记的自选工作区。 */
@Composable
fun KnowledgeWorkspacesEntry(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .bettboxListGroup()
            .testTag("knowledge_workspaces_entry")
            .clickable(role = Role.Button, onClick = onClick),
        headlineContent = { Text(stringResource(R.string.workspace_kb_entry)) },
        supportingContent = {
            Text(
                if (count == 0) stringResource(R.string.workspace_kb_entry_empty)
                else pluralStringResource(R.plurals.workspace_kb_count, count, count),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Icon(Icons.Rounded.FolderSpecial, contentDescription = null) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** 入口与面板的组合：入口显示登记数量，面板只读浏览、预览与加入知识库。 */
@Composable
fun KnowledgeWorkspacesGroup(
    knowledgeRootUuid: String?,
    onImported: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as? NexaraApplication ?: return
    val library = app.workspaceLibrary
    val workspacesFlow = remember(library) { runCatching { library.observe() }.getOrElse { flowOf(emptyList()) } }
    val workspaces by workspacesFlow.collectAsState(initial = emptyList())
    var open by remember { mutableStateOf(false) }
    KnowledgeWorkspacesEntry(count = workspaces.size, onClick = { open = true }, modifier = modifier)
    KnowledgeWorkspacesSheet(
        show = open,
        onDismiss = { open = false },
        workspaces = workspaces,
        library = library,
        knowledgeRootUuid = knowledgeRootUuid,
        onImported = onImported,
    )
}

private sealed interface KnowledgeWorkspacePage {
    data object List : KnowledgeWorkspacePage
    data class Browse(val workspace: WorkspaceEntity, val path: String) : KnowledgeWorkspacePage
    data class File(val workspace: WorkspaceEntity, val directory: String, val entry: ExternalFolderEntry) : KnowledgeWorkspacePage
}

@Composable
private fun KnowledgeWorkspacesSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    workspaces: List<WorkspaceEntity>,
    library: WorkspaceLibrary,
    knowledgeRootUuid: String?,
    onImported: (String) -> Unit,
) {
    if (!show) return
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<KnowledgeWorkspacePage>(KnowledgeWorkspacePage.List) }
    var notice by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleting by remember { mutableStateOf<WorkspaceEntity?>(null) }
    val operationFailed = stringResource(R.string.workspace_kb_operation_failed)
    val resources = LocalResources.current

    NexaraBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.workspace_kb_entry),
        modifier = Modifier.fillMaxHeight(0.85f),
        dragGesturesEnabled = false,
    ) {
        when (val current = page) {
            KnowledgeWorkspacePage.List -> KnowledgeWorkspaceListContent(
                workspaces = workspaces,
                notice = notice,
                onOpen = { notice = null; page = KnowledgeWorkspacePage.Browse(it, "/") },
                onRename = { notice = null; renaming = it },
                onDelete = { notice = null; deleting = it },
            )
            is KnowledgeWorkspacePage.Browse -> KnowledgeWorkspaceBrowser(
                library = library,
                workspace = current.workspace,
                path = current.path,
                onNavigate = { page = current.copy(path = it) },
                onBackToList = { page = KnowledgeWorkspacePage.List },
                onOpenFile = { page = KnowledgeWorkspacePage.File(current.workspace, current.path, it) },
            )
            is KnowledgeWorkspacePage.File -> KnowledgeWorkspaceFile(
                library = library,
                workspace = current.workspace,
                entry = current.entry,
                knowledgeRootUuid = knowledgeRootUuid,
                onImported = onImported,
                onBack = { page = KnowledgeWorkspacePage.Browse(current.workspace, current.directory) },
            )
        }
    }

    renaming?.let { target ->
        var error by remember(target.id) { mutableStateOf<String?>(null) }
        WorkspaceNameDialog(
            title = stringResource(R.string.workspace_kb_rename),
            label = stringResource(R.string.workspace_kb_display_name),
            confirmLabel = stringResource(R.string.common_btn_confirm),
            initialValue = target.displayName,
            error = error,
            onValueChange = { error = null },
            onDismiss = { renaming = null },
            onConfirm = { name ->
                scope.launch {
                    try {
                        library.rename(target.id, name)
                        renaming = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = operationFailed
                    }
                }
            },
        )
    }
    deleting?.let { target ->
        ConfirmDialog(
            show = true,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch {
                    notice = try {
                        when (val result = library.delete(target.id)) {
                            WorkspaceDeleteResult.Deleted, WorkspaceDeleteResult.Missing -> null
                            is WorkspaceDeleteResult.Referenced -> resources.getString(
                                R.string.workspace_kb_delete_referenced, result.sessions, result.agents,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        operationFailed
                    }
                }
            },
            title = stringResource(R.string.workspace_kb_delete_title),
            description = stringResource(R.string.workspace_kb_delete_message, target.displayName),
            confirmLabel = stringResource(R.string.workspace_kb_delete),
        )
    }
}

/** 已登记工作区列表；重命名与删除只改登记，删除前明确提示不会删除目录内容。 */
@Composable
fun KnowledgeWorkspaceListContent(
    workspaces: List<WorkspaceEntity>,
    modifier: Modifier = Modifier,
    notice: String? = null,
    onOpen: (WorkspaceEntity) -> Unit = {},
    onRename: (WorkspaceEntity) -> Unit = {},
    onDelete: (WorkspaceEntity) -> Unit = {},
) = Column(modifier.fillMaxSize()) {
    Text(
        stringResource(R.string.workspace_kb_desc),
        style = MaterialTheme.typography.bodySmall.forReading(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    notice?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall.forReading(),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = NexaraSpacing.Small).testTag("knowledge_workspaces_notice"),
        )
    }
    if (workspaces.isEmpty()) {
        Text(
            stringResource(R.string.workspace_kb_empty),
            style = MaterialTheme.typography.bodyMedium.forReading(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = NexaraSpacing.Large),
        )
    } else LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = NexaraSpacing.Small).testTag("knowledge_workspaces_list"),
        contentPadding = PaddingValues(bottom = NexaraSpacing.Large),
    ) {
        items(workspaces, key = { it.id }) { workspace ->
            var menu by remember(workspace.id) { mutableStateOf(false) }
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(workspace) }
                    .testTag("knowledge_workspace_${workspace.id}"),
                headlineContent = { Text(workspace.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    NexaraIdentifierText(
                        workspaceKindLabel(workspace.kind) + " · " + compactSharedPath(workspace.location),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { WorkspaceKindIcon(workspace.kind) },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("knowledge_workspace_menu_${workspace.id}")) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.workspace_kb_more, workspace.displayName))
                        }
                        NexaraDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_kb_rename)) },
                                leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                                onClick = { menu = false; onRename(workspace) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_kb_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onDelete(workspace) },
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun KnowledgeWorkspaceBrowser(
    library: WorkspaceLibrary,
    workspace: WorkspaceEntity,
    path: String,
    onNavigate: (String) -> Unit,
    onBackToList: () -> Unit,
    onOpenFile: (ExternalFolderEntry) -> Unit,
) {
    var entries by remember { mutableStateOf<List<ExternalFolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(workspace.id, path, reload) {
        loading = true
        error = null
        entries = emptyList()
        try {
            entries = library.list(workspace.id, path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: failure.javaClass.simpleName
        } finally {
            loading = false
        }
    }
    KnowledgeWorkspaceBrowserContent(
        workspace = workspace,
        path = path,
        entries = entries,
        loading = loading,
        error = error,
        onNavigate = onNavigate,
        onBackToList = onBackToList,
        onOpenFile = onOpenFile,
        onRetry = { reload++ },
    )
}

/** 工作区目录的只读浏览：面包屑以工作区名为根，目录逐级进入，文件打开预览。 */
@Composable
fun KnowledgeWorkspaceBrowserContent(
    workspace: WorkspaceEntity,
    path: String,
    entries: List<ExternalFolderEntry>,
    loading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
    onBackToList: () -> Unit = {},
    onOpenFile: (ExternalFolderEntry) -> Unit = {},
    onRetry: () -> Unit = {},
) = Column(modifier.fillMaxSize()) {
    val context = LocalContext.current
    val segments = path.split('/').filter(String::isNotEmpty)
    val crumbs = segments.mapIndexed { index, name -> FolderCrumb("/" + segments.take(index + 1).joinToString("/"), name) }
    TextButton(onClick = onBackToList, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp))
        Text(stringResource(R.string.workspace_kb_back), modifier = Modifier.padding(start = NexaraSpacing.XSmall))
    }
    FolderBreadcrumbsBar(
        folderStack = crumbs,
        rootTitle = workspace.displayName,
        onNavigateBack = { onNavigate(crumbs.dropLast(1).lastOrNull()?.id ?: "/") },
        onNavigateToCrumb = { index -> onNavigate(if (index <= 0) "/" else crumbs[index - 1].id) },
        backButtonTag = "knowledge_workspace_back",
    )
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    when {
        error != null -> Column(Modifier.fillMaxWidth().padding(vertical = NexaraSpacing.Medium)) {
            Text(
                stringResource(R.string.workspace_unavailable_reason, error),
                style = MaterialTheme.typography.bodyMedium.forReading(),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.common_retry))
            }
        }
        !loading && entries.isEmpty() -> Text(
            stringResource(R.string.workspace_kb_folder_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = NexaraSpacing.Medium),
        )
        else -> LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("knowledge_workspace_browser"),
            contentPadding = PaddingValues(bottom = NexaraSpacing.Large),
        ) {
            items(entries, key = { it.path }) { entry ->
                ListItem(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        if (entry.isDirectory) onNavigate(entry.path) else onOpenFile(entry)
                    },
                    headlineContent = { NexaraIdentifierText(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
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
private fun KnowledgeWorkspaceFile(
    library: WorkspaceLibrary,
    workspace: WorkspaceEntity,
    entry: ExternalFolderEntry,
    knowledgeRootUuid: String?,
    onImported: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var preview by remember(entry.path) { mutableStateOf<ExternalTextPreview>(ExternalTextPreview.Loading) }
    var previewError by remember(entry.path) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember(entry.path) { mutableStateOf<KnowledgeImportNotice?>(null) }
    LaunchedEffect(workspace.id, entry.path) {
        preview = if (entry.size > TEXT_PREVIEW_BYTES) ExternalTextPreview.TooLarge else try {
            val bytes = library.read(workspace.id, entry.path, TEXT_PREVIEW_BYTES.toLong())
            withContext(Dispatchers.Default) { decodeExternalTextPreview(bytes, TEXT_PREVIEW_BYTES) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            previewError = failure.message ?: failure.javaClass.simpleName
            ExternalTextPreview.Failed
        }
    }
    KnowledgeWorkspaceFileContent(
        entry = entry,
        preview = preview,
        previewError = previewError,
        busy = busy,
        notice = notice,
        canImport = knowledgeRootUuid != null,
        onBack = onBack,
        onImport = {
            val root = knowledgeRootUuid ?: return@KnowledgeWorkspaceFileContent
            busy = true
            notice = null
            scope.launch {
                notice = try {
                    val uuid = library.importToKnowledge(workspace.id, entry.path, root)
                    onImported(uuid)
                    KnowledgeImportNotice.Imported
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    KnowledgeImportNotice.Failed(failure.message ?: failure.javaClass.simpleName)
                } finally {
                    busy = false
                }
            }
        },
    )
}

sealed interface KnowledgeImportNotice {
    data object Imported : KnowledgeImportNotice
    data class Failed(val reason: String) : KnowledgeImportNotice
}

/** 文本文件预览与“加入知识库”；加入只复制当前内容，不索引所在目录。 */
@Composable
internal fun KnowledgeWorkspaceFileContent(
    entry: ExternalFolderEntry,
    preview: ExternalTextPreview,
    modifier: Modifier = Modifier,
    previewError: String? = null,
    busy: Boolean = false,
    notice: KnowledgeImportNotice? = null,
    canImport: Boolean = true,
    onBack: () -> Unit = {},
    onImport: () -> Unit = {},
) = Column(modifier.fillMaxSize()) {
    val context = LocalContext.current
    TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp))
        Text(stringResource(R.string.workspace_kb_back), modifier = Modifier.padding(start = NexaraSpacing.XSmall))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall)) {
        NexaraIdentifierText(entry.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        NexaraIdentifierText(
            entry.path + " · " + Formatter.formatShortFileSize(context, entry.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(
        onClick = onImport,
        enabled = canImport && !busy,
        modifier = Modifier.fillMaxWidth().padding(top = NexaraSpacing.Medium)
            .heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("knowledge_workspace_import"),
    ) { Text(stringResource(R.string.workspace_kb_import)) }
    Text(
        stringResource(R.string.workspace_kb_import_info),
        style = MaterialTheme.typography.bodySmall.forReading(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = NexaraSpacing.XSmall),
    )
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = NexaraSpacing.Small))
    when (notice) {
        KnowledgeImportNotice.Imported -> Text(
            stringResource(R.string.workspace_kb_imported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = NexaraSpacing.Small).testTag("knowledge_workspace_import_notice"),
        )
        is KnowledgeImportNotice.Failed -> Text(
            stringResource(R.string.workspace_kb_import_failed, notice.reason),
            style = MaterialTheme.typography.bodySmall.forReading(),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = NexaraSpacing.Small).testTag("knowledge_workspace_import_notice"),
        )
        null -> Unit
    }
    Box(
        Modifier.weight(1f).fillMaxWidth().padding(top = NexaraSpacing.Medium)
            .bettboxListGroup(),
    ) {
        when (preview) {
            ExternalTextPreview.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            ExternalTextPreview.Failed -> PreviewMessage(stringResource(R.string.workspace_kb_preview_failed, previewError.orEmpty()), error = true)
            ExternalTextPreview.Binary -> PreviewMessage(stringResource(R.string.workspace_kb_preview_binary))
            ExternalTextPreview.TooLarge -> PreviewMessage(stringResource(R.string.workspace_kb_preview_too_large))
            is ExternalTextPreview.Text -> SelectionContainer(Modifier.fillMaxSize()) {
                Text(
                    preview.content,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NexaraSpacing.Medium)
                        .testTag("knowledge_workspace_preview"),
                    style = NexaraTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PreviewMessage(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.forReading(),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(NexaraSpacing.Medium),
    )
}
