package com.promenar.nexara.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.security.SshCredential
import com.promenar.nexara.data.ssh.SshAuthType
import com.promenar.nexara.data.ssh.SshAgentPreferences
import com.promenar.nexara.data.ssh.SshHost
import com.promenar.nexara.data.ssh.SshHostKey
import com.promenar.nexara.data.ssh.SshKeyGenerator
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraSnackbar
import com.promenar.nexara.ui.common.NexaraSnackbarData
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.common.SnackbarType
import com.promenar.nexara.ui.ssh.SshHostDraft
import com.promenar.nexara.ui.ssh.SshInputException
import com.promenar.nexara.ui.ssh.SshPrivateKeyInput
import com.promenar.nexara.ui.ssh.safeSshError
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forInput
import com.promenar.nexara.ui.theme.nexaraDomainColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SshPendingAction { VERIFY, TERMINAL, FORWARD }
private data class SshTrustPrompt(val host: SshHost, val key: SshHostKey, val action: SshPendingAction)

@Composable
fun SshSettingsScreen(onBack: () -> Unit, onOpenTerminal: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NexaraApplication
    val store = app.sshHostStore
    val runtime = app.sshRuntime
    val hosts by remember(store) { store.observeHosts() }.collectAsState(initial = emptyList())
    val forwards by runtime.forwardStates.collectAsState()
    val cleanupWarnings by runtime.cleanupWarnings.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<SshHost?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SshHost?>(null) }
    var trustPrompt by remember { mutableStateOf<SshTrustPrompt?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    val settingsPrefs = remember(context) { context.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE) }
    var agentManagement by remember(settingsPrefs) { mutableStateOf(SshAgentPreferences.isHostManagementEnabled(settingsPrefs)) }
    val copiedMessage = stringResource(R.string.ssh_public_key_copied)
    val verifiedMessage = stringResource(R.string.ssh_fingerprint_matches)

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = null
        statusIsError = false
        scope.launch {
            try {
                action()
            } catch (error: Exception) {
                status = safeSshError(error)
                statusIsError = true
            } finally {
                busy = false
            }
        }
    }

    suspend fun finishAction(host: SshHost, action: SshPendingAction) {
        when (action) {
            SshPendingAction.VERIFY -> {
                status = verifiedMessage
                statusIsError = false
            }
            SshPendingAction.TERMINAL -> onOpenTerminal(host.alias)
            SshPendingAction.FORWARD -> withContext(Dispatchers.IO) { runtime.startForward(host.alias) }
        }
    }

    fun verify(host: SshHost, action: SshPendingAction) = perform {
        val key = withContext(Dispatchers.IO) { runtime.probe(host.alias) }
        if (host.trustedFingerprint == key.fingerprint && host.trustedKeyType == key.type) {
            finishAction(host, action)
        } else {
            trustPrompt = SshTrustPrompt(host, key, action)
        }
    }

    Box(Modifier.fillMaxSize()) {
        SshSettingsContent(
            state = SshSettingsVisualState(
                hosts = hosts,
                forwards = forwards,
                busy = busy,
                cleanupWarning = cleanupWarnings.isNotEmpty(),
                status = status,
                statusIsError = statusIsError,
                agentManagementEnabled = agentManagement,
            ),
            actions = SshSettingsVisualActions(
                onAgentManagementChange = { value ->
                    settingsPrefs.edit().putBoolean(SshAgentPreferences.HOST_MANAGEMENT_KEY, value).apply()
                    agentManagement = value
                },
                onBack = onBack,
                onAddHost = { editing = null; showEditor = true },
                onTerminal = { verify(it, SshPendingAction.TERMINAL) },
                onVerify = { verify(it, SshPendingAction.VERIFY) },
                onEdit = { editing = it; showEditor = true },
                onDelete = { deleting = it },
                onCopyPublicKey = { host ->
                    perform {
                        val publicKey = withContext(Dispatchers.IO) {
                            store.credential(host)?.use { credential ->
                                SshKeyGenerator.publicKey(credential.privateKey, credential.passphrase)
                            } ?: throw SshInputException("SSH_CREDENTIAL_MISSING")
                        }
                        copySshPublicKey(context, publicKey)
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        status = copiedMessage
                        statusIsError = false
                    }
                },
                onToggleForward = { host ->
                    if (forwards.containsKey(host.alias)) perform {
                        withContext(Dispatchers.IO) { runtime.stopForward(host.alias) }
                    } else verify(host, SshPendingAction.FORWARD)
                },
            ),
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        ) { data ->
            NexaraSnackbar(NexaraSnackbarData(data.visuals.message, SnackbarType.SUCCESS))
        }
    }

    if (showEditor) {
        SshHostEditorSheet(
            existing = editing,
            onDismiss = { showEditor = false; editing = null },
            onSave = { host, credential, generate ->
                val publicKey = withContext(Dispatchers.IO) {
                    if (generate) {
                        SshKeyGenerator.generate().use { generated ->
                            SshCredential(privateKey = generated.privateKey.copyOf()).use {
                                store.save(host, it)
                            }
                            generated.publicKey
                        }
                    } else {
                        if (credential != null && host.authType == SshAuthType.KEY) {
                            SshKeyGenerator.publicKey(credential.privateKey, credential.passphrase)
                        }
                        store.save(host, credential)
                        null
                    }
                }
                if (publicKey != null) {
                    copySshPublicKey(context, publicKey)
                    scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    status = copiedMessage
                    statusIsError = false
                }
                showEditor = false
                editing = null
            },
        )
    }

    deleting?.let { host ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text(stringResource(R.string.ssh_delete_title)) },
            text = { Text(stringResource(R.string.ssh_delete_message, host.alias)) },
            confirmButton = {
                TextButton(onClick = {
                    perform {
                        withContext(Dispatchers.IO) {
                            runtime.stopForward(host.alias)
                            store.delete(host.alias)
                        }
                        deleting = null
                    }
                }, enabled = !busy, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.ssh_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, enabled = !busy, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.ssh_cancel))
                }
            },
        )
    }

    trustPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { if (!busy) trustPrompt = null },
            title = {
                Text(stringResource(if (prompt.host.trustedFingerprint == null) R.string.ssh_trust_title else R.string.ssh_changed_title))
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium)) {
                    Text(stringResource(R.string.ssh_compare_fingerprint))
                    prompt.host.trustedFingerprint?.let { previous ->
                        Text(stringResource(R.string.ssh_previous_fingerprint), style = MaterialTheme.typography.labelLarge)
                        SelectionContainer {
                            Text("${prompt.host.trustedKeyType.orEmpty()}\n$previous", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                    Text(stringResource(R.string.ssh_presented_fingerprint), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text("${prompt.key.type}\n${prompt.key.fingerprint}", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    }
                    status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    perform {
                        val accepted = withContext(Dispatchers.IO) {
                            store.trust(prompt.host.alias, prompt.host.trustedFingerprint, prompt.key, prompt.host)
                        }
                        if (!accepted) throw SshInputException("SSH_HOST_CHANGED_RETRY")
                        trustPrompt = null
                        finishAction(prompt.host, prompt.action)
                    }
                }, enabled = !busy, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                    Text(stringResource(if (prompt.host.trustedFingerprint == null) R.string.ssh_trust else R.string.ssh_replace_trust))
                }
            },
            dismissButton = {
                TextButton(onClick = { trustPrompt = null }, enabled = !busy, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.ssh_cancel))
                }
            },
        )
    }
}

