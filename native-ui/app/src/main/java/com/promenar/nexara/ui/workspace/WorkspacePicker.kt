package com.promenar.nexara.ui.workspace

import com.promenar.nexara.ui.common.NexaraSheetHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.storage.RealPathMountSupport
import com.promenar.nexara.data.workspace.CreateFolderResult
import com.promenar.nexara.data.workspace.FolderNameError
import com.promenar.nexara.data.workspace.WorkspaceDirectoryBrowser
import com.promenar.nexara.data.workspace.WorkspaceFolder
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.ui.common.FolderBreadcrumbsBar
import com.promenar.nexara.ui.common.FolderCrumb
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.theme.NexaraMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.hierarchicalTransition
import com.promenar.nexara.ui.theme.forReading
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** 选择器当前所在层级：起始页、项目根或某个共享卷内的目录。 */
sealed interface WorkspacePickerPage {
    data object Home : WorkspacePickerPage
    data object Projects : WorkspacePickerPage
    data class Shared(val volume: File, val path: File) : WorkspacePickerPage
}

/** 页面层级：首页为 0，项目与存储卷根为 1，共享目录按相对卷根的段数递增。 */
internal val WorkspacePickerPage.depth: Int
    get() = when (this) {
        WorkspacePickerPage.Home -> 0
        WorkspacePickerPage.Projects -> 1
        is WorkspacePickerPage.Shared -> 1 + path.path.removePrefix(volume.path).split('/').count(String::isNotEmpty)
    }

