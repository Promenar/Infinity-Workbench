package com.promenar.nexara.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.navigation.NavDestinations
import com.promenar.nexara.ui.common.NexaraConfirmDialog
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

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    val state = remember(providers) {
        ProviderListScreenState(
            providers = providers
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
    val providers: List<ProviderListItem> = emptyList()
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
            if (state.providers.isEmpty()) {
                item {
                    ProviderEmptyState(compactHeight = compactHeightEmptyState)
                }
            } else {
                itemsIndexed(state.providers, key = { _, provider -> provider.id }) { index, provider ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    ProviderCard(
                        provider = provider,
                        onClick = { actions.onProviderModels(provider.id) },
                        onEdit = { actions.onEditProvider(provider.id) },
                        onDelete = if (provider.id == "default") {
                            null
                        } else {
                            { actions.onRequestDeleteProvider(provider.id) }
                        }
                    )
                }
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
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
    val credentialStatus = when (provider.protocolType) {
        ProtocolType.Google_VertexAI -> stringResource(
            if (provider.hasVertexCredentials) {
                R.string.settings_provider_vertex_credentials_configured
            } else {
                R.string.settings_provider_vertex_credentials_not_configured
            },
        )
        ProtocolType.Local -> stringResource(R.string.settings_provider_credentials_not_required)
        else -> stringResource(
            if (provider.hasApiKey) {
                R.string.settings_provider_api_key_configured
            } else {
                R.string.settings_provider_api_key_not_configured
            },
        )
    }
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
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
            .testTag(UiTags.settingsProviderCard(provider.id))
            .semantics { stateDescription = providerStateDescription }
            .clickable(
                role = Role.Button,
                onClickLabel = manageDescription,
                onClick = onClick,
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = provider.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (provider.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = "$localizedTypeName · $providerStateDescription",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = provider.baseUrl.removePrefix("https://").removePrefix("http://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
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
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
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
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                            )
                        },
                    )
                    if (onDelete != null) {
                        DropdownMenuItem(
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
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
        },
    )
}
