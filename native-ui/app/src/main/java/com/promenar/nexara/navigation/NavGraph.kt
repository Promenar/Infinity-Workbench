package com.promenar.nexara.navigation

import androidx.compose.runtime.Composable
import com.promenar.nexara.ui.theme.NexaraMotion
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.BuildConfig
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.onboarding.OnboardingStateStore
import com.promenar.nexara.onboarding.OnboardingStep
import com.promenar.nexara.onboarding.OnboardingLanguageSelectionCoordinator
import com.promenar.nexara.onboarding.isSuccessfulOnboardingAssistant
import com.promenar.nexara.ui.chat.ChatRoute
import com.promenar.nexara.ui.hub.AgentAdvancedRetrievalScreen
import com.promenar.nexara.ui.hub.AgentEditScreen
import com.promenar.nexara.ui.hub.AgentRagConfigScreen
import com.promenar.nexara.ui.rag.DocEditorScreen
import com.promenar.nexara.ui.rag.GlobalRagConfigScreen
import com.promenar.nexara.ui.rag.KnowledgeGraphScreen
import com.promenar.nexara.ui.rag.RagAdvancedScreen
import com.promenar.nexara.ui.rag.RagDebugScreen
import com.promenar.nexara.ui.rag.RagFolderScreen
import com.promenar.nexara.ui.rag.AdvancedRetrievalScreen
import com.promenar.nexara.ui.settings.BackupSettingsScreen
import com.promenar.nexara.ui.settings.LocalModelsScreen
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.ui.settings.ProviderFormScreen
import com.promenar.nexara.ui.settings.ProviderModelsScreen
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.settings.ProviderListScreen
import com.promenar.nexara.ui.settings.DefaultModelsScreen
import com.promenar.nexara.ui.settings.SkillsScreen
import com.promenar.nexara.ui.settings.ThemeScreen
import com.promenar.nexara.ui.settings.TokenUsageScreen
import com.promenar.nexara.ui.welcome.OnboardingRoute
import com.promenar.nexara.ui.welcome.offersOnboardingLinux
import com.promenar.nexara.util.LocaleController
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

object NavDestinations {
    const val WELCOME = "welcome"
    const val MAIN_TAB_SCAFFOLD = "main_tab_scaffold"
    const val CHAT_HERO = "chat_hero/{sessionId}"
    const val PROVIDER_FORM = "provider_form?providerId={providerId}"
    const val PROVIDER_MODELS = "provider_models/{providerId}"
    const val AGENT_EDIT = "agent_edit/{agentId}"
    const val AGENT_RAG_CONFIG = "agent_rag_config/{agentId}"
    const val AGENT_ADVANCED_RETRIEVAL = "agent_advanced_retrieval/{agentId}"
    const val DOC_EDITOR = "doc_editor/{workspaceRootUuid}/{docId}"
    const val KNOWLEDGE_GRAPH = "knowledge_graph"
    const val KNOWLEDGE_GRAPH_ROUTE = "knowledge_graph?query={query}"
    const val SUBAGENT_VIEWER_ROUTE = "subagent/{sessionId}/{stepId}/{index}"
    const val RAG_ADVANCED = "rag_advanced"
    const val RAG_ADVANCED_KG = "rag_advanced_kg"
    const val RAG_GLOBAL_CONFIG = "rag_global_config"
    const val LONG_TERM_MEMORY = "long_term_memory"
    const val RAG_FOLDER = "rag_folder/{folderId}"
    const val RAG_DEBUG = "rag_debug"
    const val TOKEN_USAGE = "token_usage"
    const val SKILLS_CONFIG = "skills_config"
    const val SSH_CONFIG = "ssh_config"
    const val TERMUX_CONFIG = "termux_config"
    const val LINUX_ENVIRONMENT = "linux_environment"
    const val LINUX_PACKAGES = "linux_packages"
    const val LINUX_MIRRORS = "linux_mirrors"
    const val LINUX_TERMINAL = "linux_terminal?session={session}&dir={dir}&cmd={cmd}"
    const val LINUX_FILES = "linux_files?path={path}"
    const val LINUX_FILE = "linux_file?path={path}"
    const val COMMAND_OUTPUT = "command_output/{sessionId}/{messageId}/{toolCallId}"
    const val NETWORK_ACCESS = "network_access"
    const val TAILSCALE_CONFIG = "tailscale_config"
    const val SSH_TERMINAL = "ssh_terminal/{alias}"
    const val GIT_CREDENTIALS = "git_credentials"
    const val BACKGROUND_ACTIVITY = "background_activity"
    const val THEME_CONFIG = "theme_config"
    const val BACKUP_SETTINGS = "backup_settings"
    const val LOCAL_MODELS = "local_models"
    const val PROVIDER_LIST = "provider_list"
    const val DEFAULT_MODELS = "default_models"

