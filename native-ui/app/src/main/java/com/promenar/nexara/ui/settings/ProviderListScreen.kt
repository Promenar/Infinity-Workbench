package com.promenar.nexara.ui.settings

import androidx.compose.foundation.clickable
import com.promenar.nexara.ui.common.ModelBrandTile
import com.promenar.nexara.ui.common.ModelBrandResolver
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import com.promenar.nexara.ui.common.NexaraIdentifierText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.model.UnsupportedProviderListItem
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.navigation.NavDestinations
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraMetaLine
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraDropdownMenu
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing

@Composable
fun ProviderListScreen(
    onNavigateToSecondary: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val providers by viewModel.providers.collectAsState()
    val unsupportedProviders by viewModel.unsupportedProviders.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    val state = remember(providers, unsupportedProviders) {
        ProviderListScreenState(
            providers = providers,
            unsupportedProviders = unsupportedProviders,
        )
    }

    val actions = remember(viewModel, onNavigateToSecondary, onNavigateBack) {
        ProviderListScreenActions(
            onNavigateBack = onNavigateBack,
            onAddProvider = { onNavigateToSecondary(NavDestinations.providerForm()) },
            onEditProvider = { id -> onNavigateToSecondary(NavDestinations.providerForm(id)) },
            onProviderModels = { id -> onNavigateToSecondary(NavDestinations.providerModels(id)) },
            onRequestDeleteProvider = { id -> showDeleteDialog = id }
        )
    }

    ProviderListScreenContent(
        state = state,
        actions = actions
    )

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
}

internal data class ProviderListScreenState(
    val providers: List<ProviderListItem> = emptyList(),
    val unsupportedProviders: List<UnsupportedProviderListItem> = emptyList(),
)

internal data class ProviderListScreenActions(
    val onNavigateBack: () -> Unit = {},
    val onAddProvider: () -> Unit = {},
    val onEditProvider: (String) -> Unit = {},
    val onProviderModels: (String) -> Unit = {},
    val onRequestDeleteProvider: (String) -> Unit = {}
)