data class WorkspacePickerUiState(
    val page: WorkspacePickerPage = WorkspacePickerPage.Home,
    val registered: List<WorkspaceEntity> = emptyList(),
    val currentWorkspaceId: String? = null,
    val sharedSupported: Boolean = false,
    val sharedPermission: Boolean = false,
    val sharedRoots: List<File> = emptyList(),
    val folders: List<WorkspaceFolder> = emptyList(),
    val loading: Boolean = false,
    val loadError: String? = null,
    val selectedProject: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** 选定后由 [WorkspaceDirectoryBrowser] 登记；共享卷之下的路径段转成面包屑，根级“位置”回到起始页。 */
internal fun WorkspacePickerPage.crumbs(volumeLabel: (File) -> String, projectsLabel: String): List<FolderCrumb> = when (this) {
    WorkspacePickerPage.Home -> emptyList()
    WorkspacePickerPage.Projects -> listOf(FolderCrumb("projects", projectsLabel))
    is WorkspacePickerPage.Shared -> {
        val relative = volume.toPath().relativize(path.toPath()).toString()
        val segments = relative.split('/').filter(String::isNotEmpty)
        listOf(FolderCrumb(volume.path, volumeLabel(volume))) + segments.mapIndexed { index, name ->
            FolderCrumb(File(volume, segments.take(index + 1).joinToString("/")).path, name)
        }
    }
}

/**
 * 深层目录只显示首级、省略号与当前目录，保证当前所在目录在窄屏上可见；
 * 省略号指向当前目录的上一级，其余层级可经返回按钮逐级回到。
 */
internal fun List<FolderCrumb>.collapsedForDisplay(): List<FolderCrumb> =
    if (size <= 3) this else listOf(first(), this[size - 2].copy(name = "…"), last())

/** 点击 id 为 [crumbId] 的面包屑（null 为根级“位置”）后所在的页面。 */
internal fun WorkspacePickerPage.navigateToCrumb(crumbId: String?): WorkspacePickerPage = when {
    crumbId == null || this == WorkspacePickerPage.Home -> WorkspacePickerPage.Home
    this is WorkspacePickerPage.Shared -> {
        val target = File(crumbId)
        if (target.toPath().startsWith(volume.toPath())) copy(path = target) else this
    }
    else -> this
}

internal fun WorkspacePickerPage.parent(): WorkspacePickerPage = when (this) {
    WorkspacePickerPage.Home, WorkspacePickerPage.Projects -> WorkspacePickerPage.Home
    is WorkspacePickerPage.Shared -> if (path == volume) WorkspacePickerPage.Home else copy(path = path.parentFile ?: volume)
}

@Composable
internal fun sharedVolumeLabel(roots: List<File>, volume: File): String =
    if (roots.indexOf(volume) <= 0) stringResource(R.string.workspace_picker_shared_volume)
    else stringResource(R.string.workspace_picker_shared_volume_named, volume.name)

/**
 * 可复用的工作区选择器：列出已登记的工作区，并可逐级浏览 Nexara 项目文件夹与（仅侧载渠道的）共享存储卷。
 * 选定目录后登记并回调 [onPicked]；未授权时共享存储一栏显示授权引导。
 */
@Composable
fun WorkspacePickerSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onPicked: (WorkspaceEntity) -> Unit,
    currentWorkspaceId: String? = null,
) {
    if (!show) return
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as? NexaraApplication ?: return
    val browser = app.workspaceDirectoryBrowser
    val registeredFlow = remember(app) { runCatching { app.database.workspaceDao().observeAll() }.getOrElse { flowOf(emptyList()) } }
    val registered by registeredFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var page by remember { mutableStateOf<WorkspacePickerPage>(WorkspacePickerPage.Home) }
    var permission by remember { mutableStateOf(browser.hasSharedPermission()) }
    var roots by remember { mutableStateOf(browser.sharedRoots()) }
    var folders by remember { mutableStateOf<List<WorkspaceFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<FolderNameError?>(null) }

    val refreshPermission by rememberUpdatedState {
        permission = browser.hasSharedPermission()
        roots = browser.sharedRoots()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshPermission() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(page, reload, permission) {
        folders = emptyList()
        loadError = null
        val current = page
        if (current == WorkspacePickerPage.Home) return@LaunchedEffect
        loading = true
        try {
            folders = when (current) {
                WorkspacePickerPage.Projects -> browser.listProjects()
                is WorkspacePickerPage.Shared -> browser.listShared(current.path)
                WorkspacePickerPage.Home -> emptyList()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = failure.message ?: failure.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    fun register(action: suspend () -> WorkspaceEntity) {
        busy = true
        error = null
        scope.launch {
            try {
                onPicked(action())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = (failure as? ExternalFolderException)?.message ?: failure.message ?: failure.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    val state = WorkspacePickerUiState(
        page = page, registered = registered, currentWorkspaceId = currentWorkspaceId,
        sharedSupported = browser.sharedSupported, sharedPermission = permission, sharedRoots = roots,
        folders = folders, loading = loading, loadError = loadError, selectedProject = selectedProject,
        busy = busy, error = error,
    )

    NexaraBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.workspace_picker_title),
        height = NexaraSheetHeight.Full,
        dismissEnabled = !busy,
        dragGesturesEnabled = false,
    ) {
        WorkspacePickerContent(
            state = state,
            onNavigate = { next ->
                page = next
                selectedProject = null
                error = null
            },
            onPickRegistered = onPicked,
            onSelectProject = { selectedProject = it },
            onCreate = {
                createError = null
                creating = true
            },
            onUseFolder = {
                when (val current = page) {
                    WorkspacePickerPage.Projects -> selectedProject?.let { name -> register { browser.registerProject(name) } }
                    is WorkspacePickerPage.Shared -> register { browser.registerShared(current.path) }
                    WorkspacePickerPage.Home -> Unit
                }
            },
            onGrantPermission = {
                try {
                    RealPathMountSupport.permissionIntent(context)?.let(context::startActivity)
                } catch (_: Exception) {
                    error = resources.getString(R.string.mount_operation_error)
                }
            },
            onRetry = { reload++ },
        )
    }

    if (creating) {
        val project = page == WorkspacePickerPage.Projects
        WorkspaceNameDialog(
            title = stringResource(if (project) R.string.workspace_picker_new_project else R.string.workspace_picker_new_folder),
            label = stringResource(if (project) R.string.workspace_picker_project_name else R.string.workspace_picker_folder_name),
            confirmLabel = stringResource(R.string.workspace_picker_create),
            hint = if (project) stringResource(R.string.workspace_picker_project_name_hint) else null,
            error = createError?.let { folderNameErrorText(it, project) },
            busy = busy,
            onValueChange = { createError = null },
            onDismiss = { creating = false },
            onConfirm = { name ->
                val current = page
                busy = true
                scope.launch {
                    try {
                        val result = when (current) {
                            WorkspacePickerPage.Projects -> browser.createProject(name)
                            is WorkspacePickerPage.Shared -> browser.createSharedFolder(current.path, name)
                            WorkspacePickerPage.Home -> null
                        }
                        when (result) {
                            is CreateFolderResult.Created -> {
                                creating = false
                                reload++
                                if (current == WorkspacePickerPage.Projects) selectedProject = result.folder.name
                                else if (current is WorkspacePickerPage.Shared) page = current.copy(path = result.folder.path)
                            }
                            is CreateFolderResult.Rejected -> createError = result.error
                            null -> creating = false
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        creating = false
                        error = failure.message ?: failure.javaClass.simpleName
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
}

/** 选择器的无状态内容，供截图预览与 [WorkspacePickerSheet] 共用。 */
@Composable
fun WorkspacePickerContent(
    state: WorkspacePickerUiState,
    modifier: Modifier = Modifier,
    onNavigate: (WorkspacePickerPage) -> Unit = {},
    onPickRegistered: (WorkspaceEntity) -> Unit = {},
    onSelectProject: (String) -> Unit = {},
    onCreate: () -> Unit = {},
    onUseFolder: () -> Unit = {},
    onGrantPermission: () -> Unit = {},
    onRetry: () -> Unit = {},
) = Column(modifier.fillMaxSize()) {
    val page = state.page
    val projectsLabel = stringResource(R.string.workspace_picker_projects)
    val volumeLabels = state.sharedRoots.associateWith { sharedVolumeLabel(state.sharedRoots, it) }
    if (page != WorkspacePickerPage.Home) {
        val crumbs = page.crumbs({ volumeLabels[it] ?: it.name }, projectsLabel).collapsedForDisplay()
        FolderBreadcrumbsBar(
            folderStack = crumbs,
            rootTitle = stringResource(R.string.workspace_picker_root),
            onNavigateBack = { onNavigate(page.parent()) },
            onNavigateToCrumb = { index -> onNavigate(page.navigateToCrumb(crumbs.getOrNull(index - 1)?.id)) },
            modifier = Modifier.testTag("workspace_picker_breadcrumbs"),
            backButtonTag = "workspace_picker_back",
        )
        TextButton(
            onClick = onCreate,
            enabled = !state.busy && !state.loading && state.loadError == null,
            modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("workspace_picker_create"),
        ) {
            Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(NexaraSpacing.Small))
            Text(stringResource(if (page == WorkspacePickerPage.Projects) R.string.workspace_picker_new_project else R.string.workspace_picker_new_folder))
        }
    }
    AnimatedVisibility(
        visible = page != WorkspacePickerPage.Home && (state.loading || state.busy),
        enter = NexaraMotion.expandIn,
        exit = NexaraMotion.shrinkOut,
    ) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    // 进入子目录与返回上级按层级方向横向切换；离场页保留自己的列表快照。
    AnimatedContent(
        targetState = state,
        contentKey = { it.page },
        transitionSpec = { hierarchicalTransition { it.page.depth } },
        modifier = Modifier.weight(1f).fillMaxWidth(),
        label = "workspace_picker_page",
    ) { state ->
        val page = state.page
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("workspace_picker_list"),
            contentPadding = PaddingValues(bottom = NexaraSpacing.Large),
        ) {
            when (page) {
                WorkspacePickerPage.Home -> homeItems(state, volumeLabels, onNavigate, onPickRegistered, onGrantPermission)
                WorkspacePickerPage.Projects -> folderItems(state, onRetry, emptyText = R.string.workspace_picker_projects_empty) { folder ->
                    NexaraSettingsItem(
                        title = folder.name,
                        onClick = { onSelectProject(folder.name) },
                        role = Role.RadioButton,
                        selected = state.selectedProject == folder.name,
                        horizontalPadding = 0.dp,
                        leadingContent = { Icon(Icons.Rounded.Work, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = if (state.selectedProject == folder.name) ({
                            Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                        }) else null,
                        modifier = Modifier.testTag("workspace_picker_project_${folder.name}"),
                    )
                }
                is WorkspacePickerPage.Shared -> folderItems(state, onRetry, emptyText = R.string.workspace_picker_folder_empty) { folder ->
                    NexaraSettingsItem(
                        title = folder.name,
                        onClick = { onNavigate(page.copy(path = folder.path)) },
                        showChevron = true,
                        horizontalPadding = 0.dp,
                        leadingContent = { Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
        }
    }
    if (page != WorkspacePickerPage.Home) {
        val target = when (page) {
            WorkspacePickerPage.Projects -> state.selectedProject?.let { stringResource(R.string.workspace_location_project, it) }
            is WorkspacePickerPage.Shared -> page.path.path
            WorkspacePickerPage.Home -> null
        }
        Column(Modifier.fillMaxWidth().padding(top = NexaraSpacing.Small), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall)) {
            state.error?.let {
                Text(
                    stringResource(R.string.workspace_picker_register_failed, it),
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = target?.let { stringResource(R.string.workspace_picker_target, it) }
                    ?: stringResource(R.string.workspace_picker_select_project),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Button(
                onClick = onUseFolder,
                enabled = target != null && !state.busy && state.loadError == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("workspace_picker_use"),
            ) { Text(stringResource(R.string.workspace_picker_use_folder)) }
        }
    }
}

private fun LazyListScope.homeItems(
    state: WorkspacePickerUiState,
    volumeLabels: Map<File, String>,
    onNavigate: (WorkspacePickerPage) -> Unit,
    onPickRegistered: (WorkspaceEntity) -> Unit,
    onGrantPermission: () -> Unit,
) {
    if (state.registered.isNotEmpty()) {
        item(key = "registered_header") { SettingsSectionHeader(stringResource(R.string.workspace_picker_registered)) }
        items(state.registered, key = { "registered:" + it.id }) { workspace ->
            val selected = workspace.id == state.currentWorkspaceId
            NexaraSettingsItem(
                title = workspace.displayName,
                subtitle = workspaceLocationText(workspace.kind, workspace.location),
                subtitleIsIdentifier = true,
                onClick = { onPickRegistered(workspace) },
                enabled = !state.busy,
                horizontalPadding = 0.dp,
                selected = selected,
                leadingContent = { WorkspaceKindIcon(workspace.kind) },
                trailingContent = if (selected) ({
                    Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                }) else null,
                modifier = Modifier.testTag("workspace_picker_registered_${workspace.id}"),
            )
        }
    }
    item(key = "locations_header") { SettingsSectionHeader(stringResource(R.string.workspace_picker_locations)) }
    item(key = "projects") {
        NexaraSettingsItem(
            title = stringResource(R.string.workspace_picker_projects),
            subtitle = stringResource(R.string.workspace_picker_projects_desc),
            onClick = { onNavigate(WorkspacePickerPage.Projects) },
            showChevron = true,
            horizontalPadding = 0.dp,
            leadingContent = { WorkspaceKindIcon(WorkspaceEntity.KIND_PROJECT) },
            modifier = Modifier.testTag("workspace_picker_projects"),
        )
    }
    if (state.sharedSupported) {
        if (!state.sharedPermission) {
            item(key = "shared_permission") { SharedPermissionCard(onGrantPermission) }
        } else {
            items(state.sharedRoots, key = { "volume:" + it.path }) { volume ->
                NexaraSettingsItem(
                    title = volumeLabels[volume] ?: volume.name,
                    subtitle = volume.path,
                    subtitleIsIdentifier = true,
                    onClick = { onNavigate(WorkspacePickerPage.Shared(volume, volume)) },
                    showChevron = true,
                    horizontalPadding = 0.dp,
                    leadingContent = { Icon(Icons.Rounded.SdStorage, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.testTag("workspace_picker_volume"),
                )
            }
        }
    }
}

private fun LazyListScope.folderItems(
    state: WorkspacePickerUiState,
    onRetry: () -> Unit,
    emptyText: Int,
    row: @Composable (WorkspaceFolder) -> Unit,
) {
    val loadError = state.loadError
    when {
        loadError != null -> item(key = "error") {
            Column(Modifier.fillMaxWidth().padding(vertical = NexaraSpacing.Medium)) {
                Text(
                    stringResource(R.string.workspace_picker_load_failed, loadError),
                    style = MaterialTheme.typography.bodyMedium.forReading(),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.common_retry))
                }
            }
        }
        !state.loading && state.folders.isEmpty() -> item(key = "empty") {
            Text(
                stringResource(emptyText),
                style = MaterialTheme.typography.bodyMedium.forReading(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = NexaraSpacing.Medium),
            )
        }
        else -> items(state.folders, key = { it.path.path }) { row(it) }
    }
}

@Composable
private fun SharedPermissionCard(onGrantPermission: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(top = NexaraSpacing.Small).testTag("workspace_picker_shared_permission"),
    ) {
        Column(Modifier.padding(NexaraSpacing.Large), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(NexaraSpacing.Small))
                Text(
                    stringResource(R.string.workspace_picker_shared_volume) + " · " +
                        stringResource(R.string.workspace_picker_shared_permission_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                stringResource(R.string.workspace_picker_shared_permission_desc),
                style = MaterialTheme.typography.bodySmall.forReading(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(NexaraSpacing.XSmall))
            OutlinedButton(
                onClick = onGrantPermission,
                modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("workspace_picker_grant"),
            ) { Text(stringResource(R.string.workspace_picker_grant)) }
        }
    }
}

@Composable
internal fun WorkspaceKindIcon(kind: String?) {
    Icon(
        imageVector = when (kind) {
            WorkspaceEntity.KIND_PROJECT -> Icons.Rounded.Work
            WorkspaceEntity.KIND_SHARED -> Icons.Rounded.FolderShared
            else -> Icons.Rounded.Lock
        },
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}
