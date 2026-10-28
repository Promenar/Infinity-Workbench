package com.promenar.infinity.ui.hub

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinityTabHeader
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPopoverAlign
import com.promenar.infinity.ui.common.InfinityPopoverAnchor
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.settings.EnvironmentConnectionRows
import com.promenar.infinity.ui.settings.EnvironmentConnectionState
import com.promenar.infinity.ui.settings.SettingsViewModel
import com.promenar.infinity.ui.settings.rememberEnvironmentConnectionState
import com.promenar.infinity.ui.settings.SettingsAsyncErrorCode
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.avatar.rememberAvatarCropLauncher
import com.promenar.infinity.ui.theme.forInput
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsHomeScreen(
    onNavigateToSecondary: (String) -> Unit,
    selectedRoute: String? = null,
) {
    val context = LocalContext.current
    val viewModel = viewModel<SettingsViewModel>(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    val language by viewModel.language.collectAsState()
    val settingsError by viewModel.settingsError.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val environment = rememberEnvironmentConnectionState()
    val diagnosticsChooserTitle = stringResource(R.string.settings_diagnostics_export)
    val coroutineScope = rememberCoroutineScope()

    var showNameEditor by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(userName) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val languageAnchor = rememberInfinityPopoverAnchor()
    val avatarCropLauncher = rememberAvatarCropLauncher(
        onCropped = { viewModel.updateUserAvatar(it.toString()) },
        onFailure = viewModel::reportAvatarImportFailure,
    )

    val state = remember(userName, userAvatar, settingsError, selectedRoute, language, mcpServers.size, environment) {
        UserSettingsHomeScreenState(
            userName = userName,
            selectedRoute = selectedRoute,
            userAvatar = userAvatar,
            error = settingsError,
            localInferenceAvailable = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
            language = language,
            mcpServerCount = mcpServers.size,
            environment = environment,
        )
    }
    val exportDiagnostics: () -> Unit = {
        coroutineScope.launch {
            val file = com.promenar.infinity.utils.InfinityLogger.prepareDiagnosticExport(context)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/x-ndjson"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, diagnosticsChooserTitle),
            )
        }
    }

    val actions = UserSettingsHomeScreenActions(
            onChangeAvatar = {
                avatarCropLauncher.launch()
            },
            onEditName = {
                editingName = userName
                showNameEditor = true
            },
            onShowLanguageDialog = { if (!languageAnchor.consumeRecentDismiss()) showLanguageDialog = true },
            onNavigateToSecondary = onNavigateToSecondary,
            onAboutClick = {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(context.getString(R.string.ux2_project_repository_url)),
                    ),
                )
            },
            onOpenGithub = {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(context.getString(R.string.ux2_project_repository_url)),
                    ),
                )
            },
            onRetryError = viewModel::retryLastError,
            onExportDiagnostics = exportDiagnostics,
        )

    UserSettingsHomeScreenContent(state = state, actions = actions, languageAnchor = languageAnchor)

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

    if (showLanguageDialog) {
        val activity = LocalContext.current as? Activity
        LanguageSelectorSheet(
            currentLanguage = language,
            onSelect = { lang ->
                viewModel.setLanguage(lang)
                showLanguageDialog = false
                activity?.recreate()
            },
            onDismiss = { showLanguageDialog = false },
            popover = InfinityPopoverSpec(languageAnchor, align = InfinityPopoverAlign.Auto),
        )
    }
}

internal data class UserSettingsHomeScreenState(
    val userName: String = "",
    /** 宽屏双列时右列正在显示的根页面，对应条目高亮；单列为 null。 */
    val selectedRoute: String? = null,
    val userAvatar: String? = null,
    val localInferenceAvailable: Boolean = false,
    val error: SettingsAsyncErrorCode? = null,
    /** 偏好中保存的界面语言（"zh" / "en"），语言行按它显示，不读系统区域。 */
    val language: String = "zh",
    val mcpServerCount: Int? = null,
    val environment: EnvironmentConnectionState = EnvironmentConnectionState(),
)

