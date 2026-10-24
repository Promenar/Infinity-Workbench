package com.promenar.nexara.ui.chat.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.storage.IExternalFolderManager
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.common.FolderBreadcrumbsBar
import com.promenar.nexara.ui.theme.hierarchicalTransition
import androidx.compose.animation.AnimatedContent
import com.promenar.nexara.ui.theme.NexaraMotion
import androidx.compose.animation.AnimatedVisibility
import com.promenar.nexara.ui.common.FolderCrumb
import com.promenar.nexara.ui.common.navigateToCrumb
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import kotlinx.coroutines.flow.collectLatest

/** 搜索命中项与其从工作区根（不含根）到父目录的目录链。 */
internal data class WorkspaceSearchHit(
    val file: FileEntry,
    val parents: List<FolderCrumb>,
) {
    /** 打开命中项时进入的导航栈：目录进入自身，文件进入所在目录。 */
    val navigationStack: List<FolderCrumb>
        get() = if (file.isDirectory) parents + FolderCrumb(file.uuid, file.name) else parents
}

/** 自 [path] 起逐级向上查找第一个存在的目录；到达工作区根仍未找到时返回 null（停在根目录）。 */
internal suspend fun locateFolderStack(
    path: String,
    workspaceRootUuid: String,
    lookup: suspend (String) -> FileEntry?,
): FileEntry? {
    var candidate: String? = com.promenar.nexara.ui.media.normalizeLocatePath(path)
    while (candidate != null && candidate != "/") {
        val entry = lookup(candidate)
        if (entry != null && entry.isDirectory && entry.uuid != workspaceRootUuid) return entry
        candidate = com.promenar.nexara.ui.media.mediaParentPath(candidate)
    }
    return null
}

/** 沿 parentUuid 回溯到工作区根，返回从根下第一级开始的目录链；遇到环或缺失的父节点即停止。 */
internal suspend fun resolveFolderChain(
    file: FileEntry,
    workspaceRootUuid: String,
    lookup: suspend (String) -> FileEntry?,
): List<FolderCrumb> {
    val chain = ArrayDeque<FolderCrumb>()
    val visited = mutableSetOf(file.uuid)
    var parentUuid = file.parentUuid
    while (!parentUuid.isNullOrBlank() && parentUuid != workspaceRootUuid && visited.add(parentUuid)) {
        val parent = lookup(parentUuid) ?: break
        chain.addFirst(FolderCrumb(parent.uuid, parent.name))
        parentUuid = parent.parentUuid
    }
    return chain.toList()
}

/**
 * 会话工作区的穿透式目录浏览：每次只显示一级，点目录进入下一级，面包屑与系统返回回到上级。
 * 搜索时改为整个工作区的扁平结果并标出所在路径，点结果跳转到对应目录。
 */
