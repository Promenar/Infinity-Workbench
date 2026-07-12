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
    private val presentationStore: GenerationPresentationStore,
) : GenerationRunnerFactory {
    override fun create(request: GenerationRequest, taskId: String): GenerationRunner {
        val contentStrategy = DefaultChatGenerationContentStrategy(settings, skillRegistry)
        val runtime = DefaultChatGenerationRuntime(
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
            ui = presentationStore.port(request.sessionId, taskId),
        )
        return ChatGenerationRunner(runtime)
    }
}