internal data class SshSettingsVisualState(
    val hosts: List<SshHost> = emptyList(),
    val forwards: Map<String, Int> = emptyMap(),
    val busy: Boolean = false,
    val cleanupWarning: Boolean = false,
    val status: String? = null,
    val statusIsError: Boolean = false,
    val agentManagementEnabled: Boolean = false,
)

internal data class SshSettingsVisualActions(
    val onBack: () -> Unit = {},
    val onAddHost: () -> Unit = {},
    val onTerminal: (SshHost) -> Unit = {},
    val onVerify: (SshHost) -> Unit = {},
    val onEdit: (SshHost) -> Unit = {},
    val onDelete: (SshHost) -> Unit = {},
    val onCopyPublicKey: (SshHost) -> Unit = {},
    val onToggleForward: (SshHost) -> Unit = {},
    val onAgentManagementChange: (Boolean) -> Unit = {},
)

@Composable
internal fun SshSettingsContent(
    state: SshSettingsVisualState,
    actions: SshSettingsVisualActions,
    modifier: Modifier = Modifier,
) {
    NexaraSettingsPageLayout(
        title = stringResource(R.string.ssh_settings_title),
        onBack = actions.onBack,
        modifier = modifier,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item("manage") {
                NexaraSettingsSection(
                    title = stringResource(R.string.ssh_visual_manage_section),
                    footer = stringResource(R.string.ssh_settings_description),
                ) {
                    SshActionItem(
                        icon = Icons.Rounded.AddCircleOutline,
                        title = stringResource(R.string.ssh_add_host),
                        subtitle = stringResource(R.string.settings_batch1_ssh_summary),
                        enabled = !state.busy,
                        onClick = actions.onAddHost,
                        showChevron = true,
                    )
                    if (state.hosts.isEmpty()) {
                        NexaraSettingsItem(
                            icon = Icons.Rounded.Terminal,
                            title = stringResource(R.string.ssh_no_hosts),
                            onClick = actions.onAddHost,
                        )
                    }
                }
            }
            item("agent") {
                NexaraSettingsSection(
                    title = stringResource(R.string.ssh_agent_section),
                    footer = stringResource(R.string.ssh_agent_manage_description),
                ) {
                    SettingsToggle(
                        title = stringResource(R.string.ssh_agent_manage_title),
                        description = stringResource(R.string.ssh_agent_manage_summary),
                        checked = state.agentManagementEnabled,
                        onCheckedChange = actions.onAgentManagementChange,
                        icon = Icons.Rounded.SmartToy,
                        enabled = !state.busy,
                    )
                }
            }
            if (state.busy || state.cleanupWarning || state.status != null) {
                item("status") {
                    NexaraSettingsSection(title = stringResource(R.string.ssh_visual_status_section)) {
                        if (state.busy) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.Large),
                                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(NexaraSpacing.XLarge))
                                Text(stringResource(R.string.ssh_visual_working), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (state.cleanupWarning) {
                            SshInformationRow(
                                icon = Icons.Rounded.WarningAmber,
                                text = stringResource(R.string.ssh_cleanup_warning),
                                color = MaterialTheme.nexaraDomainColors.warning,
                            )
                        }
                        state.status?.let { message ->
                            SshInformationRow(
                                icon = if (state.statusIsError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                                text = message,
                                color = if (state.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.nexaraDomainColors.success,
                            )
                        }
                    }
                }
            }
            items(state.hosts, key = { it.id }) { host ->
                SshHostCard(
                    host = host,
                    forwardPort = state.forwards[host.alias],
                    enabled = !state.busy,
                    onTerminal = { actions.onTerminal(host) },
                    onVerify = { actions.onVerify(host) },
                    onEdit = { actions.onEdit(host) },
                    onDelete = { actions.onDelete(host) },
                    onCopyPublicKey = { actions.onCopyPublicKey(host) },
                    onToggleForward = { actions.onToggleForward(host) },
                )
            }
            item("notes") {
                NexaraSettingsSection(
                    title = stringResource(R.string.ssh_visual_notes_section),
                    footer = listOf(stringResource(R.string.ssh_backup_notice), stringResource(R.string.ssh_termux_notice)).joinToString("\n"),
                ) {}
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SshHostCard(
    host: SshHost,
    forwardPort: Int?,
    enabled: Boolean,
    onTerminal: () -> Unit,
    onVerify: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onToggleForward: () -> Unit,
) {
    NexaraSettingsSection(
        title = host.alias,
        footer = listOfNotNull(
            stringResource(R.string.ssh_visual_host_address, host.username, host.hostname, host.port) + ", " +
                stringResource(R.string.settings_batch1_ssh_auth_footer,
                stringResource(if (host.authType == SshAuthType.KEY) R.string.ssh_auth_key else R.string.ssh_auth_password)),
            stringResource(if (host.keepTerminal) R.string.ssh_keep_enabled else R.string.ssh_close_on_exit),
            if (host.localForwardPort != null) stringResource(R.string.ssh_forward_route, host.localForwardPort, host.forwardHost, host.forwardPort ?: 0) else null,
        ).joinToString("\n"),
    ) {
        NexaraSettingsItem(
            icon = Icons.Rounded.Terminal,
            title = stringResource(R.string.ssh_open_terminal),
            subtitle = stringResource(if (host.trustedFingerprint == null) R.string.settings_batch1_ssh_untrusted_summary else R.string.ssh_trusted),
            enabled = enabled,
            onClick = onTerminal,
            showChevron = true,
            onClickLabel = stringResource(R.string.ssh_open_terminal),
        )
        SshActionItem(icon = Icons.Rounded.Security, title = stringResource(R.string.ssh_verify), enabled = enabled, onClick = onVerify)
        if (host.authType == SshAuthType.KEY) {
            SshActionItem(icon = Icons.Rounded.ContentCopy, title = stringResource(R.string.ssh_copy_public_key), enabled = enabled, onClick = onCopyPublicKey)
        }
        SshActionItem(icon = Icons.Rounded.Edit, title = stringResource(R.string.ssh_edit_host), enabled = enabled, onClick = onEdit, showChevron = true)
        if (host.localForwardPort != null) {
            SettingsToggle(
                title = stringResource(R.string.ssh_forward_configure),
                description = if (forwardPort == null) stringResource(R.string.ssh_forward_stopped)
                else stringResource(R.string.settings_batch1_ssh_forward_running_summary, forwardPort),
                checked = forwardPort != null,
                icon = Icons.Rounded.SwapHoriz,
                onCheckedChange = { onToggleForward() },
                enabled = enabled,
            )

        }
        SshActionItem(
            icon = Icons.Rounded.DeleteOutline,
            title = stringResource(R.string.ssh_delete),
            enabled = enabled,
            onClick = onDelete,
            destructive = true,
        )
    }
}

@Composable
private fun SshActionItem(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    showChevron: Boolean = false,
    destructive: Boolean = false,
) {
    NexaraSettingsItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        enabled = enabled,
        showChevron = showChevron,
        titleColor = if (destructive) MaterialTheme.colorScheme.error else null,
        modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
    )
}

@Composable
private fun SshInformationRow(
    icon: ImageVector,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.Large),
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(NexaraSpacing.XLarge))
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun SshStatusTag(text: String, icon: ImageVector, color: Color) {
    Surface(
        color = color.copy(alpha = 0.1f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NexaraSpacing.Small, vertical = NexaraSpacing.XSmall),
            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(NexaraSpacing.Large))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SshHostEditorSheet(
    existing: SshHost?,
    onDismiss: () -> Unit,
    onSave: suspend (SshHost, SshCredential?, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var alias by remember { mutableStateOf(existing?.alias.orEmpty()) }
    var hostname by remember { mutableStateOf(existing?.hostname.orEmpty()) }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var authType by remember { mutableStateOf(existing?.authType ?: SshAuthType.KEY) }
    var keepTerminal by remember { mutableStateOf(existing?.keepTerminal ?: false) }
    var forwarding by remember { mutableStateOf(existing?.localForwardPort != null) }
    var localPort by remember { mutableStateOf(existing?.localForwardPort?.toString().orEmpty()) }
    var forwardHost by remember { mutableStateOf(existing?.forwardHost ?: "127.0.0.1") }
    var forwardPort by remember { mutableStateOf(existing?.forwardPort?.toString().orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorCode by remember { mutableStateOf<String?>(null) }

    fun clearSecrets() {
        password = ""
        passphrase = ""
        privateKey?.fill(0)
        privateKey = null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) clearSecrets() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            clearSecrets()
        }
    }

    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                var loaded: ByteArray? = null
                try {
                    loaded = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use(SshPrivateKeyInput::read)
                            ?: throw SshInputException("SSH_KEY_READ_FAILED")
                    }
                    privateKey?.fill(0)
                    privateKey = loaded
                    loaded = null
                    errorCode = null
                } catch (error: Exception) {
                    errorCode = safeSshError(error)
                } finally {
                    loaded?.fill(0)
                    busy = false
                }
            }
        }
    }

    fun save(generate: Boolean) {
        if (busy) return
        val host = try {
            SshHostDraft(
                alias, hostname, port, username, authType, keepTerminal, forwarding,
                localPort, forwardHost, forwardPort,
            ).toHost(existing, privateKey != null, password.isNotEmpty(), generate)
        } catch (error: SshInputException) {
            errorCode = safeSshError(error)
            return
        }
        busy = true
        errorCode = null
        scope.launch {
            var credential: SshCredential? = null
            try {
                credential = when {
                    generate -> null
                    authType == SshAuthType.PASSWORD && password.isNotEmpty() ->
                        SshCredential(password = password.toByteArray(Charsets.UTF_8))
                    authType == SshAuthType.KEY && privateKey != null ->
                        SshCredential(privateKey = privateKey!!.copyOf(), passphrase = passphrase.toByteArray(Charsets.UTF_8))
                    else -> null
                }
                onSave(host, credential, generate)
                clearSecrets()
            } catch (error: Exception) {
                errorCode = safeSshError(error)
            } finally {
                credential?.close()
                busy = false
            }
        }
    }

    NexaraBottomSheet(
        show = true,
        onDismiss = { if (!busy) onDismiss() },
        dismissEnabled = !busy,
        dragGesturesEnabled = false,
        title = stringResource(if (existing == null) R.string.ssh_add_host else R.string.ssh_edit_host),
        modifier = Modifier.fillMaxHeight(0.95f).imePadding(),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                NexaraSettingsSection(
                    title = stringResource(R.string.ssh_visual_connection_section),
                    footer = stringResource(R.string.ssh_alias_help),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
                    ) {
                        SshEditorField(alias, { alias = it }, R.string.ssh_alias, enabled = !busy && existing == null)
                        SshEditorField(hostname, { hostname = it }, R.string.ssh_hostname, enabled = !busy)
                        SshEditorField(port, { port = it }, R.string.ssh_port, keyboardType = KeyboardType.Number, enabled = !busy)
                        SshEditorField(username, { username = it }, R.string.ssh_username, enabled = !busy)
                    }
                }
                NexaraSettingsSection(
                    title = stringResource(R.string.ssh_authentication),
                    footer = listOfNotNull(
                        if (authType == SshAuthType.KEY) stringResource(if (privateKey == null) R.string.ssh_key_import_help else R.string.ssh_key_imported) else null,
                        if (authType == SshAuthType.KEY) stringResource(R.string.ssh_generate_help) else null,
                        if (existing != null) stringResource(R.string.ssh_keep_credential_hint) else null,
                    ).joinToString("\n"),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
                        ) {
                            SshAuthType.entries.forEach { type ->
                                FilterChip(
                                    selected = authType == type,
                                    onClick = { authType = type; clearSecrets() },
                                    enabled = !busy,
                                    label = { Text(stringResource(if (type == SshAuthType.KEY) R.string.ssh_auth_key else R.string.ssh_auth_password)) },
                                    modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
                                )
                            }
                        }
                        if (authType == SshAuthType.PASSWORD) {
                            SshEditorField(password, { password = it }, R.string.ssh_password, secret = true, enabled = !busy)
                        } else {
                            TextButton(
                                onClick = { importKey.launch(arrayOf("*/*")) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = NexaraSpacing.MinimumTouchTarget),
                            ) {
                                Text(stringResource(R.string.ssh_import_key))
                            }
                            if (privateKey != null) {
                                SshEditorField(passphrase, { passphrase = it }, R.string.ssh_passphrase, secret = true, enabled = !busy)
                            }
                            TextButton(
                                onClick = { save(true) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = NexaraSpacing.MinimumTouchTarget),
                            ) {
                                Text(stringResource(R.string.ssh_generate_save_copy))
                            }
                        }
                    }
                }
                NexaraSettingsSection(
                    title = stringResource(R.string.ssh_visual_terminal_section),
                    footer = stringResource(R.string.ssh_keep_terminal_help),
                ) {
                    SettingsToggle(
                        title = stringResource(R.string.ssh_keep_terminal),
                        description = stringResource(R.string.settings_batch1_ssh_keep_summary),
                        checked = keepTerminal,
                        onCheckedChange = { keepTerminal = it },
                        icon = Icons.Rounded.Terminal,
                        enabled = !busy,
                    )
                }
                NexaraSettingsSection(
                    title = stringResource(R.string.ssh_forward_configure),
                    footer = stringResource(R.string.ssh_forward_loopback_only),
                ) {
                    SettingsToggle(
                        title = stringResource(R.string.settings_batch1_network_forwarding),
                        description = stringResource(R.string.settings_batch1_ssh_forward_summary),
                        checked = forwarding,
                        onCheckedChange = { forwarding = it },
                        enabled = !busy,
                    )
                    if (forwarding) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.Large),
                            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
                        ) {
                            SshEditorField(localPort, { localPort = it }, R.string.ssh_forward_local_port, keyboardType = KeyboardType.Number, enabled = !busy)
                            SshEditorField(forwardHost, { forwardHost = it }, R.string.ssh_forward_remote_host, enabled = !busy)
                            SshEditorField(forwardPort, { forwardPort = it }, R.string.ssh_forward_remote_port, keyboardType = KeyboardType.Number, enabled = !busy)
                        }
                    }
                }
                errorCode?.let { message ->
                    NexaraSettingsSection(title = stringResource(R.string.ssh_visual_status_section)) {
                        SshInformationRow(icon = Icons.Rounded.ErrorOutline, text = message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(top = NexaraSpacing.Small).size(NexaraSpacing.XLarge))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = NexaraSpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = NexaraSpacing.MinimumTouchTarget),
                ) {
                    Text(stringResource(R.string.ssh_cancel))
                }
                Button(
                    onClick = { save(false) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = NexaraSpacing.MinimumTouchTarget),
                ) {
                    Text(stringResource(R.string.ssh_save))
                }
            }
        }
    }
}

@Composable
private fun SshEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge.forInput(),
        shape = MaterialTheme.shapes.medium,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType, autoCorrectEnabled = false),
    )
}

private fun copySshPublicKey(context: Context, publicKey: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.ssh_public_key_label), publicKey))
}
