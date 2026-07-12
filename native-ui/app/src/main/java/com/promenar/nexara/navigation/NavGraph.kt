package com.promenar.nexara.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.domain.usecase.IdGenerator
import com.promenar.nexara.onboarding.OnboardingStateStore
import com.promenar.nexara.onboarding.OnboardingStep
import com.promenar.nexara.onboarding.OnboardingLanguageSelectionCoordinator
import com.promenar.nexara.onboarding.createOnboardingAgentSession
import com.promenar.nexara.onboarding.isSuccessfulOnboardingAssistant
import com.promenar.nexara.ui.chat.ChatScreen
import com.promenar.nexara.ui.chat.SessionSettingsScreen
import com.promenar.nexara.ui.hub.AgentAdvancedRetrievalScreen
import com.promenar.nexara.ui.hub.AgentEditScreen
import com.promenar.nexara.ui.hub.AgentRagConfigScreen
import com.promenar.nexara.ui.hub.AgentSessionsScreen
import com.promenar.nexara.ui.rag.DocEditorScreen
import com.promenar.nexara.ui.rag.GlobalRagConfigScreen
import com.promenar.nexara.ui.rag.KnowledgeGraphScreen
import com.promenar.nexara.ui.rag.RagAdvancedScreen
import com.promenar.nexara.ui.rag.RagDebugScreen
import com.promenar.nexara.ui.rag.RagFolderScreen
import com.promenar.nexara.ui.rag.AdvancedRetrievalScreen
import com.promenar.nexara.ui.settings.BackupSettingsScreen
import com.promenar.nexara.ui.settings.DeveloperScreen
import com.promenar.nexara.ui.settings.LocalModelsScreen
import com.promenar.nexara.ui.settings.ModelInfo
import com.promenar.nexara.ui.settings.ProviderFormScreen
import com.promenar.nexara.ui.settings.ProviderModelsScreen
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.settings.SearchConfigScreen
import com.promenar.nexara.ui.settings.SkillsScreen
import com.promenar.nexara.ui.settings.ThemeScreen
import com.promenar.nexara.ui.settings.TokenUsageScreen
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.welcome.WelcomeScreen
import com.promenar.nexara.ui.welcome.eligibleOnboardingModels
import com.promenar.nexara.util.LocaleController
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

object NavDestinations {
    const val WELCOME = "welcome"
    const val MAIN_TAB_SCAFFOLD = "main_tab_scaffold"
    const val SESSION_LIST = "session_list/{agentId}"
    const val CHAT_HERO = "chat_hero/{sessionId}"
    const val SESSION_SETTINGS = "session_settings/{sessionId}"
    const val PROVIDER_FORM = "provider_form?providerId={providerId}"
    const val PROVIDER_MODELS = "provider_models/{providerId}"
    const val AGENT_EDIT = "agent_edit/{agentId}"
    const val AGENT_RAG_CONFIG = "agent_rag_config/{agentId}"
    const val AGENT_ADVANCED_RETRIEVAL = "agent_advanced_retrieval/{agentId}"
    const val SESSION_SETTINGS_SHEET = "session_settings_sheet/{sessionId}"
    const val WORKSPACE_SHEET = "workspace_sheet/{sessionId}"
    const val DOC_EDITOR = "doc_editor/{workspaceRootUuid}/{docId}"
    const val KNOWLEDGE_GRAPH = "knowledge_graph"
    const val RAG_ADVANCED = "rag_advanced"
    const val RAG_ADVANCED_KG = "rag_advanced_kg"
    const val RAG_GLOBAL_CONFIG = "rag_global_config"
    const val RAG_FOLDER = "rag_folder/{folderId}/{folderName}"
    const val RAG_DEBUG = "rag_debug"
    const val TOKEN_USAGE = "token_usage"
    const val SEARCH_CONFIG = "search_config"
    const val SKILLS_CONFIG = "skills_config"
    const val THEME_CONFIG = "theme_config"
    const val BACKUP_SETTINGS = "backup_settings"
    const val LOCAL_MODELS = "local_models"
    const val DEVELOPER_PANEL = "developer_panel"

