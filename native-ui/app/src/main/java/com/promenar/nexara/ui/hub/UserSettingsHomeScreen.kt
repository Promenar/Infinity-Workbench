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
import androidx.compose.ui.res.stringResource
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
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.SpaceGrotesk
import com.promenar.nexara.ui.theme.Manrope
import com.yalantis.ucrop.UCrop
import android.net.Uri
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.promenar.nexara.data.remote.protocol.ProtocolType
import java.io.File

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
                setToolbarTitle(context.getString(R.string.settings_edit_avatar))
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

    Scaffold(
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
                            tokenCost = tokenCost,
                            language = language,
                            themeMode = themeMode,
                            haptic = haptic,
                            onNavigateToSecondary = onNavigateToSecondary,
                            userAvatar = userAvatar,
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
                            onAboutClick = {
                                onNavigateToSecondary("developer_panel")
                            }
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

    val allModels by viewModel.providerModels.collectAsState()
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
        val currentIdState = when (type) {
            "summary" -> viewModel.summaryModelId.collectAsState()
            "image" -> viewModel.imageModelId.collectAsState()
            "embedding" -> viewModel.embeddingModelId.collectAsState()
            "rerank" -> viewModel.rerankModelId.collectAsState()
            else -> remember { mutableStateOf("") }
        }
        val currentId = currentIdState.value

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
    viewModel: SettingsViewModel,
    userName: String,
    tokenCost: String,
    language: String,
    themeMode: String,
    haptic: Boolean,
    onNavigateToSecondary: (String) -> Unit,
    userAvatar: String?,
    onChangeAvatar: () -> Unit,
    onEditName: () -> Unit,
    onShowLanguageDialog: () -> Unit,
    onShowModelPicker: (String) -> Unit,
    onAboutClick: () -> Unit
) {
    // 收集模型列表用于解析模型 ID → 友好名称
    val allModels by viewModel.providerModels.collectAsState()

    /**
     * 将模型 ID 解析为友好的显示名称。
     * 优先级：已加载模型的 name → ModelSpec.note → 原始 ID
     */
    fun resolveModelName(id: String): String {
        if (id.isEmpty()) return ""
        val loaded = allModels.find { it.id == id }
        if (loaded != null && loaded.name.isNotEmpty() && loaded.name != id) {
            return loaded.name
        }
        // 回退：从静态模型规格表查找友好名称
        val spec = com.promenar.nexara.data.model.findModelSpec(id)
        return spec?.note ?: id
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { UserProfileHeader(userName = userName, avatarUri = userAvatar, onEditName = onEditName, onChangeAvatar = onChangeAvatar) }
        item { Spacer(modifier = Modifier.height(8.dp)) }

        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Key,
                title = stringResource(R.string.settings_language),
                subtitle = if (language == "zh") stringResource(R.string.settings_language_zh) else stringResource(R.string.settings_language_en),
                onClick = { onShowLanguageDialog() }
            )
        }

        // Haptic feedback is now enabled by default and removed from UI

        item {
            val summaryId by viewModel.summaryModelId.collectAsState()
            NexaraSettingsItem(
                icon = Icons.Rounded.Psychology,
                title = stringResource(R.string.settings_model_summary),
                subtitle = resolveModelName(summaryId).ifEmpty { stringResource(R.string.settings_not_set) },
                onClick = { onShowModelPicker("summary") }
            )
        }
        item {
            val imageId by viewModel.imageModelId.collectAsState()
            NexaraSettingsItem(
                icon = Icons.Rounded.Image,
                title = stringResource(R.string.settings_model_image),
                subtitle = resolveModelName(imageId).ifEmpty { stringResource(R.string.settings_not_set) },
                onClick = { onShowModelPicker("image") }
            )
        }
        item {
            val embeddingId by viewModel.embeddingModelId.collectAsState()
            NexaraSettingsItem(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.settings_model_embedding),
                subtitle = resolveModelName(embeddingId).ifEmpty { stringResource(R.string.settings_not_set) },
                onClick = { onShowModelPicker("embedding") }
            )
        }
        item {
            val rerankId by viewModel.rerankModelId.collectAsState()
            NexaraSettingsItem(
                icon = Icons.Rounded.Route,
                title = stringResource(R.string.settings_model_rerank),
                subtitle = resolveModelName(rerankId).ifEmpty { stringResource(R.string.settings_not_set) },
                onClick = { onShowModelPicker("rerank") }
            )
        }

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

        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Settings,
                title = stringResource(R.string.settings_backup),
                subtitle = stringResource(R.string.settings_backup_desc),
                onClick = { onNavigateToSecondary("backup_settings") }
            )
        }
        // Logs settings removed from UI

        item {
            NexaraSettingsItem(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_about_nexara),
                subtitle = stringResource(R.string.settings_version, "1.0.0"),
                onClick = onAboutClick
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
        item {
            val context = LocalContext.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/promenar/nexara"))
                        context.startActivity(intent)
                    },
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
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
            items(providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    onClick = { onNavigateToSecondary("provider_models/${provider.id}") },
                    onEdit = { onNavigateToSecondary("provider_form?providerId=${provider.id}") },
                    onDelete = { onDeleteProvider(provider.id) }
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
                    .clickable { onChangeAvatar() },
                contentAlignment = Alignment.Center
            ) {
                if (avatarUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(avatarUri)
                            .crossfade(true)
                            .diskCacheKey(avatarUri)
                            .memoryCacheKey(avatarUri)
                            .build(),
                        contentDescription = "Avatar",
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
                Text(
                    text = "ID: 8823192",
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant
                )
            }

            IconButton(onClick = onEditName) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
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
            .clickable { onClick() },
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
    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = NexaraShapes.medium as RoundedCornerShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val providerIcon = remember(provider.typeName) {
                ProtocolType.entries.find { it.displayName == provider.typeName }?.iconRes
            }

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
                    color = NexaraColors.OnSurface
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = provider.typeName,
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnSurfaceVariant
                    )
                    
                    // 分隔小点
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(NexaraColors.Outline.copy(alpha = 0.5f))
                    )
                    
                    Text(
                        text = provider.baseUrl.removePrefix("https://").removePrefix("http://"),
                        style = NexaraTypography.labelMedium.copy(fontFamily = SpaceGrotesk, fontSize = 11.sp),
                        color = NexaraColors.Outline,
                        maxLines = 1
                    )
                }
            }

            // 操作区 - 增加间距和对齐
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = stringResource(R.string.settings_manage_models),
                        tint = NexaraColors.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
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
