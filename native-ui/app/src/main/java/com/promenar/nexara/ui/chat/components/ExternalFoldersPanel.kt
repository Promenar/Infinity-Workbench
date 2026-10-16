package com.promenar.nexara.ui.chat.components

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.nexara.BuildConfig
import com.promenar.nexara.R
import com.promenar.nexara.data.storage.RealPathMountSupport
import com.promenar.nexara.distribution.DistributionCapabilities
import com.promenar.nexara.domain.storage.ExternalFolderEntry
import com.promenar.nexara.domain.storage.ExternalMount
import com.promenar.nexara.domain.storage.ExternalRecycleItem
import com.promenar.nexara.domain.storage.IExternalFolderManager
import com.promenar.nexara.ui.common.NexaraIdentifierText
import com.promenar.nexara.ui.common.NexaraMetaLine
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.forInput
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 挂载状态按当前会话隔离；文件浏览在独立有界弹窗内承载。 */
@Composable
internal fun ExternalFoldersPanel(
    sessionId: String?,
    workspaceRootUuid: String?,
    manager: IExternalFolderManager,
    onReindex: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    key(sessionId) {
        ExternalFoldersPanelContent(sessionId, workspaceRootUuid, manager, onReindex, modifier)
    }
}

@Composable
private fun ExternalFoldersPanelContent(
    sessionId: String?,
    workspaceRootUuid: String?,
    manager: IExternalFolderManager,
    onReindex: ((String) -> Unit)?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val sessionKey = sessionId.orEmpty()
    var mounts by remember { mutableStateOf<List<ExternalMount>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var managerOpen by rememberSaveable { mutableStateOf(false) }
    var formSource by rememberSaveable { mutableStateOf<ExternalMountSource?>(null) }
    var alias by rememberSaveable { mutableStateOf("") }
    var sourcePath by rememberSaveable { mutableStateOf("") }
    var readOnly by rememberSaveable { mutableStateOf(true) }
    var sessionOnly by rememberSaveable { mutableStateOf(!sessionId.isNullOrBlank()) }
    var pendingAlias by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMountId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingReadOnly by rememberSaveable { mutableStateOf(true) }
    var pendingSessionOnly by rememberSaveable { mutableStateOf(false) }
    var pickerActive by rememberSaveable { mutableStateOf(false) }
    var browseMountId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeMountId by rememberSaveable { mutableStateOf<String?>(null) }
    var recycleOpen by rememberSaveable { mutableStateOf(false) }
    var realPathPermission by remember { mutableStateOf(false) }
    val sources = availableExternalMountSources(
        BuildConfig.ALL_FILES_ACCESS_AVAILABLE,
        DistributionCapabilities.allFilesAccessAvailable,
    )

    val perform: (suspend () -> Unit) -> Unit = { action ->
        if (!busy) {
            busy = true
            notice = null
            scope.launch {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    notice = R.string.mount_operation_error
                } finally {
                    busy = false
                }
            }
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pickerActive = false
        val requestedAlias = pendingAlias
        val requestedMountId = pendingMountId
        val requestedReadOnly = pendingReadOnly
        val requestedScope = externalMountSessionScope(sessionId, pendingSessionOnly)
        pendingAlias = null
        pendingMountId = null
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val resultIntent = result.data ?: return@rememberLauncherForActivityResult
        val treeUri = resultIntent.data ?: return@rememberLauncherForActivityResult
        if (requestedAlias == null && requestedMountId == null) return@rememberLauncherForActivityResult
        perform {
            if (requestedMountId != null) {
                manager.reauthorize(requestedMountId, treeUri.toString(), resultIntent.flags)
            } else if (requestedAlias != null) {
                manager.addSaf(
                    alias = requestedAlias,
                    sessionId = requestedScope,
                    treeUri = treeUri.toString(),
                    grantFlags = resultIntent.flags,
                    readOnly = requestedReadOnly,
                )
            }
            formSource = null
            managerOpen = true
        }
    }

    val launchTreePicker: (String?, String?) -> Unit = { requestedAlias, requestedMountId ->
        pendingAlias = requestedAlias
        pendingMountId = requestedMountId
        pendingReadOnly = readOnly
        pendingSessionOnly = sessionOnly
        pickerActive = true
        notice = null
        try {
            treePicker.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                ),
            )
        } catch (_: Exception) {
            pickerActive = false
            pendingAlias = null
            pendingMountId = null
            notice = R.string.mount_operation_error
        }
    }

    val refresh: () -> Unit = {
        scope.launch {
            if (BuildConfig.ALL_FILES_ACCESS_AVAILABLE && DistributionCapabilities.allFilesAccessAvailable) {
                realPathPermission = RealPathMountSupport.hasPermission(context)
            }
            try {
                manager.refresh(sessionKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = R.string.mount_load_error
            }
        }
    }
    val currentRefresh by rememberUpdatedState(refresh)
    DisposableEffect(lifecycleOwner, manager, sessionKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(manager, sessionKey) {
        currentRefresh()
        try {
            manager.observeMounts(sessionKey).collect {
                mounts = visibleExternalMounts(it, sessionId)
                loading = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loading = false
            notice = R.string.mount_load_error
        }
    }

    val openAccessSettings: (() -> Unit)? =
        if (BuildConfig.ALL_FILES_ACCESS_AVAILABLE && DistributionCapabilities.allFilesAccessAvailable) {
            {
                try {
                    val intent = RealPathMountSupport.permissionIntent(context)
                    if (intent == null) notice = R.string.mount_operation_error
                    else context.startActivity(intent)
                } catch (_: Exception) {
                    notice = R.string.mount_operation_error
                }
            }
        } else null

    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .testTag("external_folders_entry")
            .clickable(role = Role.Button) { managerOpen = true },
        headlineContent = { Text(stringResource(R.string.mount_title)) },
        supportingContent = {
            val label = notice ?: if (mounts.isEmpty()) R.string.mount_empty else R.string.mount_count
            Text(
                text = if (label == R.string.mount_count) stringResource(label, mounts.size) else stringResource(label),
                color = if (notice != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
        trailingContent = {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            else Icon(Icons.Rounded.MoreVert, contentDescription = null)
        },
    )

    if (managerOpen) {
        ExternalMountManagerDialog(
            mounts = mounts,
            sources = sources,
            loading = loading,
            busy = busy || pickerActive,
            notice = notice,
            onDismiss = { managerOpen = false },
            onRefresh = refresh,
            onAdd = { source ->
                var candidate = "external"
                var suffix = 2
                while (mounts.any { it.alias == candidate }) candidate = "external" + suffix++
                alias = candidate
                sourcePath = ""
                readOnly = true
                sessionOnly = !sessionId.isNullOrBlank()
                notice = null
                formSource = source
                managerOpen = false
            },
            onBrowse = {
                browseMountId = it.id
                managerOpen = false
            },
            onReauthorize = { launchTreePicker(null, it.id) },
            onRemove = { removeMountId = it.id },
            onGrantRealPath = openAccessSettings,
            onRecycle = {
                recycleOpen = true
                managerOpen = false
            },
        )
    }

    formSource?.takeIf { it in sources }?.let { source ->
        ExternalMountFormDialog(
            source = source,
            alias = alias,
            sourcePath = sourcePath,
            readOnly = readOnly,
            sessionOnly = sessionOnly,
            hasCurrentSession = !sessionId.isNullOrBlank(),
            realPathPermission = realPathPermission,
            busy = busy || pickerActive,
            notice = notice,
            onAliasChanged = { alias = it },
            onPathChanged = { sourcePath = it },
            onReadOnlyChanged = { readOnly = it },
            onSessionOnlyChanged = { sessionOnly = it },
            onGrantAccess = openAccessSettings,
            onDismiss = {
                formSource = null
                managerOpen = true
            },
            onConfirm = {
                if (source == ExternalMountSource.Saf) {
                    launchTreePicker(alias.trim(), null)
                } else if (BuildConfig.ALL_FILES_ACCESS_AVAILABLE && DistributionCapabilities.allFilesAccessAvailable) {
                    perform {
                        manager.addRealPath(
                            alias = alias.trim(),
                            sessionId = externalMountSessionScope(sessionId, sessionOnly),
                            source = sourcePath.trim(),
                            readOnly = readOnly,
                        )
                        formSource = null
                        managerOpen = true
                    }
                }
            },
        )
    }

    removeMountId?.let { mountId ->
        AlertDialog(
            onDismissRequest = { if (!busy) removeMountId = null },
            title = { Text(stringResource(R.string.mount_remove_title)) },
            text = { Text(stringResource(R.string.mount_remove_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        perform {
                            manager.remove(mountId)
                            removeMountId = null
                        }
                    },
                ) { Text(stringResource(R.string.mount_remove)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { removeMountId = null }) {
                    Text(stringResource(R.string.common_btn_cancel))
                }
            },
        )
    }

    mounts.firstOrNull { it.id == browseMountId }?.let { mount ->
        ExternalMountBrowserDialog(
            mount = mount,
            sessionId = sessionKey,
            workspaceRootUuid = workspaceRootUuid,
            manager = manager,
            onReindex = onReindex,
            onDismiss = {
                browseMountId = null
                managerOpen = true
            },
        )
    }
    if (recycleOpen) {
        ExternalMountRecycleDialog(
            sessionId = sessionKey,
            manager = manager,
            onDismiss = {
                recycleOpen = false
                managerOpen = true
            },
        )
    }
}

@Composable
private fun ExternalMountManagerDialog(
    mounts: List<ExternalMount>,
    sources: List<ExternalMountSource>,
    loading: Boolean,
    busy: Boolean,
    notice: Int?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: (ExternalMountSource) -> Unit,
    onBrowse: (ExternalMount) -> Unit,
    onReauthorize: (ExternalMount) -> Unit,
    onRemove: (ExternalMount) -> Unit,
    onGrantRealPath: (() -> Unit)?,
    onRecycle: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mount_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).testTag("external_mounts_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sources.forEach { source ->
                            TextButton(enabled = !busy, onClick = { onAdd(source) }) {
                                Text(stringResource(
                                    if (source == ExternalMountSource.Saf) R.string.mount_add_saf
                                    else R.string.mount_add_real_path,
                                ))
                            }
                        }
                    }
                    Text(stringResource(R.string.mount_system_limits), style = NexaraTypography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.mount_termux_hint), style = NexaraTypography.bodySmall)
                }
                if (notice != null) item { MountNotice(notice) }
                if (loading || busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                if (!loading && mounts.isEmpty()) item { Text(stringResource(R.string.mount_empty)) }
                items(mounts, key = { it.id }) { mount ->
                    ExternalMountRow(mount, busy, onBrowse, onReauthorize, onRemove, onGrantRealPath)
                }
                item {
                    HorizontalDivider()
                    TextButton(onClick = onRecycle, enabled = !busy) {
                        Text(stringResource(R.string.mount_recycle_title))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mount_close)) }
        },
        dismissButton = {
            TextButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.mount_refresh)) }
        },
    )
}

