package com.promenar.nexara.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.promenar.nexara.ui.common.SecretField
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.testing.UiTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: BackupViewModel = viewModel(factory = BackupViewModel.factory(context.applicationContext as android.app.Application))
    BackupSettingsScreen(onNavigateBack, viewModel)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun BackupSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupViewModel,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.operation) {
        if (uiState.operation != BackupOperation.Idle) {
            listState.scrollToItem(1)
        }
    }

    var contentExpanded by remember { mutableStateOf(true) }
    var showWebdavSheet by remember { mutableStateOf(false) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var showUploadPasswordDialog by remember { mutableStateOf(false) }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var restoreRemote by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }

    var tempWebdavUrl by remember(uiState.webdavUrl) { mutableStateOf(uiState.webdavUrl) }
    var tempWebdavUser by remember(uiState.webdavUser) { mutableStateOf(uiState.webdavUser) }
    var tempWebdavPass by remember { mutableStateOf("") }

    fun clearPasswords() {
        backupPassword = ""
        passwordConfirmation = ""
        restorePassword = ""
        tempWebdavPass = ""
    }
    DisposableEffect(Unit) { onDispose { clearPasswords() } }

    val coreContentLabels = listOf(
        stringResource(R.string.backup_content_sessions),
        stringResource(R.string.backup_content_library),
        stringResource(R.string.backup_content_files),
        stringResource(R.string.backup_content_settings),
    )

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        var output: java.io.OutputStream? = null
        var handedOff = false
        try {
            if (uri != null) {
                output = context.contentResolver.openOutputStream(uri)
                if (output == null) {
                    viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_CREATE_FAILED)
                } else {
                    handedOff = true
                    viewModel.export(
                        output,
                        backupPassword.takeIf { uiState.includeKeys }?.toCharArray(),
                        passwordConfirmation.takeIf { uiState.includeKeys }?.toCharArray(),
                    )
                }
            }
        } catch (_: SecurityException) {
            viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_CREATE_FAILED)
        } catch (_: java.io.IOException) {
            viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_CREATE_FAILED)
        } finally {
            if (!handedOff) runCatching { output?.close() }
            clearPasswords()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            pendingRestoreUri = it
            restoreRemote = false
            showRestorePasswordDialog = true
        }
    }
    val onExportClick = {
        if (uiState.includeKeys) showExportPasswordDialog = true
        else exportLauncher.launch("nexara_backup_${System.currentTimeMillis()}.nexara")
    }
    val onImportClick = { importLauncher.launch("*/*") }

    NexaraSettingsPageLayout(
        title = stringResource(R.string.backup_title),
        onBack = onNavigateBack,
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = paddingValues,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.backup_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                backupOperationText(uiState.operation)?.let { status ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (uiState.operation is BackupOperation.Error || uiState.operation is BackupOperation.Blocked)
                                MaterialTheme.colorScheme.errorContainer
                            else
                                MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = status,
                                modifier = Modifier.testTag(UiTags.BACKUP_OPERATION_STATUS),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (uiState.operation is BackupOperation.Error || uiState.operation is BackupOperation.Blocked)
                                    MaterialTheme.colorScheme.onErrorContainer
                                else
                                    MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (uiState.operation.isCancellable) {
                                FilledTonalButton(
                                    onClick = viewModel::cancelOperation,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) {
                                    Icon(Icons.Rounded.DeleteForever, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.common_btn_cancel))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { contentExpanded = !contentExpanded },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.backup_content_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.backup_core_content_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = if (contentExpanded) Icons.Rounded.KeyboardArrowUp
                                else Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    AnimatedVisibility(visible = contentExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.backup_core_content_fixed),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = coreContentLabels.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SettingsToggle(
                                title = stringResource(R.string.backup_content_keys),
                                checked = uiState.keysChecked,
                                onCheckedChange = { viewModel.setIncludeKeys(it) }
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.backup_section_local)) }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ExportButton(
                        icon = Icons.Rounded.Download,
                        title = stringResource(R.string.backup_export_title),
                        subtitle = if (uiState.isExporting) stringResource(R.string.backup_exporting) else stringResource(R.string.backup_export_subtitle),
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.BACKUP_EXPORT),
                        enabled = uiState.canExecute,
                        onClick = onExportClick,
                    )
                    ExportButton(
                        icon = Icons.Rounded.Upload,
                        title = stringResource(R.string.backup_import_title),
                        subtitle = if (uiState.isImporting) stringResource(R.string.backup_importing) else stringResource(R.string.backup_import_subtitle),
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.BACKUP_IMPORT),
                        enabled = uiState.canExecute,
                        onClick = onImportClick,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.backup_section_webdav)) }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.backup_webdav_sync),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        supportingContent = {
                            Text(
                                text = if (uiState.webdavEnabled) stringResource(R.string.backup_webdav_configured) else stringResource(R.string.backup_webdav_not_configured),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = uiState.webdavEnabled,
                                onCheckedChange = { viewModel.setWebdavEnabled(it) }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    AnimatedVisibility(visible = uiState.webdavEnabled) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SettingsToggle(
                                title = stringResource(R.string.backup_auto_backup),
                                checked = uiState.autoBackup,
                                onCheckedChange = { viewModel.setAutoBackup(it) }
                            )

                            Button(
                                onClick = {
                                    if (uiState.includeKeys) showUploadPasswordDialog = true
                                    else viewModel.upload(null, null)
                                },
                                enabled = uiState.canExecute,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Upload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.backup_upload_cloud))
                            }

                            FilledTonalButton(
                                onClick = { viewModel.listRemote() },
                                enabled = uiState.canExecute,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.backup_remote_refresh))
                            }

                            if (uiState.operation is BackupOperation.ListingRemote) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            } else if (uiState.remoteBackups.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.backup_remote_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }

                            uiState.remoteBackups.forEach { remote ->
                                val selected = uiState.selectedRemote == remote
                                ListItem(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = uiState.canExecute) { viewModel.selectRemote(remote) }
                                        .border(
                                            width = 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(8.dp)
                                        ),
                                    headlineContent = {
                                        Text(remote.fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    },
                                    supportingContent = {
                                        Text(
                                            text = stringResource(R.string.backup_remote_metadata, remote.sizeBytes, remote.lastModifiedEpochMillis),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    leadingContent = {
                                        if (selected) {
                                            Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.common_cd_selected), tint = MaterialTheme.colorScheme.primary)
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }

                            if (uiState.selectedRemote != null) {
                                Button(
                                    onClick = {
                                        restoreRemote = true
                                        showRestorePasswordDialog = true
                                    },
                                    enabled = uiState.canExecute,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Rounded.Restore, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.backup_restore_cloud))
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { showWebdavSheet = true },
                        enabled = uiState.canExecute,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.backup_config_webdav))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 12.dp))
                }
            }

            item {
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.backup_info_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }

    if (showWebdavSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = {
                tempWebdavPass = ""
                showWebdavSheet = false
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .safeDrawingPadding()
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.backup_webdav_config_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = tempWebdavUrl,
                    onValueChange = { tempWebdavUrl = it },
                    label = { Text(stringResource(R.string.backup_webdav_url_label)) },
                    placeholder = { Text(stringResource(R.string.backup_webdav_url_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = tempWebdavUser,
                    onValueChange = { tempWebdavUser = it },
                    label = { Text(stringResource(R.string.backup_webdav_user_label)) },
                    placeholder = { Text(stringResource(R.string.backup_webdav_user_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.backup_webdav_pass_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SecretField(
                    value = tempWebdavPass,
                    onValueChange = { tempWebdavPass = it },
                    hasStoredSecret = uiState.hasWebDavPassword,
                    onRevealRequest = viewModel::revealWebDavPassword,
                    onClear = {
                        tempWebdavPass = ""
                        viewModel.deleteWebDavPassword()
                    },
                )

                Button(
                    onClick = {
                        val accepted = viewModel.saveAndTestWebDavConfig(
                            tempWebdavUrl,
                            tempWebdavUser,
                            tempWebdavPass.takeIf { it.isNotEmpty() }?.toCharArray(),
                        )
                        if (accepted) tempWebdavPass = ""
                    },
                    enabled = uiState.canExecute,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_test_connection))
                }

                Button(
                    onClick = {
                        val accepted = viewModel.saveWebDavConfig(
                            tempWebdavUrl,
                            tempWebdavUser,
                            tempWebdavPass.takeIf { it.isNotEmpty() }?.toCharArray(),
                        )
                        if (accepted) tempWebdavPass = ""
                    },
                    enabled = uiState.canExecute,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.CloudSync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_save_config))
                }

                backupOperationText(uiState.operation)?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.operation is BackupOperation.Blocked ||
                            uiState.operation is BackupOperation.Error
                        ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val blockedCode = (uiState.operation as? BackupOperation.Blocked)?.code
                if (blockedCode == BackupErrorCode.CONNECTION_FAILED ||
                    blockedCode == BackupErrorCode.CONFIGURATION_MISSING
                ) {
                    Button(
                        onClick = {
                            tempWebdavPass = ""
                            viewModel.resetWebDavAuth()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Rounded.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.backup_reset_webdav_security))
                    }
                }
            }
        }
    }

    if (showExportPasswordDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_password_export_title),
            password = backupPassword,
            passwordConfirmation = passwordConfirmation,
            requireConfirmation = true,
            onPasswordChange = { backupPassword = it },
            onConfirmationChange = { passwordConfirmation = it },
            onDismiss = { showExportPasswordDialog = false; clearPasswords() },
            onConfirm = {
                showExportPasswordDialog = false
                exportLauncher.launch("nexara_backup_${System.currentTimeMillis()}.nexara")
            },
        )
    }
    if (showUploadPasswordDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_password_upload_title),
            password = backupPassword,
            passwordConfirmation = passwordConfirmation,
            requireConfirmation = true,
            onPasswordChange = { backupPassword = it },
            onConfirmationChange = { passwordConfirmation = it },
            onDismiss = { showUploadPasswordDialog = false; clearPasswords() },
            onConfirm = {
                showUploadPasswordDialog = false
                viewModel.upload(backupPassword.toCharArray(), passwordConfirmation.toCharArray())
                clearPasswords()
            },
        )
    }
    if (showRestorePasswordDialog) {
        BackupPasswordDialog(
            title = stringResource(if (restoreRemote) R.string.backup_password_remote_restore_title else R.string.backup_password_restore_title),
            password = restorePassword,
            passwordConfirmation = "",
            requireConfirmation = false,
            onPasswordChange = { restorePassword = it },
            onConfirmationChange = {},
            onDismiss = {
                showRestorePasswordDialog = false
                pendingRestoreUri = null
                clearPasswords()
            },
            onConfirm = {
                showRestorePasswordDialog = false
                if (restoreRemote) {
                    viewModel.restoreSelectedRemote(restorePassword.takeIf(String::isNotEmpty)?.toCharArray())
                } else {
                    pendingRestoreUri?.let { uri ->
                        var input: java.io.InputStream? = null
                        var handedOff = false
                        try {
                            input = context.contentResolver.openInputStream(uri)
                            if (input == null) {
                                viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_OPEN_FAILED)
                            } else {
                                handedOff = true
                                viewModel.restoreLocal(input, restorePassword.takeIf(String::isNotEmpty)?.toCharArray())
                            }
                        } catch (_: SecurityException) {
                            viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_OPEN_FAILED)
                        } catch (_: java.io.IOException) {
                            viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_OPEN_FAILED)
                        } finally {
                            if (!handedOff) runCatching { input?.close() }
                        }
                    }
                }
                pendingRestoreUri = null
                clearPasswords()
            },
        )
    }
}