@Composable
internal fun ProviderListScreenContent(
    state: ProviderListScreenState,
    actions: ProviderListScreenActions
) {
    NexaraSettingsPageLayout(
        title = stringResource(R.string.settings_provider_management),
        onBack = actions.onNavigateBack,
        horizontalContentPadding = 0.dp,
        modifier = Modifier.testTag(UiTags.SETTINGS_PROVIDER_LIST),
        actions = {
            IconButton(
                onClick = actions.onAddProvider,
                modifier = Modifier
                    .sizeIn(
                        minWidth = NexaraSpacing.MinimumTouchTarget,
                        minHeight = NexaraSpacing.MinimumTouchTarget,
                    )
                    .testTag(UiTags.SETTINGS_ADD_PROVIDER),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.settings_add_provider),
                )
            }
        }
    ) { contentPadding ->
        val compactHeightEmptyState = LocalConfiguration.current.screenHeightDp < 480

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (state.providers.isEmpty() && state.unsupportedProviders.isEmpty()) {
                item {
                    ProviderEmptyState(compactHeight = compactHeightEmptyState)
                }
            } else {
                item("provider-group") {
                    // 所有提供商放在同一分组内，协议、凭据状态与地址写在行内。
                    NexaraSettingsSection(
                        title = stringResource(R.string.settings_section_ai_models),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        state.providers.forEach { provider ->
                            ProviderCard(
                                provider = provider,
                                onClick = { actions.onProviderModels(provider.id) },
                                onEdit = { actions.onEditProvider(provider.id) },
                                onDelete = if (provider.id == "default") null else {
                                    { actions.onRequestDeleteProvider(provider.id) }
                                },
                            )
                        }
                        state.unsupportedProviders.forEach { provider ->
                            UnsupportedProviderCard(
                                provider = provider,
                                onEdit = { actions.onEditProvider(provider.id) },
                                onDelete = if (provider.id == "default") null else {
                                    { actions.onRequestDeleteProvider(provider.id) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnsupportedProviderCard(
    provider: UnsupportedProviderListItem,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var menuExpanded by remember(provider.id) { mutableStateOf(false) }
    val unsupportedDescription = stringResource(R.string.settings_provider_protocol_unsupported)
    val deleteDescription = stringResource(R.string.shared_btn_delete)
    NexaraSettingsItem(
        title = provider.name,
        subtitle = unsupportedDescription + "\n" + provider.rawProtocolId,
        subtitleMaxLines = 2,
        titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
        leadingContent = { ProviderAvatar(provider.name, baseUrl = null) },
        onClick = onEdit,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.settingsProviderCard(provider.id))
            .semantics { stateDescription = unsupportedDescription },
        trailingContent = {
            if (onDelete == null) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.common_cd_edit),
                    )
                }
            } else Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .testTag(UiTags.settingsProviderActions(provider.id))
                        .sizeIn(
                            minWidth = NexaraSpacing.MinimumTouchTarget,
                            minHeight = NexaraSpacing.MinimumTouchTarget,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "${provider.name}: $deleteDescription",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NexaraDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = {
                            Text(
                                text = deleteDescription,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        modifier = Modifier
                            .testTag("${UiTags.settingsProviderActions(provider.id)}:delete")
                            .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                            .semantics { role = Role.Button },
                    )
                }
            }
        },
    )
}

@Composable
private fun ProviderEmptyState(compactHeight: Boolean) {
    val emptyText = stringResource(R.string.settings_provider_empty)
    if (compactHeight) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = NexaraSpacing.XLarge,
                    vertical = NexaraSpacing.Small,
                ),
            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderEmptyIcon(size = NexaraSpacing.MinimumTouchTarget)
            Text(
                text = emptyText,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = NexaraSpacing.XLarge,
                    vertical = NexaraSpacing.XXLarge,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Large),
        ) {
            ProviderEmptyIcon(size = 64.dp)
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProviderEmptyIcon(size: androidx.compose.ui.unit.Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(NexaraSpacing.XXLarge),
            )
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderListItem,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var menuExpanded by remember(provider.id) { mutableStateOf(false) }
    val manageDescription = stringResource(R.string.settings_manage_models)
    val editDescription = stringResource(R.string.shared_btn_edit)
    val deleteDescription = stringResource(R.string.shared_btn_delete)
    val actionsDescription = if (onDelete == null) {
        "${provider.name}: $editDescription"
    } else {
        "${provider.name}: $editDescription / $deleteDescription"
    }
    val enabledStatus = stringResource(
        if (provider.enabled) {
            R.string.settings_provider_state_enabled
        } else {
            R.string.settings_provider_state_disabled
        },
    )
    val credentialStatus = providerCredentialStatus(provider)
    val providerStateDescription = stringResource(
        R.string.settings_provider_status_summary,
        enabledStatus,
        credentialStatus,
    )
    val localizedTypeName = when (provider.typeName) {
        ProtocolType.Generic_OpenAI_Compat.displayName ->
            stringResource(R.string.settings_provider_type_generic_openai)
        ProtocolType.Local.displayName ->
            stringResource(R.string.settings_provider_type_local)
        else -> provider.typeName
    }
    NexaraSettingsItem(
        title = provider.name,
        subtitle = listOfNotNull(localizedTypeName, if (provider.enabled) null else enabledStatus, credentialStatus)
            .joinToString(" · ") + "\n" + provider.baseUrl,
        // 窄屏上状态行可能折成两行，留第三行给地址。
        subtitleMaxLines = 3,
        titleColor = if (provider.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        leadingContent = { ProviderAvatar(provider.name, provider.baseUrl) },
        onClick = onClick,
        onClickLabel = manageDescription,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.settingsProviderCard(provider.id))
            .semantics { stateDescription = providerStateDescription },
        trailingContent = {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .testTag(UiTags.settingsProviderActions(provider.id))
                        .sizeIn(
                            minWidth = NexaraSpacing.MinimumTouchTarget,
                            minHeight = NexaraSpacing.MinimumTouchTarget,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = actionsDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NexaraDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = {
                            Text(
                                text = editDescription,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                        modifier = Modifier
                            .testTag("${UiTags.settingsProviderActions(provider.id)}:edit")
                            .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                            .semantics { role = Role.Button },
                    )
                    if (onDelete != null) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            text = {
                                Text(
                                    text = deleteDescription,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            modifier = Modifier
                                .testTag("${UiTags.settingsProviderActions(provider.id)}:delete")
                                .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                                .semantics { role = Role.Button },
                        )
                    }
                }
            }
        },
    )
}

/** 提供商头像：能识别品牌时显示品牌标志，否则显示名称首字。 */
@Composable
private fun ProviderAvatar(name: String, baseUrl: String?) {
    val brand = remember(name, baseUrl) { ModelBrandResolver.brandKey(name, baseUrl) }
    if (brand != null) {
        ModelBrandTile(modelId = brand, size = 20.dp)
        return
    }
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun providerCredentialStatus(provider: ProviderListItem): String = when (provider.protocolType) {
    ProtocolType.Google_VertexAI -> stringResource(
        if (provider.hasVertexCredentials) R.string.settings_provider_vertex_credentials_configured
        else R.string.settings_provider_vertex_credentials_not_configured,
    )
    ProtocolType.Local -> stringResource(R.string.settings_provider_credentials_not_required)
    else -> stringResource(
        if (provider.hasApiKey) R.string.settings_provider_api_key_configured
        else R.string.settings_provider_api_key_not_configured,
    )
}
