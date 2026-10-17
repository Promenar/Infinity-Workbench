package com.promenar.nexara.ui.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.promenar.nexara.ui.common.NexaraCapsuleTabRow
import com.promenar.nexara.ui.common.NexaraTabItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalTextStyle
import com.promenar.nexara.R
import com.promenar.nexara.data.remote.mcp.McpEndpointPolicy
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.ui.common.NexaraSlider
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
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val agentSkillsViewModel: AgentSkillsViewModel =
        viewModel(factory = AgentSkillsViewModel.factory(context.applicationContext as android.app.Application))
    val agentSkills by agentSkillsViewModel.skills.collectAsState()
    val agentSkillEvent by agentSkillsViewModel.event.collectAsState()
    var agentSkillEditor by remember { mutableStateOf<AgentSkillEditorState?>(null) }
    val agentSkillScope = androidx.compose.runtime.rememberCoroutineScope()
    val importSkillLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { agentSkillsViewModel.import(it) } }

    val skillIcons = remember {
        mapOf(
            "web_search" to Icons.Rounded.Search,
            "web_fetch" to Icons.Rounded.Description,
            "search_tavily" to Icons.Rounded.Search,
            "search_searxng" to Icons.Rounded.Search,
            "calculator" to Icons.Rounded.Build,
            "image_generation" to Icons.Rounded.Image,
            "file_read" to Icons.Rounded.Description,
            "file_write" to Icons.Rounded.Edit,
            "file_list" to Icons.Rounded.Folder,
            "file_search" to Icons.Rounded.Search,
            "file_diff" to Icons.Rounded.Sync,
            "file_patch" to Icons.Rounded.Build,
            "exec_js" to Icons.Rounded.Code,
            "run_script" to Icons.Rounded.Code,
            "initialize_plan" to Icons.Rounded.AccountTree,
            "update_plan" to Icons.Rounded.Edit,
            "get_plan" to Icons.Rounded.Visibility,
            "drop_plan" to Icons.Rounded.Cancel,
            "create_file" to Icons.Rounded.Add,
            "create_directory" to Icons.Rounded.Folder,
            "move_file" to Icons.Rounded.Sync,
            "delete_file" to Icons.Rounded.Delete,
        )
    }
    val presetSkills by viewModel.skills.collectAsState()
    val userSkills by viewModel.userSkills.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val loopLimit by viewModel.loopLimit.collectAsState()
    val tokenLimit by viewModel.taskTokenLimit.collectAsState()
    val contextRatio by viewModel.toolContextRatio.collectAsState()
    val keepRounds by viewModel.toolContextKeepRounds.collectAsState()
    val repeatLimit by viewModel.toolLoopRepeatLimit.collectAsState()
    val scriptHosts by viewModel.scriptLanHosts.collectAsState()
    val approveWrites by viewModel.scriptApproveFileWrites.collectAsState()
    val application = context.applicationContext as NexaraApplication
    val sshHosts by remember(application) {
        application.sshHostStore.observeHosts().flowOn(Dispatchers.IO)
    }.collectAsState(initial = emptyList())
    var termuxAvailability by remember { mutableStateOf<TermuxAvailability?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val resetMessage = stringResource(R.string.settings_consent_reset)
    val hostsSavedMessage = stringResource(R.string.settings_hosts_saved)
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
    var showSearchConfig by remember { mutableStateOf<String?>(null) }
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
                        loopLimit, tokenLimit, contextRatio, keepRounds, repeatLimit, scriptHosts, approveWrites,
                        sshHostCount = sshHosts.size,
                        termuxAvailability = termuxAvailability,
                    ),
                    actions = SkillsSettingsVisualActions(
                        onLoopLimitChange = viewModel::updateLoopLimit,
                        onTokenLimitChange = viewModel::updateTaskTokenLimit,
                        onContextRatioChange = viewModel::updateToolContextRatio,
                        onKeepRoundsChange = viewModel::updateToolContextKeepRounds,
                        onRepeatLimitChange = viewModel::updateToolLoopRepeatLimit,
                        onResetConsent = {
                            viewModel.resetUnlimitedAutoConsent()
                            scope.launch { snackbarHost.showSnackbar(resetMessage) }
                        },
                        onApproveScriptWritesChange = viewModel::updateScriptApproveFileWrites,
                        onSaveScriptHosts = viewModel::updateScriptLanHosts,
                        onScriptHostsSaved = { scope.launch { snackbarHost.showSnackbar(hostsSavedMessage) } },
                        onOpenSsh = onOpenSsh,
                        onOpenTermux = onOpenTermux,
                    ),
                )

                Spacer(modifier = Modifier.height(NexaraSpacing.Large))

                val skillTabs = remember(tabs) {
                    tabs.map { NexaraTabItem(title = it) }
                }
                NexaraCapsuleTabRow(
                    tabs = skillTabs,
                    selectedIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.fillMaxWidth(),
                    height = 46.dp,
                )

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> BettboxListGroup {
                        presetSkills.forEachIndexed { index, skill ->
                            SkillItem(
                                skill = skill,
                                icon = skillIcons[skill.id] ?: Icons.Rounded.Code,
                                onToggle = { viewModel.toggleSkill(skill.id) },
                                onConfig = if (skill.id in listOf("web_search", "search_tavily", "search_searxng")) {
                                    { showSearchConfig = skill.id }
                                } else null
                            )
                            if (index < presetSkills.lastIndex) SkillRowDivider()
                        }
                    }
                    1 -> {
                        AgentSkillsSection(
                            skills = agentSkills,
                            onToggle = { name, enabled -> agentSkillsViewModel.setEnabled(name, enabled) },
                            onOpen = { name ->
                                agentSkillScope.launch {
                                    val document = agentSkillsViewModel.loadDocument(name) ?: return@launch
                                    agentSkillEditor = AgentSkillEditorState(
                                        name = name,
                                        description = document.metadata.description,
                                        body = document.body,
                                        allowedTools = document.metadata.allowedTools,
                                    )
                                }
                            },
                            onDelete = agentSkillsViewModel::delete,
                            onImport = {
                                importSkillLauncher.launch(
                                    arrayOf("application/zip", "text/markdown", "text/plain", "application/octet-stream"),
                                )
                            },
                            onCreate = { agentSkillEditor = AgentSkillEditorState(null, "", "", emptyList()) },
                        )

                        if (userSkills.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(24.dp))
                            SectionCaption(
                                title = stringResource(R.string.agent_skills_legacy_header),
                                description = stringResource(R.string.sheet_tool_custom_unsandboxed),
                            )
                            BettboxListGroup {
                                userSkills.forEachIndexed { index, skill ->
                                    UserSkillItem(
                                        id = skill.id,
                                        name = skill.name,
                                        description = skill.description,
                                        enabled = skill.enabled,
                                        icon = Icons.Rounded.Code,
                                        onToggle = { viewModel.toggleSkill(skill.id) },
                                        onEdit = {
                                            selectedSkillForEdit = skill.id
                                            showCreateSkill = true
                                        },
                                        onDelete = { viewModel.deleteCustomSkill(skill.id) }
                                    )
                                    if (index < userSkills.lastIndex) SkillRowDivider()
                                }
                            }
                        }
                    }
                    2 -> {
                        Button(
                            onClick = { showAddMcp = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.skills_add_mcp))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        BettboxListGroup {
                            if (mcpServers.isEmpty()) {
                                SkillsEmptyState(
                                    icon = Icons.Rounded.Hub,
                                    message = stringResource(R.string.skills_mcp_empty),
                                )
                            }
                            mcpServers.forEachIndexed { index, server ->
                                McpServerItem(
                                    server = server,
                                    isExpanded = expandedServerId == server.id,
                                    onToggleExpand = { expandedServerId = if (expandedServerId == server.id) null else server.id },
                                    onToggleEnabled = { enabled -> viewModel.toggleMcpServer(server.id, enabled) },
                                    onDelete = { viewModel.deleteMcpServer(server.id) },
                                    onSync = { viewModel.syncMcpServer(server.id) },
                                )
                                if (index < mcpServers.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
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
        ModalBottomSheet(
            onDismissRequest = { if (!mcpSaving) showAddMcp = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
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

                Text(
                    text = stringResource(R.string.mcp_connection_policy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = allowLoopback,
                        enabled = !mcpSaving,
                        role = Role.Switch,
                        onValueChange = viewModel::setAllowLoopbackMcp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.mcp_allow_loopback_http),
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = allowLoopback, onCheckedChange = null, enabled = !mcpSaving)
                }
                if (allowLoopback) {
                    Text(
                        stringResource(R.string.mcp_loopback_security_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isLoopback) {
                    OutlinedTextField(
                        textStyle = LocalTextStyle.current.forInput(),
                        value = mcpBearer,
                        onValueChange = { mcpBearer = it },
                        enabled = !mcpSaving,
                        label = { Text(stringResource(R.string.mcp_bearer_token)) },
                        supportingText = { Text(stringResource(R.string.mcp_bearer_token_storage)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
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

        ModalBottomSheet(
            onDismissRequest = { showCreateSkill = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
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
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp).forInput(),
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
        is AgentSkillEvent.Conflict -> androidx.compose.material3.AlertDialog(
            onDismissRequest = agentSkillsViewModel::consumeEvent,
            title = { Text(stringResource(R.string.agent_skills_conflict_title)) },
            text = { Text(stringResource(R.string.agent_skills_conflict_message, event.name)) },
            confirmButton = {
                TextButton(onClick = {
                    agentSkillsViewModel.consumeEvent()
                    event.retry()
                }) { Text(stringResource(R.string.agent_skills_replace)) }
            },
            dismissButton = {
                TextButton(onClick = agentSkillsViewModel::consumeEvent) {
                    Text(stringResource(R.string.common_btn_cancel))
                }
            },
        )
        is AgentSkillEvent.Failed -> androidx.compose.material3.AlertDialog(
            onDismissRequest = agentSkillsViewModel::consumeEvent,
            text = { Text(stringResource(R.string.agent_skills_import_failed, event.message)) },
            confirmButton = {
                TextButton(onClick = agentSkillsViewModel::consumeEvent) {
                    Text(stringResource(R.string.shared_btn_close))
                }
            },
        )
        is AgentSkillEvent.Installed -> androidx.compose.runtime.LaunchedEffect(event) {
            agentSkillsViewModel.consumeEvent()
        }
        null -> Unit
    }

    if (showSearchConfig != null) {
        SearchConfigBottomSheet(
            skillId = showSearchConfig!!,
            onDismiss = { showSearchConfig = null }
        )
    }
}

/** 轮次和不限开关共用设置行标尺，回调保留原有范围与切换值。 */
@Composable
internal fun LoopLimitCard(
    loopLimit: Int,
    onChange: (Int) -> Unit,
) {
    NexaraStepperSetting(
        title = stringResource(R.string.skills_loop_limit),
        description = stringResource(R.string.skills_loop_limit_desc),
        value = if (loopLimit == 0) stringResource(R.string.p0_unlimited) else loopLimit.toString(),
        canDecrease = loopLimit > 1,
        canIncrease = loopLimit in 1..99,
        onDecrease = { onChange((loopLimit - 1).coerceAtLeast(1)) },
        onIncrease = { onChange((loopLimit + 1).coerceAtMost(100)) },
    )
    SettingsToggle(
        title = stringResource(R.string.p0_unlimited),
        checked = loopLimit == 0,
        onCheckedChange = { onChange(if (it) 0 else 50) },
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

/** 图标 40dp + 左边距 16dp + 间距 14dp：正文与分隔线的起点。 */
private val SkillIconSize = 40.dp
internal val SkillTextStart = 70.dp

/**
 * 预设工具行：右侧只放开关，说明获得完整宽度；可配置的工具在说明下方给出“配置”入口。
 * 整行可点击切换开关。
 */
@Composable
private fun SkillItem(
    skill: SkillInfo,
    icon: ImageVector,
    onToggle: () -> Unit,
    onConfig: (() -> Unit)? = null
) {
    var enabled by remember { mutableStateOf(skill.enabled) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = { enabled = it; onToggle() },
            )
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (onConfig != null) 6.dp else 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SkillIconTile(icon)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(skill.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                skill.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            // 工具 ID 与“配置”入口同处一行，避免入口单独占一行留出空白。
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = if (onConfig != null) 40.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = skill.id,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 4.dp),
                )
                if (onConfig != null) {
                    TextButton(
                        onClick = onConfig,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.common_cd_config), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
private fun UserSkillItem(
    id: String,
    name: String,
    description: String,
    enabled: Boolean,
    icon: ImageVector,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var isEnabled by remember { mutableStateOf(enabled) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = isEnabled,
                    role = Role.Switch,
                    onValueChange = { isEnabled = it; onToggle() },
                )
                .padding(start = 16.dp, end = 16.dp, top = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SkillIconTile(icon)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (description.isNotBlank()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = isEnabled, onCheckedChange = null)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SkillTextStart - 12.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.skills_configure))
            }
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.shared_btn_delete))
            }
        }
    }
}

/** MCP 服务器：状态点、名称、地址与连接状态；开关在右侧，同步与删除在下方操作行。 */
@Composable
private fun McpServerItem(
    server: McpServerUiModel,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit,
) {
    val enabledLabel = stringResource(R.string.skills_mcp_enabled)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier.size(SkillIconSize),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (server.isConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            CircleShape
                        )
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                NexaraIdentifierText(
                    text = server.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        !server.isSupported && McpEndpointPolicy.kind(server.url) == McpEndpointPolicy.Kind.LOOPBACK_HTTP ->
                            stringResource(R.string.mcp_loopback_unavailable)
                        !server.isSupported -> stringResource(R.string.skills_mcp_legacy_unsupported)
                        server.syncError != null -> stringResource(R.string.skills_mcp_sync_error, server.syncError)
                        else -> stringResource(R.string.skills_mcp_modern_transport)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (server.isSupported && server.syncError == null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = server.isEnabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.semantics { contentDescription = "${server.name}: $enabledLabel" },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SkillTextStart - 12.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (server.tools.isNotEmpty()) {
                TextButton(onClick = onToggleExpand) {
                    Text(
                        text = stringResource(R.string.skills_mcp_tools, server.tools.size),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onSync, enabled = server.isSupported && server.isEnabled) {
                Icon(Icons.Rounded.Sync, contentDescription = stringResource(R.string.common_cd_sync))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.common_cd_delete), tint = MaterialTheme.colorScheme.error)
            }
        }

        if (server.tools.isNotEmpty()) {
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = SkillTextStart, end = 16.dp, bottom = 12.dp)
                ) {
                    server.tools.forEach { tool ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                            Text(tool, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchConfigBottomSheet(
    skillId: String,
    onDismiss: () -> Unit
) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val searchViewModel: SearchConfigViewModel = viewModel(
        factory = SearchConfigViewModel.factory(application)
    )
    val searchState by searchViewModel.uiState.collectAsState()

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), CircleShape)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = when (skillId) {
                    "web_search" -> stringResource(R.string.search_sheet_web_title)
                    "search_tavily" -> stringResource(R.string.search_sheet_tavily_title)
                    "search_searxng" -> stringResource(R.string.search_sheet_searxng_title)
                    else -> stringResource(R.string.search_sheet_title)
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (skillId == "web_search") {
                Text(stringResource(R.string.search_engine_select), style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineOption("duckduckgo", stringResource(R.string.search_engine_duckduckgo), searchState.searchEngine == "duckduckgo") {
                        searchViewModel.updateSearchEngine("duckduckgo")
                    }
                    EngineOption("keenable", stringResource(R.string.search_engine_keenable), searchState.searchEngine == "keenable") {
                        searchViewModel.updateSearchEngine("keenable")
                    }
                    EngineOption("tavily", stringResource(R.string.search_engine_tavily), searchState.searchEngine == "tavily") {
                        searchViewModel.updateSearchEngine("tavily")
                    }
                    EngineOption("searxng", stringResource(R.string.search_engine_searxng), searchState.searchEngine == "searxng") {
                        searchViewModel.updateSearchEngine("searxng")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.search_count_label) + ": ${searchState.resultCount}",
                    style = MaterialTheme.typography.labelMedium
                )
                NexaraSlider(
                    value = searchState.resultCount.toFloat(),
                    valueRange = 1f..20f,
                    steps = 18,
                    onValueChange = { searchViewModel.updateResultCount(it.toInt()) }
                )
            }

            if (skillId == "web_search" && searchState.searchEngine == "keenable") {
                Text(
                    stringResource(R.string.search_keenable_keyless_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.search_api_key_optional), style = MaterialTheme.typography.labelMedium)
                KeenableSecretEditor(searchViewModel, searchState)
            }

            if (skillId == "search_tavily" || (skillId == "web_search" && searchState.searchEngine == "tavily")) {
                Text(stringResource(R.string.search_api_key), style = MaterialTheme.typography.labelMedium)
                TavilySecretEditor(searchViewModel, searchState)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.search_depth_label), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DepthChip("basic", stringResource(R.string.search_depth_basic), searchState.searchDepth == "basic") {
                            searchViewModel.updateSearchDepth("basic")
                        }
                        DepthChip("advanced", stringResource(R.string.search_depth_advanced), searchState.searchDepth == "advanced") {
                            searchViewModel.updateSearchDepth("advanced")
                        }
                    }
                }
            }

            if (skillId == "search_searxng" || (skillId == "web_search" && searchState.searchEngine == "searxng")) {
                Text(stringResource(R.string.search_instance_url), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = searchState.searXngUrl,
                    onValueChange = { searchViewModel.updateSearXngUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace).forInput()
                )
            }
        }
    }
}

@Composable
internal fun EngineOption(id: String, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
            .border(
                width = if (isSelected) 1.dp else 0.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .defaultMinSize(minHeight = 48.dp)
            .testTag("search_engine_$id")
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun DepthChip(id: String, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .defaultMinSize(minHeight = 48.dp)
            .testTag("search_depth_$id")
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
