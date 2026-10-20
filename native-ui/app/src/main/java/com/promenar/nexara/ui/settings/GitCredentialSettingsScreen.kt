package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.git.remote.GitCredential
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.SecretKeyboardOptions
import com.promenar.nexara.ui.common.SecretTextInput
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forInput

internal data class GitCredentialSettingsActions(
    val onBack: () -> Unit = {},
    val onSave: (host: String, username: String, token: CharArray) -> Unit = { _, _, token -> token.fill('\u0000') },
    val onDelete: (String) -> Unit = {},
    val onOpenSsh: () -> Unit = {},
)

@Composable
fun GitCredentialSettingsScreen(onBack: () -> Unit, onOpenSsh: () -> Unit) {
    val context = LocalContext.current
    val viewModel: GitCredentialSettingsViewModel =
        viewModel(factory = GitCredentialSettingsViewModel.factory(context.applicationContext as android.app.Application))
    val state by viewModel.state.collectAsState()
    GitCredentialSettingsContent(
        state = state,
        actions = GitCredentialSettingsActions(
            onBack = onBack,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
            onOpenSsh = onOpenSsh,
        ),
    )
}

/** Git 凭据页：已保存的主机只显示主机与用户名，令牌输入始终遮蔽且不提供显示原文。 */
@Composable
internal fun GitCredentialSettingsContent(
    state: GitCredentialUiState,
    actions: GitCredentialSettingsActions,
    modifier: Modifier = Modifier,
) {
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<GitCredential?>(null) }
    val operation = state.operation
    LaunchedEffect(operation) {
        if (operation is GitCredentialOperation.Saved) {
            host = ""
            username = ""
            token = ""
        }
    }
    NexaraSettingsPageLayout(
        title = stringResource(R.string.git_credentials_title),
        onBack = actions.onBack,
        modifier = modifier,
    ) { contentPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item("saved") {
                NexaraSettingsSection(
                    title = stringResource(R.string.git_credentials_saved_section),
                    footer = stringResource(R.string.git_credentials_description),
                ) {
                    val credentials = state.credentials
                    if (credentials.isNullOrEmpty()) {
                        NexaraSettingsItem(
                            icon = Icons.Rounded.Key,
                            title = stringResource(if (credentials == null) R.string.settings_status_loading else R.string.git_credentials_empty),
                            onClick = {},
                            enabled = false,
                        )
                    }
                    credentials.orEmpty().forEach { credential ->
                        NexaraSettingsItem(
                            icon = Icons.Rounded.Key,
                            title = credential.host,
                            subtitle = stringResource(R.string.git_credentials_saved_subtitle, credential.username),
                            onClick = {
                                host = credential.host
                                username = credential.username
                                token = ""
                            },
                            onClickLabel = stringResource(R.string.git_credentials_update_token),
                            subtitleIsIdentifier = true,
                            modifier = Modifier.testTag("git_credential_${credential.host}"),
                            trailingContent = {
                                IconButton(
                                    onClick = { deleting = credential },
                                    modifier = Modifier.testTag("git_credential_delete_${credential.host}"),
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = stringResource(R.string.git_credentials_delete_action, credential.host),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            item("editor") {
                NexaraSettingsSection(
                    title = stringResource(R.string.git_credentials_editor_section),
                    footer = stringResource(R.string.git_credentials_token_hint),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
                    ) {
                        GitCredentialField(host, { host = it }, R.string.git_credentials_host_label, "git_credential_host",
                            placeholder = "github.com", keyboardType = KeyboardType.Uri)
                        GitCredentialField(username, { username = it }, R.string.git_credentials_username_label, "git_credential_username")
                        GitCredentialField(token, { token = it }, R.string.git_credentials_token_label, "git_credential_token", secret = true)
                        Button(
                            onClick = {
                                val secret = token.toCharArray()
                                token = ""
                                actions.onSave(host, username, secret)
                            },
                            enabled = host.isNotBlank() && username.isNotBlank() && token.isNotEmpty() &&
                                operation != GitCredentialOperation.Saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("git_credential_save"),
                        ) {
                            Text(stringResource(R.string.shared_btn_save))
                        }
                        GitCredentialStatus(operation)
                    }
                }
            }
            item("ssh") {
                NexaraSettingsSection(
                    title = stringResource(R.string.git_credentials_ssh_section),
                    footer = stringResource(R.string.git_credentials_ssh_description),
                ) {
                    NexaraSettingsItem(
                        icon = Icons.Rounded.Terminal,
                        title = stringResource(R.string.ssh_settings_entry),
                        onClick = actions.onOpenSsh,
                        showChevron = true,
                    )
                }
            }
        }
    }
    deleting?.let { credential ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.git_credentials_delete_title)) },
            text = { Text(stringResource(R.string.git_credentials_delete_message, credential.host)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onDelete(credential.host)
                        deleting = null
                    },
                    modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("git_credential_delete_confirm"),
                ) { Text(stringResource(R.string.git_credentials_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun GitCredentialStatus(operation: GitCredentialOperation) {
    val (text, error) = when (operation) {
        GitCredentialOperation.Idle -> return
        GitCredentialOperation.Saving -> stringResource(R.string.git_credentials_saving) to false
        is GitCredentialOperation.Saved -> stringResource(R.string.git_credentials_saved, operation.host) to false
        is GitCredentialOperation.Deleted -> stringResource(R.string.git_credentials_deleted, operation.host) to false
        is GitCredentialOperation.Error -> stringResource(
            when (operation.code) {
                GitCredentialErrorCode.INVALID_HOST -> R.string.git_credentials_invalid_host
                GitCredentialErrorCode.INVALID_USERNAME -> R.string.git_credentials_invalid_username
                GitCredentialErrorCode.INVALID_TOKEN -> R.string.git_credentials_invalid_token
                GitCredentialErrorCode.LOAD_FAILED -> R.string.git_credentials_load_failed
                GitCredentialErrorCode.SAVE_FAILED -> R.string.git_credentials_save_failed
                GitCredentialErrorCode.DELETE_FAILED -> R.string.git_credentials_delete_failed
            },
        ) to true
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("git_credential_status"),
    )
}

@Composable
private fun GitCredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    tag: String,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    val field = @Composable {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(label)) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(tag),
            textStyle = MaterialTheme.typography.bodyLarge.forInput(),
            shape = MaterialTheme.shapes.medium,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (secret) SecretKeyboardOptions else KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        )
    }
    if (secret) SecretTextInput(field) else field()
}
