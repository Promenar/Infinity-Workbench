package com.promenar.nexara.ui.hub

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.promenar.nexara.BuildConfig
import com.promenar.nexara.R
import com.promenar.nexara.navigation.NavDestinations
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.settings.SettingsAsyncErrorCode
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.yalantis.ucrop.UCrop
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsHomeScreen(
    onNavigateToSecondary: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModel = viewModel<SettingsViewModel>(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    val language by viewModel.language.collectAsState()
    val settingsError by viewModel.settingsError.collectAsState()

    var showNameEditor by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(userName) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val editAvatarTitle = stringResource(R.string.settings_edit_avatar)
    val colorScheme = MaterialTheme.colorScheme

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
            val destinationUri = Uri.fromFile(File(context.cacheDir, "temp_crop_${System.currentTimeMillis()}.jpg"))
            val options = UCrop.Options().apply {
                setCircleDimmedLayer(true)
                setShowCropFrame(false)
                setShowCropGrid(false)
                setToolbarColor(colorScheme.surface.toArgb())
                setStatusBarColor(colorScheme.surface.toArgb())
                setActiveControlsWidgetColor(colorScheme.primary.toArgb())
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

    val state = remember(userName, userAvatar, settingsError) {
        UserSettingsHomeScreenState(
            userName = userName,
            userAvatar = userAvatar,
            error = settingsError,
            localInferenceAvailable = BuildConfig.LOCAL_INFERENCE_AVAILABLE
        )
    }

    val actions = UserSettingsHomeScreenActions(
            onChangeAvatar = {
                photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onEditName = {
                editingName = userName
                showNameEditor = true
            },
            onShowLanguageDialog = { showLanguageDialog = true },
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
            onRetryError = viewModel::retryLastError,
        )

    UserSettingsHomeScreenContent(state = state, actions = actions)

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

internal data class UserSettingsHomeScreenState(
    val userName: String = "",
    val userAvatar: String? = null,
    val localInferenceAvailable: Boolean = false,
    val error: SettingsAsyncErrorCode? = null,
)

internal data class UserSettingsHomeScreenActions(
    val onChangeAvatar: () -> Unit = {},
    val onEditName: () -> Unit = {},
    val onShowLanguageDialog: () -> Unit = {},
    val onNavigateToSecondary: (String) -> Unit = {},
    val onAboutClick: () -> Unit = {},
    val onOpenGithub: () -> Unit = {},
    val onRetryError: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserSettingsHomeScreenContent(
    state: UserSettingsHomeScreenState,
    actions: UserSettingsHomeScreenActions,
) {
    NexaraSettingsPageLayout(
        title = stringResource(R.string.settings_title),
        modifier = Modifier.testTag(UiTags.SETTINGS_ROOT),
        horizontalContentPadding = 0.dp,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(UiTags.SETTINGS_APP_LIST),
            contentPadding = contentPadding,
        ) {
            item {
                NexaraSettingsSection(
                    title = stringResource(R.string.settings_section_account),
                    headerStartPadding = NexaraSpacing.ScreenHorizontal,
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
                            modifier = Modifier.padding(horizontal = NexaraSpacing.ScreenHorizontal),
                        )
                        TextButton(
                            onClick = actions.onRetryError,
                            modifier = Modifier.padding(horizontal = NexaraSpacing.ScreenHorizontal),
                        ) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }

            item {
                NexaraSettingsSection(
                    title = stringResource(R.string.settings_section_general),
                    headerStartPadding = NexaraSpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        title = stringResource(R.string.settings_language),
                        subtitle = stringResource(R.string.settings_language_desc),
                        onClick = actions.onShowLanguageDialog,
                    )
                    UserSettingsNavigationItem(
                        title = stringResource(R.string.settings_appearance),
                        subtitle = stringResource(R.string.settings_appearance_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.THEME_CONFIG) }
                    )
                }
            }

            item {
                NexaraSettingsSection(
                    title = stringResource(R.string.settings_section_ai_models),
                    headerStartPadding = NexaraSpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        title = stringResource(R.string.settings_provider_management),
                        subtitle = stringResource(R.string.settings_provider_management_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.PROVIDER_LIST) },
                        modifier = Modifier.testTag(UiTags.SETTINGS_PROVIDER_ENTRY),
                    )
                    UserSettingsNavigationItem(
                        title = stringResource(R.string.settings_default_models),
                        subtitle = stringResource(R.string.settings_default_models_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.DEFAULT_MODELS) },
                        modifier = Modifier.testTag(UiTags.SETTINGS_DEFAULT_MODELS_ENTRY),
                    )
                    if (state.localInferenceAvailable) {
                        Box(modifier = Modifier.testTag(UiTags.SETTINGS_LOCAL_INFERENCE_ENTRY)) {
                            UserSettingsNavigationItem(
                                title = stringResource(R.string.settings_local_models),
                                subtitle = stringResource(R.string.settings_local_models_desc),
                                onClick = { actions.onNavigateToSecondary("local_models") }
                            )
                        }
                    }
                }
            }

            item {
                NexaraSettingsSection(
                    title = stringResource(R.string.settings_section_knowledge_retrieval),
                    headerStartPadding = NexaraSpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        title = stringResource(R.string.settings_rag_config),
                        subtitle = stringResource(R.string.settings_rag_desc),
                        onClick = { actions.onNavigateToSecondary("rag_global_config") },
                    )
                    UserSettingsNavigationItem(
                        title = stringResource(R.string.settings_advanced_retrieval),
                        subtitle = stringResource(R.string.settings_retrieval_desc),
                        onClick = { actions.onNavigateToSecondary("rag_advanced") },
                    )
                }
            }

            item {
                NexaraSettingsSection(
                    title = stringResource(R.string.settings_section_tools_data),
                    headerStartPadding = NexaraSpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        title = stringResource(R.string.settings_skills),
                        subtitle = stringResource(R.string.settings_skills_desc),
                        onClick = { actions.onNavigateToSecondary("skills_config") },
                    )
                    UserSettingsNavigationItem(
                        title = stringResource(R.string.settings_token_usage),
                        subtitle = stringResource(R.string.settings_token_usage_desc),
                        onClick = { actions.onNavigateToSecondary("token_usage") },
                    )
                    UserSettingsNavigationItem(
                        title = stringResource(R.string.settings_backup),
                        subtitle = stringResource(R.string.settings_backup_desc),
                        onClick = { actions.onNavigateToSecondary("backup_settings") },
                    )
                }
            }

            item {
                NexaraSettingsSection(
                    title = stringResource(R.string.settings_section_about),
                    headerStartPadding = NexaraSpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        title = stringResource(R.string.settings_about_nexara),
                        subtitle = stringResource(R.string.settings_about_nexara_desc),
                        onClick = actions.onAboutClick,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.size(NexaraSpacing.Large))
                GitHubProjectFooter(onOpen = actions.onOpenGithub)
            }
        }
    }
}

@Composable
private fun UserSettingsNavigationItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.clickable(
            role = Role.Button,
            onClick = onClick,
        ),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Normal,
                ),
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Normal,
                ),
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
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
            .defaultMinSize(minHeight = 88.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = editNameDescription,
                onClick = onEditName,
            )
            .padding(
                horizontal = NexaraSpacing.ScreenHorizontal,
                vertical = NexaraSpacing.Large,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
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
        Spacer(modifier = Modifier.width(NexaraSpacing.Large))
        Text(
            text = userName,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun GitHubProjectFooter(onOpen: () -> Unit) {
    val openLinkLabel = stringResource(R.string.rag_details_open_link)
    val githubAddress = "github.com/promenar/nexara"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = githubAddress
            }
            .clickable(
                role = Role.Button,
                onClickLabel = openLinkLabel,
                onClick = onOpen,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            text = "Nexara AI • Project Narcis",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = githubAddress,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.settings_edit_name), style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge,
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
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
internal fun LanguageOption(
    label: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(horizontal = NexaraSpacing.Small, vertical = NexaraSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