@Composable
private fun ExternalMountRow(
    mount: ExternalMount,
    busy: Boolean,
    onBrowse: (ExternalMount) -> Unit,
    onReauthorize: (ExternalMount) -> Unit,
    onRemove: (ExternalMount) -> Unit,
    onGrantRealPath: (() -> Unit)?,
) {
    var menuOpen by remember(mount.id) { mutableStateOf(false) }
    val unavailable = mount.unavailableReason != null
    val unavailableLabel = stringResource(R.string.mount_unavailable)
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (unavailable) stateDescription = unavailableLabel }
            .clickable(enabled = !busy && !unavailable, role = Role.Button) { onBrowse(mount) },
        headlineContent = { NexaraIdentifierText(mount.alias) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NexaraIdentifierText("/mnt/" + mount.alias, style = NexaraTypography.bodySmall)
                NexaraMetaLine(
                    segments = listOf(
                        stringResource(if (mount.sourceType == "real_path") R.string.mount_source_real_path else R.string.mount_source_saf),
                        stringResource(if (mount.readOnly) R.string.mount_read_only else R.string.mount_read_write),
                        stringResource(if (mount.sessionId == null) R.string.mount_scope_global else R.string.mount_scope_current),
                    ),
                    style = NexaraTypography.labelSmall,
                )
                if (unavailable) Text(
                    unavailableLabel,
                    color = MaterialTheme.colorScheme.error,
                    style = NexaraTypography.bodySmall,
                )
            }
        },
        leadingContent = {
            Icon(
                if (unavailable) Icons.Rounded.ErrorOutline else Icons.Rounded.Folder,
                contentDescription = null,
            )
        },
        trailingContent = {
            Column {
                IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.mount_title))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mount_browse)) },
                        enabled = !unavailable,
                        onClick = { menuOpen = false; onBrowse(mount) },
                    )
                    if (mount.sourceType == "saf_tree") DropdownMenuItem(
                        text = { Text(stringResource(R.string.mount_reauthorize)) },
                        onClick = { menuOpen = false; onReauthorize(mount) },
                    )
                    if (mount.sourceType == "real_path" && onGrantRealPath != null) DropdownMenuItem(
                        text = { Text(stringResource(R.string.mount_grant_access)) },
                        onClick = { menuOpen = false; onGrantRealPath() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mount_remove)) },
                        onClick = { menuOpen = false; onRemove(mount) },
                    )
                }
            }
        },
    )
}