@Composable
private fun backupOperationText(operation: BackupOperation): String? = when (operation) {
    BackupOperation.Idle -> null
    BackupOperation.Initializing -> stringResource(R.string.backup_status_initializing)
    BackupOperation.SavingConfig -> stringResource(R.string.backup_status_saving_config)
    BackupOperation.Testing -> stringResource(R.string.backup_status_testing)
    BackupOperation.ListingRemote -> stringResource(R.string.backup_status_listing)
    BackupOperation.Exporting -> stringResource(R.string.backup_exporting)
    BackupOperation.Uploading -> stringResource(R.string.backup_status_uploading)
    BackupOperation.StagingRestore -> stringResource(R.string.backup_status_staging_restore)
    BackupOperation.CancellingRestore -> stringResource(R.string.backup_status_cancelling)
    BackupOperation.Restarting -> stringResource(R.string.backup_status_restarting)
    is BackupOperation.Success -> if (operation.cleanupWarning) {
        stringResource(R.string.backup_status_success_cleanup_warning)
    } else when (operation.code) {
        BackupSuccessCode.REMOTE_LISTED -> stringResource(R.string.backup_status_remote_listed, operation.itemCount)
        BackupSuccessCode.CONFIG_SAVED -> stringResource(R.string.backup_status_config_saved)
        BackupSuccessCode.PASSWORD_CLEARED -> stringResource(R.string.backup_status_password_cleared)
        BackupSuccessCode.CONFIG_RESET -> stringResource(R.string.backup_status_config_reset)
        BackupSuccessCode.CONNECTION_TESTED -> stringResource(R.string.backup_status_connection_success)
        BackupSuccessCode.EXPORTED -> stringResource(R.string.backup_status_export_success)
        BackupSuccessCode.UPLOADED -> stringResource(R.string.backup_status_upload_success)
    }
    is BackupOperation.Blocked -> backupErrorText(operation.code)
    is BackupOperation.Error -> backupErrorText(operation.code)
}

