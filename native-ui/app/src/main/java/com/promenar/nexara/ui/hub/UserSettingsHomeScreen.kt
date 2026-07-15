package com.promenar.nexara.ui.hub

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.ModelItem
import com.promenar.nexara.ui.common.ModelCapability
import com.promenar.nexara.ui.common.ModelPicker
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.SpaceGrotesk
import com.promenar.nexara.ui.theme.Manrope
import com.yalantis.ucrop.UCrop
import android.net.Uri
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.BuildConfig
import java.io.File

internal enum class SettingsTab(val labelRes: Int) {
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
    val userAvatar by viewModel.userAvatar.collectAsState()
    val tokenCost by viewModel.tokenCostThisMonth.collectAsState()
    val language by viewModel.language.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val haptic by viewModel.hapticEnabled.collectAsState()
    val providers by viewModel.providers.collectAsState()

    val selectedSettingsTab by viewModel.selectedSettingsTab.collectAsState()
    val selectedTab = remember(selectedSettingsTab) { SettingsTab.entries[selectedSettingsTab] }
    var showNameEditor by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(userName) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showModelPickerType by remember { mutableStateOf<String?>(null) }
    val editAvatarTitle = stringResource(R.string.settings_edit_avatar)

    val cropLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) {
                viewModel.updateUserAvatar(resultUri.toString())
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            // 启动 UCrop 裁切
            val destinationUri = Uri.fromFile(File(context.cacheDir, "temp_crop_${System.currentTimeMillis()}.jpg"))
            val options = UCrop.Options().apply {
                setCircleDimmedLayer(true) // 显示圆形遮罩
                setShowCropFrame(false)
                setShowCropGrid(false)
                setToolbarColor(android.graphics.Color.BLACK)
                setStatusBarColor(android.graphics.Color.BLACK)
                setActiveControlsWidgetColor(android.graphics.Color.parseColor("#888DFF")) // Primary color
                setToolbarTitle(editAvatarTitle)
            }
            
            val intent = UCrop.of(uri, destinationUri)
                .withAspectRatio(1f, 1f)
                .withMaxResultSize(512, 512)
                .withOptions(options)
                .getIntent(context)
            
            cropLauncher.launch(intent)
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == SettingsTab.PROVIDER) {
            viewModel.refreshProviders()
        }
    }

    val allModels by viewModel.providerModels.collectAsState()
    val summaryModelId by viewModel.summaryModelId.collectAsState()
    val imageModelId by viewModel.imageModelId.collectAsState()
    val embeddingModelId by viewModel.embeddingModelId.collectAsState()
    val rerankModelId by viewModel.rerankModelId.collectAsState()

    val state = remember(
        selectedTab, userName, userAvatar, tokenCost, language, themeMode, haptic,
        allModels, summaryModelId, imageModelId, embeddingModelId, rerankModelId,
        providers, showModelPickerType,
    ) {
        UserSettingsHomeScreenState(
            selectedTab = selectedTab,
            userName = userName,
            userAvatar = userAvatar,
            tokenCost = tokenCost,
            language = language,
            themeMode = themeMode,
            hapticEnabled = haptic,
            summaryModelName = resolveModelName(allModels, summaryModelId),
            imageModelName = resolveModelName(allModels, imageModelId),
            embeddingModelName = resolveModelName(allModels, embeddingModelId),
            rerankModelName = resolveModelName(allModels, rerankModelId),
            providers = providers,
            versionName = BuildConfig.VERSION_NAME,
            localInferenceAvailable = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
        )
    }
    val actions = UserSettingsHomeScreenActions(
        onTabSelected = { viewModel.setSelectedSettingsTab(it.ordinal) },
        onChangeAvatar = {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        },
        onEditName = {
            editingName = userName
            showNameEditor = true
        },
        onShowLanguageDialog = { showLanguageDialog = true },
        onShowModelPicker = { type ->
            viewModel.refreshProviders()
            showModelPickerType = type
        },
        onNavigateToSecondary = onNavigateToSecondary,
        onAboutClick = {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/Promenar/Nexara"),
                ),
            )
        },
        onOpenGithub = {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/promenar/nexara"),
                ),
            )
        },
        onRequestDeleteProvider = { providerId -> showDeleteDialog = providerId },
    )

    UserSettingsHomeScreenContent(state = state, actions = actions)

    val modelItems by remember {
        derivedStateOf {
            allModels
                .filter { it.enabled }
                .map { model ->
                    ModelItem(
                        id = model.id,
                        name = model.name.ifEmpty { model.id },
                        providerName = model.providerName,
                        capabilities = model.capabilities.mapNotNull { cap ->
                            try {
                                ModelCapability.valueOf(cap.uppercase())
                            } catch (_: Exception) {
                                null
                            }
                        },
                        contextLength = model.contextLength
                    )
                }
        }
    }

    if (showModelPickerType != null) {
        val type = showModelPickerType!!
        val currentId = when (type) {
            "summary" -> summaryModelId
            "image" -> imageModelId
            "embedding" -> embeddingModelId
            "rerank" -> rerankModelId
            else -> ""
        }

        val filter = when (type) {
            "summary" -> "chat"
            "image" -> "image"
            "embedding" -> "embedding"
            "rerank" -> "rerank"
            else -> null
        }
        val title = when (type) {
            "summary" -> stringResource(R.string.settings_model_summary)
            "image" -> stringResource(R.string.settings_model_image)
            "embedding" -> stringResource(R.string.settings_model_embedding)
            "rerank" -> stringResource(R.string.settings_model_rerank)
            else -> ""
        }

        ModelPicker(
            show = true,
            title = title,
            filterTag = filter,
            currentModelId = currentId,
            models = modelItems,
            onDismiss = { showModelPickerType = null },
            onSelect = { id, _ ->
                viewModel.setPresetModel(type, id)
                showModelPickerType = null
            }
        )
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
        androidx.compose.ui.window.Dialog(onDismissRequest = { showDeleteDialog = null }) {
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

/**
 * 将模型 ID 解析为友好的显示名称。
 * 优先级：已加载模型的 name → ModelSpec.note → 原始 ID
 */
private fun resolveModelName(
    allModels: List<com.promenar.nexara.ui.settings.ModelInfo>,
    id: String,
): String {
    if (id.isEmpty()) return ""
    val loaded = allModels.find { it.id == id }
    if (loaded != null && loaded.name.isNotEmpty() && loaded.name != id) {
        return loaded.name
    }
    val spec = com.promenar.nexara.data.model.findModelSpec(id)
    return spec?.note ?: id
}

// =====================================================================================
// 状态提升 seam —— Content 只消费显式 state/actions，不触碰 ViewModel / Context / Intent
// =====================================================================================

internal data class UserSettingsHomeScreenState(
    val selectedTab: SettingsTab = SettingsTab.APP,
    val userName: String = "",
    val userAvatar: String? = null,
    val tokenCost: String = "",
    val language: String = "en",
    val themeMode: String = "system",
    val hapticEnabled: Boolean = true,
    val summaryModelName: String = "",
    val imageModelName: String = "",
    val embeddingModelName: String = "",
    val rerankModelName: String = "",
    val providers: List<ProviderListItem> = emptyList(),
    val versionName: String = "",
    val localInferenceAvailable: Boolean = false,
)

internal data class UserSettingsHomeScreenActions(
    val onTabSelected: (SettingsTab) -> Unit = {},
    val onChangeAvatar: () -> Unit = {},
    val onEditName: () -> Unit = {},
    val onShowLanguageDialog: () -> Unit = {},
    val onShowModelPicker: (String) -> Unit = {},
    val onNavigateToSecondary: (String) -> Unit = {},
    val onAboutClick: () -> Unit = {},
    val onOpenGithub: () -> Unit = {},
    val onRequestDeleteProvider: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserSettingsHomeScreenContent(
    state: UserSettingsHomeScreenState,
    actions: UserSettingsHomeScreenActions,
) {
    Scaffold(
        modifier = Modifier.testTag(UiTags.SETTINGS_ROOT),
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.settings_title), style = NexaraTypography.headlineLarge)
                    }
                },
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
                selectedTab = state.selectedTab,
                onTabSelected = actions.onTabSelected,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Crossfade(
                targetState = state.selectedTab,
                animationSpec = tween(300),
                label = "settingsTab"
            ) { tab ->
                when (tab) {
                    SettingsTab.APP -> AppSettingsContent(state = state, actions = actions)
                    SettingsTab.PROVIDER -> ProviderSettingsContent(state = state, actions = actions)
                }
            }
        }
    }
}