@Composable
private fun ExternalMountFormDialog(
    source: ExternalMountSource,
    alias: String,
    sourcePath: String,
    readOnly: Boolean,
    sessionOnly: Boolean,
    hasCurrentSession: Boolean,
    realPathPermission: Boolean,
    busy: Boolean,
    notice: Int?,
    onAliasChanged: (String) -> Unit,
    onPathChanged: (String) -> Unit,
    onReadOnlyChanged: (Boolean) -> Unit,
    onSessionOnlyChanged: (Boolean) -> Unit,
    onGrantAccess: (() -> Unit)?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val isSaf = source == ExternalMountSource.Saf
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.mount_form_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(if (isSaf) R.string.mount_source_saf else R.string.mount_source_real_path))
                OutlinedTextField(
                    value = alias,
                    onValueChange = onAliasChanged,
                    label = { Text(stringResource(R.string.mount_alias)) },
                    supportingText = { Text(stringResource(R.string.mount_alias_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = NexaraTypography.bodyLarge.forInput(),
                    modifier = Modifier.fillMaxWidth().testTag("external_mount_alias"),
                )
                if (!isSaf) {
                    OutlinedTextField(
                        value = sourcePath,
                        onValueChange = onPathChanged,
                        label = { Text(stringResource(R.string.mount_path)) },
                        supportingText = { Text(stringResource(R.string.mount_path_hint)) },
                        enabled = !busy,
                        textStyle = NexaraTypography.bodyLarge.forInput(),
                        modifier = Modifier.fillMaxWidth().testTag("external_mount_real_path"),
                    )
                    Text(stringResource(R.string.mount_real_path_help), style = NexaraTypography.bodySmall)
                    if (!realPathPermission) {
                        Text(stringResource(R.string.mount_permission_missing), color = MaterialTheme.colorScheme.error)
                        if (onGrantAccess != null) TextButton(onClick = onGrantAccess) {
                            Text(stringResource(R.string.mount_grant_access))
                        }
                    }
                } else {
                    Text(stringResource(R.string.mount_system_limits), style = NexaraTypography.bodySmall)
                    Text(stringResource(R.string.mount_termux_hint), style = NexaraTypography.bodySmall)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .toggleable(
                            value = readOnly,
                            enabled = !busy,
                            role = Role.Checkbox,
                            onValueChange = onReadOnlyChanged,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = readOnly, onCheckedChange = null, enabled = !busy)
                    Text(stringResource(R.string.mount_read_only), modifier = Modifier.padding(start = 8.dp))
                }
                if (hasCurrentSession) {
                    Column(modifier = Modifier.selectableGroup()) {
                        ExternalMountScopeOption(
                            label = stringResource(R.string.mount_scope_current),
                            selected = sessionOnly,
                            enabled = !busy,
                            onClick = { onSessionOnlyChanged(true) },
                        )
                        ExternalMountScopeOption(
                            label = stringResource(R.string.mount_scope_global),
                            selected = !sessionOnly,
                            enabled = !busy,
                            onClick = { onSessionOnlyChanged(false) },
                        )
                    }
                } else Text(stringResource(R.string.mount_scope_global))
                if (notice != null) MountNotice(notice)
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy && alias.isNotBlank() && (isSaf || (realPathPermission && sourcePath.isNotBlank())),
            ) { Text(stringResource(if (isSaf) R.string.mount_select_folder else R.string.mount_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_btn_cancel)) }
        },
    )
}