@Composable
internal fun WorkspaceFolderBrowser(
    workspaceRootUuid: String?,
    workspaceRepo: IWorkspaceRepository,
    sessionId: String?,
    externalFolderManager: IExternalFolderManager?,
    searchQuery: String,
    rootFiles: List<FileEntry>,
    isLoading: Boolean,
    hasLoadError: Boolean,
    onRetryLoad: () -> Unit,
    onClearSearch: () -> Unit,
    initialFolderPath: String? = null,
    onImport: (() -> Unit)? = null,
) {
    var folderStack by rememberSaveable(workspaceRootUuid) { mutableStateOf(listOf<FolderCrumb>()) }
    val atRoot = folderStack.isEmpty()
    val rootTitle = stringResource(R.string.resource_explorer_workspace_root)

    // 媒体不可用占位请求的定位：进入该虚拟目录，不存在时进入最近的已存在上级。
    var consumedLocatePath by rememberSaveable(workspaceRootUuid) { mutableStateOf<String?>(null) }
    LaunchedEffect(workspaceRootUuid, initialFolderPath) {
        val rootUuid = workspaceRootUuid ?: return@LaunchedEffect
        val target = initialFolderPath ?: return@LaunchedEffect
        if (target == consumedLocatePath) return@LaunchedEffect
        consumedLocatePath = target
        folderStack = locateFolderStack(target, rootUuid) { path ->
            runCatching { workspaceRepo.getByMaterializedPath(rootUuid, path) }.getOrNull()
        }?.let { folder ->
            resolveFolderChain(folder, rootUuid) { uuid -> workspaceRepo.getByUuid(rootUuid, uuid) } +
                FolderCrumb(folder.uuid, folder.name)
        }.orEmpty()
    }

    // 点按图片、视频、音频文件时直接打开查看器；条目的虚拟路径即其 materializedPath。
    val openMedia = rememberWorkspaceMediaOpener(sessionId, workspaceRootUuid)
    val onMediaFileClick: ((FileEntry) -> Unit)? = openMedia?.let { open -> { entry -> open(entry.materializedPath) } }

    BackHandler(enabled = !atRoot && searchQuery.isBlank()) {
        folderStack = folderStack.dropLast(1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (searchQuery.isBlank()) {
            // 工作区名称已在顶部工作区栏显示，根部不再重复；进入子文件夹后才出现返回与路径。
            AnimatedVisibility(visible = !atRoot, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
                FolderBreadcrumbsBar(
                    folderStack = folderStack,
                    rootTitle = rootTitle,
                    onNavigateBack = { folderStack = folderStack.dropLast(1) },
                    onNavigateToCrumb = { index -> folderStack = folderStack.navigateToCrumb(index) },
                    modifier = Modifier
                        .padding(horizontal = NexaraSpacing.Small)
                        .testTag(UiTags.RESOURCE_EXPLORER_BREADCRUMBS),
                    backButtonTag = UiTags.RESOURCE_EXPLORER_FOLDER_BACK,
                )
            }
            // 页头保持不动，目录内容按进入或返回的方向横向切换。
            AnimatedContent(
                targetState = folderStack,
                contentKey = { it.lastOrNull()?.id },
                transitionSpec = { hierarchicalTransition { it.size } },
                modifier = Modifier.weight(1f),
                label = "workspace_folder_navigation",
            ) { shownStack ->
                val shownFolderId = shownStack.lastOrNull()?.id ?: workspaceRootUuid
                val shownAtRoot = shownStack.isEmpty()
                key(workspaceRootUuid, shownFolderId) {
                    FilesPanel(
                        workspaceRootUuid = workspaceRootUuid,
                        workspaceRepo = workspaceRepo,
                        sessionId = sessionId,
                        externalFolderManager = externalFolderManager.takeIf { shownAtRoot },
                        searchQuery = "",
                        rootFiles = rootFiles.takeIf { shownAtRoot },
                        isLoading = isLoading && shownAtRoot,
                        hasLoadError = hasLoadError && shownAtRoot,
                        onRetryLoad = onRetryLoad,
                        currentParentUuid = shownFolderId,
                        allowTreeExpansion = false,
                        onFolderClick = { folderId, folderName ->
                            folderStack = shownStack + FolderCrumb(folderId, folderName)
                        },
                        onMediaFileClick = onMediaFileClick,
                        onImport = onImport.takeIf { shownAtRoot },
                    )
                }
            }
        } else {
            WorkspaceSearchResults(
                workspaceRootUuid = workspaceRootUuid,
                workspaceRepo = workspaceRepo,
                query = searchQuery,
                rootTitle = rootTitle,
                onOpen = { hit ->
                    folderStack = hit.navigationStack
                    onClearSearch()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WorkspaceSearchResults(
    workspaceRootUuid: String?,
    workspaceRepo: IWorkspaceRepository,
    query: String,
    rootTitle: String,
    onOpen: (WorkspaceSearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hits by produceState<List<WorkspaceSearchHit>?>(null, workspaceRootUuid, workspaceRepo, query) {
        val root = workspaceRootUuid
        if (root == null) {
            value = emptyList()
            return@produceState
        }
        workspaceRepo.searchByName(root, query).collectLatest { matches ->
            value = matches.map { match ->
                WorkspaceSearchHit(match, resolveFolderChain(match, root) { workspaceRepo.getByUuid(root, it) })
            }
        }
    }
    val current = hits
    when {
        current == null -> LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = NexaraSpacing.Large, vertical = NexaraSpacing.Medium),
        )
        current.isEmpty() -> Box(
            modifier = modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = stringResource(R.string.common_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .testTag(UiTags.RESOURCE_EXPLORER_SEARCH_RESULTS),
        ) {
            items(current, key = { it.file.uuid }) { hit ->
                WorkspaceSearchHitRow(hit, rootTitle, onClick = { onOpen(hit) })
            }
        }
    }
}

@Composable
private fun WorkspaceSearchHitRow(
    hit: WorkspaceSearchHit,
    rootTitle: String,
    onClick: () -> Unit,
) {
    val path = (listOf(rootTitle) + hit.parents.map(FolderCrumb::name)).joinToString(" › ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = NexaraSpacing.Large, vertical = NexaraSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        Icon(
            imageVector = if (hit.file.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
            contentDescription = null,
            tint = if (hit.file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hit.file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