@Composable
private fun backupErrorText(code: BackupErrorCode): String = stringResource(
    when (code) {
        BackupErrorCode.PASSWORD_REQUIRED -> R.string.backup_error_password_required
        BackupErrorCode.PASSWORD_MISMATCH -> R.string.backup_password_mismatch
        BackupErrorCode.CONFIGURATION_MISSING -> R.string.backup_error_configuration_missing
        BackupErrorCode.CONNECTION_FAILED -> R.string.backup_error_connection
        BackupErrorCode.REMOTE_LIST_FAILED -> R.string.backup_error_remote_list
        BackupErrorCode.STALE_SELECTION -> R.string.backup_error_stale_selection
        BackupErrorCode.EXPORT_FAILED -> R.string.backup_error_export
        BackupErrorCode.UPLOAD_FAILED -> R.string.backup_error_upload
        BackupErrorCode.RESTORE_FAILED -> R.string.backup_error_restore
        BackupErrorCode.RESTART_FAILED -> R.string.backup_error_restart
        BackupErrorCode.RESTORE_CLEANUP_FAILED -> R.string.backup_error_cleanup
        BackupErrorCode.DOCUMENT_CREATE_FAILED -> R.string.backup_error_document_create
        BackupErrorCode.DOCUMENT_OPEN_FAILED -> R.string.backup_error_document_open
    },
)