internal data class UserSettingsHomeScreenActions(
    val onChangeAvatar: () -> Unit = {},
    val onEditName: () -> Unit = {},
    val onShowLanguageDialog: () -> Unit = {},
    val onNavigateToSecondary: (String) -> Unit = {},
    val onAboutClick: () -> Unit = {},
    val onOpenGithub: () -> Unit = {},
    val onRetryError: () -> Unit = {},
    val onExportDiagnostics: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserSettingsHomeScreenContent(
    state: UserSettingsHomeScreenState,
    actions: UserSettingsHomeScreenActions,
    /** 语言行的位置：宽窗口下语言选择贴着该行弹出。 */
    languageAnchor: InfinityPopoverAnchor? = null,
) {
    InfinitySettingsPageLayout(
        title = stringResource(R.string.settings_title),
        modifier = Modifier.testTag(UiTags.SETTINGS_ROOT),
        horizontalContentPadding = 0.dp,
        header = { InfinityTabHeader(title = stringResource(R.string.settings_title)) },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(UiTags.SETTINGS_APP_LIST),
            contentPadding = contentPadding,
        ) {
            item {
                BettboxListGroup(
                    modifier = Modifier.padding(
                        start = InfinitySpacing.ScreenHorizontal,
                        top = InfinitySpacing.Small,
                        end = InfinitySpacing.ScreenHorizontal,
                        bottom = InfinitySpacing.SectionGap,
                    ),
                ) {
                    UserProfileListItem(
                        userName = state.userName,
                        avatarUri = state.userAvatar,
                        onEditName = actions.onEditName,
                        onChangeAvatar = actions.onChangeAvatar,
                    )
                    state.error?.let { error ->
                        Text(
                            text = stringResource(
                                when (error) {
                                    SettingsAsyncErrorCode.AVATAR_IMPORT_FAILED -> R.string.settings_error_avatar
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal),
                        )
                        TextButton(
                            onClick = actions.onRetryError,
                            modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal),
                        ) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_section_general),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    InfinityValueRow(
                        icon = Icons.Rounded.Language,
                        title = stringResource(R.string.settings_language),
                        value = stringResource(if (state.language == "en") R.string.settings_language_en else R.string.settings_language_zh),
                        onClick = actions.onShowLanguageDialog,
                        modifier = if (languageAnchor != null) Modifier.infinityPopoverAnchor(languageAnchor) else Modifier,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Palette,
                        title = stringResource(R.string.settings_appearance),
                        subtitle = stringResource(R.string.settings_appearance_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.THEME_CONFIG) },
                        selected = state.selectedRoute == NavDestinations.THEME_CONFIG,
                    )
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_ia_section_models),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Storage,
                        title = stringResource(R.string.settings_provider_management),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.PROVIDER_LIST) },
                        selected = state.selectedRoute == NavDestinations.PROVIDER_LIST,
                        modifier = Modifier.testTag(UiTags.SETTINGS_PROVIDER_ENTRY),
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.SmartToy,
                        title = stringResource(R.string.settings_default_models),
                        subtitle = stringResource(R.string.settings_ia_default_models_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.DEFAULT_MODELS) },
                        selected = state.selectedRoute == NavDestinations.DEFAULT_MODELS,
                        modifier = Modifier.testTag(UiTags.SETTINGS_DEFAULT_MODELS_ENTRY),
                    )
                    if (state.localInferenceAvailable) {
                        Box(modifier = Modifier.testTag(UiTags.SETTINGS_LOCAL_INFERENCE_ENTRY)) {
                            UserSettingsNavigationItem(
                                icon = Icons.Rounded.Memory,
                                title = stringResource(R.string.settings_local_models),
                                subtitle = stringResource(R.string.settings_local_models_desc),
                                onClick = { actions.onNavigateToSecondary(NavDestinations.LOCAL_MODELS) },
                                selected = state.selectedRoute == NavDestinations.LOCAL_MODELS,
                            )
                        }
                    }
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_ia_section_agent_runtime),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Loop,
                        title = stringResource(R.string.settings_execution_budget),
                        subtitle = stringResource(R.string.settings_ia_budget_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.AGENT_BUDGET) },
                        selected = state.selectedRoute == NavDestinations.AGENT_BUDGET,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Build,
                        title = stringResource(R.string.settings_ia_builtin_tools_title),
                        subtitle = stringResource(R.string.settings_ia_builtin_tools_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.BUILTIN_TOOLS) },
                        selected = state.selectedRoute == NavDestinations.BUILTIN_TOOLS,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.AutoAwesome,
                        title = stringResource(R.string.settings_ia_skills_title),
                        subtitle = stringResource(R.string.settings_ia_skills_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.SKILLS_CONFIG) },
                        selected = state.selectedRoute == NavDestinations.SKILLS_CONFIG,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Hub,
                        title = stringResource(R.string.settings_ia_mcp_title),
                        subtitle = when (val count = state.mcpServerCount) {
                            null, 0 -> stringResource(R.string.settings_ia_mcp_empty)
                            else -> pluralStringResource(R.plurals.settings_ia_mcp_count, count, count)
                        },
                        onClick = { actions.onNavigateToSecondary(NavDestinations.MCP_SERVERS) },
                        selected = state.selectedRoute == NavDestinations.MCP_SERVERS,
                    )
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_ia_section_knowledge),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Psychology,
                        title = stringResource(R.string.settings_ia_kb_settings_title),
                        subtitle = stringResource(R.string.settings_ia_kb_settings_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.RAG_GLOBAL_CONFIG) },
                        selected = state.selectedRoute == NavDestinations.RAG_GLOBAL_CONFIG,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.AccountTree,
                        title = stringResource(R.string.settings_ia_kg_title),
                        subtitle = stringResource(R.string.settings_ia_kg_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.RAG_ADVANCED_KG) },
                        selected = state.selectedRoute == NavDestinations.RAG_ADVANCED_KG,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Bookmarks,
                        title = stringResource(R.string.ltm_title),
                        subtitle = stringResource(R.string.settings_ia_ltm_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.LONG_TERM_MEMORY) },
                        selected = state.selectedRoute == NavDestinations.LONG_TERM_MEMORY,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Tune,
                        title = stringResource(R.string.settings_ia_debug_title),
                        subtitle = stringResource(R.string.settings_ia_debug_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.RAG_DEBUG) },
                        selected = state.selectedRoute == NavDestinations.RAG_DEBUG,
                    )
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_ia_section_environment),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    EnvironmentConnectionRows(
                        state = state.environment,
                        onOpen = actions.onNavigateToSecondary,
                        selectedRoute = state.selectedRoute,
                    )
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_ia_section_data),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Toll,
                        title = stringResource(R.string.settings_token_usage),
                        subtitle = stringResource(R.string.settings_batch1_home_token_summary),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.TOKEN_USAGE) },
                        selected = state.selectedRoute == NavDestinations.TOKEN_USAGE,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Backup,
                        title = stringResource(R.string.settings_backup),
                        subtitle = stringResource(R.string.settings_backup_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.BACKUP_SETTINGS) },
                        selected = state.selectedRoute == NavDestinations.BACKUP_SETTINGS,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.BugReport,
                        title = stringResource(R.string.settings_diagnostics_export),
                        onClick = actions.onExportDiagnostics,
                    )
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_section_about),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.settings_about_infinity),
                        onClick = actions.onAboutClick,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.size(InfinitySpacing.Large))
                GitHubProjectFooter(onOpen = actions.onOpenGithub)
                Spacer(modifier = Modifier.size(InfinitySpacing.XXLarge))
            }
        }
    }
}

