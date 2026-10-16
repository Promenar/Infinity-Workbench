package com.promenar.nexara.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.ssh.SshAuthType
import com.promenar.nexara.data.ssh.SshFailure
import com.promenar.nexara.data.ssh.SshHost
import com.promenar.nexara.data.ssh.SshKeyGenerator
import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.data.termux.TermuxCommandResult
import com.promenar.nexara.data.termux.TermuxPreferences
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalMount
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.ui.common.NexaraIdentifierText
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
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
private const val TERMUX_DIAGNOSTIC_COMMAND = "printf 'NEXARA_TERMUX_OK\\n'"
private const val TERMUX_ALLOW_EXTERNAL_COMMAND =
    "mkdir -p ~/.termux && touch ~/.termux/termux.properties && " +
        "sed -i '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' ~/.termux/termux.properties && " +
        "printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties && termux-reload-settings"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TermuxSettingsScreen(onBack: () -> Unit, onOpenSsh: () -> Unit) {
    val context = LocalContext.current
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
    var keyMenuOpen by remember { mutableStateOf(false) }
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
                showNotice(termuxSetupFailure(failure, failureMessage), isError = true)
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
                showNotice("无法读取 Termux 状态，请稍后刷新。", isError = true)
            } finally {
                refreshing = false
            }
        }
    }

    fun copy(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw IllegalStateException("剪贴板不可用")
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        showNotice("已复制$label，请在 Termux 中粘贴。")
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
        perform("无法读取 SSH 公钥，请在 SSH 主机设置中检查密钥。") {
            // 私钥仅在 IO 中的 use 作用域内使用，页面只接收推导后的公钥。
            val publicKey = withContext(Dispatchers.IO) {
                hostStore.credential(host)?.use { credential ->
                    SshKeyGenerator.publicKey(credential.privateKey, credential.passphrase)
                } ?: throw SshFailure("CREDENTIAL_MISSING")
            }
            if (asCommand) copy("公钥写入命令", TermuxSetupSupport.authorizedKeysCommand(publicKey))
            else copy("SSH 公钥", publicKey)
        }
    }

    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) showNotice("尚未授予运行命令权限，请在 Nexara 应用详情的权限页中允许。", isError = true)
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
            showNotice("系统未返回文件夹授权，请重新选择。", isError = true)
            return@rememberLauncherForActivityResult
        }
        if (treeUri.authority != TERMUX_DOCUMENTS_AUTHORITY) {
            showNotice("请选择文件选择器中 Termux 的主目录或其子文件夹。", isError = true)
            return@rememberLauncherForActivityResult
        }
        perform("无法挂载 Termux 文件夹，请重新选择并允许所需的文件访问权限。") {
            val mount = withContext(Dispatchers.IO) {
                folderManager.addSaf(requestedAlias, null, treeUri.toString(), returnedIntent.flags, requestedReadOnly)
            }
            aliasEdited = false
            showNotice("已挂载到 /mnt/${mount.alias}（${if (mount.readOnly) "只读" else "可读写"}），所有会话可使用。")
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
            showNotice("无法读取 SSH 主机列表，请打开 SSH 设置检查。", isError = true)
        }
    }
    LaunchedEffect(folderManager) {
        try {
            folderManager.observeMounts("").flowOn(Dispatchers.IO).collect { mounts = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            showNotice("无法读取已有挂载，请打开文件夹管理检查。", isError = true)
        }
    }
    LaunchedEffect(usedAliases) {
        if (!aliasEdited) mountAlias = TermuxSetupSupport.availableMountAlias(usedAliases)
    }

    NexaraSettingsPageLayout(title = "Termux", onBack = onBack) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("termux_settings_screen"),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    BettboxListGroup {
                        SettingsToggle(
                            title = "Termux 集成",
                            description = "启用后，Agent 可使用本机终端工具运行命令，执行遵循会话工具审批设置。",
                            checked = enabled,
                            enabled = settingsLoaded && !busy && !refreshing,
                            modifier = Modifier.testTag("termux_integration_toggle"),
                            onCheckedChange = { value ->
                                perform("无法保存 Termux 集成设置，请重试。") {
                                    withContext(Dispatchers.IO) {
                                        check(application.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE).edit()
                                            .putBoolean(TermuxPreferences.ENABLED_KEY, value).commit())
                                    }
                                    enabled = value
                                    diagnostic = null
                                    showNotice(if (value) "Termux 集成已启用。" else "Termux 集成已关闭。")
                                }
                            },
                        )
                    }
                    Text(
                        "SSH 与主目录挂载可独立配置。以下安装与配置命令均由你在 Termux 中粘贴执行。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (busy || refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                notice?.let { message ->
                    item {
                        Text(
                            message,
                            color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                item {
                    TermuxGuideGroup("检测与诊断") {
                        Text("安装状态：${when (availability?.installed) { true -> "已安装"; false -> "未安装"; null -> "读取中" }}")
                        NexaraIdentifierText("版本：${availability?.versionName ?: "未获取"}", style = MaterialTheme.typography.bodyMedium)
                        Text("运行命令权限：${when (availability?.permissionGranted) { true -> "已授予"; false -> "未授予"; null -> "读取中" }}")
                        availability?.guidance?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("固定测试只输出一行标记，用于确认权限、外部应用开关与结果回传。", style = MaterialTheme.typography.bodyMedium)
                        TermuxCommandText(TERMUX_DIAGNOSTIC_COMMAND)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = ::refresh, enabled = !busy && !refreshing, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("刷新状态")
                            }
                            Button(
                                enabled = enabled && availability?.errorCode == null && availability?.installed == true &&
                                    availability?.permissionGranted == true && !busy && !refreshing,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("termux_diagnostic_test"),
                                onClick = {
                                    perform("诊断请求失败，请检查 Termux 状态后重试。") {
                                        diagnostic = null
                                        diagnostic = withContext(Dispatchers.IO) {
                                            runtime.execute(TERMUX_DIAGNOSTIC_COMMAND, timeoutSeconds = 15)
                                        }
                                    }
                                },
                            ) { Text("运行诊断测试") }
                        }
                        if (!enabled) Text("开启 Termux 集成后可运行诊断测试。", style = MaterialTheme.typography.bodyMedium)
                        diagnostic?.let { TermuxDiagnosticResult(it) }
                    }
                }
                item {
                    TermuxGuideGroup("1. 安装 Termux") {
                        Text("从 F-Droid 或 GitHub 安装，结果回传需要 0.109 及以上版本。安装后先打开 Termux，等待环境初始化完成。")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")), "无法打开 F-Droid，请使用浏览器访问其官方网站。") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("F-Droid") }
                            TextButton(
                                onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app/releases")), "无法打开 GitHub，请使用浏览器访问 Termux 官方仓库。") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("GitHub") }
                        }
                    }
                }
                item {
                    TermuxGuideGroup("2. 允许外部应用运行命令") {
                        Text("在 Termux 中执行以下命令，设置 allow-external-apps=true 并重新加载配置。")
                        TermuxCommandText(TERMUX_ALLOW_EXTERNAL_COMMAND)
                        OutlinedButton(
                            onClick = { perform("复制失败，请重试。") { copy("外部应用配置命令", TERMUX_ALLOW_EXTERNAL_COMMAND) } },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("复制配置命令") }
                    }
                }
                item {
                    TermuxGuideGroup("3. 授予 Nexara 运行命令权限") {
                        Text("允许 Nexara 使用“在 Termux 环境中运行命令”权限。若系统没有弹出申请，请在应用详情的“权限 → 其它权限”中查找。返回此页会自动刷新状态。")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    try { permissionRequest.launch(TERMUX_RUN_PERMISSION) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { showNotice("无法发起权限申请，请打开 Nexara 应用详情手动授权。", isError = true) }
                                },
                                enabled = availability?.installed == true && !busy,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("申请运行命令权限") }
                            TextButton(
                                onClick = {
                                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)), "无法打开应用详情，请在系统设置中找到 Nexara 的权限页。")
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Nexara 应用详情") }
                        }
                    }
                }
                item {
                    TermuxGuideGroup("可选：通过 SSH 连接") {
                        Text("在 Termux 中依次执行以下命令。whoami 的输出是 SSH 用户名；密码登录先运行 passwd，公钥登录则按下方步骤配置公钥。")
                        TermuxCommandText("pkg install openssh\nwhoami\npasswd")
                        OutlinedButton(
                            onClick = { perform("复制失败，请重试。") { copy("SSH 准备命令", "pkg install openssh\nwhoami") } },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("复制安装与用户名命令") }
                        Text("在 SSH 主机设置中添加地址 127.0.0.1、端口 8022，填写 whoami 显示的用户名，并选择密码或密钥认证。首次连接前核对主机指纹。")
                        Text("仅供同机连接时，使用以下 sshd 命令启动服务并限制监听环回地址。", style = MaterialTheme.typography.bodyMedium)
                        TermuxCommandText("sshd -o ListenAddress=127.0.0.1")
                        Button(onClick = onOpenSsh, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开 SSH 主机设置") }
                        if (keyHosts.isEmpty()) {
                            Text("如需公钥登录，请先在 SSH 主机设置中保存一份密钥。", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text("复用已保存的 SSH 密钥", style = MaterialTheme.typography.titleSmall)
                            Box {
                                OutlinedButton(onClick = { keyMenuOpen = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                    NexaraIdentifierText(selectedKeyHost?.alias.orEmpty())
                                }
                                DropdownMenu(expanded = keyMenuOpen, onDismissRequest = { keyMenuOpen = false }) {
                                    keyHosts.forEach { host ->
                                        DropdownMenuItem(
                                            text = { NexaraIdentifierText(host.alias) },
                                            onClick = { selectedKeyHostId = host.id; keyMenuOpen = false },
                                        )
                                    }
                                }
                            }
                            Text("选择密钥后复制公钥，或复制写入 authorized_keys 的命令，在 Termux 中粘贴执行。", style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { selectedKeyHost?.let { copyHostKey(it, asCommand = false) } },
                                    enabled = !busy,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("复制公钥") }
                                OutlinedButton(
                                    onClick = { selectedKeyHost?.let { copyHostKey(it, asCommand = true) } },
                                    enabled = !busy,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("复制公钥写入命令") }
                            }
                        }
                    }
                }
                item {
                    TermuxGuideGroup("可选：挂载 Termux 主目录") {
                        Text("通过系统文件选择器授权 Termux 主目录或其子文件夹。挂载后可在所有会话中使用 /mnt/别名 访问，不需要所有文件访问权限。")
                        OutlinedTextField(
                            value = mountAlias,
                            onValueChange = { mountAlias = it; aliasEdited = true },
                            label = { Text("唯一挂载别名") },
                            supportingText = { Text("不能使用路径、mnt 或 .nexara 保留名称。") },
                            enabled = !busy && !pickerActive,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                            modifier = Modifier.fillMaxWidth().testTag("termux_mount_alias"),
                        )
                        SettingsToggle(
                            title = "只读挂载",
                            description = "关闭后允许通过 Nexara 写入所选文件夹。",
                            checked = readOnly,
                            onCheckedChange = { readOnly = it },
                            enabled = !busy && !pickerActive,
                            modifier = Modifier.testTag("termux_mount_read_only"),
                        )
                        OutlinedButton(
                            onClick = {
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
                                } catch (failure: IllegalArgumentException) {
                                    pickerActive = false
                                    pendingAlias = null
                                    showNotice(failure.message ?: "请输入有效的唯一挂载别名。", isError = true)
                                } catch (_: Exception) {
                                    pickerActive = false
                                    pendingAlias = null
                                    showNotice("无法打开文件选择器，请确认已安装并打开过 Termux。", isError = true)
                                }
                            },
                            enabled = availability?.installed == true && !busy && !pickerActive,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("termux_choose_home"),
                        ) { Text("选择 Termux 文件夹") }
                        Text("如果选择器未自动定位，请在侧栏中选择 Termux。别名冲突时请换一个名称。", style = MaterialTheme.typography.bodyMedium)
                        mounts.filter { Uri.parse(it.sourceId).authority == TERMUX_DOCUMENTS_AUTHORITY }.forEach { mount ->
                            NexaraIdentifierText("/mnt/${mount.alias}（${if (mount.readOnly) "只读" else "可读写"}）", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    TermuxGuideGroup("后台运行与子进程限制") {
                        Text("Android 12 及以上可能结束应用派生的子进程，终端可能显示 signal 9。Android 14 及以上的部分设备可在“开发者选项”中找到“停用子进程限制”；具体名称和可用性由系统决定。关闭开发者选项可能恢复限制。")
                        Text("长任务可在 Termux 中运行 termux-wake-lock 保持 CPU 唤醒，任务结束后运行 termux-wake-unlock 释放。唤醒锁会增加耗电，且不能解除子进程限制或厂商的后台管理。")
                        TermuxCommandText("termux-wake-lock\ntermux-wake-unlock")
                        Text("如任务在后台中断，可检查 Termux 的电池与后台运行设置，并将它保持在通知栏中。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            SnackbarHost(
                hostState = noticeHost,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }
}

@Composable
private fun TermuxGuideGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeader(title)
        BettboxListGroup {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun TermuxCommandText(command: String) {
    SelectionContainer {
        Text(command, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun TermuxDiagnosticResult(result: TermuxCommandResult) {
    val succeeded = TermuxSetupSupport.diagnosticSucceeded(result)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            if (succeeded) "诊断成功，命令结果已回传。" else "诊断未通过，请按下方提示检查配置。",
            color = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall,
        )
        Text("退出码：${result.exitCode?.toString() ?: "未返回"}", style = MaterialTheme.typography.bodyMedium)
        if (result.timedOut) Text("等待结果超时。请检查 Termux 后台运行状态后重试。", color = MaterialTheme.colorScheme.error)
        result.errorCode?.let { NexaraIdentifierText("错误：$it", style = MaterialTheme.typography.bodyMedium) }
        result.guidance?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (result.stdout.isNotEmpty()) {
            Text("标准输出", style = MaterialTheme.typography.labelLarge)
            TermuxCommandText(result.stdout)
        }
        if (result.stderr.isNotEmpty()) {
            Text("错误输出", style = MaterialTheme.typography.labelLarge)
            TermuxCommandText(result.stderr)
        }
        if (!succeeded && result.errorCode == null && !result.timedOut) {
            Text("预期输出为 NEXARA_TERMUX_OK，且退出码为 0。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun termuxSetupFailure(failure: Exception, fallback: String): String = when (failure) {
    is CancellationException -> throw failure
    is SshFailure -> "无法读取 SSH 公钥（${failure.code}），请在 SSH 主机设置中检查密钥。"
    is ExternalFolderException -> when (failure.code) {
        "CONFLICT" -> "挂载别名已存在，请选择其它名称。"
        "INVALID_PATH" -> "挂载路径或别名无效，请重新选择 Termux 文件夹。"
        "READ_ONLY" -> "Termux 未授予所需的写入权限，可选择只读挂载或重新授权。"
        "GRANT_LIMIT" -> "持久文件夹授权已达上限，请先移除不再使用的挂载。"
        else -> "Termux 文件夹当前不可用，请打开 Termux 并重新授权。"
    }
    is SecurityException -> "系统拒绝了所需权限，请在应用详情中检查授权后重试。"
    else -> fallback
}