@Composable
internal fun TabBar(
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
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .testTag(
                        when (tab) {
                            SettingsTab.APP -> UiTags.SETTINGS_TAB_APP
                            SettingsTab.PROVIDER -> UiTags.SETTINGS_TAB_PROVIDER
                        }
                    )
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { selected = isSelected }
                    .clickable(role = Role.Tab) { onTabSelected(tab) },
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
                            if (isSelected) NexaraColors.Primary else Color.Transparent,
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun AppSettingsContent(
    state: UserSettingsHomeScreenState,
    actions: UserSettingsHomeScreenActions,
) {
    val notSet = stringResource(R.string.settings_not_set)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.SETTINGS_APP_LIST),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { UserProfileHeader(userName = state.userName, avatarUri = state.userAvatar, onEditName = actions.onEditName, onChangeAvatar = actions.onChangeAvatar) }
        item { Spacer(modifier = Modifier.height(8.dp)) }

        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Key,
                title = stringResource(R.string.settings_language),
                subtitle = if (state.language == "zh") stringResource(R.string.settings_language_zh) else stringResource(R.string.settings_language_en),
                onClick = { actions.onShowLanguageDialog() }
            )
        }

        // Haptic feedback is now enabled by default and removed from UI

        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Psychology,
                title = stringResource(R.string.settings_model_summary),
                subtitle = state.summaryModelName.ifEmpty { notSet },
                onClick = { actions.onShowModelPicker("summary") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Image,
                title = stringResource(R.string.settings_model_image),
                subtitle = state.imageModelName.ifEmpty { notSet },
                onClick = { actions.onShowModelPicker("image") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.settings_model_embedding),
                subtitle = state.embeddingModelName.ifEmpty { notSet },
                onClick = { actions.onShowModelPicker("embedding") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Route,
                title = stringResource(R.string.settings_model_rerank),
                subtitle = state.rerankModelName.ifEmpty { notSet },
                onClick = { actions.onShowModelPicker("rerank") }
            )
        }

        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Settings,
                title = stringResource(R.string.settings_rag_config),
                subtitle = stringResource(R.string.settings_rag_desc),
                onClick = { actions.onNavigateToSecondary("rag_global_config") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_advanced_retrieval),
                subtitle = stringResource(R.string.settings_retrieval_desc),
                onClick = { actions.onNavigateToSecondary("rag_advanced") }
            )
        }
        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Edit,
                title = stringResource(R.string.settings_token_usage),
                subtitle = stringResource(R.string.settings_token_cost_month, state.tokenCost),
                onClick = { actions.onNavigateToSecondary("token_usage") }
            )
        }

        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_skills),
                subtitle = stringResource(R.string.settings_skills_desc),
                onClick = { actions.onNavigateToSecondary("skills_config") }
            )
        }

        if (state.localInferenceAvailable) {
            item {
                Box(modifier = Modifier.testTag(UiTags.SETTINGS_LOCAL_INFERENCE_ENTRY)) {
                    NexaraSettingsItem(
                        icon = Icons.Rounded.Edit,
                        title = stringResource(R.string.settings_local_models),
                        subtitle = stringResource(R.string.settings_local_models_desc),
                        onClick = { actions.onNavigateToSecondary("local_models") }
                    )
                }
            }
        }

        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Settings,
                title = stringResource(R.string.settings_backup),
                subtitle = stringResource(R.string.settings_backup_desc),
                onClick = { actions.onNavigateToSecondary("backup_settings") }
            )
        }
        // Logs settings removed from UI

        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_about_nexara),
                subtitle = stringResource(R.string.settings_version, state.versionName),
                onClick = actions.onAboutClick
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
        item {
            GitHubProjectFooter(onOpen = actions.onOpenGithub)
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
internal fun GitHubProjectFooter(onOpen: () -> Unit) {
    val openLinkLabel = stringResource(R.string.rag_details_open_link)
    val githubAddress = "github.com/promenar/nexara"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = githubAddress
            }
            .clickable(
                role = Role.Button,
                onClickLabel = openLinkLabel,
                onClick = onOpen,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Nexara AI • Project Narcis",
            style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
            color = NexaraColors.Outline,
        )
        Text(
            text = githubAddress,
            style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = SpaceGrotesk),
            color = NexaraColors.Primary.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ProviderSettingsContent(
    state: UserSettingsHomeScreenState,
    actions: UserSettingsHomeScreenActions,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.SETTINGS_PROVIDER_LIST),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            AddProviderButton(onClick = { actions.onNavigateToSecondary("provider_form") })
        }

        if (state.providers.isEmpty()) {
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
            items(state.providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    onClick = { actions.onNavigateToSecondary("provider_models/${provider.id}") },
                    onEdit = { actions.onNavigateToSecondary("provider_form?providerId=${provider.id}") },
                    onDelete = { actions.onRequestDeleteProvider(provider.id) }
                )
            }
        }
    }
}

