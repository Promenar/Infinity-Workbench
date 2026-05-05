package com.promenar.nexara.ui.hub

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.settings.ProviderListItem
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.SpaceGrotesk
import com.promenar.nexara.ui.theme.Manrope

private enum class SettingsTab(val labelRes: Int) {
    APP(R.string.settings_tab_app),
    PROVIDER(R.string.settings_tab_provider)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsHomeScreen(
    onNavigateToSecondary: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val userName by viewModel.userName.collectAsState()
    val currentModel by viewModel.currentModelSummary.collectAsState()
    val activeSources by viewModel.activeSourcesCount.collectAsState()
    val tokenCost by viewModel.tokenCostThisMonth.collectAsState()
    val language by viewModel.language.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val haptic by viewModel.hapticEnabled.collectAsState()
    val logEnabled by viewModel.logEnabled.collectAsState()
    val providers by viewModel.providers.collectAsState()

    val selectedSettingsTab by viewModel.selectedSettingsTab.collectAsState()
    val selectedTab = remember(selectedSettingsTab) { SettingsTab.entries[selectedSettingsTab] }
    var showNameEditor by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(userName) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == SettingsTab.PROVIDER) {
            viewModel.refreshProviders()
        }
    }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), style = NexaraTypography.headlineLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f),
                    titleContentColor = NexaraColors.OnSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
        ) {
            TabBar(
                selectedTab = selectedTab,
                onTabSelected = { viewModel.setSelectedSettingsTab(it.ordinal) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Crossfade(
                targetState = selectedTab,
                animationSpec = tween(300),
                label = "settingsTab"
            ) { tab ->
                when (tab) {
                    SettingsTab.APP -> {
                        AppSettingsContent(
                            viewModel = viewModel,
                            userName = userName,
                            currentModel = currentModel,
                            activeSources = activeSources,
                            tokenCost = tokenCost,
                            language = language,
                            themeMode = themeMode,
                            haptic = haptic,
                            logEnabled = logEnabled,
                            onNavigateToSecondary = onNavigateToSecondary,
                            onEditName = {
                                editingName = userName
                                showNameEditor = true
                            },
                            onShowLanguageDialog = { showLanguageDialog = true }
                        )
                    }
                    SettingsTab.PROVIDER -> {
                        ProviderSettingsContent(
                            providers = providers,
                            onNavigateToSecondary = onNavigateToSecondary,
                            onDeleteProvider = { providerId -> showDeleteDialog = providerId }
                        )
                    }
                }
            }
        }
    }

    if (showNameEditor) {
        NameEditDialog(
            currentName = editingName,
            onDismiss = { showNameEditor = false },
            onSave = {
                viewModel.updateUserName(it)
                showNameEditor = false
            }
        )
    }

    if (showDeleteDialog != null) {
        NexaraConfirmDialog(
            title = stringResource(R.string.settings_delete_provider_title),
            message = stringResource(R.string.settings_delete_provider_message),
            confirmText = stringResource(R.string.settings_btn_delete),
            isDestructive = true,
            onConfirm = {
                showDeleteDialog?.let { viewModel.deleteProvider(it) }
                showDeleteDialog = null
            },
            onCancel = { showDeleteDialog = null }
        )
    }

    if (showLanguageDialog) {
        val activity = LocalContext.current as? Activity
        LanguageSelectorDialog(
            currentLanguage = language,
            onSelect = { lang ->
                viewModel.setLanguage(lang)
                showLanguageDialog = false
                activity?.recreate()
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

@Composable
private fun TabBar(
    selectedTab: SettingsTab,
    onTabSelected: (SettingsTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SettingsTab.entries.forEach { tab ->
            val isSelected = selectedTab == tab
            val textColor by animateColorAsState(
                targetValue = if (isSelected) NexaraColors.Primary else NexaraColors.Outline,
                animationSpec = tween(200),
                label = "tabTextColor"
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onTabSelected(tab) }
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    style = NexaraTypography.labelMedium,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(2.dp)
                        .background(
                            if (isSelected) NexaraColors.Primary else NexaraColors.Primary.copy(alpha = 0f),
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun AppSettingsContent(
    viewModel: SettingsViewModel,
    userName: String,
    currentModel: String,
    activeSources: Int,
    tokenCost: String,
    language: String,
    themeMode: String,
    haptic: Boolean,
    logEnabled: Boolean,
    onNavigateToSecondary: (String) -> Unit,
    onEditName: () -> Unit,
    onShowLanguageDialog: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { UserProfileHeader(userName = userName, onEditName = onEditName) }
        item { Spacer(modifier = Modifier.height(16.dp)) }

        item { SettingsSectionHeader(stringResource(R.string.settings_section_general)) }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Key,
                title = stringResource(R.string.settings_language),
                subtitle = if (language == "zh") stringResource(R.string.settings_language_zh) else stringResource(R.string.settings_language_en),
                onClick = { onShowLanguageDialog() }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_appearance),
                subtitle = when (themeMode) {
                    "light" -> stringResource(R.string.settings_theme_light)
                    "system" -> stringResource(R.string.settings_theme_system)
                    else -> stringResource(R.string.settings_theme_dark)
                },
                onClick = { onNavigateToSecondary("theme_config") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Visibility,
                title = stringResource(R.string.settings_theme_color),
                subtitle = stringResource(R.string.settings_theme_color_desc),
                onClick = { onNavigateToSecondary("theme_config") }
            )
        }
        item {
            SettingsToggle(
                title = stringResource(R.string.settings_haptic),
                checked = haptic,
                onCheckedChange = { viewModel.setHaptic(it) }
            )
        }

        item { SettingsSectionHeader(stringResource(R.string.settings_section_model_presets)) }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Speed,
                title = stringResource(R.string.settings_model_summary),
                subtitle = currentModel,
                onClick = { onNavigateToSecondary("provider_form") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Speed,
                title = stringResource(R.string.settings_model_image),
                subtitle = stringResource(R.string.settings_not_set),
                onClick = { onNavigateToSecondary("provider_form") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Speed,
                title = stringResource(R.string.settings_model_embedding),
                subtitle = "BAAI/bge-m3",
                onClick = { onNavigateToSecondary("provider_form") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Speed,
                title = stringResource(R.string.settings_model_rerank),
                subtitle = stringResource(R.string.settings_not_set),
                onClick = { onNavigateToSecondary("provider_form") }
            )
        }

        item { SettingsSectionHeader(stringResource(R.string.settings_section_knowledge)) }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Settings,
                title = stringResource(R.string.settings_rag_config),
                subtitle = stringResource(R.string.settings_rag_desc),
                onClick = { onNavigateToSecondary("rag_global_config") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_advanced_retrieval),
                subtitle = stringResource(R.string.settings_retrieval_desc),
                onClick = { onNavigateToSecondary("rag_advanced") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Edit,
                title = stringResource(R.string.settings_token_usage),
                subtitle = stringResource(R.string.settings_token_cost_month, tokenCost),
                onClick = { onNavigateToSecondary("token_usage") }
            )
        }

        item { SettingsSectionHeader(stringResource(R.string.settings_section_tools)) }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Speed,
                title = stringResource(R.string.settings_web_search),
                subtitle = stringResource(R.string.settings_web_search_desc),
                onClick = { onNavigateToSecondary("search_config") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Settings,
                title = stringResource(R.string.settings_workbench),
                subtitle = stringResource(R.string.settings_workbench_desc),
                onClick = { onNavigateToSecondary("workbench") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_skills),
                subtitle = stringResource(R.string.settings_skills_desc),
                onClick = { onNavigateToSecondary("skills_config") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Edit,
                title = stringResource(R.string.settings_local_models),
                subtitle = stringResource(R.string.settings_local_models_desc),
                onClick = { onNavigateToSecondary("local_models") }
            )
        }

        item { SettingsSectionHeader(stringResource(R.string.settings_section_data)) }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Settings,
                title = stringResource(R.string.settings_backup),
                subtitle = stringResource(R.string.settings_backup_desc),
                onClick = { onNavigateToSecondary("backup_settings") }
            )
        }
        item {
            SettingsToggle(
                title = stringResource(R.string.settings_logs),
                description = stringResource(R.string.settings_logs_desc),
                checked = logEnabled,
                onCheckedChange = { viewModel.setLogEnabled(it) }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Edit,
                title = stringResource(R.string.settings_export_logs),
                subtitle = stringResource(R.string.settings_export_logs_desc),
                onClick = { }
            )
        }

        item { SettingsSectionHeader(stringResource(R.string.settings_section_about)) }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_about_nexara),
                subtitle = stringResource(R.string.settings_version, "1.0.0"),
                onClick = { }
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Nexara AI • Project Narcis",
                    style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                    color = NexaraColors.Outline
                )
                Text(
                    text = "github.com/promenar/nexara",
                    style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = SpaceGrotesk),
                    color = NexaraColors.Primary.copy(alpha = 0.6f)
                )
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun ProviderSettingsContent(
    providers: List<ProviderListItem>,
    onNavigateToSecondary: (String) -> Unit,
    onDeleteProvider: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            AddProviderButton(onClick = { onNavigateToSecondary("provider_form") })
        }

        if (providers.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.settings_provider_empty),
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(providers.size) { index ->
                val provider = providers[index]
                ProviderCard(
                    provider = provider,
                    onEdit = { onNavigateToSecondary(com.promenar.nexara.navigation.NavDestinations.providerForm(provider.id)) },
                    onManageModels = { onNavigateToSecondary(com.promenar.nexara.navigation.NavDestinations.providerModels(provider.id)) },
                    onDelete = { onDeleteProvider(provider.id) }
                )
            }
        }
    }
}

