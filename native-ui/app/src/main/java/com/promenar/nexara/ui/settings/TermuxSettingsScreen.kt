package com.promenar.nexara.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.ssh.SshAuthType
import com.promenar.nexara.data.ssh.SshFailure
import com.promenar.nexara.data.ssh.SshHost
import com.promenar.nexara.data.ssh.SshKeyGenerator
import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.data.termux.TermuxCommandResult
import com.promenar.nexara.data.termux.TermuxPreferences
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalMount
import com.promenar.nexara.ui.common.NexaraCommandBlock
import com.promenar.nexara.ui.common.NexaraIdentifierText
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TERMUX_RUN_PERMISSION = "com.termux.permission.RUN_COMMAND"
private const val TERMUX_DOCUMENTS_AUTHORITY = "com.termux.documents"
// TermuxDocumentsProvider 的 root/document ID 均由官方主目录绝对路径生成。
private const val TERMUX_HOME_DOCUMENT_ID = "/data/data/com.termux/files/home"

@Composable
fun TermuxSettingsScreen(onBack: () -> Unit, onOpenSsh: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val application = context.applicationContext as NexaraApplication
    val runtime = application.termuxRuntime
    val hostStore = application.sshHostStore
    val folderManager = application.externalFolderManager
    val scope = rememberCoroutineScope()
    val noticeHost = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(false) }
    var settingsLoaded by remember { mutableStateOf(false) }
    var availability by remember { mutableStateOf<TermuxAvailability?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }
    var diagnostic by remember { mutableStateOf<TermuxCommandResult?>(null) }
    var keyHosts by remember { mutableStateOf<List<SshHost>>(emptyList()) }
    var mounts by remember { mutableStateOf<List<ExternalMount>>(emptyList()) }
    var selectedKeyHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var mountAlias by rememberSaveable { mutableStateOf("termux") }
    var aliasEdited by rememberSaveable { mutableStateOf(false) }
    var readOnly by rememberSaveable { mutableStateOf(true) }
    var pickerActive by rememberSaveable { mutableStateOf(false) }
    var pendingAlias by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingReadOnly by rememberSaveable { mutableStateOf(true) }
    val usedAliases = remember(mounts) { mounts.map { it.alias }.toSet() }
    val selectedKeyHost = keyHosts.firstOrNull { it.id == selectedKeyHostId } ?: keyHosts.firstOrNull()

    fun showNotice(message: String, isError: Boolean = false) {
        notice = message
        noticeIsError = isError
        scope.launch {
            noticeHost.currentSnackbarData?.dismiss()
            noticeHost.showSnackbar(message, duration = if (isError) SnackbarDuration.Long else SnackbarDuration.Short)
        }
    }

    fun perform(failureMessage: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showNotice(termuxSetupFailure(resources, failure, failureMessage), isError = true)
            } finally {
                busy = false
            }
        }
    }

    fun refresh() {
        if (refreshing || busy) return
        refreshing = true
        scope.launch {
            try {
                val (currentAvailability, currentEnabled) = withContext(Dispatchers.IO) {
                    runtime.availability() to application.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE)
                        .getBoolean(TermuxPreferences.ENABLED_KEY, false)
                }
                availability = currentAvailability
                enabled = currentEnabled
                settingsLoaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showNotice(resources.getString(R.string.termux_error_status), isError = true)
            } finally {
                refreshing = false
            }
        }
    }

    fun copy(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw IllegalStateException(resources.getString(R.string.termux_error_clipboard))
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        showNotice(resources.getString(R.string.termux_copied, label))
    }

    fun open(intent: Intent, failureMessage: String) {
        try {
            context.startActivity(intent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            showNotice(failureMessage, isError = true)
        }
    }

    fun copyHostKey(host: SshHost, asCommand: Boolean) {
        perform(resources.getString(R.string.termux_error_key)) {
            // 私钥仅在 IO 中的 use 作用域内使用，页面只接收推导后的公钥。
            val publicKey = withContext(Dispatchers.IO) {
                hostStore.credential(host)?.use { credential ->
                    SshKeyGenerator.publicKey(credential.privateKey, credential.passphrase)
                } ?: throw SshFailure("CREDENTIAL_MISSING")
            }
            if (asCommand) copy(resources.getString(R.string.termux_copy_key_command_label), TermuxSetupSupport.authorizedKeysCommand(publicKey))
            else copy(resources.getString(R.string.termux_copy_public_key_label), publicKey)
        }
    }

    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) showNotice(resources.getString(R.string.termux_error_permission_denied), isError = true)
        refresh()
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pickerActive = false
        val requestedAlias = pendingAlias
        val requestedReadOnly = pendingReadOnly
        pendingAlias = null
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val returnedIntent = result.data
        val treeUri = returnedIntent?.data
        if (requestedAlias == null || returnedIntent == null || treeUri == null) {
            showNotice(resources.getString(R.string.termux_error_picker_empty), isError = true)
            return@rememberLauncherForActivityResult
        }
        if (treeUri.authority != TERMUX_DOCUMENTS_AUTHORITY) {
            showNotice(resources.getString(R.string.termux_error_picker_provider), isError = true)
            return@rememberLauncherForActivityResult
        }
        perform(resources.getString(R.string.termux_error_mount)) {
            val mount = withContext(Dispatchers.IO) {
                folderManager.addSaf(requestedAlias, null, treeUri.toString(), returnedIntent.flags, requestedReadOnly)
            }
            aliasEdited = false
            showNotice(resources.getString(
                R.string.termux_mounted_notice,
                mount.alias,
                resources.getString(if (mount.readOnly) R.string.termux_read_only else R.string.termux_read_write),
            ))
        }
    }

    val currentRefresh by rememberUpdatedState(::refresh)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(runtime) { currentRefresh() }
    LaunchedEffect(hostStore) {
        try {
            hostStore.observeHosts().flowOn(Dispatchers.IO).collect { hosts ->
                keyHosts = hosts.filter { it.authType == SshAuthType.KEY }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            showNotice(resources.getString(R.string.termux_error_hosts), isError = true)
        }
    }
    LaunchedEffect(folderManager) {
        try {
            folderManager.observeMounts("").flowOn(Dispatchers.IO).collect { mounts = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            showNotice(resources.getString(R.string.termux_error_mounts), isError = true)
        }
    }
    LaunchedEffect(usedAliases) {
        if (!aliasEdited) mountAlias = TermuxSetupSupport.availableMountAlias(usedAliases)
    }

    NexaraSettingsPageLayout(title = stringResource(R.string.termux_title), onBack = onBack) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
            TermuxSettingsContent(
                state = TermuxSettingsUiState(
                    enabled = enabled,
                    settingsLoaded = settingsLoaded,
                    availability = availability,
                    refreshing = refreshing,
                    busy = busy,
                    notice = notice,
                    noticeIsError = noticeIsError,
                    diagnostic = diagnostic,
                    keyHosts = keyHosts.map { TermuxSettingsKeyHost(it.id, it.alias) },
                    selectedKeyHostId = selectedKeyHost?.id,
                    mountAlias = mountAlias,
                    readOnly = readOnly,
                    pickerActive = pickerActive,
                    mounts = mounts.filter { Uri.parse(it.sourceId).authority == TERMUX_DOCUMENTS_AUTHORITY }
                        .map { TermuxSettingsMount(it.alias, it.readOnly) },
                ),
                actions = TermuxSettingsActions(
                    onEnabledChange = { value ->
                        perform(resources.getString(R.string.termux_error_save_integration)) {
                            withContext(Dispatchers.IO) {
                                check(application.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE).edit()
                                    .putBoolean(TermuxPreferences.ENABLED_KEY, value).commit())
                            }
                            enabled = value
                            diagnostic = null
                            showNotice(resources.getString(if (value) R.string.termux_enabled_notice else R.string.termux_disabled_notice))
                        }
                    },
                    onRefresh = ::refresh,
                    onRunDiagnostic = {
                        perform(resources.getString(R.string.termux_error_diagnostic)) {
                            diagnostic = null
                            diagnostic = withContext(Dispatchers.IO) {
                                runtime.execute(resources.getString(R.string.termux_command_diagnostic), timeoutSeconds = 15)
                            }
                        }
                    },
                    onOpenFdroid = {
                        open(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")), resources.getString(R.string.termux_error_fdroid))
                    },
                    onOpenGithub = {
                        open(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app/releases")), resources.getString(R.string.termux_error_github))
                    },
                    onCopyCommand = { command ->
                        perform(resources.getString(R.string.termux_error_copy)) {
                            copy(resources.getString(R.string.termux_copy_command_label), command)
                        }
                    },
                    onRequestPermission = {
                        try { permissionRequest.launch(TERMUX_RUN_PERMISSION) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { showNotice(resources.getString(R.string.termux_error_permission_request), isError = true) }
                    },
                    onOpenAppDetails = {
                        open(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            resources.getString(R.string.termux_error_app_details),
                        )
                    },
                    onOpenSsh = onOpenSsh,
                    onSelectKeyHost = { selectedKeyHostId = it },
                    onCopyPublicKey = { selectedKeyHost?.let { copyHostKey(it, asCommand = false) } },
                    onCopyPublicKeyCommand = { selectedKeyHost?.let { copyHostKey(it, asCommand = true) } },
                    onMountAliasChange = { mountAlias = it; aliasEdited = true },
                    onReadOnlyChange = { readOnly = it },
                    onChooseHome = {
                        try {
                            val requestedAlias = TermuxSetupSupport.normalizeMountAlias(mountAlias, usedAliases)
                            pendingAlias = requestedAlias
                            pendingReadOnly = readOnly
                            pickerActive = true
                            val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                                (if (readOnly) 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            treePicker.launch(
                                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                    .addFlags(grantFlags)
                                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(TERMUX_DOCUMENTS_AUTHORITY, TERMUX_HOME_DOCUMENT_ID)),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IllegalArgumentException) {
                            pickerActive = false
                            pendingAlias = null
                            val message = when (rejectedTermuxMountAliasError(mountAlias, usedAliases)) {
                                TermuxMountAliasError.ControlCharacter -> R.string.termux_error_alias_control
                                TermuxMountAliasError.Conflict -> R.string.termux_error_alias_conflict
                                TermuxMountAliasError.Invalid -> R.string.termux_error_alias_invalid
                            }
                            showNotice(resources.getString(message), isError = true)
                        } catch (_: Exception) {
                            pickerActive = false
                            pendingAlias = null
                            showNotice(resources.getString(R.string.termux_error_picker), isError = true)
                        }
                    },
                ),
                contentPadding = contentPadding,
            )
            SnackbarHost(
                hostState = noticeHost,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = NexaraSpacing.Large)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }
}

/** 无应用依赖的设置页内容，供截图测试与真实页面共用。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TermuxSettingsContent(
    state: TermuxSettingsUiState,
    actions: TermuxSettingsActions = TermuxSettingsActions(),
    contentPadding: PaddingValues = PaddingValues(NexaraSpacing.ScreenHorizontal),
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val visuals = state.visualState()
    var keyMenuOpen by remember { mutableStateOf(false) }
    val selectedKeyHost = state.keyHosts.firstOrNull { it.id == state.selectedKeyHostId } ?: state.keyHosts.firstOrNull()
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("termux_settings_screen"),
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        item {
            NexaraSettingsSection(title = stringResource(R.string.termux_integration_section)) {
                SettingsToggle(
                    title = stringResource(R.string.termux_integration_title),
                    description = stringResource(R.string.termux_integration_description),
                    icon = Icons.Rounded.Terminal,
                    checked = state.enabled,
                    enabled = state.settingsLoaded && !state.busy && !state.refreshing,
                    modifier = Modifier.testTag("termux_integration_toggle"),
                    onCheckedChange = actions.onEnabledChange,
                )
                TermuxSectionBody {
                    Text(stringResource(R.string.termux_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                    ) {
                        TermuxStatusChip(
                            text = stringResource(when (state.availability?.installed) {
                                true -> R.string.termux_installed
                                false -> R.string.termux_not_installed
                                null -> R.string.termux_reading
                            }),
                            status = visuals.installation,
                        )
                        TermuxStatusChip(
                            text = stringResource(when (state.availability?.permissionGranted) {
                                true -> R.string.termux_permission_granted
                                false -> R.string.termux_permission_missing
                                null -> R.string.termux_permission_reading
                            }),
                            status = visuals.permission,
                        )
                    }
                    NexaraIdentifierText(
                        stringResource(R.string.termux_version, state.availability?.versionName ?: stringResource(R.string.termux_not_available)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    termuxGuidance(state.availability?.errorCode, state.availability?.guidance)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.busy || state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.notice?.let { message ->
                        TermuxInlineNotice(message, if (state.noticeIsError) TermuxStepStatus.Error else TermuxStepStatus.Complete)
                    }
                    Text(stringResource(R.string.termux_diagnostic_description), style = MaterialTheme.typography.bodyMedium)
                    NexaraCommandBlock(command = stringResource(R.string.termux_command_diagnostic), enabled = !state.busy, onCopy = actions.onCopyCommand)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
                        Button(
                            enabled = visuals.canRunDiagnostic,
                            modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("termux_diagnostic_test"),
                            onClick = actions.onRunDiagnostic,
                        ) { Text(stringResource(R.string.termux_run_diagnostic)) }
                        TextButton(
                            onClick = actions.onRefresh,
                            enabled = !state.busy && !state.refreshing,
                            modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
                        ) { Text(stringResource(R.string.termux_refresh)) }
                    }
                    if (!state.enabled) {
                        Text(stringResource(R.string.termux_diagnostic_requires_enabled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.diagnostic?.let { TermuxDiagnosticResult(it) }
                }
            }
        }
        item {
            NexaraSettingsSection(title = stringResource(R.string.termux_step_install)) {
                TermuxSectionBody {
                    TermuxStepStatusLabel(visuals.installation)
                    Text(stringResource(R.string.termux_install_description), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
                        // 已安装时安装入口降为次要操作，未满足的步骤才给出主按钮
                        if (visuals.installation == TermuxStepStatus.Complete) {
                            TextButton(onClick = actions.onOpenFdroid, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                                Text(stringResource(R.string.termux_fdroid))
                            }
                        } else {
                            Button(onClick = actions.onOpenFdroid, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                                Text(stringResource(R.string.termux_fdroid))
                            }
                        }
                        TextButton(onClick = actions.onOpenGithub, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                            Text(stringResource(R.string.termux_github))
                        }
                    }
                }
            }
        }
        item {
            NexaraSettingsSection(title = stringResource(R.string.termux_step_external_apps)) {
                TermuxSectionBody {
                    TermuxStepStatusLabel(visuals.externalApps)
                    Text(stringResource(R.string.termux_external_apps_description), style = MaterialTheme.typography.bodyMedium)
                    NexaraCommandBlock(command = stringResource(R.string.termux_command_external_apps), enabled = !state.busy, onCopy = actions.onCopyCommand)
                    Text(
                        stringResource(if (visuals.externalApps == TermuxStepStatus.Complete) R.string.termux_external_apps_verified else R.string.termux_external_apps_unverified),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            NexaraSettingsSection(title = stringResource(R.string.termux_step_permission)) {
                TermuxSectionBody {
                    TermuxStepStatusLabel(visuals.permission)
                    Text(stringResource(R.string.termux_permission_description), style = MaterialTheme.typography.bodyMedium)
                    if (visuals.permission != TermuxStepStatus.Complete) {
                        Button(
                            onClick = actions.onRequestPermission,
                            enabled = state.availability?.installed == true && !state.busy,
                            modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
                        ) { Text(stringResource(R.string.termux_request_permission)) }
                    }
                }
                NexaraSettingsItem(
                    icon = Icons.Rounded.Settings,
                    title = stringResource(R.string.termux_app_details),
                    subtitle = stringResource(R.string.termux_app_details_description),
                    onClick = actions.onOpenAppDetails,
                    showChevron = true,
                )
            }
        }
        item {
            NexaraSettingsSection(title = stringResource(R.string.termux_step_ssh)) {
                TermuxSectionBody {
                    TermuxStatusChip(
                        text = stringResource(if (state.keyHosts.isEmpty()) R.string.termux_step_optional else R.string.termux_key_available),
                        status = if (state.keyHosts.isEmpty()) TermuxStepStatus.Optional else TermuxStepStatus.Complete,
                    )
                    Text(stringResource(R.string.termux_ssh_description), style = MaterialTheme.typography.bodyMedium)
                    NexaraCommandBlock(command = stringResource(R.string.termux_command_ssh_setup), enabled = !state.busy, onCopy = actions.onCopyCommand)
                    Text(stringResource(R.string.termux_ssh_password_description), style = MaterialTheme.typography.bodyMedium)
                    NexaraCommandBlock(command = stringResource(R.string.termux_command_password), enabled = !state.busy, onCopy = actions.onCopyCommand)
                    Text(stringResource(R.string.termux_ssh_host_description), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.termux_sshd_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    NexaraCommandBlock(command = stringResource(R.string.termux_command_sshd), enabled = !state.busy, onCopy = actions.onCopyCommand)
                    Button(onClick = actions.onOpenSsh, enabled = !state.busy, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                        Text(stringResource(R.string.termux_open_ssh))
                    }
                    if (state.keyHosts.isEmpty()) {
                        Text(stringResource(R.string.termux_key_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.keyHosts.isNotEmpty()) {
                    Box {
                        NexaraSettingsItem(
                            icon = Icons.Rounded.Key,
                            title = stringResource(R.string.termux_reuse_key),
                            subtitle = selectedKeyHost?.alias,
                            subtitleIsIdentifier = true,
                            onClick = { keyMenuOpen = true },
                            enabled = !state.busy,
                            trailingContent = {
                                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                        )
                        DropdownMenu(expanded = keyMenuOpen, onDismissRequest = { keyMenuOpen = false }) {
                            state.keyHosts.forEach { host ->
                                DropdownMenuItem(
                                    text = { NexaraIdentifierText(host.alias) },
                                    onClick = { actions.onSelectKeyHost(host.id); keyMenuOpen = false },
                                )
                            }
                        }
                    }
                    TermuxSectionBody {
                        Text(stringResource(R.string.termux_key_copy_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
                            TextButton(onClick = actions.onCopyPublicKey, enabled = !state.busy, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                                Text(stringResource(R.string.termux_copy_public_key))
                            }
                            TextButton(onClick = actions.onCopyPublicKeyCommand, enabled = !state.busy, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                                Text(stringResource(R.string.termux_copy_key_command))
                            }
                        }
                    }
                }
            }
        }
        item {
            NexaraSettingsSection(title = stringResource(R.string.termux_step_mount)) {
                TermuxSectionBody {
                    TermuxStatusChip(
                        text = if (state.mounts.isEmpty()) stringResource(R.string.termux_step_optional)
                            else stringResource(R.string.termux_mount_count, state.mounts.size),
                        status = if (state.mounts.isEmpty()) TermuxStepStatus.Optional else TermuxStepStatus.Complete,
                    )
                    Text(stringResource(R.string.termux_mount_description), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = state.mountAlias,
                        onValueChange = actions.onMountAliasChange,
                        label = { Text(stringResource(R.string.termux_mount_alias)) },
                        supportingText = { Text(stringResource(R.string.termux_mount_alias_hint)) },
                        enabled = !state.busy && !state.pickerActive,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().testTag("termux_mount_alias"),
                    )
                }
                SettingsToggle(
                    title = stringResource(R.string.termux_mount_read_only),
                    description = stringResource(R.string.termux_mount_read_only_description),
                    icon = Icons.Rounded.FolderOpen,
                    checked = state.readOnly,
                    onCheckedChange = actions.onReadOnlyChange,
                    enabled = !state.busy && !state.pickerActive,
                    modifier = Modifier.testTag("termux_mount_read_only"),
                )
                TermuxSectionBody {
                    Button(
                        onClick = actions.onChooseHome,
                        enabled = state.availability?.installed == true && !state.busy && !state.pickerActive,
                        modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("termux_choose_home"),
                    ) { Text(stringResource(R.string.termux_choose_folder)) }
                    Text(stringResource(R.string.termux_picker_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.mounts.forEach { mount ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(NexaraSpacing.XLarge))
                            NexaraIdentifierText(
                                stringResource(R.string.termux_mount_path, mount.alias, stringResource(if (mount.readOnly) R.string.termux_read_only else R.string.termux_read_write)),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        item {
            NexaraSettingsSection(title = stringResource(R.string.termux_background_section)) {
                TermuxSectionBody {
                    Text(stringResource(R.string.termux_background_description), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.termux_wake_lock_description), style = MaterialTheme.typography.bodyMedium)
                    NexaraCommandBlock(command = stringResource(R.string.termux_command_wake_lock), enabled = !state.busy, onCopy = actions.onCopyCommand)
                    Text(stringResource(R.string.termux_background_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TermuxSectionBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
        content = content,
    )
}

@Composable
private fun TermuxStepStatusLabel(status: TermuxStepStatus) {
    TermuxStatusChip(
        text = stringResource(when (status) {
            TermuxStepStatus.Complete -> R.string.termux_step_complete
            TermuxStepStatus.Pending -> R.string.termux_step_pending
            TermuxStepStatus.Optional -> R.string.termux_step_optional
            TermuxStepStatus.Reading -> R.string.termux_reading
            TermuxStepStatus.Error -> R.string.termux_step_attention
        }),
        status = status,
    )
}

@Composable
private fun TermuxStatusChip(text: String, status: TermuxStepStatus) {
    val colors = MaterialTheme.colorScheme
    val color = when (status) {
        TermuxStepStatus.Complete -> colors.primaryContainer
        TermuxStepStatus.Pending, TermuxStepStatus.Error -> colors.errorContainer
        TermuxStepStatus.Optional, TermuxStepStatus.Reading -> colors.surfaceContainerHighest
    }
    val contentColor = when (status) {
        TermuxStepStatus.Complete -> colors.onPrimaryContainer
        TermuxStepStatus.Pending, TermuxStepStatus.Error -> colors.onErrorContainer
        TermuxStepStatus.Optional, TermuxStepStatus.Reading -> colors.onSurfaceVariant
    }
    Surface(color = color, contentColor = contentColor, shape = MaterialTheme.shapes.small) {
        Row(
            modifier = Modifier.padding(horizontal = NexaraSpacing.Small, vertical = NexaraSpacing.XSmall),
            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (status) {
                    TermuxStepStatus.Complete -> Icons.Rounded.CheckCircle
                    TermuxStepStatus.Pending, TermuxStepStatus.Error -> Icons.Rounded.Error
                    TermuxStepStatus.Optional -> Icons.Rounded.Info
                    TermuxStepStatus.Reading -> Icons.Rounded.HourglassEmpty
                },
                contentDescription = null,
                modifier = Modifier.size(NexaraSpacing.Large),
            )
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TermuxInlineNotice(message: String, status: TermuxStepStatus) {
    val isError = status == TermuxStepStatus.Error
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(NexaraSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (isError) Icons.Rounded.Error else Icons.Rounded.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(NexaraSpacing.XLarge),
            )
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TermuxDiagnosticResult(result: TermuxCommandResult) {
    val succeeded = TermuxSetupSupport.diagnosticSucceeded(result)
    Column(
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        TermuxInlineNotice(
            message = stringResource(if (succeeded) R.string.termux_diagnostic_success else R.string.termux_diagnostic_failure),
            status = if (succeeded) TermuxStepStatus.Complete else TermuxStepStatus.Error,
        )
        Text(
            stringResource(R.string.termux_exit_code, result.exitCode?.toString() ?: stringResource(R.string.termux_not_returned)),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (result.timedOut) Text(stringResource(R.string.termux_diagnostic_timeout), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        result.errorCode?.let { NexaraIdentifierText(stringResource(R.string.termux_error_code, it), style = MaterialTheme.typography.bodyMedium) }
        termuxGuidance(result.errorCode, result.guidance)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        result.errmsg?.takeIf(String::isNotBlank)?.let {
            NexaraIdentifierText(stringResource(R.string.termux_error_detail, it), style = MaterialTheme.typography.bodySmall)
        }
        if (result.stdout.isNotEmpty()) {
            Text(stringResource(R.string.termux_stdout), style = MaterialTheme.typography.labelLarge)
            TermuxOutputText(result.stdout)
        }
        if (result.stderr.isNotEmpty()) {
            Text(stringResource(R.string.termux_stderr), style = MaterialTheme.typography.labelLarge)
            TermuxOutputText(result.stderr)
        }
        if (!succeeded && result.errorCode == null && !result.timedOut) {
            Text(stringResource(R.string.termux_diagnostic_expected), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 诊断输出是执行结果，保留选择复制能力并避免命令操作语义。 */
@Composable
private fun TermuxOutputText(output: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        SelectionContainer {
            Text(
                output,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(NexaraSpacing.Large),
            )
        }
    }
}

@Composable
private fun termuxGuidance(errorCode: String?, fallback: String?): String? = when (errorCode) {
    "TERMUX_NOT_INSTALLED" -> stringResource(R.string.termux_guidance_not_installed)
    "TERMUX_VERSION_UNSUPPORTED" -> stringResource(R.string.termux_guidance_version)
    "TERMUX_PERMISSION_DENIED" -> stringResource(R.string.termux_guidance_permission)
    "TERMUX_EXTERNAL_APPS_DISABLED" -> stringResource(R.string.termux_guidance_external_apps)
    "TERMUX_INTERNAL_ERROR" -> stringResource(R.string.termux_guidance_internal)
    "TERMUX_COMMAND_FAILED" -> stringResource(R.string.termux_guidance_command_failed)
    "TERMUX_RESULT_MALFORMED" -> stringResource(R.string.termux_guidance_malformed)
    "TERMUX_INTEGRATION_DISABLED" -> stringResource(R.string.termux_guidance_integration_disabled)
    "TERMUX_START_FAILED" -> stringResource(R.string.termux_guidance_start_failed)
    "TERMUX_INVALID_ARGUMENTS" -> stringResource(R.string.termux_guidance_invalid_arguments)
    "TERMUX_TIMEOUT" -> stringResource(R.string.termux_guidance_timeout)
    else -> fallback
}

private fun termuxSetupFailure(resources: Resources, failure: Exception, fallback: String): String = when (failure) {
    is CancellationException -> throw failure
    is SshFailure -> resources.getString(R.string.termux_error_key_code, failure.code)
    is ExternalFolderException -> resources.getString(when (failure.code) {
        "CONFLICT" -> R.string.termux_error_alias_conflict
        "INVALID_PATH" -> R.string.termux_error_mount_path
        "READ_ONLY" -> R.string.termux_error_mount_write_permission
        "GRANT_LIMIT" -> R.string.termux_error_grant_limit
        else -> R.string.termux_error_mount_unavailable
    })
    is SecurityException -> resources.getString(R.string.termux_error_system_permission)
    else -> fallback
}