    fun sessionList(agentId: String) = "session_list/$agentId"
    fun chatHero(sessionId: String) = "chat_hero/$sessionId"
    fun sessionSettings(sessionId: String) = "session_settings/$sessionId"
    fun agentEdit(agentId: String) = "agent_edit/$agentId"
    fun agentRagConfig(agentId: String) = "agent_rag_config/$agentId"
    fun agentAdvancedRetrieval(agentId: String) = "agent_advanced_retrieval/$agentId"
    fun providerForm(providerId: String? = null) =
        if (providerId != null) "provider_form?providerId=$providerId" else "provider_form"
    fun providerModels(providerId: String) = "provider_models/$providerId"
    fun docEditor(workspaceRootUuid: String, docId: String) = "doc_editor/$workspaceRootUuid/$docId"
    fun ragFolder(folderId: String, folderName: String) =
        "rag_folder/$folderId/$folderName"
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(title, style = NexaraTypography.headlineMedium, color = NexaraColors.OnBackground)
    }
}

@Composable
fun NexaraNavGraph(
    navController: NavHostController,
    startDestination: String = NavDestinations.WELCOME,
    onboardingStateStore: OnboardingStateStore? = null,
    onboardingModelsOverride: List<ModelInfo>? = null,
    forceLocalProbeFailureForTesting: Boolean = false,
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

    LaunchedEffect(onboardingState.step) {
        if (onboardingState.step == OnboardingStep.COMPLETED) {
            navController.navigate(NavDestinations.MAIN_TAB_SCAFFOLD) {
                popUpTo(NavDestinations.WELCOME) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300),
                    initialOffset = { it / 10 }
                )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(300)) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300),
                    targetOffset = { -it / 10 }
                )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300),
                    initialOffset = { -it / 10 }
                )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300),
                    targetOffset = { it / 10 }
                )
        }
    ) {
        composable(NavDestinations.WELCOME) {
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
            val providerModels by viewModel.providerModels.collectAsState()
            val isFetchingModels by viewModel.isFetchingModels.collectAsState()
            val models = eligibleOnboardingModels(
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
                        navController.navigate(NavDestinations.chatHero(sessionId))
                    }
                },
            )
        }

        composable(NavDestinations.MAIN_TAB_SCAFFOLD) {
            com.promenar.nexara.ui.MainTabScaffold(
                onNavigateToSecondary = { route ->
                    navController.navigate(route)
                },
                onNavigateToSessionList = { agentId ->
                    navController.navigate(NavDestinations.sessionList(agentId))
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
            route = NavDestinations.SESSION_LIST,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
            AgentSessionsScreen(
                agentId = agentId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToChat = { sessionId ->
                    navController.navigate(NavDestinations.chatHero(sessionId))
                },
                onNavigateToAgentEdit = {
                    navController.navigate(NavDestinations.agentEdit(agentId))
                }
            )
        }

        composable(
            route = NavDestinations.CHAT_HERO,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            ChatScreen(
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavDestinations.SESSION_SETTINGS,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            SessionSettingsScreen(
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() }
            )
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
                scopeLabel = "Agent",
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
                scopeLabel = "Agent",
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavDestinations.SESSION_SETTINGS_SHEET,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { _ ->
            PlaceholderScreen("Session Settings Sheet")
        }

        composable(
            route = NavDestinations.WORKSPACE_SHEET,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { _ ->
            PlaceholderScreen("Workspace Sheet")
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

        composable(NavDestinations.KNOWLEDGE_GRAPH) {
            KnowledgeGraphScreen(
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
                navArgument("folderId") { type = NavType.StringType },
                navArgument("folderName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId") ?: ""
            val folderName = backStackEntry.arguments?.getString("folderName") ?: ""
            RagFolderScreen(
                folderId = folderId,
                folderName = folderName,
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
                onNavigateToModels = {
                    val pid = providerId ?: ""
                    navController.navigate(NavDestinations.providerModels(pid)) {
                        popUpTo(NavDestinations.PROVIDER_FORM) { inclusive = true }
                    }
                },
                onNavigateToLocalModels = {
                    navController.navigate(NavDestinations.LOCAL_MODELS) {
                        popUpTo(NavDestinations.PROVIDER_FORM) { inclusive = true }
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

        composable(NavDestinations.SEARCH_CONFIG) {
            SearchConfigScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.SKILLS_CONFIG) {
            SkillsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.THEME_CONFIG) {
            ThemeScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.BACKUP_SETTINGS) {
            BackupSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.LOCAL_MODELS) {
            LocalModelsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavDestinations.DEVELOPER_PANEL) {
            DeveloperScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