    fun chatHero(sessionId: String) = "chat_hero/$sessionId"
    fun agentEdit(agentId: String) = "agent_edit/$agentId"
    fun agentRagConfig(agentId: String) = "agent_rag_config/$agentId"
    fun agentAdvancedRetrieval(agentId: String) = "agent_advanced_retrieval/$agentId"
    fun providerForm(providerId: String? = null) =
        if (providerId != null) "provider_form?providerId=$providerId" else "provider_form"
    fun providerModels(providerId: String) = "provider_models/$providerId"
    fun docEditor(workspaceRootUuid: String, docId: String) = "doc_editor/$workspaceRootUuid/$docId"
    @Suppress("UNUSED_PARAMETER")
    fun ragFolder(folderId: String) = "rag_folder/$folderId"
    fun knowledgeGraph(query: String) = "knowledge_graph?query=${android.net.Uri.encode(query)}"
    fun linuxTerminal(sessionId: String? = null, directory: String? = null, command: String? = null) =
        "linux_terminal" + query("session" to sessionId, "dir" to directory, "cmd" to command)
    fun linuxFiles(path: String? = null) = "linux_files" + query("path" to path)
    fun linuxFile(path: String) = "linux_file" + query("path" to path)
    fun commandOutput(sessionId: String, messageId: String, toolCallId: String) =
        "command_output/${android.net.Uri.encode(sessionId)}/${android.net.Uri.encode(messageId)}/${android.net.Uri.encode(toolCallId)}"
    private fun query(vararg params: Pair<String, String?>) = params.filter { it.second != null }
        .joinToString("&", prefix = "?") { (key, value) -> "$key=${android.net.Uri.encode(value)}" }
        .takeIf { it != "?" }.orEmpty()
    fun subagentViewer(sessionId: String, stepId: String, index: Int) =
        "subagent/${android.net.Uri.encode(sessionId)}/${android.net.Uri.encode(stepId)}/$index"
}

typealias ChatDestination = @Composable (
    sessionId: String,
    onNavigateBack: () -> Unit,
    onNavigateToSession: (String) -> Unit,
    onOpenDefaultModels: () -> Unit,
    onOpenKnowledgeGraph: (String) -> Unit,
    onOpenSubagent: (sessionId: String, stepId: String, index: Int) -> Unit,
    onOpenTailscaleSettings: () -> Unit,
) -> Unit

