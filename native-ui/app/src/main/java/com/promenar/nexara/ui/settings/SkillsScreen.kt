package com.promenar.nexara.ui.settings

import com.promenar.nexara.ui.theme.NexaraMonospace
import com.promenar.nexara.ui.common.LocalNexaraHaptics
import com.promenar.nexara.ui.common.NexaraSheetHeight
import com.promenar.nexara.ui.common.NexaraModalSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import com.promenar.nexara.ui.common.NexaraIdentifierText
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.promenar.nexara.ui.common.NexaraCapsuleTabRow
import com.promenar.nexara.ui.common.NexaraTabItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalTextStyle
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.data.remote.mcp.McpEndpointPolicy
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraSettingsGroup
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.NexaraNumericSetting
import com.promenar.nexara.ui.common.NexaraValueRow
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.theme.NexaraMotion
import com.promenar.nexara.ui.theme.forInput
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.ui.common.NexaraStepperSetting
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.common.NexaraSnackbar
import com.promenar.nexara.ui.common.NexaraSnackbarData
import com.promenar.nexara.ui.common.SnackbarType
import com.promenar.nexara.ui.theme.NexaraSpacing
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import com.promenar.nexara.ui.common.NexaraPopoverSpec
import com.promenar.nexara.ui.common.nexaraPopoverAnchor
import com.promenar.nexara.ui.common.rememberNexaraPopoverAnchor

