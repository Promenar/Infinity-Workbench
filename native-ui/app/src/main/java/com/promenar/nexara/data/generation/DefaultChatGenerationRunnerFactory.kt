package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.ToolExecutionLedger
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
    private val skillCatalog: () -> List<com.promenar.nexara.data.skills.AgentSkillMetadata> = { emptyList() },
    private val toolGrantStore: com.promenar.nexara.data.repository.SessionToolGrantStore? = null,
    private val workspaceRepository: com.promenar.nexara.domain.repository.IWorkspaceRepository? = null,
    private val outputStore: com.promenar.nexara.data.repository.ToolOutputStore? = null,
    private val interactionStore: GenerationInteractionStore? = null,
    private val externalFolders: com.promenar.nexara.domain.storage.ExternalWorkspaceAccess? = null,
    private val sshRuntime: com.promenar.nexara.data.ssh.SshRuntime? = null,
    private val compactionPipeline: ContextCompactionPipeline = ContextCompactionPipeline(),
    private val compactionService: ContextCompactionService? = null,
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
            toolGrantStore = toolGrantStore,
            workspaceRepository = workspaceRepository,
            outputStore = outputStore,
            interactionStore = interactionStore,
            externalFolders = externalFolders,
            sshRuntime = sshRuntime,
            compactionPipeline = compactionPipeline,
            compactionService = compactionService,
        )
    }
}