@Composable
private fun UserSettingsNavigationItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    InfinitySettingsItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        modifier = if (selected) modifier.background(MaterialTheme.colorScheme.secondaryContainer) else modifier,
        showChevron = true,
        selected = selected.takeIf { it },
    )
}

@Composable
private fun UserProfileListItem(
    userName: String,
    avatarUri: String?,
    onEditName: () -> Unit,
    onChangeAvatar: () -> Unit
) {
    val editAvatarDescription = stringResource(R.string.settings_edit_avatar)
    val editNameDescription = stringResource(R.string.settings_edit_name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = editNameDescription,
                onClick = onEditName,
            )
            .padding(
                horizontal = InfinitySpacing.ScreenHorizontal,
                vertical = InfinitySpacing.XSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .testTag(UiTags.SETTINGS_USER_AVATAR)
                .clickable(
                    role = Role.Button,
                    onClickLabel = editAvatarDescription,
                    onClick = onChangeAvatar,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUri != null) {
                    AsyncImage(
                        model = avatarUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = userName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 22.sp,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(InfinitySpacing.Large))
        Text(
            text = userName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 设置首页底部：左侧一行“Infinity Workbench 版本名（versionCode）· 渠道”，点击复制版本信息；
 * 行尾 GitHub 标志按钮打开项目主页。两个点击目标各自满足 48dp 触控区。
 */
@Composable
internal fun GitHubProjectFooter(
    onOpen: () -> Unit,
    versionInfo: AppVersionInfo = AppVersionInfo.current(),
) {
    val context = LocalContext.current
    val channelLabel = stringResource(
        if (versionInfo.isSideload) R.string.ux2_settings_channel_sideload else R.string.ux2_settings_channel_play,
    )
    val footerText = appVersionFooterText(versionInfo, stringResource(R.string.settings_ia_home_footer), channelLabel)
    val copyText = appVersionCopyText(versionInfo, stringResource(R.string.ux2_settings_version_copy_text), channelLabel)
    val copyLabel = stringResource(R.string.ux2_settings_version_copy_action)
    val copiedToast = stringResource(R.string.ux2_settings_version_copied)
    val githubLabel = stringResource(R.string.ux2_settings_github_open)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .sizeIn(minHeight = InfinitySpacing.MinimumTouchTarget)
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button, onClickLabel = copyLabel) {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(copyLabel, copyText))
                    // Android 13 起系统自带复制提示，只在更早版本补一条。
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                        android.widget.Toast.makeText(context, copiedToast, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(horizontal = InfinitySpacing.Small)
                .testTag("settings_version_footer"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = footerText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .size(InfinitySpacing.MinimumTouchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = githubLabel, onClick = onOpen)
                .semantics { contentDescription = githubLabel }
                .testTag("settings_github_button"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github_mark),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_edit_name),
        message = "",
        confirmText = stringResource(R.string.settings_btn_save),
        cancelText = stringResource(R.string.settings_btn_cancel),
        onConfirm = { onSave(name) },
        onCancel = onDismiss,
        content = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                singleLine = true
            )
        },
    )
}

@Composable
internal fun LanguageSelectorSheet(
    currentLanguage: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    popover: InfinityPopoverSpec? = null,
) {
    InfinityBottomSheet(
        show = true,
        scrollContent = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_language),
        popover = popover,
    ) {
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
}

@Composable
internal fun LanguageOption(
    label: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    InfinitySettingsItem(
                horizontalPadding = 0.dp,
        title = label,
        onClick = onSelect,
        role = Role.RadioButton,
        selected = isSelected,
        trailingContent = {
            if (isSelected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
        },
    )
}