/** 技能编辑器的输入；name 为 null 表示新建。 */
private data class AgentSkillEditorState(
    val name: String?,
    val description: String,
    val body: String,
    val allowedTools: List<String>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onNavigateBack: () -> Unit,
    onOpenSsh: () -> Unit = {},
    onOpenTermux: () -> Unit = {},
    onOpenGitCredentials: () -> Unit = {},
    onOpenTailscale: () -> Unit = {},
    onOpenBackground: () -> Unit = {},
    onOpenNetworkAccess: () -> Unit = {},
    onOpenLinux: () -> Unit = {},
) {
    val context = LocalContext.current
    val linuxRuntime = (context.applicationContext as com.promenar.nexara.NexaraApplication).linux.runtime
    val linuxState by linuxRuntime.state.collectAsState()
    val linuxAgentEnabled by linuxRuntime.agentEnabled.collectAsState()
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val agentSkillsViewModel: AgentSkillsViewModel =
        viewModel(factory = AgentSkillsViewModel.factory(context.applicationContext as android.app.Application))
    val skillCatalogViewModel: com.promenar.nexara.ui.skills.SkillCatalogViewModel =
        viewModel(factory = com.promenar.nexara.ui.skills.SkillCatalogViewModel.factory(context.applicationContext as android.app.Application))
    androidx.compose.runtime.LaunchedEffect(Unit) { skillCatalogViewModel.bind(null) }
    val skillCatalogState by skillCatalogViewModel.state.collectAsState()
    val skillCatalogError by skillCatalogViewModel.error.collectAsState()
    val agentSkillEvent by agentSkillsViewModel.event.collectAsState()
    var agentSkillEditor by remember { mutableStateOf<AgentSkillEditorState?>(null) }
    val agentSkillScope = androidx.compose.runtime.rememberCoroutineScope()
    val importSkillLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { agentSkillsViewModel.import(it) } }

    val presetSkills by viewModel.skills.collectAsState()
    val userSkills by viewModel.userSkills.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val loopLimit by viewModel.loopLimit.collectAsState()
    val tokenLimit by viewModel.taskTokenLimit.collectAsState()
    val repeatLimit by viewModel.toolLoopRepeatLimit.collectAsState()
    val retryLimit by viewModel.streamRetryLimit.collectAsState()
    val application = context.applicationContext as NexaraApplication
    val networkSnapshot by application.agentNetworkAccess.snapshot.collectAsState()
    val sshHosts by remember(application) {
        application.sshHostStore.observeHosts().flowOn(Dispatchers.IO)
    }.collectAsState(initial = emptyList())
    var termuxAvailability by remember { mutableStateOf<TermuxAvailability?>(null) }
    // 凭据存储首次取用时解密 Keystore 中的主机列表，放到 IO 线程。
    val gitCredentialCount by androidx.compose.runtime.produceState<Int?>(null, application) {
        withContext(Dispatchers.IO) { application.gitCredentialStore }.credentials.collect { value = it.size }
    }
    val tailscaleState by application.tailnetManager.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val resetMessage = stringResource(R.string.settings_consent_reset)
    DisposableEffect(lifecycleOwner, application) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { termuxAvailability = withContext(Dispatchers.IO) { application.termuxRuntime.availability() } }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.skills_tab_preset),
        stringResource(R.string.skills_tab_user),
        stringResource(R.string.skills_tab_mcp)
    )

    var showAddMcp by remember { mutableStateOf(false) }
    var showCreateSkill by remember { mutableStateOf(false) }
    var showSearchConfig by remember { mutableStateOf(false) }
    var selectedSkillForEdit by remember { mutableStateOf<String?>(null) }
    var expandedServerId by remember { mutableStateOf<String?>(null) }

    NexaraSettingsPageLayout(
        title = stringResource(R.string.skills_title),
        onBack = onNavigateBack,
    ) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
            ) {

                SkillsSettingsHeaderContent(
                    state = SkillsSettingsVisualState(
                        loopLimit, tokenLimit, repeatLimit,
                        sshHostCount = sshHosts.size,
                        termuxAvailability = termuxAvailability,
                        retryLimit = retryLimit,
                        gitCredentialCount = gitCredentialCount,
                        tailscaleState = tailscaleState,
                        networkHostCount = networkSnapshot.lanHosts.size,
                        linuxState = linuxState,
                        linuxAgentEnabled = linuxAgentEnabled,
                    ),
                    actions = SkillsSettingsVisualActions(
                        onLoopLimitChange = viewModel::updateLoopLimit,
                        onTokenLimitChange = viewModel::updateTaskTokenLimit,
                        onRepeatLimitChange = viewModel::updateToolLoopRepeatLimit,
                        onRetryLimitChange = viewModel::updateStreamRetryLimit,
                        onResetConsent = {
                            viewModel.resetUnlimitedAutoConsent()
                            scope.launch { snackbarHost.showSnackbar(resetMessage) }
                        },
                        onOpenNetworkAccess = onOpenNetworkAccess,
                        onOpenSsh = onOpenSsh,
                        onOpenTermux = onOpenTermux,
                        onOpenGitCredentials = onOpenGitCredentials,
                        onOpenTailscale = onOpenTailscale,
                        onOpenBackground = onOpenBackground,
                        onOpenLinux = onOpenLinux,
                    ),
                )

                val skillTabs = remember(tabs) {
                    tabs.map { NexaraTabItem(title = it) }
                }
                NexaraCapsuleTabRow(
                    tabs = skillTabs,
                    selectedIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 同级标签按左右顺序做共享 X 轴切换，高度差由尺寸过渡平滑衔接。
                androidx.compose.animation.AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = { NexaraMotion.sharedAxisX(forward = targetState > initialState) },
                    label = "skills_tab",
                ) { tab ->
                    when (tab) {
                        0 -> BuiltinToolsSections(
                            tools = presetSkills,
                            onToggle = viewModel::toggleSkill,
                            onConfigureSearch = { showSearchConfig = true },
                        )
                        1 -> Column(verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Large)) {
                            AgentSkillsSection(
                                skills = skillCatalogState.snapshot?.user.orEmpty(),
                                actions = com.promenar.nexara.ui.skills.SkillCatalogActions(
                                    onToggle = { skill, enabled -> skillCatalogViewModel.setEnabled(skill, enabled) },
                                    onAcknowledge = skillCatalogViewModel::acknowledgeModified,
                                    onDelete = skillCatalogViewModel::delete,
                                    loadDocument = skillCatalogViewModel::loadDocument,
                                    onEdit = { skill ->
                                        agentSkillScope.launch {
                                            val document = agentSkillsViewModel.loadDocument(skill.name) ?: return@launch
                                            agentSkillEditor = AgentSkillEditorState(
                                                name = skill.name,
                                                description = document.metadata.description,
                                                body = document.body,
                                                allowedTools = document.metadata.allowedTools,
                                            )
                                        }
                                    },
                                ),
                                onImport = {
                                    importSkillLauncher.launch(
                                        arrayOf("application/zip", "text/markdown", "text/plain", "application/octet-stream"),
                                    )
                                },
                                onCreate = { agentSkillEditor = AgentSkillEditorState(null, "", "", emptyList()) },
                            )
                            if (userSkills.isNotEmpty()) {
                                LegacyUserToolsSection(
                                    tools = userSkills.map { SkillInfo(it.id, it.name, it.description, it.enabled) },
                                    onToggle = viewModel::toggleSkill,
                                    onEdit = { id ->
                                        selectedSkillForEdit = id
                                        showCreateSkill = true
                                    },
                                    onDelete = viewModel::deleteCustomSkill,
                                )
                            }
                        }
                        2 -> McpServersSection(
                            servers = mcpServers,
                            expandedServerId = expandedServerId,
                            onToggleExpand = { id -> expandedServerId = if (expandedServerId == id) null else id },
                            onToggleEnabled = viewModel::toggleMcpServer,
                            onDelete = viewModel::deleteMcpServer,
                            onSync = viewModel::syncMcpServer,
                            onAdd = { showAddMcp = true },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            ) { data -> NexaraSnackbar(NexaraSnackbarData(data.visuals.message, SnackbarType.SUCCESS)) }
        }
    }

    if (showAddMcp) {
        var mcpName by remember { mutableStateOf("") }
        var mcpUrl by remember { mutableStateOf("") }
        var mcpBearer by remember { mutableStateOf("") }
        var mcpSaving by remember { mutableStateOf(false) }
        val allowLoopback by viewModel.allowLoopbackMcp.collectAsState()
        val configError by viewModel.mcpConfigError.collectAsState()
        val isLoopback = McpEndpointPolicy.kind(mcpUrl.trim()) == McpEndpointPolicy.Kind.LOOPBACK_HTTP
        val canSave = mcpName.isNotBlank() && !mcpSaving && McpEndpointPolicy.isAllowed(
            "http", mcpUrl.trim(), allowLoopback, McpEndpointPolicy.isValidBearerToken(mcpBearer),
        )
        androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.clearMcpConfigError() }
        NexaraModalSheet(onDismissRequest = { if (!mcpSaving) showAddMcp = false }, height = NexaraSheetHeight.Full) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.skills_add_mcp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)

                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = mcpName,
                    onValueChange = { mcpName = it },
                    enabled = !mcpSaving,
                    label = { Text(stringResource(R.string.skills_mcp_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = mcpUrl,
                    onValueChange = { mcpUrl = it },
                    enabled = !mcpSaving,
                    label = { Text(stringResource(R.string.skills_mcp_url_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )

                NexaraSettingsSection(
                    title = stringResource(R.string.settings_batch1_mcp_security),
                    footer = listOfNotNull(
                        stringResource(R.string.mcp_connection_policy),
                        if (allowLoopback) stringResource(R.string.mcp_loopback_security_notice) else null,
                    ).joinToString("\n"),
                ) {
                    SettingsToggle(
                        title = stringResource(R.string.mcp_allow_loopback_http),
                        checked = allowLoopback,
                        enabled = !mcpSaving,
                        onCheckedChange = viewModel::setAllowLoopbackMcp,
                    )
                }
                if (isLoopback) com.promenar.nexara.ui.common.SecretTextInput {
                    OutlinedTextField(
                        textStyle = LocalTextStyle.current.forInput(),
                        value = mcpBearer,
                        onValueChange = { mcpBearer = it },
                        enabled = !mcpSaving,
                        label = { Text(stringResource(R.string.mcp_bearer_token)) },
                        supportingText = { Text(stringResource(R.string.mcp_bearer_token_storage)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = com.promenar.nexara.ui.common.SecretKeyboardOptions,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val errorMessage = when (configError) {
                    McpConfigError.INVALID_ENDPOINT -> R.string.mcp_invalid_endpoint
                    McpConfigError.BEARER_REQUIRED -> R.string.mcp_bearer_required
                    McpConfigError.SAVE_FAILED -> R.string.mcp_config_save_failed
                    null -> null
                }
                if (errorMessage != null) {
                    Text(
                        stringResource(errorMessage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        mcpSaving = true
                        viewModel.addMcpServer(mcpName, mcpUrl, "http", mcpBearer) { saved ->
                            mcpSaving = false
                            if (saved) {
                                mcpBearer = ""
                                showAddMcp = false
                            }
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (mcpSaving) R.string.mcp_config_saving else R.string.skills_mcp_add_btn))
                }
            }
        }
    }

    if (showCreateSkill) {
        val skillToEdit = selectedSkillForEdit?.let { id -> userSkills.find { it.id == id } }
        var skillName by remember { mutableStateOf(skillToEdit?.name ?: "") }
        var skillDesc by remember { mutableStateOf(skillToEdit?.description ?: "") }
        var skillCode by remember { mutableStateOf(skillToEdit?.code ?: "") }

        NexaraModalSheet(onDismissRequest = { showCreateSkill = false }, height = NexaraSheetHeight.Full) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (selectedSkillForEdit == null) stringResource(R.string.skills_add_custom)
                           else stringResource(R.string.skills_edit_custom),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(stringResource(R.string.skills_metadata), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = skillName,
                    onValueChange = { skillName = it },
                    label = { Text(stringResource(R.string.skills_tool_name_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = skillDesc,
                    onValueChange = { skillDesc = it },
                    label = { Text(stringResource(R.string.skills_description_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(stringResource(R.string.skills_implementation), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    value = skillCode,
                    onValueChange = { skillCode = it },
                    label = { Text(stringResource(R.string.skills_code_example)) },
                    textStyle = TextStyle(fontFamily = NexaraMonospace, fontSize = 13.sp).forInput(),
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )

                Button(
                    onClick = {
                        if (skillName.isNotBlank()) {
                            viewModel.addCustomSkill(
                                name = skillName,
                                description = skillDesc,
                                schema = "{}",
                                code = skillCode,
                                id = selectedSkillForEdit
                            )
                            showCreateSkill = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.shared_btn_save))
                }
            }
        }
    }

    agentSkillEditor?.let { editor ->
        AgentSkillEditorSheet(
            initialName = editor.name,
            initialDescription = editor.description,
            initialBody = editor.body,
            allowedTools = editor.allowedTools,
            onDismiss = { agentSkillEditor = null },
            onSave = { name, description, body ->
                agentSkillsViewModel.save(editor.name, name, description, body)
                agentSkillEditor = null
            },
        )
    }

    when (val event = agentSkillEvent) {
        is AgentSkillEvent.Conflict -> NexaraConfirmDialog(
            onDismissRequest = agentSkillsViewModel::consumeEvent,
            title = stringResource(R.string.agent_skills_conflict_title),
            message = stringResource(R.string.agent_skills_conflict_message, event.name),
            confirmText = stringResource(R.string.agent_skills_replace),
            cancelText = stringResource(R.string.common_btn_cancel),
            onConfirm = {
                agentSkillsViewModel.consumeEvent()
                event.retry()
            },
            onCancel = agentSkillsViewModel::consumeEvent,
        )
        // 无独立标题的纯提示：错误文案作标题，正文留空。
        is AgentSkillEvent.Failed -> NexaraConfirmDialog(
            onDismissRequest = agentSkillsViewModel::consumeEvent,
            title = "",
            message = stringResource(R.string.agent_skills_import_failed, event.message),
            confirmText = stringResource(R.string.shared_btn_close),
            cancelText = null,
            onConfirm = agentSkillsViewModel::consumeEvent,
            onCancel = agentSkillsViewModel::consumeEvent,
        )
        is AgentSkillEvent.Installed -> androidx.compose.runtime.LaunchedEffect(event) {
            agentSkillsViewModel.consumeEvent()
            // 导入与应用内编辑经技能包存储写入同一目录，完成后重扫技能目录。
            skillCatalogViewModel.bind(null)
        }
        null -> Unit
    }

    skillCatalogError?.let { message ->
        NexaraConfirmDialog(
            onDismissRequest = skillCatalogViewModel::consumeError,
            title = "",
            message = stringResource(R.string.skills_runtime_error, message),
            confirmText = stringResource(R.string.shared_btn_close),
            cancelText = null,
            onConfirm = skillCatalogViewModel::consumeError,
            onCancel = skillCatalogViewModel::consumeError,
        )
    }

    if (showSearchConfig) {
        SearchConfigBottomSheet(onDismiss = { showSearchConfig = false })
    }
}

/** 轮次预设与自定义值共用已有偏好回调，0 表示不限。 */
@Composable
internal fun LoopLimitCard(
    loopLimit: Int,
    onChange: (Int) -> Unit,
    icon: ImageVector? = null,
) {
    NexaraNumericSetting(
        icon = icon,
        title = stringResource(R.string.skills_loop_limit),
        value = loopLimit,
        spec = ToolLoopLimitSpec,
        description = stringResource(R.string.skills_loop_limit_desc),
        onValueChange = { onChange(it.toInt()) },
    )
}

/** 列表内分隔线：从正文起点开始，与图标列对齐。 */
@Composable
internal fun SkillRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = SkillTextStart, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 列表上方的小节标题与说明，放在卡片外，避免说明文字贴着卡片边缘。 */
@Composable
internal fun SectionCaption(title: String?, description: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 卡片内的空态：图标加居中说明。 */
@Composable
internal fun SkillsEmptyState(icon: ImageVector, message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 工具图标底座。 */
@Composable
internal fun SkillIconTile(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(SkillIconSize)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

/** 正文与公共设置行的图标列对齐。 */
private val SkillIconSize = 20.dp
internal val SkillTextStart = 52.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchConfigBottomSheet(
    onDismiss: () -> Unit
) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val searchViewModel: SearchConfigViewModel = viewModel(
        factory = SearchConfigViewModel.factory(application)
    )
    val searchState by searchViewModel.uiState.collectAsState()

    NexaraModalSheet(onDismissRequest = onDismiss, height = NexaraSheetHeight.Full) {
        SearchConfigContent(
            searchState = searchState,
            onSearchEngineChange = searchViewModel::updateSearchEngine,
            onResultCountChange = searchViewModel::updateResultCount,
            onSearchDepthChange = searchViewModel::updateSearchDepth,
            onSearXngUrlChange = searchViewModel::updateSearXngUrl,
            keenableSecretEditor = { KeenableSecretEditor(searchViewModel, searchState) },
            tavilySecretEditor = { TavilySecretEditor(searchViewModel, searchState) },
        )
    }
}

@Composable
internal fun SearchConfigContent(
    searchState: SearchConfigState = SearchConfigState(),
    onSearchEngineChange: (String) -> Unit = {},
    onResultCountChange: (Int) -> Unit = {},
    onSearchDepthChange: (String) -> Unit = {},
    onSearXngUrlChange: (String) -> Unit = {},
    keenableSecretEditor: @Composable () -> Unit = {},
    tavilySecretEditor: @Composable () -> Unit = {},
) {
    val depthAnchor = rememberNexaraPopoverAnchor()
    var showDepthPicker by remember(searchState.searchEngine) { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = stringResource(R.string.search_sheet_web_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.onSurface
            )

            NexaraSettingsSection(title = stringResource(R.string.search_engine_select)) {
                EngineOption("duckduckgo", stringResource(R.string.search_engine_duckduckgo), searchState.searchEngine == "duckduckgo") {
                    onSearchEngineChange("duckduckgo")
                }
                EngineOption("keenable", stringResource(R.string.search_engine_keenable), searchState.searchEngine == "keenable") {
                    onSearchEngineChange("keenable")
                }
                EngineOption("tavily", stringResource(R.string.search_engine_tavily), searchState.searchEngine == "tavily") {
                    onSearchEngineChange("tavily")
                }
                EngineOption("searxng", stringResource(R.string.search_engine_searxng), searchState.searchEngine == "searxng") {
                    onSearchEngineChange("searxng")
                }
            }

            NexaraSettingsSection(title = stringResource(R.string.settings_batch1_search_results)) {
            NexaraStepperSetting(
                title = stringResource(R.string.search_count_label),
                value = searchState.resultCount.toString(),
                canDecrease = searchState.resultCount > 1,
                canIncrease = searchState.resultCount < 20,
                onDecrease = { onResultCountChange(searchState.resultCount - 1) },
                onIncrease = { onResultCountChange(searchState.resultCount + 1) },
            )
            }

            if (searchState.searchEngine == "keenable") {
                NexaraSettingsSection(
                    title = stringResource(R.string.search_api_key_optional),
                    footer = stringResource(R.string.search_keenable_keyless_hint),
                ) { Column(Modifier.padding(16.dp)) { keenableSecretEditor() } }
            }

            if (searchState.searchEngine == "tavily") {
                NexaraSettingsSection(title = stringResource(R.string.search_api_key)) {
                    Column(Modifier.padding(16.dp)) { tavilySecretEditor() }
                }

                NexaraSettingsSection(title = stringResource(R.string.settings_batch1_search_options)) {
                    NexaraValueRow(
                        title = stringResource(R.string.search_depth_label),
                        value = when (searchState.searchDepth) {
                            "basic" -> stringResource(R.string.search_depth_basic)
                            "advanced" -> stringResource(R.string.search_depth_advanced)
                            else -> searchState.searchDepth
                        },
                        onClick = { if (!depthAnchor.consumeRecentDismiss()) showDepthPicker = true },
                        modifier = Modifier.nexaraPopoverAnchor(depthAnchor).testTag("search_depth_picker"),
                    )
                }
            }

            if (searchState.searchEngine == "searxng") {
                OutlinedTextField(
                    label = { Text(stringResource(R.string.search_instance_url)) },
                    value = searchState.searXngUrl,
                    onValueChange = { onSearXngUrlChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = NexaraMonospace).forInput()
                )
            }
        }
    NexaraBottomSheet(
        show = showDepthPicker,
        scrollContent = true,
        title = stringResource(R.string.search_depth_label),
        onDismiss = { showDepthPicker = false },
        popover = NexaraPopoverSpec(depthAnchor),
    ) {
        DepthChip("basic", stringResource(R.string.search_depth_basic), searchState.searchDepth == "basic") {
            onSearchDepthChange("basic")
            showDepthPicker = false
        }
        DepthChip("advanced", stringResource(R.string.search_depth_advanced), searchState.searchDepth == "advanced") {
            onSearchDepthChange("advanced")
            showDepthPicker = false
        }
    }

}

@Composable
internal fun EngineOption(id: String, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val haptics = LocalNexaraHaptics.current
    val select = { haptics.select(isSelected); onClick() }
    NexaraSettingsItem(
        title = label,
        onClick = select,
        modifier = Modifier.testTag("search_engine_$id")
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = select),
        role = Role.RadioButton,
        selected = isSelected,
        trailingContent = { androidx.compose.material3.RadioButton(selected = isSelected, onClick = null) },
    )
}

/** 搜索深度单选行；函数签名供既有无障碍测试复用。 */
@Composable
internal fun DepthChip(id: String, label: String, isSelected: Boolean, onClick: () -> Unit) {
    NexaraSettingsItem(
        title = label,
        horizontalPadding = 0.dp,
        onClick = onClick,
        modifier = Modifier.testTag("search_depth_$id"),
        role = Role.RadioButton,
        selected = isSelected,
        trailingContent = { androidx.compose.material3.RadioButton(selected = isSelected, onClick = null) },
    )
}