@Composable
private fun ExternalMountScopeOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ExternalMountBrowserDialog(
    mount: ExternalMount,
    sessionId: String,
    workspaceRootUuid: String?,
    manager: IExternalFolderManager,
    onReindex: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootPath = "/mnt/" + mount.alias
    val externalOpenTitle = stringResource(R.string.mount_open_external)
    var path by rememberSaveable(mount.id) { mutableStateOf(rootPath) }
    var entries by remember(mount.id) { mutableStateOf<List<ExternalFolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var selectedFile by remember(mount.id) { mutableStateOf<ExternalFolderEntry?>(null) }
    var preview by remember { mutableStateOf<ExternalTextPreview>(ExternalTextPreview.Loading) }
    var sourceChanged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(manager, sessionId, path, reload, mount.unavailableReason) {
        entries = emptyList()
        loading = true
        loadError = false
        try {
            if (mount.unavailableReason != null) {
                loadError = true
            } else {
                entries = manager.list(sessionId, path).sortedWith(
                    compareByDescending<ExternalFolderEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(manager, sessionId, selectedFile?.path, reload) {
        val entry = selectedFile ?: return@LaunchedEffect
        notice = null
        sourceChanged = false
        preview = ExternalTextPreview.Loading
        try {
            sourceChanged = manager.snapshotChanged(sessionId, entry.path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 快照状态失败不阻断独立的文件预览。
        }
        preview = if (entry.size > EXTERNAL_TEXT_PREVIEW_BYTES) {
            ExternalTextPreview.TooLarge
        } else {
            try {
                val bytes = manager.read(sessionId, entry.path, EXTERNAL_TEXT_PREVIEW_BYTES.toLong())
                withContext(Dispatchers.Default) {
                    decodeExternalTextPreview(bytes, EXTERNAL_TEXT_PREVIEW_BYTES)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ExternalTextPreview.Failed
            }
        }
    }

    val parentPath = parentExternalFolderPath(rootPath, path)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { NexaraIdentifierText(selectedFile?.name ?: mount.alias) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NexaraIdentifierText(selectedFile?.path ?: path, style = NexaraTypography.bodySmall)
                if (mount.unavailableReason != null) MountNotice(R.string.mount_unavailable)
                val entry = selectedFile
                if (entry == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (parentPath != null) TextButton(onClick = { path = parentPath }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                            Text(stringResource(R.string.mount_back))
                        }
                        TextButton(onClick = { reload++ }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Text(stringResource(R.string.mount_refresh))
                        }
                    }
                    if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (loadError) MountNotice(R.string.mount_load_error)
                    if (!loading && !loadError && entries.isEmpty()) Text(stringResource(R.string.mount_directory_empty))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("external_mount_browser"),
                    ) {
                        items(entries, key = { it.path }) { file ->
                            ListItem(
                                modifier = Modifier.clickable(role = Role.Button) {
                                    if (file.isDirectory) path = file.path else selectedFile = file
                                },
                                headlineContent = { NexaraIdentifierText(file.name) },
                                supportingContent = {
                                    if (!file.isDirectory) Text(
                                        Formatter.formatShortFileSize(context, file.size),
                                        style = NexaraTypography.bodySmall,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        if (file.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                    }
                } else {
                    NexaraMetaLine(
                        segments = buildList {
                            add(Formatter.formatShortFileSize(context, entry.size))
                            if (entry.lastModified > 0L) add(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(entry.lastModified)),
                            )
                        },
                        style = NexaraTypography.labelSmall,
                    )
                    if (sourceChanged) MountNotice(R.string.mount_source_changed, error = false)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy && workspaceRootUuid != null && mount.unavailableReason == null,
                            onClick = {
                                val rootUuid = workspaceRootUuid ?: return@TextButton
                                busy = true
                                notice = null
                                scope.launch {
                                    try {
                                        val fileUuid = manager.importSnapshot(sessionId, rootUuid, entry.path)
                                        onReindex?.invoke(fileUuid)
                                        sourceChanged = false
                                        notice = R.string.mount_imported
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        notice = R.string.mount_operation_error
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(
                                if (sourceChanged) R.string.mount_reimport_to_kb else R.string.mount_import_to_kb,
                            ))
                        }
                        TextButton(
                            enabled = !busy && mount.unavailableReason == null,
                            onClick = {
                                if (entry.size > EXTERNAL_OPEN_BYTES) {
                                    notice = R.string.mount_open_limit
                                } else {
                                    busy = true
                                    notice = null
                                    scope.launch {
                                        try {
                                            val intent = prepareExternalFolderOpen(context, manager, sessionId, entry)
                                            context.startActivity(
                                                Intent.createChooser(intent, externalOpenTitle)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            notice = R.string.mount_open_failed
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.mount_open_external)) }
                    }
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    notice?.let { MountNotice(it, error = it != R.string.mount_imported) }
                    when (val currentPreview = preview) {
                        ExternalTextPreview.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        ExternalTextPreview.Failed -> MountNotice(R.string.mount_preview_failed)
                        ExternalTextPreview.Binary -> Text(stringResource(R.string.mount_preview_binary))
                        ExternalTextPreview.TooLarge -> Text(stringResource(R.string.mount_preview_too_large))
                        is ExternalTextPreview.Text -> SelectionContainer(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        ) {
                            Text(
                                currentPreview.content,
                                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                                style = NexaraTypography.bodyMedium,
                            )
                        }
                    }
                    Text(stringResource(R.string.mount_snapshot_info), style = NexaraTypography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.mount_close)) }
        },
        dismissButton = {
            if (selectedFile != null) TextButton(
                enabled = !busy,
                onClick = { selectedFile = null; notice = null },
            ) { Text(stringResource(R.string.mount_back)) }
        },
    )
}

@Composable
private fun ExternalMountRecycleDialog(
    sessionId: String,
    manager: IExternalFolderManager,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<ExternalRecycleItem>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(manager, sessionId, reload) {
        loading = true
        try {
            entries = manager.recycleItems(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notice = R.string.mount_load_error
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.mount_recycle_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text(stringResource(R.string.mount_recycle_help), style = NexaraTypography.bodySmall) }
                if (loading || busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                notice?.let { code -> item { MountNotice(code, error = code != R.string.mount_restored) } }
                if (!loading && entries.isEmpty()) item { Text(stringResource(R.string.mount_recycle_empty)) }
                items(entries, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { NexaraIdentifierText(entry.originalPath) },
                        supportingContent = {
                            Text(Formatter.formatShortFileSize(context, entry.size), style = NexaraTypography.bodySmall)
                        },
                        trailingContent = {
                            TextButton(
                                enabled = !busy && !loading,
                                onClick = {
                                    busy = true
                                    notice = null
                                    scope.launch {
                                        try {
                                            manager.restore(sessionId, entry.id)
                                            notice = R.string.mount_restored
                                            reload++
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            notice = R.string.mount_operation_error
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.mount_restore)) }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.mount_close)) }
        },
        dismissButton = {
            TextButton(onClick = { notice = null; reload++ }, enabled = !busy) {
                Text(stringResource(R.string.mount_retry))
            }
        },
    )
}

@Composable
private fun MountNotice(resourceId: Int, error: Boolean = true) {
    Text(
        text = stringResource(resourceId),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = NexaraTypography.bodySmall,
    )
}

/** 外部应用只获得私有快照的临时读权限，不暴露来源路径或写权限。 */
private suspend fun prepareExternalFolderOpen(
    context: Context,
    manager: IExternalFolderManager,
    sessionId: String,
    entry: ExternalFolderEntry,
): Intent = withContext(Dispatchers.IO) {
    val bytes = manager.read(sessionId, entry.path, EXTERNAL_OPEN_BYTES)
    check(bytes.size.toLong() <= EXTERNAL_OPEN_BYTES)
    val previewsRoot = File(context.filesDir, "external-folder-previews")
    check(previewsRoot.isDirectory || previewsRoot.mkdirs())
    val retained = previewsRoot.listFiles().orEmpty().sortedByDescending { it.lastModified() }
    retained.forEachIndexed { index, file ->
        if (index >= 3 || System.currentTimeMillis() - file.lastModified() > EXTERNAL_PREVIEW_RETENTION_MS) {
            file.deleteRecursively()
        }
    }
    val directory = File(previewsRoot, UUID.randomUUID().toString())
    check(directory.mkdir())
    val snapshot = File(directory, externalPreviewFileName(entry.name))
    snapshot.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", snapshot)
    val extension = snapshot.extension.lowercase()
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        clipData = ClipData.newRawUri(entry.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private const val EXTERNAL_TEXT_PREVIEW_BYTES = 256 * 1024
private const val EXTERNAL_OPEN_BYTES = 8L * 1024 * 1024
private const val EXTERNAL_PREVIEW_RETENTION_MS = 24L * 60 * 60 * 1000