@Composable
internal fun BackupPasswordDialog(
    title: String,
    password: String,
    passwordConfirmation: String,
    requireConfirmation: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val mismatch = requireConfirmation && passwordConfirmation.isNotEmpty() && password != passwordConfirmation
    val valid = !requireConfirmation || (password.isNotEmpty() && password == passwordConfirmation)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_password_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecretField(
                    value = password,
                    onValueChange = onPasswordChange,
                    hasStoredSecret = false,
                    onRevealRequest = { null },
                    onClear = { onPasswordChange("") },
                    placeholder = stringResource(R.string.backup_password_hint),
                )
                if (requireConfirmation) {
                    Text(stringResource(R.string.backup_password_confirm_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecretField(
                        value = passwordConfirmation,
                        onValueChange = onConfirmationChange,
                        hasStoredSecret = false,
                        onRevealRequest = { null },
                        onClear = { onConfirmationChange("") },
                        placeholder = stringResource(R.string.backup_password_confirm_hint),
                    )
                    if (mismatch) Text(stringResource(R.string.backup_password_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.backup_restore_password_optional), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = onConfirm, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.common_btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.common_btn_cancel))
            }
        },
    )
}

internal fun shouldStackBackupActions(fontScale: Float): Boolean = fontScale >= 1.5f

@Composable
private fun ExportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    ListItem(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
