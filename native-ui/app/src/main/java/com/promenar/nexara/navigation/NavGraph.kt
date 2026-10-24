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
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.BuildConfig
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.domain.usecase.IdGenerator
import com.promenar.nexara.onboarding.OnboardingStateStore
import com.promenar.nexara.onboarding.OnboardingStep
import com.promenar.nexara.onboarding.OnboardingLanguageSelectionCoordinator
import com.promenar.nexara.onboarding.createOnboardingAgentSession
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
import com.promenar.nexara.ui.welcome.WelcomeScreen
import com.promenar.nexara.ui.welcome.onboardingModelCandidates
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
    const val RAG_FOLDER = "rag_folder/{folderId}"
    const val RAG_DEBUG = "rag_debug"
    const val TOKEN_USAGE = "token_usage"
    const val SKILLS_CONFIG = "skills_config"
    const val SSH_CONFIG = "ssh_config"
    const val TERMUX_CONFIG = "termux_config"
    const val LINUX_ENVIRONMENT = "linux_environment"
    const val LINUX_PACKAGES = "linux_packages"
    const val LINUX_MIRRORS = "linux_mirrors"
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
        onboardingStateStore ?: OnboardingStateStore(app)
    }
    val onboardingState by onboardingStore.state.collectAsState()
    val onboardingScope = rememberCoroutineScope()
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
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
            val providerModels by viewModel.providerModels.collectAsState()
            val isFetchingModels by viewModel.isFetchingModels.collectAsState()
            val models = onboardingModelCandidates(
                onboardingModelsOverride ?: providerModels,
                onboardingState.providerId,
            )
            LaunchedEffect(onboardingState.step, onboardingState.providerId) {
                if (onboardingState.step == OnboardingStep.MODEL && onboardingModelsOverride == null) {
                    onboardingState.providerId?.let(viewModel::refreshProviderModels)
                }
            }
            var isWorking by remember { mutableStateOf(false) }
            var onboardingError by remember { mutableStateOf<String?>(null) }
            val actionFailed = androidx.compose.ui.res.stringResource(com.promenar.nexara.R.string.onboarding_action_failed)
            WelcomeScreen(
                onLanguageSelected = { languageCode ->
                    if (!isWorking) {
                        isWorking = true
                        languageSelection.select(languageCode)
                        isWorking = false
                    }
                },
                state = onboardingState,
                models = models,
                isWorking = isWorking || isFetchingModels,
                errorMessage = onboardingError,
                onOpenProvider = { navController.navigate(NavDestinations.providerForm()) },
                onOpenConnection = {
                    navController.navigate(NavDestinations.providerForm(onboardingState.providerId))
                },
                onModelSelected = { modelId: String ->
                    models.firstOrNull { it.id == modelId }?.let { model ->
                        if (!isWorking) {
                            isWorking = true
                            onboardingError = null
                            onboardingScope.launch {
                                val verified = viewModel.verifyOnboardingModel(
                                    providerId = onboardingState.providerId.orEmpty(),
                                    model = model,
                                )
                                if (verified == null) {
                                    onboardingError = app.getString(
                                        com.promenar.nexara.R.string.onboarding_model_probe_failed
                                    )
                                } else {
                                    if (!verified.enabled) viewModel.updateModel(verified.copy(enabled = true))
                                    onboardingStore.selectModel(verified.id)
                                }
                                isWorking = false
                            }
                        }
                    }
                },
                onRefreshModels = {
                    onboardingState.providerId?.let(viewModel::refreshProviderModels)
                },
                onBackToConnection = { onboardingStore.returnToConnection() },
                onCreateAgent = { name ->
                    if (!isWorking) {
                        isWorking = true
                        onboardingError = null
                        onboardingScope.launch {
                            runCatching {
                                val modelId = requireNotNull(
                                    onboardingState.modelId?.takeIf { it.isNotBlank() }
                                ) { "首次引导尚未选择模型" }
                                createOnboardingAgentSession(
                                    modelId = modelId,
                                    createAgent = {
                                        app.createAgentUseCase(
                                            name = name,
                                            description = "",
                                            modelId = modelId,
                                            systemPrompt = "",
                                        ).id
                                    },
                                    newSessionId = IdGenerator::session,
                                    createSession = app.sessionRepository::create,
                                    deleteSession = app.sessionRepository::delete,
                                    deleteAgent = app.agentRepository::delete,
                                    chatStore = app.chatStore,
                                    recordCheckpoint = onboardingStore::recordAgentCreated,
                                )
                            }.onFailure {
                                onboardingError = actionFailed
                            }
                            isWorking = false
                        }
                    }
                },
                onOpenFirstChat = {
                    onboardingState.sessionId?.let { sessionId ->
                        openOnboardingFirstChat(navController, sessionId)
                    }
                },
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
                }
            )
        }

        composable(
            route = NavDestinations.CHAT_HERO,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            chatDestination(
                sessionId,
                { navController.popBackStack() },
                { branchedSessionId ->
                    if (branchedSessionId != sessionId) {
                        navController.navigate(NavDestinations.chatHero(branchedSessionId)) {
                            launchSingleTop = true
                        }
                    }
                },
                {
                    navController.navigate(NavDestinations.DEFAULT_MODELS) {
                        launchSingleTop = true
                    }
                },
                { query -> navController.navigate(NavDestinations.knowledgeGraph(query)) },
                { owner, stepId, index -> navController.navigate(NavDestinations.subagentViewer(owner, stepId, index)) },
                { navController.navigate(NavDestinations.TAILSCALE_CONFIG) { launchSingleTop = true } },
            )
        }

        composable(
            route = NavDestinations.SUBAGENT_VIEWER_ROUTE,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("stepId") { type = NavType.StringType },
                navArgument("index") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
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
                    onBack = { navController.popBackStack() },
                )
            }
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

        composable(
            route = NavDestinations.KNOWLEDGE_GRAPH_ROUTE,
            arguments = listOf(navArgument("query") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            KnowledgeGraphScreen(
                initialQuery = backStackEntry.arguments?.getString("query"),
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.RAG_ADVANCED) {
            AdvancedRetrievalScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.RAG_ADVANCED_KG) {
            RagAdvancedScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToGraph = { navController.navigate(NavDestinations.KNOWLEDGE_GRAPH) }
            )
        }

        composable(NavDestinations.RAG_GLOBAL_CONFIG) {
            GlobalRagConfigScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAdvanced = { navController.navigate(NavDestinations.RAG_ADVANCED_KG) },
                onNavigateToDebug = { navController.navigate(NavDestinations.RAG_DEBUG) }
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

        composable(NavDestinations.RAG_DEBUG) {
            RagDebugScreen(
                onNavigateBack = { navController.popBackStack() }
            )
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
            val providerId = backStackEntry.arguments?.getString("providerId")
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
            val onboardingMode = onboardingState.step == OnboardingStep.PROVIDER ||
                onboardingState.step == OnboardingStep.CONNECTION
            ProviderFormScreen(
                providerId = providerId,
                forceLocalProbeFailureForTesting = forceLocalProbeFailureForTesting,
                onNavigateBack = { navController.popBackStack() },
                onOpenTailscaleSettings = { navController.navigate(NavDestinations.TAILSCALE_CONFIG) { launchSingleTop = true } },
                onNavigateToModels = {
                    val pid = providerId ?: ""
                    navController.navigate(NavDestinations.providerModels(pid)) {
                        popUpTo(NavDestinations.PROVIDER_FORM) { inclusive = true }
                    }
                },
                onNavigateToLocalModels = {
                    if (BuildConfig.LOCAL_INFERENCE_AVAILABLE) {
                        navController.navigate(NavDestinations.LOCAL_MODELS) {
                            popUpTo(NavDestinations.PROVIDER_FORM) { inclusive = true }
                        }
                    }
                },
                onboardingMode = onboardingMode,
                onSaved = { savedProviderId ->
                    if (onboardingState.step == OnboardingStep.PROVIDER) {
                        onboardingStore.recordProviderSaved(savedProviderId)
                    }
                },
                onConnectionVerified = { verifiedProviderId ->
                    if (onboardingState.step == OnboardingStep.CONNECTION) {
                        onboardingStore.recordConnectionResult(verifiedProviderId, succeeded = true)
                    }
                },
                onSave = { protocolType, baseUrl, credential, model, name ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                      val providerManager = com.promenar.nexara.data.manager.ProviderManager.getInstance()
                      val existingSummary = providerId?.let(providerManager::getProviderSummary)
                      val credentialPresent = when (credential) {
                          is CredentialUpdate.Replace -> true
                          CredentialUpdate.Clear -> false
                          CredentialUpdate.Preserve -> existingSummary?.let {
                              it.hasApiKey || it.hasVertexCredentials
                          } ?: false
                      }
                      if (providerId == null) {
                        // 判断是首次配置还是新增额外提供商
                        val mainConfig = providerManager.getProviderSummary("default")
                        if (mainConfig == null || (!mainConfig.hasApiKey && !mainConfig.hasVertexCredentials)) {
                            // 全新安装 / 主提供商未配置：创建主提供商，使 Tier 3 兜底生效
                            app.updateProvider(protocolType, baseUrl, credential, model, name)
                            "default"
                        } else {
                            // 已有主提供商：新增额外提供商
                            val id = "extra_${java.util.UUID.randomUUID()}"
                            val item = com.promenar.nexara.data.model.ProviderListItem(
                                id = id,
                                name = name ?: protocolType.displayName,
                                typeName = protocolType.displayName,
                                baseUrl = baseUrl,
                                model = model,
                                protocolType = protocolType,
                                hasApiKey = credentialPresent && protocolType !is ProtocolType.Google_VertexAI,
                                hasVertexCredentials = credentialPresent && protocolType is ProtocolType.Google_VertexAI,
                            )
                            com.promenar.nexara.data.manager.ProviderManager.getInstance()
                                .addProvider(
                                    item,
                                    credential,
                                )
                            id
                        }
                      } else if (providerId == "default") {
                        // 编辑主提供商
                        app.updateProvider(protocolType, baseUrl, credential, model, name)
                        providerId
                      } else {
                        // 编辑额外提供商
                        val item = com.promenar.nexara.data.model.ProviderListItem(
                            id = providerId,
                            name = name ?: protocolType.displayName,
                            typeName = protocolType.displayName,
                            baseUrl = baseUrl,
                            model = model,
                            protocolType = protocolType,
                            hasApiKey = credentialPresent && protocolType !is ProtocolType.Google_VertexAI,
                            hasVertexCredentials = credentialPresent && protocolType is ProtocolType.Google_VertexAI,
                        )
                        com.promenar.nexara.data.manager.ProviderManager.getInstance()
                            .updateExtraProvider(
                                providerId,
                                item,
                                credential,
                            )
                        providerId
                      }
                    }
                }
            )
        }

        composable(
            route = NavDestinations.PROVIDER_MODELS,
            arguments = listOf(navArgument("providerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.getString("providerId") ?: ""
            val context = LocalContext.current
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))

            ProviderModelsScreen(
                providerId = providerId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.TOKEN_USAGE) {
            TokenUsageScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.SKILLS_CONFIG) {
            SkillsScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSsh = { navController.navigate(NavDestinations.SSH_CONFIG) },
                onOpenTermux = { navController.navigate(NavDestinations.TERMUX_CONFIG) },
                onOpenGitCredentials = { navController.navigate(NavDestinations.GIT_CREDENTIALS) },
                onOpenTailscale = { navController.navigate(NavDestinations.TAILSCALE_CONFIG) },
                onOpenBackground = { navController.navigate(NavDestinations.BACKGROUND_ACTIVITY) },
                onOpenNetworkAccess = { navController.navigate(NavDestinations.NETWORK_ACCESS) },
                onOpenLinux = { navController.navigate(NavDestinations.LINUX_ENVIRONMENT) },
            )
        }

        composable(NavDestinations.LINUX_ENVIRONMENT) {
            com.promenar.nexara.ui.linux.settings.LinuxEnvironmentScreen(
                onBack = { navController.popBackStack() },
                onOpenTerminal = {},
                onOpenFiles = {},
                onOpenPackages = { navController.navigate(NavDestinations.LINUX_PACKAGES) },
                onOpenMirrors = { navController.navigate(NavDestinations.LINUX_MIRRORS) },
                onOpenTermux = { navController.navigate(NavDestinations.TERMUX_CONFIG) },
                onOpenSsh = { navController.navigate(NavDestinations.SSH_CONFIG) },
            )
        }
        composable(NavDestinations.LINUX_PACKAGES) {
            com.promenar.nexara.ui.linux.settings.LinuxPackagesScreen(onBack = { navController.popBackStack() })
        }
        composable(NavDestinations.LINUX_MIRRORS) {
            com.promenar.nexara.ui.linux.settings.LinuxMirrorsScreen(onBack = { navController.popBackStack() })
        }

        composable(NavDestinations.NETWORK_ACCESS) {
            com.promenar.nexara.ui.settings.NetworkAccessSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTailscale = { navController.navigate(NavDestinations.TAILSCALE_CONFIG) },
            )
        }

        composable(NavDestinations.BACKGROUND_ACTIVITY) {
            com.promenar.nexara.ui.settings.BackgroundActivitySettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenSession = { sessionId -> navController.navigate(NavDestinations.chatHero(sessionId)) },
            )
        }

        composable(NavDestinations.TAILSCALE_CONFIG) {
            com.promenar.nexara.ui.settings.TailscaleSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(NavDestinations.TERMUX_CONFIG) {
            com.promenar.nexara.ui.settings.TermuxSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenSsh = { navController.navigate(NavDestinations.SSH_CONFIG) },
            )
        }

        composable(NavDestinations.SSH_CONFIG) {
            com.promenar.nexara.ui.settings.SshSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTerminal = { alias -> navController.navigate("ssh_terminal/${android.net.Uri.encode(alias)}") },
            )
        }
        composable(NavDestinations.SSH_TERMINAL) { entry ->
            val alias = entry.arguments?.getString("alias").orEmpty()
            com.promenar.nexara.ui.ssh.SshTerminalScreen(alias, onBack = { navController.popBackStack() })
        }
        composable(NavDestinations.GIT_CREDENTIALS) {
            com.promenar.nexara.ui.settings.GitCredentialSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenSsh = { navController.navigate(NavDestinations.SSH_CONFIG) },
            )
        }

        composable(NavDestinations.THEME_CONFIG) {
            ThemeScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.PROVIDER_LIST) {
            ProviderListScreen(
                onNavigateToSecondary = { route -> navController.navigate(route) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.DEFAULT_MODELS) {
            DefaultModelsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.BACKUP_SETTINGS) {
            BackupSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        if (BuildConfig.LOCAL_INFERENCE_AVAILABLE) {
            composable(NavDestinations.LOCAL_MODELS) {
                LocalModelsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

    }
}
