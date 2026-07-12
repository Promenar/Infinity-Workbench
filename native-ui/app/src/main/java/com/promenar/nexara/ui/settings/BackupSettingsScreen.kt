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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.SpaceGrotesk

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.SecretField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: BackupViewModel = viewModel(factory = BackupViewModel.factory(context.applicationContext as android.app.Application))
    BackupSettingsScreen(onNavigateBack, viewModel)
}

/** 测试与预览可注入真实状态机；生产入口仍使用上方 Application factory。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun BackupSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupViewModel,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

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
    
    // WebDAV local editing states for the sheet
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
    val stackLocalActions = shouldStackBackupActions(LocalDensity.current.fontScale)

    val onExportClick = {
        if (uiState.includeKeys) showExportPasswordDialog = true
        else exportLauncher.launch("nexara_backup_${System.currentTimeMillis()}.nexara")
    }
    val onImportClick = { importLauncher.launch("*/*") }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title), style = NexaraTypography.headlineLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_cd_back),
                            tint = NexaraColors.OnSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f),
                    titleContentColor = NexaraColors.OnSurface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 24.dp, bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.backup_desc),
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                backupOperationText(uiState.operation)?.let { status ->
                    NexaraGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = status,
                                style = NexaraTypography.bodyMedium,
                                color = when {
                                    uiState.operation is BackupOperation.Error || uiState.operation is BackupOperation.Blocked -> NexaraColors.Error
                                    (uiState.operation as? BackupOperation.Success)?.cleanupWarning == true -> NexaraColors.Tertiary
                                    else -> NexaraColors.OnSurfaceVariant
                                },
                            )
                            if (uiState.operation.isCancellable) {
                                ActionButton(
                                    label = stringResource(R.string.common_btn_cancel),
                                    icon = Icons.Rounded.DeleteForever,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = viewModel::cancelOperation,
                                )
                            }
                        }
                    }
                }
            }

            item {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { contentExpanded = !contentExpanded }
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Link,
                                    contentDescription = null,
                                    tint = NexaraColors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.backup_content_title),
                                        style = NexaraTypography.labelMedium,
                                        color = NexaraColors.OnSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.backup_core_content_summary),
                                        style = NexaraTypography.bodyMedium.copy(
                                            fontSize = 12.sp,
                                            fontFamily = SpaceGrotesk
                                        ),
                                        color = NexaraColors.OnSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (contentExpanded) Icons.Rounded.KeyboardArrowUp
                                else Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = NexaraColors.Outline
                            )
                        }

                        AnimatedVisibility(visible = contentExpanded) {
                            Column(
                                modifier = Modifier
                                    .background(NexaraColors.SurfaceLow.copy(alpha = 0.5f))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.backup_core_content_fixed),
                                    style = NexaraTypography.labelMedium,
                                    color = NexaraColors.OnSurface,
                                )
                                Text(
                                    text = coreContentLabels.joinToString(" · "),
                                    style = NexaraTypography.bodyMedium,
                                    color = NexaraColors.OnSurfaceVariant,
                                )
                                SettingsToggle(stringResource(R.string.backup_content_keys), checked = uiState.keysChecked, onCheckedChange = { viewModel.setIncludeKeys(it) })
                            }
                        }
                    }
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.backup_section_local)) }

            item {
                if (stackLocalActions) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExportButton(
                            icon = Icons.Rounded.Download,
                            title = stringResource(R.string.backup_export_title),
                            subtitle = if (uiState.isExporting) stringResource(R.string.backup_exporting) else stringResource(R.string.backup_export_subtitle),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState.canExecute,
                            onClick = onExportClick,
                        )
                        ExportButton(
                            icon = Icons.Rounded.Upload,
                            title = stringResource(R.string.backup_import_title),
                            subtitle = if (uiState.isImporting) stringResource(R.string.backup_importing) else stringResource(R.string.backup_import_subtitle),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState.canExecute,
                            onClick = onImportClick,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExportButton(
                            icon = Icons.Rounded.Download,
                            title = stringResource(R.string.backup_export_title),
                            subtitle = if (uiState.isExporting) stringResource(R.string.backup_exporting) else stringResource(R.string.backup_export_subtitle),
                            modifier = Modifier.weight(1f),
                            enabled = uiState.canExecute,
                            onClick = onExportClick,
                        )
                        ExportButton(
                            icon = Icons.Rounded.Upload,
                            title = stringResource(R.string.backup_import_title),
                            subtitle = if (uiState.isImporting) stringResource(R.string.backup_importing) else stringResource(R.string.backup_import_subtitle),
                            modifier = Modifier.weight(1f),
                            enabled = uiState.canExecute,
                            onClick = onImportClick,
                        )
                    }
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.backup_section_webdav)) }

            item {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(NexaraColors.SurfaceContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CloudSync,
                                        contentDescription = null,
                                        tint = NexaraColors.Tertiary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.backup_webdav_sync),
                                        style = NexaraTypography.headlineMedium,
                                        color = NexaraColors.OnSurface
                                    )
                                    Text(
                                        text = if (uiState.webdavEnabled) stringResource(R.string.backup_webdav_configured) else stringResource(R.string.backup_webdav_not_configured),
                                        style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                                        color = NexaraColors.OnSurfaceVariant
                                    )
                                }
                            }
                            androidx.compose.material3.Switch(
                                checked = uiState.webdavEnabled,
                                onCheckedChange = { viewModel.setWebdavEnabled(it) },
                                colors = androidx.compose.material3.SwitchDefaults.colors(
                                    checkedTrackColor = NexaraColors.Primary,
                                    checkedThumbColor = NexaraColors.OnPrimary,
                                    uncheckedTrackColor = NexaraColors.SurfaceHighest,
                                    uncheckedThumbColor = NexaraColors.Secondary
                                )
                            )
                        }

                        AnimatedVisibility(visible = uiState.webdavEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsToggle(stringResource(R.string.backup_auto_backup), checked = uiState.autoBackup, onCheckedChange = { viewModel.setAutoBackup(it) })
                                ActionButton(
                                    label = stringResource(R.string.backup_upload_cloud),
                                    icon = Icons.Rounded.Upload,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = uiState.canExecute,
                                    onClick = {
                                        if (uiState.includeKeys) showUploadPasswordDialog = true
                                        else viewModel.upload(null, null)
                                    }
                                )
                                ActionButton(
                                    label = stringResource(R.string.backup_remote_refresh),
                                    icon = Icons.Rounded.Refresh,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = uiState.canExecute,
                                    onClick = { viewModel.listRemote() },
                                )
                                if (uiState.operation is BackupOperation.ListingRemote) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = NexaraColors.Primary)
                                } else if (uiState.remoteBackups.isEmpty()) {
                                    Text(
                                        stringResource(R.string.backup_remote_empty),
                                        style = NexaraTypography.bodyMedium,
                                        color = NexaraColors.OnSurfaceVariant,
                                    )
                                }
                                uiState.remoteBackups.forEach { remote ->
                                    val selected = uiState.selectedRemote == remote
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(NexaraShapes.medium)
                                            .border(
                                                1.dp,
                                                if (selected) NexaraColors.Primary else NexaraColors.GlassBorder,
                                                NexaraShapes.medium,
                                            )
                                            .clickable(enabled = uiState.canExecute) { viewModel.selectRemote(remote) }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.common_cd_selected), tint = NexaraColors.Primary)
                                        Column(modifier = Modifier.weight(1f).padding(start = if (selected) 8.dp else 0.dp)) {
                                            Text(remote.fileName, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                            Text(stringResource(R.string.backup_remote_metadata, remote.sizeBytes, remote.lastModifiedEpochMillis), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                                        }
                                    }
                                }
                                if (uiState.selectedRemote != null) {
                                    ActionButton(
                                        label = stringResource(R.string.backup_restore_cloud),
                                        icon = Icons.Rounded.Restore,
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = uiState.canExecute,
                                        onClick = {
                                            restoreRemote = true
                                            showRestorePasswordDialog = true
                                        },
                                    )
                                }
                            }
                        }
                        ActionButton(
                            label = stringResource(R.string.backup_config_webdav),
                            icon = Icons.Rounded.Link,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState.canExecute,
                            onClick = { showWebdavSheet = true }
                        )
                    }
                }
            }

            item {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = NexaraColors.Outline,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.backup_info_text),
                            style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }
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
            containerColor = NexaraColors.SurfaceContainer,
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
                    style = NexaraTypography.headlineMedium,
                    color = NexaraColors.OnSurface
                )
                GlassInputField(stringResource(R.string.backup_webdav_url_label), tempWebdavUrl, { tempWebdavUrl = it }, stringResource(R.string.backup_webdav_url_hint))
                GlassInputField(stringResource(R.string.backup_webdav_user_label), tempWebdavUser, { tempWebdavUser = it }, stringResource(R.string.backup_webdav_user_hint))
                Text(stringResource(R.string.backup_webdav_pass_label), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurfaceVariant)
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
                ActionButton(
                    label = stringResource(R.string.backup_test_connection),
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.canExecute,
                    onClick = {
                        val accepted = viewModel.saveAndTestWebDavConfig(
                            tempWebdavUrl,
                            tempWebdavUser,
                            tempWebdavPass.takeIf { it.isNotEmpty() }?.toCharArray(),
                        )
                        if (accepted) tempWebdavPass = ""
                    }
                )
                ActionButton(
                    label = stringResource(R.string.backup_save_config),
                    icon = Icons.Rounded.CloudSync,
                    modifier = Modifier.fillMaxWidth(),
                    isPrimary = true,
                    enabled = uiState.canExecute,
                    onClick = {
                        val accepted = viewModel.saveWebDavConfig(
                            tempWebdavUrl,
                            tempWebdavUser,
                            tempWebdavPass.takeIf { it.isNotEmpty() }?.toCharArray(),
                        )
                        if (accepted) tempWebdavPass = ""
                    }
                )
                backupOperationText(uiState.operation)?.let { status ->
                    Text(
                        text = status,
                        style = NexaraTypography.bodyMedium,
                        color = if (uiState.operation is BackupOperation.Blocked ||
                            uiState.operation is BackupOperation.Error
                        ) NexaraColors.Error else NexaraColors.OnSurfaceVariant,
                    )
                }
                val blockedCode = (uiState.operation as? BackupOperation.Blocked)?.code
                if (blockedCode == BackupErrorCode.CONNECTION_FAILED ||
                    blockedCode == BackupErrorCode.CONFIGURATION_MISSING
                ) {
                    ActionButton(
                        label = stringResource(R.string.backup_reset_webdav_security),
                        icon = Icons.Rounded.DeleteForever,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            tempWebdavPass = ""
                            viewModel.resetWebDavAuth()
                        },
                    )
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
        containerColor = NexaraColors.SurfaceContainer,
        title = { Text(title, style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_password_label), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurfaceVariant)
                SecretField(
                    value = password,
                    onValueChange = onPasswordChange,
                    hasStoredSecret = false,
                    onRevealRequest = { null },
                    onClear = { onPasswordChange("") },
                    placeholder = stringResource(R.string.backup_password_hint),
                )
                if (requireConfirmation) {
                    Text(stringResource(R.string.backup_password_confirm_label), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurfaceVariant)
                    SecretField(
                        value = passwordConfirmation,
                        onValueChange = onConfirmationChange,
                        hasStoredSecret = false,
                        onRevealRequest = { null },
                        onClear = { onConfirmationChange("") },
                        placeholder = stringResource(R.string.backup_password_confirm_hint),
                    )
                    if (mismatch) Text(stringResource(R.string.backup_password_mismatch), color = NexaraColors.Error, style = NexaraTypography.bodyMedium)
                } else {
                    Text(stringResource(R.string.backup_restore_password_optional), color = NexaraColors.OnSurfaceVariant, style = NexaraTypography.bodyMedium)
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
    NexaraGlassCard(
        modifier = modifier,
        shape = NexaraShapes.large as RoundedCornerShape,
        onClick = if (enabled) onClick else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(NexaraColors.SurfaceContainer, CircleShape()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NexaraColors.Primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = title,
                style = NexaraTypography.labelMedium,
                color = NexaraColors.OnSurface
            )
            Text(
                text = subtitle,
                style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                color = NexaraColors.OnSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(NexaraShapes.medium)
            .background(if (isPrimary) NexaraColors.InversePrimary else NexaraColors.SurfaceHigh)
            .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isPrimary) NexaraColors.OnPrimary else NexaraColors.Primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = NexaraTypography.labelMedium,
                color = if (isPrimary) NexaraColors.OnPrimary else NexaraColors.Primary
            )
        }
    }
}

@Composable
private fun GlassInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
            color = NexaraColors.OnSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(NexaraShapes.medium)
                .background(NexaraColors.SurfaceContainer)
                .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = NexaraTypography.bodyMedium.copy(
                    fontFamily = SpaceGrotesk,
                    color = NexaraColors.OnSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(NexaraColors.Primary),
                visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = NexaraTypography.bodyMedium.copy(fontFamily = SpaceGrotesk),
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun CircleShape() = RoundedCornerShape(50)