@Composable
fun NexaraNavGraph(
    navController: NavHostController,
    startDestination: String = NavDestinations.WELCOME,
    onboardingStateStore: OnboardingStateStore? = null,
    onboardingModelsOverride: List<ModelInfo>? = null,
    forceLocalProbeFailureForTesting: Boolean = false,
    openGenerationRequest: OpenGenerationRequest? = null,
    onOpenGenerationConsumed: (String) -> Boolean = { false },
    chatDestination: ChatDestination = { sessionId, onNavigateBack, onNavigateToSession, onOpenDefaultModels, onOpenKnowledgeGraph, onOpenSubagent, onOpenTailscaleSettings ->
        ChatRoute(
            sessionId = sessionId,
            onNavigateBack = onNavigateBack,
            onNavigateToSession = onNavigateToSession,
            onOpenDefaultModels = onOpenDefaultModels,
            onOpenKnowledgeGraph = onOpenKnowledgeGraph,
            onOpenSubagent = onOpenSubagent,
            onOpenTailscaleSettings = onOpenTailscaleSettings,
        )
    },
) {
    val context = LocalContext.current
    val app = context.applicationContext as NexaraApplication
    val onboardingStore = remember(app, onboardingStateStore) {
        onboardingStateStore ?: OnboardingStateStore(app, linuxOffered = app.offersOnboardingLinux())
    }
    val onboardingState by onboardingStore.state.collectAsState()
    val languageSelection = remember(context, onboardingStore) {
        OnboardingLanguageSelectionCoordinator(
            stateStore = onboardingStore,
            currentLanguage = { LocaleController.getSavedLanguage(context) },
            persistLanguage = { LocaleController.persistLanguage(context, it) },
            applyLanguage = { LocaleController.applyLanguage(context, it) },
        )
    }

    LaunchedEffect(onboardingState.step, onboardingState.sessionId) {
        val sessionId = onboardingState.sessionId
        if (onboardingState.step == OnboardingStep.FIRST_CHAT && sessionId != null) {
            app.chatStore.state.collect { chatState ->
                val assistantSucceeded = chatState.sessions
                    .firstOrNull { it.id == sessionId }
                    ?.messages
                    .orEmpty()
                    .any(::isSuccessfulOnboardingAssistant)
                if (assistantSucceeded) {
                    onboardingStore.recordFirstChatResult(sessionId, assistantSucceeded = true)
                }
            }
        }
    }

    LaunchedEffect(openGenerationRequest, onboardingState.step) {
        val request = openGenerationRequest ?: return@LaunchedEffect
        if (onboardingState.step != OnboardingStep.COMPLETED) return@LaunchedEffect
        navController.navigate(NavDestinations.chatHero(request.sessionId)) {
            launchSingleTop = true
        }
        onOpenGenerationConsumed(request.requestId)
    }

    val mainTabs = com.promenar.nexara.ui.rememberMainTabsState()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val settingsTwoPane = com.promenar.nexara.ui.settings.settingsTwoPaneAvailable(configuration.screenWidthDp, configuration.screenHeightDp)
    // 窗口变宽：设置标签打开的整页设置子页收回到设置右列，主导航回到主标签页。
    LaunchedEffect(settingsTwoPane) {
        if (!settingsTwoPane || mainTabs.selectedTab != com.promenar.nexara.ui.AppTab.SETTINGS) return@LaunchedEffect
        val pages = NavControllerSettingsBackStack(navController).takeSettingsPagesAboveMainTabs() ?: return@LaunchedEffect
        mainTabs.settingsStack = pages
        mainTabs.settingsLastRoot = pages.first()
    }
    val hubThreePane = com.promenar.nexara.ui.hub.hubThreePaneAvailable(configuration.screenWidthDp, configuration.screenHeightDp)
    // 窗口变宽（展开折叠屏）：对话页打开的整页会话收进首页会话列，主导航回到主标签页。
    LaunchedEffect(hubThreePane) {
        if (!hubThreePane || mainTabs.selectedTab != com.promenar.nexara.ui.AppTab.CHAT) return@LaunchedEffect
        val pages = NavControllerSettingsBackStack(navController).takeHubPagesAboveMainTabs() ?: return@LaunchedEffect
        mainTabs.restoreHubStack(pages)
    }
    // 首页会话列：会话与子代理查看页在列内切换，其余页面（默认模型、知识图谱、Linux 等）仍由主导航整页打开。
    val hubPaneGraph: NavGraphBuilder.(HubPaneNavigator) -> Unit = remember(navController, chatDestination, app) {
        { navigator -> hubPaneDestinations(navigator, navController, chatDestination, app) }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        // 页面层级导航统一用共享 X 轴：前进从右侧滑入，返回从左侧滑入，旧页先淡出。
        enterTransition = { NexaraMotion.sharedAxisXEnter(forward = true) },
        exitTransition = { NexaraMotion.sharedAxisXExit(forward = true) },
        popEnterTransition = { NexaraMotion.sharedAxisXEnter(forward = false) },
        popExitTransition = { NexaraMotion.sharedAxisXExit(forward = false) },
    ) {
        composable(NavDestinations.WELCOME) {
            OnboardingRoute(
                store = onboardingStore,
                languageSelection = languageSelection,
                navController = navController,
                modelsOverride = onboardingModelsOverride,
                forceLocalProbeFailureForTesting = forceLocalProbeFailureForTesting,
            )
        }

        composable(NavDestinations.MAIN_TAB_SCAFFOLD) {
            com.promenar.nexara.ui.MainTabScaffold(
                onNavigateToSecondary = { route ->
                    navController.navigate(route)
                },
                onNavigateToAgentEdit = { agentId ->
                    navController.navigate(NavDestinations.agentEdit(agentId))
                },
                onNavigateToChat = { sessionId ->
                    navController.navigate(NavDestinations.chatHero(sessionId))
                },
                state = mainTabs,
                settingsPaneGraph = { navigator -> settingsDestinations(navigator) },
                hubPaneGraph = hubPaneGraph,
            )
        }

        composable(
            route = NavDestinations.CHAT_HERO,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            ChatHeroDestination(
                sessionId = sessionId,
                navController = navController,
                chatDestination = chatDestination,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSession = { branchedSessionId ->
                    navController.navigate(NavDestinations.chatHero(branchedSessionId)) {
                        launchSingleTop = true
                    }
                },
                onOpenSubagent = { owner, stepId, index -> navController.navigate(NavDestinations.subagentViewer(owner, stepId, index)) },
            )
        }

        composable(
            route = NavDestinations.SUBAGENT_VIEWER_ROUTE,
            arguments = SubagentViewerArguments,
        ) { backStackEntry ->
            SubagentViewerDestination(app, backStackEntry, onBack = { navController.popBackStack() })
        }

        composable(
            route = NavDestinations.AGENT_EDIT,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
            AgentEditScreen(
                agentId = agentId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToRagConfig = { navController.navigate(NavDestinations.agentRagConfig(agentId)) },
                onNavigateToAdvancedRetrieval = { navController.navigate(NavDestinations.agentAdvancedRetrieval(agentId)) }
            )
        }

        composable(
            route = NavDestinations.AGENT_RAG_CONFIG,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
            AgentRagConfigScreen(
                agentId = agentId,
                scopeLabel = androidx.compose.ui.res.stringResource(com.promenar.nexara.R.string.agent_scope_label),
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavDestinations.AGENT_ADVANCED_RETRIEVAL,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
            AgentAdvancedRetrievalScreen(
                agentId = agentId,
                scopeLabel = androidx.compose.ui.res.stringResource(com.promenar.nexara.R.string.agent_scope_label),
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavDestinations.DOC_EDITOR,
            arguments = listOf(
                navArgument("workspaceRootUuid") { type = NavType.StringType },
                navArgument("docId") { type = NavType.StringType },
            )
        ) { backStackEntry ->
            val workspaceRootUuid = backStackEntry.arguments?.getString("workspaceRootUuid") ?: ""
            val docId = backStackEntry.arguments?.getString("docId") ?: ""
            DocEditorScreen(
                workspaceRootUuid = workspaceRootUuid,
                docId = docId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        settingsDestinations(SettingsNavigator(navController))

        composable(
            route = NavDestinations.KNOWLEDGE_GRAPH_ROUTE,
            arguments = listOf(navArgument("query") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            KnowledgeGraphScreen(
                initialQuery = backStackEntry.arguments?.getString("query"),
                onNavigateBack = { navController.popBackStack() },
                onOpenSession = { sessionId -> navController.navigate(NavDestinations.chatHero(sessionId)) },
            )
        }

        composable(
            route = NavDestinations.RAG_FOLDER,
            arguments = listOf(
                navArgument("folderId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId") ?: ""
            RagFolderScreen(
                folderId = folderId,
                onNavigateToDocEditor = { rootUuid, docId ->
                    navController.navigate(NavDestinations.docEditor(rootUuid, docId))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            NavDestinations.LINUX_TERMINAL,
            arguments = listOf("session", "dir", "cmd").map { name ->
                navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }
            },
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val command = args?.getString("cmd")
            val request = args?.getString("session")?.let {
                com.promenar.nexara.ui.linux.terminal.LinuxTerminalRequest(com.promenar.nexara.domain.linux.LinuxTerminalLocation.SessionWorkspace(it), command)
            } ?: args?.getString("dir")?.let {
                com.promenar.nexara.ui.linux.terminal.LinuxTerminalRequest(com.promenar.nexara.domain.linux.LinuxTerminalLocation.Directory(it), command)
            }
            com.promenar.nexara.ui.linux.terminal.LinuxTerminalScreen(
                request = request,
                onBack = { navController.popBackStack() },
                onOpenEnvironment = { navController.navigate(NavDestinations.LINUX_ENVIRONMENT) },
            )
        }
        composable(
            NavDestinations.LINUX_FILES,
            arguments = listOf(navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            com.promenar.nexara.ui.linux.files.LinuxFilesScreen(
                onBack = { navController.popBackStack() },
                onOpenTerminal = { directory, command -> navController.navigate(NavDestinations.linuxTerminal(directory = directory, command = command)) },
                onOpenEnvironment = { navController.navigate(NavDestinations.LINUX_ENVIRONMENT) },
                initialPath = backStackEntry.arguments?.getString("path") ?: com.promenar.nexara.ui.linux.files.LINUX_HOME_PATH,
                onOpenFile = { navController.navigate(NavDestinations.linuxFile(it)) },
            )
        }
        composable(
            NavDestinations.LINUX_FILE,
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { backStackEntry ->
            com.promenar.nexara.ui.linux.files.LinuxFileScreen(
                path = backStackEntry.arguments?.getString("path") ?: com.promenar.nexara.ui.linux.files.LINUX_HOME_PATH,
                onBack = { navController.popBackStack() },
                onOpenTerminal = { directory, command -> navController.navigate(NavDestinations.linuxTerminal(directory = directory, command = command)) },
            )
        }
        composable(
            NavDestinations.COMMAND_OUTPUT,
            arguments = listOf("sessionId", "messageId", "toolCallId").map { navArgument(it) { type = NavType.StringType } },
        ) { backStackEntry ->
            val args = requireNotNull(backStackEntry.arguments)
            com.promenar.nexara.ui.linux.output.CommandOutputScreen(
                source = com.promenar.nexara.ui.linux.output.CommandOutputSource(
                    sessionId = requireNotNull(args.getString("sessionId")),
                    messageId = requireNotNull(args.getString("messageId")),
                    toolCallId = requireNotNull(args.getString("toolCallId")),
                ),
                onBack = { navController.popBackStack() },
                onRerunInTerminal = { command, sessionId -> navController.navigate(NavDestinations.linuxTerminal(sessionId = sessionId, command = command)) },
            )
        }

        composable(NavDestinations.SSH_TERMINAL) { entry ->
            val alias = entry.arguments?.getString("alias").orEmpty()
            com.promenar.nexara.ui.ssh.SshTerminalScreen(alias, onBack = { navController.popBackStack() })
        }
    }
}


/** 会话页：整页与首页会话列共用，差别只在返回、打开别的会话与子代理查看页的去向。 */
@Composable
private fun ChatHeroDestination(
    sessionId: String,
    navController: NavHostController,
    chatDestination: ChatDestination,
    onNavigateBack: () -> Unit,
    onNavigateToSession: (String) -> Unit,
    onOpenSubagent: (sessionId: String, stepId: String, index: Int) -> Unit,
) {
    val linuxNavigation = remember(navController, sessionId) {
        com.promenar.nexara.ui.linux.LinuxNavigation(
            openEnvironment = { navController.navigate(NavDestinations.LINUX_ENVIRONMENT) { launchSingleTop = true } },
            openCommandOutput = { messageId, toolCallId ->
                navController.navigate(NavDestinations.commandOutput(sessionId, messageId, toolCallId))
            },
        )
    }
    CompositionLocalProvider(com.promenar.nexara.ui.linux.LocalLinuxNavigation provides linuxNavigation) {
        chatDestination(
            sessionId,
            onNavigateBack,
            { branchedSessionId -> if (branchedSessionId != sessionId) onNavigateToSession(branchedSessionId) },
            {
                navController.navigate(NavDestinations.DEFAULT_MODELS) {
                    launchSingleTop = true
                }
            },
            { query -> navController.navigate(NavDestinations.knowledgeGraph(query)) },
            onOpenSubagent,
            { navController.navigate(NavDestinations.TAILSCALE_CONFIG) { launchSingleTop = true } },
        )
    }
}

private val SubagentViewerArguments = listOf(
    navArgument("sessionId") { type = NavType.StringType },
    navArgument("stepId") { type = NavType.StringType },
    navArgument("index") { type = NavType.IntType },
)

@Composable
private fun SubagentViewerDestination(app: NexaraApplication, backStackEntry: androidx.navigation.NavBackStackEntry, onBack: () -> Unit) {
    val owner = backStackEntry.arguments?.getString("sessionId").orEmpty()
    val stepId = backStackEntry.arguments?.getString("stepId").orEmpty()
    val index = backStackEntry.arguments?.getInt("index") ?: 0
    val initial = remember(owner, stepId) { app.subagentRunStore.snapshot(owner, stepId) }
    // 已结束的记录要从磁盘读取，读完之前不显示“记录不存在”。
    var loaded by remember(owner, stepId) { mutableStateOf(initial != null) }
    val batch by remember(owner, stepId) { app.subagentRunStore.observe(owner, stepId).onEach { loaded = true } }
        .collectAsState(initial = initial)
    val providerModels by com.promenar.nexara.data.manager.ProviderManager.getInstance().providerModels.collectAsState()
    CompositionLocalProvider(com.promenar.nexara.ui.chat.LocalToolLiveOutput provides app.toolLiveOutputStore) {
        com.promenar.nexara.ui.chat.SubagentViewerScreen(
            batch = batch,
            loading = !loaded,
            index = index,
            modelName = { modelId ->
                com.promenar.nexara.ui.chat.resolveModelDisplayNames(modelId, emptyList(), providerModels)[modelId] ?: modelId
            },
            onBack = onBack,
        )
    }
}

/** 首页会话列（内屏三列右列）的页面：会话打开别的会话换掉整列，子代理查看页压在会话之上。 */
private fun NavGraphBuilder.hubPaneDestinations(
    navigator: HubPaneNavigator,
    navController: NavHostController,
    chatDestination: ChatDestination,
    app: NexaraApplication,
) {
    composable(
        route = NavDestinations.CHAT_HERO,
        arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
    ) { backStackEntry ->
        ChatHeroDestination(
            sessionId = backStackEntry.arguments?.getString("sessionId") ?: "",
            navController = navController,
            chatDestination = chatDestination,
            // 会话列的会话没有返回键；删除会话等收尾回到新建入口。
            onNavigateBack = navigator::clear,
            onNavigateToSession = navigator::openSession,
            onOpenSubagent = { owner, stepId, index -> navigator.open(NavDestinations.subagentViewer(owner, stepId, index)) },
        )
    }
    composable(route = NavDestinations.SUBAGENT_VIEWER_ROUTE, arguments = SubagentViewerArguments) { backStackEntry ->
        SubagentViewerDestination(app, backStackEntry, onBack = navigator::back)
    }
}

/**
 * 设置子页的注册：主导航（整页）与宽屏设置右列（[SettingsNavigator] 带 outside）共用。
 * 页面间跳转经 [navigator]，右列中打开终端、文件、知识图谱与会话等整页内容时交给主导航。
 */
internal fun NavGraphBuilder.settingsDestinations(navigator: SettingsNavigator) {
    composable(NavDestinations.RAG_ADVANCED) { entry ->
        navigator.Page(entry) {
            AdvancedRetrievalScreen(
                onNavigateBack = { navigator.back() }
            )
        }
    }

    composable(NavDestinations.RAG_ADVANCED_KG) { entry ->
        navigator.Page(entry) {
            RagAdvancedScreen(
                onNavigateBack = { navigator.back() },
                onNavigateToGraph = { navigator.open(NavDestinations.KNOWLEDGE_GRAPH) }
            )
        }
    }

    composable(NavDestinations.RAG_GLOBAL_CONFIG) { entry ->
        navigator.Page(entry) {
            GlobalRagConfigScreen(
                onNavigateBack = { navigator.back() },
                onNavigateToAdvanced = { navigator.open(NavDestinations.RAG_ADVANCED_KG) },
                onNavigateToDebug = { navigator.open(NavDestinations.RAG_DEBUG) }
            )
        }
    }

    composable(NavDestinations.LONG_TERM_MEMORY) { entry ->
        navigator.Page(entry) {
            com.promenar.nexara.ui.memory.LongTermMemoryScreen(onNavigateBack = { navigator.back() })
        }
    }

    composable(NavDestinations.RAG_DEBUG) { entry ->
        navigator.Page(entry) {
            RagDebugScreen(
                onNavigateBack = { navigator.back() }
            )
        }
    }

    composable(
        route = NavDestinations.PROVIDER_FORM,
        arguments = listOf(
            navArgument("providerId") {
                type = NavType.StringType
                nullable = true
            }
        )
    ) { backStackEntry ->
        navigator.Page(backStackEntry) {
            val app = LocalContext.current.applicationContext as NexaraApplication
            val providerId = backStackEntry.arguments?.getString("providerId")
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
            ProviderFormScreen(
                providerId = providerId,
                onNavigateBack = { navigator.back() },
                onOpenTailscaleSettings = { navigator.open(NavDestinations.TAILSCALE_CONFIG) { launchSingleTop = true } },
                onNavigateToModels = {
                    val pid = providerId ?: ""
                    navigator.open(NavDestinations.providerModels(pid)) {
                        popUpTo(NavDestinations.PROVIDER_FORM) { inclusive = true }
                    }
                },
                onNavigateToLocalModels = {
                    if (BuildConfig.LOCAL_INFERENCE_AVAILABLE) {
                        navigator.open(NavDestinations.LOCAL_MODELS) {
                            popUpTo(NavDestinations.PROVIDER_FORM) { inclusive = true }
                        }
                    }
                },
                onSave = { protocolType, baseUrl, credential, model, name ->
                    saveProviderConfiguration(app, providerId, protocolType, baseUrl, credential, model, name)
                },
            )
        }
    }

    composable(
        route = NavDestinations.PROVIDER_MODELS,
        arguments = listOf(navArgument("providerId") { type = NavType.StringType })
    ) { backStackEntry ->
        navigator.Page(backStackEntry) {
            val providerId = backStackEntry.arguments?.getString("providerId") ?: ""
            val context = LocalContext.current
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))

            ProviderModelsScreen(
                providerId = providerId,
                onNavigateBack = { navigator.back() }
            )
        }
    }

    composable(NavDestinations.TOKEN_USAGE) { entry ->
        navigator.Page(entry) {
            TokenUsageScreen(
                onNavigateBack = { navigator.back() }
            )
        }
    }

    composable(NavDestinations.SKILLS_CONFIG) { entry ->
        navigator.Page(entry) {
            SkillsScreen(
                onNavigateBack = { navigator.back() },
                onOpenSsh = { navigator.open(NavDestinations.SSH_CONFIG) },
                onOpenTermux = { navigator.open(NavDestinations.TERMUX_CONFIG) },
                onOpenGitCredentials = { navigator.open(NavDestinations.GIT_CREDENTIALS) },
                onOpenTailscale = { navigator.open(NavDestinations.TAILSCALE_CONFIG) },
                onOpenBackground = { navigator.open(NavDestinations.BACKGROUND_ACTIVITY) },
                onOpenNetworkAccess = { navigator.open(NavDestinations.NETWORK_ACCESS) },
                onOpenLinux = { navigator.open(NavDestinations.LINUX_ENVIRONMENT) },
            )
        }
    }

    composable(NavDestinations.LINUX_ENVIRONMENT) { entry ->
        navigator.Page(entry) {
            com.promenar.nexara.ui.linux.settings.LinuxEnvironmentScreen(
                onBack = { navigator.back() },
                onOpenTerminal = { navigator.open(NavDestinations.linuxTerminal()) },
                onOpenFiles = { navigator.open(NavDestinations.linuxFiles()) },
                onOpenPackages = { navigator.open(NavDestinations.LINUX_PACKAGES) },
                onOpenMirrors = { navigator.open(NavDestinations.LINUX_MIRRORS) },
                onOpenTermux = { navigator.open(NavDestinations.TERMUX_CONFIG) },
                onOpenSsh = { navigator.open(NavDestinations.SSH_CONFIG) },
            )
        }
    }

    composable(NavDestinations.LINUX_PACKAGES) { entry ->
        navigator.Page(entry) {
            com.promenar.nexara.ui.linux.settings.LinuxPackagesScreen(onBack = { navigator.back() })
        }
    }

    composable(NavDestinations.LINUX_MIRRORS) { entry ->
        navigator.Page(entry) {
            com.promenar.nexara.ui.linux.settings.LinuxMirrorsScreen(onBack = { navigator.back() })
        }
    }

    composable(NavDestinations.NETWORK_ACCESS) { entry ->
        navigator.Page(entry) {
            com.promenar.nexara.ui.settings.NetworkAccessSettingsScreen(
                onBack = { navigator.back() },
                onOpenTailscale = { navigator.open(NavDestinations.TAILSCALE_CONFIG) },
            )
        }
    }

    composable(NavDestinations.BACKGROUND_ACTIVITY) { entry ->
        navigator.Page(entry) {
            com.promenar.nexara.ui.settings.BackgroundActivitySettingsScreen(
                onBack = { navigator.back() },
                onOpenSession = { sessionId -> navigator.open(NavDestinations.chatHero(sessionId)) },
            )
        }
    }

    composable(NavDestinations.TAILSCALE_CONFIG) { entry ->
        navigator.Page(entry) {
            com.promenar.nexara.ui.settings.TailscaleSettingsScreen(onBack = { navigator.back() })
        }
    }

    composable(NavDestinations.TERMUX_CONFIG) { entry ->
        navigator.Page(entry) {
            com.promenar.nexara.ui.settings.TermuxSettingsScreen(
                onBack = { navigator.back() },
                onOpenSsh = { navigator.open(NavDestinations.SSH_CONFIG) },
            )
        }
    }

    composable(NavDestinations.SSH_CONFIG) { entry ->
        navigator.Page(entry) {
            com.promenar.nexara.ui.settings.SshSettingsScreen(
                onBack = { navigator.back() },
                onOpenTerminal = { alias -> navigator.open("ssh_terminal/${android.net.Uri.encode(alias)}") },
            )
        }
    }

    composable(NavDestinations.GIT_CREDENTIALS) { entry ->
        navigator.Page(entry) {
            com.promenar.nexara.ui.settings.GitCredentialSettingsScreen(
                onBack = { navigator.back() },
                onOpenSsh = { navigator.open(NavDestinations.SSH_CONFIG) },
            )
        }
    }

    composable(NavDestinations.THEME_CONFIG) { entry ->
        navigator.Page(entry) {
            ThemeScreen(
                onNavigateBack = { navigator.back() }
            )
        }
    }

    composable(NavDestinations.PROVIDER_LIST) { entry ->
        navigator.Page(entry) {
            ProviderListScreen(
                onNavigateToSecondary = { route -> navigator.open(route) },
                onNavigateBack = { navigator.back() }
            )
        }
    }

    composable(NavDestinations.DEFAULT_MODELS) { entry ->
        navigator.Page(entry) {
            DefaultModelsScreen(
                onNavigateBack = { navigator.back() }
            )
        }
    }

    composable(NavDestinations.BACKUP_SETTINGS) { entry ->
        navigator.Page(entry) {
            BackupSettingsScreen(
                onNavigateBack = { navigator.back() }
            )
        }
    }

    if (BuildConfig.LOCAL_INFERENCE_AVAILABLE) {
        composable(NavDestinations.LOCAL_MODELS) { entry ->
            navigator.Page(entry) {
                LocalModelsScreen(
                    onNavigateBack = { navigator.back() }
                )
            }
        }
    }
}