@Composable
private fun UserProfileHeader(
    userName: String,
    onEditName: () -> Unit
) {
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            NexaraColors.PrimaryContainer.copy(alpha = 0.5f),
            NexaraColors.TertiaryContainer.copy(alpha = 0.5f)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexaraShapes.large)
            .background(gradientBrush)
            .padding(1.dp)
    ) {
        NexaraGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaraShapes.large as RoundedCornerShape
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexaraColors.SurfaceContainer.copy(alpha = 0.9f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(NexaraColors.Primary, NexaraColors.Tertiary)
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = userName.take(2).uppercase(),
                        style = NexaraTypography.headlineMedium,
                        color = NexaraColors.OnPrimary
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = userName,
                        style = NexaraTypography.headlineMedium,
                        color = NexaraColors.OnSurface
                    )
                    Text(
                        text = "Pro Plan · Active",
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onEditName,
                    modifier = Modifier
                        .background(NexaraColors.GlassSurface, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.settings_edit_name),
                        tint = NexaraColors.Primary
                    )
                }
            }
        }
    }
}

@Composable
private fun AddProviderButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexaraShapes.large)
            .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
            .border(
                1.dp,
                Brush.linearGradient(listOf(NexaraColors.Outline, NexaraColors.Outline)),
                NexaraShapes.large
            )
            .clickable(onClick = onClick)
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = NexaraColors.Primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.settings_add_provider),
                style = NexaraTypography.labelMedium,
                color = NexaraColors.Primary
            )
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderListItem,
    onEdit: () -> Unit,
    onManageModels: () -> Unit,
    onDelete: () -> Unit
) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(NexaraColors.SurfaceHigh, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = NexaraColors.Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = provider.name,
                            style = NexaraTypography.headlineMedium,
                            color = NexaraColors.OnSurface
                        )
                        Text(
                            text = provider.typeName,
                            style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.shared_btn_edit),
                            tint = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.shared_btn_delete),
                            tint = NexaraColors.Error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = provider.baseUrl,
                style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp, fontFamily = SpaceGrotesk),
                color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(NexaraShapes.medium)
                    .background(NexaraColors.SurfaceHigh)
                    .clickable(onClick = onManageModels)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_manage_models),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.Primary
                )
            }
        }
    }
}

