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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onNavigateBack: () -> Unit
) {
    var sessionsChecked by remember { mutableStateOf(true) }
    var libraryChecked by remember { mutableStateOf(true) }
    var filesChecked by remember { mutableStateOf(true) }
    var settingsChecked by remember { mutableStateOf(true) }
    var keysChecked by remember { mutableStateOf(true) }
    var contentExpanded by remember { mutableStateOf(true) }
    var webdavEnabled by remember { mutableStateOf(false) }
    var autoBackup by remember { mutableStateOf(false) }
    var showWebdavSheet by remember { mutableStateOf(false) }
    var webdavUrl by remember { mutableStateOf("") }
    var webdavUser by remember { mutableStateOf("") }
    var webdavPass by remember { mutableStateOf("") }

    val selectedCount = listOf(sessionsChecked, libraryChecked, filesChecked, settingsChecked, keysChecked).count { it }

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
                                        text = stringResource(R.string.backup_items_selected, selectedCount),
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
                                SettingsToggle(stringResource(R.string.backup_content_sessions), checked = sessionsChecked, onCheckedChange = { sessionsChecked = it })
                                SettingsToggle(stringResource(R.string.backup_content_library), checked = libraryChecked, onCheckedChange = { libraryChecked = it })
                                SettingsToggle(stringResource(R.string.backup_content_files), checked = filesChecked, onCheckedChange = { filesChecked = it })
                                SettingsToggle(stringResource(R.string.backup_content_settings), checked = settingsChecked, onCheckedChange = { settingsChecked = it })
                                SettingsToggle(stringResource(R.string.backup_content_keys), checked = keysChecked, onCheckedChange = { keysChecked = it })
                            }
                        }
                    }
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.backup_section_local)) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportButton(
                        icon = Icons.Rounded.Download,
                        title = stringResource(R.string.backup_export_title),
                        subtitle = stringResource(R.string.backup_export_subtitle),
                        modifier = Modifier.weight(1f),
                        onClick = { }
                    )
                    ExportButton(
                        icon = Icons.Rounded.Upload,
                        title = stringResource(R.string.backup_import_title),
                        subtitle = stringResource(R.string.backup_import_subtitle),
                        modifier = Modifier.weight(1f),
                        onClick = { }
                    )
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
                                        text = if (webdavEnabled) stringResource(R.string.backup_webdav_configured) else stringResource(R.string.backup_webdav_not_configured),
                                        style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                                        color = NexaraColors.OnSurfaceVariant
                                    )
                                }
                            }
                            SettingsToggle("", checked = webdavEnabled, onCheckedChange = { webdavEnabled = it })
                        }

                        AnimatedVisibility(visible = webdavEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsToggle(stringResource(R.string.backup_auto_backup), checked = autoBackup, onCheckedChange = { autoBackup = it })
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ActionButton(
                                        label = stringResource(R.string.backup_upload_cloud),
                                        icon = Icons.Rounded.Upload,
                                        modifier = Modifier.weight(1f),
                                        onClick = { }
                                    )
                                    ActionButton(
                                        label = stringResource(R.string.backup_restore_cloud),
                                        icon = Icons.Rounded.Download,
                                        modifier = Modifier.weight(1f),
                                        onClick = { }
                                    )
                                }
                                ActionButton(
                                    label = stringResource(R.string.backup_config_webdav),
                                    icon = Icons.Rounded.Link,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { showWebdavSheet = true }
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
            onDismissRequest = { showWebdavSheet = false },
            sheetState = sheetState,
            containerColor = NexaraColors.SurfaceContainer,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.backup_webdav_config_title),
                    style = NexaraTypography.headlineMedium,
                    color = NexaraColors.OnSurface
                )
                GlassInputField(stringResource(R.string.backup_webdav_url_label), webdavUrl, { webdavUrl = it }, stringResource(R.string.backup_webdav_url_hint))
                GlassInputField(stringResource(R.string.backup_webdav_user_label), webdavUser, { webdavUser = it }, "user")
                GlassInputField(stringResource(R.string.backup_webdav_pass_label), webdavPass, { webdavPass = it }, "••••••••", isPassword = true)
                ActionButton(
                    label = stringResource(R.string.backup_test_connection),
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showWebdavSheet = false }
                )
                ActionButton(
                    label = stringResource(R.string.backup_save_config),
                    icon = Icons.Rounded.CloudSync,
                    modifier = Modifier.fillMaxWidth(),
                    isPrimary = true,
                    onClick = { showWebdavSheet = false }
                )
            }
        }
    }
}

@Composable
private fun ExportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    NexaraGlassCard(
        modifier = modifier,
        shape = NexaraShapes.large as RoundedCornerShape,
        onClick = onClick
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
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(NexaraShapes.medium)
            .background(if (isPrimary) NexaraColors.InversePrimary else NexaraColors.SurfaceHigh)
            .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
            .clickable(onClick = onClick)
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