@Composable
private fun UserProfileHeader(
    userName: String,
    avatarUri: String?,
    onEditName: () -> Unit,
    onChangeAvatar: () -> Unit
) {
    val editAvatarDescription = stringResource(R.string.settings_edit_avatar)
    val editNameDescription = stringResource(R.string.settings_edit_name)
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(NexaraColors.Primary, NexaraColors.Primary.copy(alpha = 0.7f))
                        )
                    )
                    .semantics { contentDescription = editAvatarDescription }
                    .clickable(
                        role = Role.Button,
                        onClickLabel = editAvatarDescription,
                    ) { onChangeAvatar() },
                contentAlignment = Alignment.Center
            ) {
                if (avatarUri != null) {
                    AsyncImage(
                        model = avatarUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Text(
                        text = userName.take(1).uppercase(),
                        style = NexaraTypography.headlineLarge,
                        color = NexaraColors.OnPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userName,
                    style = NexaraTypography.headlineMedium,
                    color = NexaraColors.OnSurface
                )
            }

            IconButton(
                onClick = onEditName,
                modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = editNameDescription,
                    tint = NexaraColors.OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AddProviderButton(
    onClick: () -> Unit
) {
    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.SETTINGS_ADD_PROVIDER)
            .sizeIn(minHeight = 48.dp)
            .clickable(role = Role.Button) { onClick() },
        shape = NexaraShapes.medium as RoundedCornerShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = NexaraColors.Primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_add_provider),
                style = NexaraTypography.labelLarge,
                color = NexaraColors.Primary
            )
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderListItem,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val manageDescription = stringResource(R.string.settings_manage_models)
    val editDescription = stringResource(R.string.shared_btn_edit)
    val deleteDescription = stringResource(R.string.shared_btn_delete)
    val localizedTypeName = when (provider.typeName) {
        ProtocolType.Generic_OpenAI_Compat.displayName ->
            stringResource(R.string.settings_provider_type_generic_openai)
        ProtocolType.Local.displayName ->
            stringResource(R.string.settings_provider_type_local)
        else -> provider.typeName
    }
    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.settingsProviderCard(provider.id))
            .clickable(
                role = Role.Button,
                onClickLabel = manageDescription,
                onClick = onClick,
            ),
        shape = NexaraShapes.medium as RoundedCornerShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val providerIcon = remember(provider.typeName) {
                ProtocolType.entries.find { it.displayName == provider.typeName }?.iconRes
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NexaraColors.Primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (providerIcon != null) {
                        Icon(
                            painter = painterResource(id = providerIcon),
                            contentDescription = null,
                            tint = NexaraColors.Primary,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Psychology,
                            contentDescription = null,
                            tint = NexaraColors.Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = NexaraTypography.headlineMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        color = NexaraColors.OnSurface,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )

                    Text(
                        text = localizedTypeName,
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )

                    Text(
                        text = provider.baseUrl.removePrefix("https://").removePrefix("http://"),
                        style = NexaraTypography.labelMedium.copy(fontFamily = SpaceGrotesk, fontSize = 11.sp),
                        color = NexaraColors.Outline,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.settingsProviderActions(provider.id)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                IconButton(
                    onClick = onClick,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = manageDescription,
                        tint = NexaraColors.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = editDescription,
                        tint = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = deleteDescription,
                        tint = NexaraColors.StatusError.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
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
        title = { Text(stringResource(R.string.settings_edit_name), style = NexaraTypography.headlineMedium) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = NexaraTypography.bodyLarge,
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) {
                Text(stringResource(R.string.settings_btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_btn_cancel))
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
        containerColor = NexaraColors.SurfaceContainer,
        title = { Text(stringResource(R.string.settings_language), style = NexaraTypography.headlineMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguageOption(
                    label = stringResource(R.string.settings_language_zh),
                    isSelected = currentLanguage == "zh",
                    onSelect = { onSelect("zh") }
                )
                LanguageOption(
                    label = stringResource(R.string.settings_language_en),
                    isSelected = currentLanguage == "en",
                    onSelect = { onSelect("en") }
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun LanguageOption(
    label: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) NexaraColors.Primary.copy(alpha = 0.1f) else Color.Transparent)
            .clickable { onSelect() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = NexaraTypography.bodyLarge, color = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurface)
        if (isSelected) {
            Icon(imageVector = Icons.Rounded.Check, contentDescription = null, tint = NexaraColors.Primary)
        }
    }
}
