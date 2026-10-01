package com.promenar.nexara.data.remote.integration

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.stableModelId
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.ui.chat.ChatViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * 真实网关 Agent 端到端夹具：经 ChatViewModel → 生成协调器 → 工具执行 → 工作区的完整应用链路。
 *
 * 仅在 instrumentation 参数 `nexaraLocalGateway=true` 且专用模拟器已 `adb reverse tcp:1337 tcp:1337` 时运行。
 * 只发送合成测试内容；每个场景单独新建会话，结束后删除测试 Provider 与模型。
 */
class AgentGatewayHarness(private val remoteModel: String) {
    val app: NexaraApplication = ApplicationProvider.getApplicationContext()
    val modelId: String = stableModelId(PROVIDER_ID, remoteModel)
    lateinit var viewModel: ChatViewModel
    lateinit var sessionId: String

    fun setUp(executionMode: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nexaraLocalGateway") == "true")
        assertWithMessage("应用启动恢复未就绪").that(waitUntil(30_000) {
            app.startupState.value == BackupStartupState.Ready
        }).isTrue()
        val providers = ProviderManager.getInstance()
        // 每次重建测试 Provider，避免沿用上一次运行残留的配置或凭据状态。
        providers.deleteProvider(PROVIDER_ID)
        run {
            providers.addProvider(
                ProviderListItem(
                    id = PROVIDER_ID,
                    name = "E2E Gateway",
                    baseUrl = "http://127.0.0.1:1337/v1",
                    model = remoteModel,
                    protocolType = ProtocolType.Generic_OpenAI_Compat,
                ),
                // 本机网关免密钥；应用要求云端 Provider 存在凭据，这里写入不对应任何服务的合成占位值。
                credentialUpdate = CredentialUpdate.Replace(PLACEHOLDER_CREDENTIAL),
            )
        }
        providers.deleteModel(modelId)
        providers.addModel(
            ModelInfo(
                name = remoteModel,
                id = modelId,
                description = "E2E gateway model",
                enabled = true,
                type = "chat",
                contextLength = 128_000,
                providerName = "E2E Gateway",
                providerId = PROVIDER_ID,
                remoteModelId = remoteModel,
            ),
        )

        val resolution = app.providerRequestRouter.resolve(modelId)
        assertWithMessage("网关模型路由失败：$resolution")
            .that(resolution).isInstanceOf(com.promenar.nexara.data.remote.ProviderResolution.Success::class.java)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            viewModel = ChatViewModel.factory(app).create(ChatViewModel::class.java)
        }
        val before = app.chatStore.state.value.sessions.map { it.id }.toSet()
        instrumentation.runOnMainSync { viewModel.createNewSession("default") }
        assertWithMessage("新会话未创建").that(waitUntil(10_000) {
            app.chatStore.state.value.sessions.any { it.id !in before && !it.workspaceRootUuid.isNullOrBlank() }
        }).isTrue()
        sessionId = app.chatStore.state.value.sessions.first { it.id !in before }.id
        instrumentation.runOnMainSync {
            viewModel.updateModelId(modelId)
            viewModel.updateExecutionMode(executionMode)
        }
        assertWithMessage("会话模型或执行模式未生效").that(waitUntil(10_000) {
            session().modelId == modelId && session().executionMode == executionMode
        }).isTrue()
    }

    fun tearDown() {
        if (!::sessionId.isInitialized) return
        runCatching { app.generationCoordinator.release(sessionId, discardTerminal = true) }
        ProviderManager.getInstance().deleteModel(modelId)
    }

    fun session(): Session = requireNotNull(app.chatStore.getSession(sessionId)) { "会话已不存在" }

    /** 发送一轮用户消息并等待生成进入终态或等待审批。 */
    fun sendAndAwait(text: String, timeoutMillis: Long = 240_000): AgentTurnResult {
        val startedAt = SystemClock.elapsedRealtime()
        val before = session().messages.map { it.id }.toSet()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.sendMessage(text) }
        assertWithMessage("用户消息未进入会话").that(waitUntil(15_000) {
            session().messages.any { it.id !in before && it.role == MessageRole.USER }
        }).isTrue()
        return awaitOutcome(startedAt, timeoutMillis)
    }

    fun approvePending(timeoutMillis: Long = 240_000): AgentTurnResult {
        val request = requireNotNull(session().approvalRequest) { "没有待审批请求" }
        val startedAt = SystemClock.elapsedRealtime()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.approveRequest(request) }
        assertWithMessage("审批未被受理").that(waitUntil(15_000) {
            session().approvalRequest == null
        }).isTrue()
        return awaitOutcome(startedAt, timeoutMillis)
    }

    /** 协调器连续 [QUIET_MILLIS] 无活动任务（或处于终态/等待审批）即视为本轮结束。 */
    private fun awaitOutcome(startedAt: Long, timeoutMillis: Long): AgentTurnResult {
        var lastPhase: GenerationPhase? = null
        var quietSince = -1L
        val finished = waitUntil(timeoutMillis) {
            val snapshot = app.generationCoordinator.observe(sessionId).value
            snapshot?.phase?.let { lastPhase = it }
            val idle = snapshot == null || snapshot.phase in TERMINAL_PHASES ||
                snapshot.phase == GenerationPhase.WAITING_APPROVAL && session().approvalRequest != null
            val now = SystemClock.elapsedRealtime()
            if (!idle) {
                quietSince = -1L
                false
            } else {
                if (quietSince < 0) quietSince = now
                now - quietSince >= QUIET_MILLIS
            }
        }
        val messages = session().messages
        val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        val pending = session().approvalRequest != null
        val terminal = when {
            lastPhase in TERMINAL_PHASES -> lastPhase
            pending -> GenerationPhase.WAITING_APPROVAL
            lastAssistant?.isError == true -> GenerationPhase.FAILED
            lastAssistant != null -> GenerationPhase.COMPLETED
            else -> null
        }
        val result = AgentTurnResult(
            terminalPhase = terminal,
            pendingApproval = pending,
            messages = messages,
            elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
        )
        assertWithMessage("生成在 ${timeoutMillis}ms 内未结束（phase=$lastPhase）\n${result.describe()}")
            .that(finished).isTrue()
        return result
    }

    fun readWorkspaceFile(path: String): String? = runBlocking {
        val root = requireNotNull(session().workspaceRootUuid)
        val entry = app.workspaceRepository.getByMaterializedPath(root, path) ?: return@runBlocking null
        app.fileOperationRepository.readFileRange(root, entry.uuid).content
    }

    companion object {
        const val PROVIDER_ID = "e2e-gateway"
        const val PLACEHOLDER_CREDENTIAL = "local-gateway-placeholder"
        const val QUIET_MILLIS = 2_500L
        val TERMINAL_PHASES = setOf(
            GenerationPhase.COMPLETED,
            GenerationPhase.FAILED,
            GenerationPhase.CANCELLED,
            GenerationPhase.PERSISTENCE_FAILED,
        )
        val ACTIVE_PHASES = setOf(
            GenerationPhase.PREPARING,
            GenerationPhase.BUILDING_CONTEXT,
            GenerationPhase.CONNECTING,
            GenerationPhase.THINKING,
            GenerationPhase.STREAMING,
            GenerationPhase.POST_PROCESSING,
        )

        fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            while (SystemClock.elapsedRealtime() < deadline) {
                if (runCatching(condition).getOrDefault(false)) return true
                SystemClock.sleep(250)
            }
            return runCatching(condition).getOrDefault(false)
        }
    }
}

data class AgentTurnResult(
    val terminalPhase: GenerationPhase?,
    val pendingApproval: Boolean,
    val messages: List<Message>,
    val elapsedMillis: Long,
) {
    val toolMessages: List<Message> get() = messages.filter { it.role == MessageRole.TOOL }
    val toolNames: List<String> get() = messages.flatMap { it.toolCalls.orEmpty() }.map { it.name }
    val finalAnswer: String get() = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content.orEmpty()

    /** 失败时输出的有界诊断：只含合成会话的角色、工具名与截断内容。 */
    fun describe(): String = buildString {
        append("phase=").append(terminalPhase).append(" approval=").append(pendingApproval)
        append(" elapsed=").append(elapsedMillis).append("ms\n")
        messages.takeLast(30).forEach { message ->
            append(message.role.name).append(": ")
            message.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                append("[calls ").append(calls.joinToString { "${it.name}(${it.arguments.take(120)})" }).append("] ")
            }
            append(message.content.replace('\n', ' ').take(200))
            if (message.isError == true) append(" [error ").append(message.errorMessage?.take(160)).append(']')
            append('\n')
        }
    }
}
