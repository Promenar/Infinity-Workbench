package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationRunner
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.domain.usecase.AgentConfigResolver
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.MessageManager
import com.promenar.nexara.ui.chat.manager.PostProcessor
import com.promenar.nexara.ui.chat.manager.SessionManager
import com.promenar.nexara.ui.chat.manager.SummaryManager
import com.promenar.nexara.ui.chat.manager.ToolExecutor
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import kotlinx.coroutines.CoroutineScope

internal class DefaultChatGenerationRunnerFactory(
    private val settings: SharedPreferences,
    private val applicationScope: CoroutineScope,
    private val store: ChatStore,
    private val agentRepository: IAgentRepository,
    private val configResolver: AgentConfigResolver,
    private val routeGate: ChatProviderRouteGate,
    private val contextBuilder: ContextBuilder,
    private val messageManager: MessageManager,
    private val localProviderFactory: (String) -> LlmProvider,
    private val provider: LlmProvider,
    private val toolLedger: ToolExecutionLedger,
    private val toolExecutor: ToolExecutor,
    private val postProcessor: PostProcessor,
    private val memoryManager: MemoryManager?,
    private val summaryManager: SummaryManager,
    private val sessionManager: SessionManager,
    private val skillRegistry: SkillRegistry?,
    private val toolResolver: SessionToolResolver,
    private val presentationStore: GenerationPresentationStore,
    private val skillCatalog: com.promenar.nexara.data.skills.SkillCatalog? = null,
    private val toolAllowlistStore: com.promenar.nexara.data.repository.ToolAllowlistStore? = null,
    private val workspaceRepository: com.promenar.nexara.domain.repository.IWorkspaceRepository? = null,
    private val outputStore: com.promenar.nexara.data.repository.ToolOutputStore? = null,
    private val interactionStore: GenerationInteractionStore? = null,
    private val externalFolders: com.promenar.nexara.domain.storage.ExternalWorkspaceAccess? = null,
    private val sshRuntime: com.promenar.nexara.data.ssh.SshRuntime? = null,
    private val compactionPipeline: ContextCompactionPipeline = ContextCompactionPipeline(),
    private val compactionService: ContextCompactionService? = null,
    /** 会话当前工作区，用于系统提示词的工作区说明；null 时按私有工作区说明。 */
    private val sessionWorkspace: (suspend (String) -> com.promenar.nexara.data.workspace.SessionWorkspace)? = null,
    private val mediaContextLoader: ViewedMediaContextLoader? = null,
    private val sessionGraph: com.promenar.nexara.data.rag.SessionGraphScheduler? = null,
    private val subagentUsage: com.promenar.nexara.data.subagent.SubagentUsageLedger? = null,
    private val toolBuildDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val sessionState: com.promenar.nexara.data.rag.SessionStateScheduler? = null,
    private val tailnetGate: com.promenar.nexara.data.tailnet.TailnetConnectGate? = null,
    private val composerQueueStore: com.promenar.nexara.data.chat.queue.ComposerQueueStore? = null,
    private val stateBoardRepository: com.promenar.nexara.data.repository.StateBoardRepository? = null,
    private val runModeCheck: (suspend (String) -> GenerationFailure?)? = null,
    private val linuxFiles: com.promenar.nexara.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess? = null,
    private val askUserLedger: com.promenar.nexara.data.repository.AskUserLedgerRepository? = null,
) : GenerationRunnerFactory {
    override fun create(request: GenerationRequest, taskId: String): GenerationRunner =
        ChatGenerationRunner(createRuntime(request.sessionId, taskId))

    fun createRuntime(sessionId: String, taskId: String): DefaultChatGenerationRuntime {
        val contentStrategy = DefaultChatGenerationContentStrategy(settings, skillRegistry, toolResolver)
        return DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = applicationScope,
            store = store,
            agentRepository = agentRepository,
            configResolver = configResolver,
            routeGate = routeGate,
            contextBuilder = contextBuilder,
            messageManager = messageManager,
            localProviderFactory = localProviderFactory,
            provider = provider,
            toolLedger = toolLedger,
            toolExecutor = toolExecutor,
            postProcessor = postProcessor,
            memoryManager = memoryManager,
            summaryManager = summaryManager,
            sessionManager = sessionManager,
            contentStrategy = contentStrategy,
            ui = presentationStore.port(sessionId, taskId),
            skillCatalog = skillCatalog,
            toolAllowlistStore = toolAllowlistStore,
            workspaceRepository = workspaceRepository,
            outputStore = outputStore,
            interactionStore = interactionStore,
            externalFolders = externalFolders,
            sshRuntime = sshRuntime,
            compactionPipeline = compactionPipeline,
            compactionService = compactionService,
            sessionWorkspace = sessionWorkspace,
            toolBuildDispatcher = toolBuildDispatcher,
            mediaContextLoader = mediaContextLoader,
            sessionGraph = sessionGraph,
            sessionState = sessionState,
            subagentUsage = subagentUsage,
            tailnetGate = tailnetGate,
            composerQueueStore = composerQueueStore,
            stateBoardRepository = stateBoardRepository,
            runModeCheck = runModeCheck,
            linuxFiles = linuxFiles,
            askUserLedger = askUserLedger,
        )
    }
}
