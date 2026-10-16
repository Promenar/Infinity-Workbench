package com.promenar.nexara.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.security.SshCredential
import com.promenar.nexara.data.ssh.SshAuthType
import com.promenar.nexara.data.ssh.SshHost
import com.promenar.nexara.data.ssh.SshHostKey
import com.promenar.nexara.data.ssh.SshKeyGenerator
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.ssh.SshInputException
import com.promenar.nexara.ui.ssh.SshHostDraft
import com.promenar.nexara.ui.ssh.SshPrivateKeyInput
import com.promenar.nexara.ui.ssh.safeSshError
import com.promenar.nexara.ui.theme.forInput
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
    var editing by remember { mutableStateOf<SshHost?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SshHost?>(null) }
    var trustPrompt by remember { mutableStateOf<SshTrustPrompt?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val copiedMessage = stringResource(R.string.ssh_public_key_copied)
    val verifiedMessage = stringResource(R.string.ssh_fingerprint_matches)

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            try {
                action()
            } catch (error: Exception) {
                status = safeSshError(error)
            } finally {
                busy = false
            }
        }
    }

    suspend fun finishAction(host: SshHost, action: SshPendingAction) {
        when (action) {
            SshPendingAction.VERIFY -> status = verifiedMessage
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

    NexaraPageLayout(
        title = stringResource(R.string.ssh_settings_title),
        onBack = onBack,
        scrollable = false,
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.ssh_settings_description), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ssh_backup_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ssh_termux_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { editing = null; showEditor = true },
                    enabled = !busy,
                    modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.ssh_add_host)) }
            }
            if (busy) item { CircularProgressIndicator() }
            if (cleanupWarnings.isNotEmpty()) item {
                Text(stringResource(R.string.ssh_cleanup_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            status?.let { message -> item { SelectionContainer { Text(message, style = MaterialTheme.typography.bodyMedium) } } }
            if (hosts.isEmpty()) item {
                Text(stringResource(R.string.ssh_no_hosts), style = MaterialTheme.typography.bodyLarge)
            }
            items(hosts, key = { it.id }) { host ->
                SshHostCard(
                    host = host,
                    forwardPort = forwards[host.alias],
                    enabled = !busy,
                    onTerminal = { verify(host, SshPendingAction.TERMINAL) },
                    onVerify = { verify(host, SshPendingAction.VERIFY) },
                    onEdit = { editing = host; showEditor = true },
                    onDelete = { deleting = host },
                    onCopyPublicKey = {
                        perform {
                            val publicKey = withContext(Dispatchers.IO) {
                                store.credential(host)?.use { credential ->
                                    SshKeyGenerator.publicKey(credential.privateKey, credential.passphrase)
                                } ?: throw SshInputException("SSH_CREDENTIAL_MISSING")
                            }
                            copySshPublicKey(context, publicKey)
                            status = copiedMessage
                        }
                    },
                    onToggleForward = {
                        if (forwards.containsKey(host.alias)) perform {
                            withContext(Dispatchers.IO) { runtime.stopForward(host.alias) }
                        } else verify(host, SshPendingAction.FORWARD)
                    },
                )
            }
        }
    }

    if (showEditor) {
        SshHostEditorDialog(
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
                    status = copiedMessage
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
                }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ssh_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
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
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.ssh_compare_fingerprint))
                    prompt.host.trustedFingerprint?.let { previous ->
                        Text(stringResource(R.string.ssh_previous_fingerprint), style = MaterialTheme.typography.labelLarge)
                        SelectionContainer {
                            Text("${prompt.host.trustedKeyType.orEmpty()}\n$previous", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(stringResource(R.string.ssh_presented_fingerprint), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text("${prompt.key.type}\n${prompt.key.fingerprint}", style = MaterialTheme.typography.bodyMedium)
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
                }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (prompt.host.trustedFingerprint == null) R.string.ssh_trust else R.string.ssh_replace_trust))
                }
            },
            dismissButton = {
                TextButton(onClick = { trustPrompt = null }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ssh_cancel))
                }
            },
        )
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(host.alias, style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text("${host.username}@${host.hostname}:${host.port}", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(if (host.trustedFingerprint == null) R.string.ssh_untrusted else R.string.ssh_trusted),
                style = MaterialTheme.typography.bodyMedium,
                color = if (host.trustedFingerprint == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(if (host.keepTerminal) R.string.ssh_keep_enabled else R.string.ssh_close_on_exit),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTerminal, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ssh_open_terminal))
                }
                OutlinedButton(onClick = onVerify, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ssh_verify))
                }
                if (host.authType == SshAuthType.KEY) {
                    TextButton(onClick = onCopyPublicKey, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.ssh_copy_public_key))
                    }
                }
                TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ssh_edit))
                }
                TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ssh_delete))
                }
            }
            if (host.localForwardPort != null) {
                Text(
                    stringResource(R.string.ssh_forward_route, host.localForwardPort, host.forwardHost, host.forwardPort ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (forwardPort == null) stringResource(R.string.ssh_forward_stopped)
                    else stringResource(R.string.ssh_forward_running, forwardPort),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onToggleForward, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (forwardPort == null) R.string.ssh_forward_start else R.string.ssh_forward_stop))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SshHostEditorDialog(
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

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (existing == null) R.string.ssh_add_host else R.string.ssh_edit_host)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SshEditorField(alias, { alias = it }, R.string.ssh_alias, enabled = !busy && existing == null)
                Text(stringResource(R.string.ssh_alias_help), style = MaterialTheme.typography.bodySmall)
                SshEditorField(hostname, { hostname = it }, R.string.ssh_hostname, enabled = !busy)
                SshEditorField(port, { port = it }, R.string.ssh_port, keyboardType = KeyboardType.Number, enabled = !busy)
                SshEditorField(username, { username = it }, R.string.ssh_username, enabled = !busy)
                Text(stringResource(R.string.ssh_authentication), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SshAuthType.entries.forEach { type ->
                        FilterChip(
                            selected = authType == type,
                            onClick = { authType = type; clearSecrets() },
                            enabled = !busy,
                            label = { Text(stringResource(if (type == SshAuthType.KEY) R.string.ssh_auth_key else R.string.ssh_auth_password)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                if (authType == SshAuthType.PASSWORD) {
                    SshEditorField(password, { password = it }, R.string.ssh_password, secret = true, enabled = !busy)
                } else {
                    Text(
                        stringResource(if (privateKey == null) R.string.ssh_key_import_help else R.string.ssh_key_imported),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { importKey.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.ssh_import_key))
                    }
                    if (privateKey != null) {
                        SshEditorField(passphrase, { passphrase = it }, R.string.ssh_passphrase, secret = true, enabled = !busy)
                    }
                    Button(onClick = { save(true) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.ssh_generate_save_copy))
                    }
                    Text(stringResource(R.string.ssh_generate_help), style = MaterialTheme.typography.bodySmall)
                }
                if (existing != null) Text(stringResource(R.string.ssh_keep_credential_hint), style = MaterialTheme.typography.bodySmall)
                SettingsToggle(
                    title = stringResource(R.string.ssh_keep_terminal),
                    description = stringResource(R.string.ssh_keep_terminal_help),
                    checked = keepTerminal,
                    onCheckedChange = { keepTerminal = it },
                    enabled = !busy,
                )
                SettingsToggle(
                    title = stringResource(R.string.ssh_forward_configure),
                    description = stringResource(R.string.ssh_forward_loopback_only),
                    checked = forwarding,
                    onCheckedChange = { forwarding = it },
                    enabled = !busy,
                )
                if (forwarding) {
                    SshEditorField(localPort, { localPort = it }, R.string.ssh_forward_local_port, keyboardType = KeyboardType.Number, enabled = !busy)
                    SshEditorField(forwardHost, { forwardHost = it }, R.string.ssh_forward_remote_host, enabled = !busy)
                    SshEditorField(forwardPort, { forwardPort = it }, R.string.ssh_forward_remote_port, keyboardType = KeyboardType.Number, enabled = !busy)
                }
                errorCode?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(onClick = { save(false) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ssh_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ssh_cancel))
            }
        },
    )
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
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType, autoCorrectEnabled = false),
    )
}

private fun copySshPublicKey(context: Context, publicKey: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.ssh_public_key_label), publicKey))
}