@Composable
private fun NameEditDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NexaraColors.SurfaceContainer,
        title = {
            Text(
                stringResource(R.string.settings_edit_name),
                style = NexaraTypography.headlineMedium,
                color = NexaraColors.OnSurface
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_edit_name_placeholder),
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NexaraColors.Primary,
                        unfocusedBorderColor = NexaraColors.GlassBorder,
                        focusedContainerColor = NexaraColors.SurfaceContainer,
                        unfocusedContainerColor = NexaraColors.SurfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) {
                Text(stringResource(R.string.settings_btn_save), color = NexaraColors.Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_btn_cancel), color = NexaraColors.OnSurfaceVariant)
            }
        }
    )
}

@Composable
private fun LanguageSelectorDialog(
    currentLanguage: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.settings_language),
                style = NexaraTypography.headlineMedium,
                color = NexaraColors.OnSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LanguageOption(
                    label = "English",
                    selected = currentLanguage == "en",
                    onClick = { onSelect("en") }
                )
                LanguageOption(
                    label = "中文 (简体)",
                    selected = currentLanguage == "zh",
                    onClick = { onSelect("zh") }
                )
            }
        },
        confirmButton = {},
        containerColor = NexaraColors.SurfaceDim
    )
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        if (selected) NexaraColors.Primary.copy(alpha = 0.1f) else Color.Transparent,
        label = "langBg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = NexaraTypography.labelMedium,
            color = if (selected) NexaraColors.Primary else NexaraColors.OnSurface
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = NexaraColors.Primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
