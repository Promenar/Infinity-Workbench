package com.promenar.infinity.data.secret

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.ToolExecutionLedgerEntity
import com.promenar.infinity.data.local.db.entity.ToolLedgerStatus
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.data.repository.ToolInvocationIdentityResolution
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.secret.*
import com.promenar.infinity.startup.StartupSubsystem
import com.promenar.infinity.startup.StartupWriteBarrier
import com.promenar.infinity.ui.chat.manager.skills.SecretRequestProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.CharBuffer
import java.security.MessageDigest
import java.util.UUID

data class SecretInputBatchResult(val pending: PendingSecretInput?, val messages: List<Message>)

/** 账本只保存请求与状态；续跑锚点复用现有提问恢复观察器。 */
class SecretInputLedgerRepository(
    private val database: InfinityDatabase,
    private val destinations: SecretDestinationWriter,
    private val executionGate: SessionExecutionGate? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : SecretInputCoordinator {
    private val ledger = database.toolExecutionLedgerDao()
    private val messages = database.messageDao()
    private val sessions = database.sessionDao()
    private val localGate = Mutex()

    private suspend fun <T> writable(sessionId: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        StartupWriteBarrier.requireWritable(sessionId = sessionId, subsystem = StartupSubsystem.TOOL_LEDGER)
        if (executionGate != null) executionGate.withSessionMutation(sessionId, block) else localGate.withLock { block() }
    }

    override fun observePending(sessionId: String): Flow<PendingSecretInput?> =
        ledger.observeInputForSession(sessionId).map { pending(sessionId) }

    suspend fun pending(sessionId: String): PendingSecretInput? = withContext(Dispatchers.IO) {
        database.withTransaction { pendingInTransaction(sessionId) }
    }

    private suspend fun pendingInTransaction(sessionId: String): PendingSecretInput? {
        val session = sessions.getById(sessionId) ?: return null
        if (session.loopStatus != LoopStatus.WAITING_FOR_INPUT.toSerializedName() || session.approvalRequest != null) return null
        val entry = ledger.getInputForSession(sessionId).singleOrNull { it.status == ToolLedgerStatus.WAITING_INPUT } ?: return null
        if (entry.toolName != SecretIoLimits.REQUEST_TOOL_NAME || entry.runtimeToolId != SecretIoLimits.REQUEST_TOOL_NAME || entry.requiresApproval) return null
        val assistant = messages.getById(entry.assistantMessageId)?.takeIf { it.sessionId == sessionId && it.role == "assistant" } ?: return null
        val calls = assistant.toolCalls?.let { runCatching { Json.decodeFromString<List<ToolCall>>(it) }.getOrNull() } ?: return null
        val call = calls.singleOrNull { it.id == entry.toolCallId && it.name == SecretIoLimits.REQUEST_TOOL_NAME } ?: return null
        val arguments = com.promenar.infinity.domain.tool.ToolArgumentsValidator().validate(call.arguments)
            as? com.promenar.infinity.domain.tool.ToolArgumentsValidation.Valid ?: return null
        if (arguments.sha256 != entry.argumentsDigest) return null
        val request = runCatching { SecretRequestProtocol.decode(call.arguments) }.getOrNull() ?: return null
        // 已删除去向仍保留卡片以便取消；提交时重新严格校验。
        val info = try { destinations.describe(sessionId, request.destination) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SecretDestinationInfo("去向已不可用") }
        return PendingSecretInput(sessionId, assistant.id, call.id, request, info.label,
            if (request.destination is SecretDestination.SessionEnv) SecretExposure.COMMAND_READABLE else SecretExposure.APP_ONLY,
            info.needsUsername, info.offersGitHubBrowserLogin, entry.createdAt + SecretIoLimits.INPUT_TIMEOUT_MILLIS)
    }

    /** 与 ask_user 同样独占整个批次，普通工具不会在待输入批次内执行。 */
    suspend fun createBatch(sessionId: String, assistantMessageId: String, calls: List<ToolCall>, availableTool: ProtocolTool?): SecretInputBatchResult = writable(sessionId) {
        database.withTransaction {
            val assistant = messages.getById(assistantMessageId)?.takeIf { it.sessionId == sessionId && it.role == "assistant" }
                ?: error("保密输入所属助手消息不存在")
            val session = sessions.getById(sessionId) ?: error("保密输入所属会话不存在")
            val distinct = calls.distinctBy { it.id }
            val first = distinct.first { it.name == SecretIoLimits.REQUEST_TOOL_NAME }
            val existing = ledger.getInputForSession(sessionId)
            val persisted = ledger.get(sessionId, assistantMessageId, first.id)
            if (persisted?.status == ToolLedgerStatus.WAITING_INPUT) return@withTransaction SecretInputBatchResult(pendingInTransaction(sessionId), emptyList())
            val decoded = runCatching { SecretRequestProtocol.decode(first.arguments) }
            val identity = availableTool?.takeIf { it.function.name == SecretIoLimits.REQUEST_TOOL_NAME && it.runtimeToolId == SecretIoLimits.REQUEST_TOOL_NAME && it.sourceId == "builtin" }
                ?.let { ToolInvocationIdentityFactory.fromPreparedToolCall(first, it, requiresApproval = false) } as? ToolInvocationIdentityResolution.Valid
            val conflict = session.approvalRequest != null || ledger.getAwaitingApprovalForSession(sessionId).isNotEmpty() ||
                existing.any { it.assistantMessageId != assistantMessageId || it.toolCallId != first.id }
            val destinationFailure = if (decoded.isSuccess && !conflict && availableTool != null) {
                try { destinations.describe(sessionId, decoded.getOrThrow().destination); null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: SecretIoFailure) { failure.code }
                catch (_: Exception) { "SECRET_DESTINATION_UNAVAILABLE" }
            } else null
            val previousCalls = if (conflict || existing.isNotEmpty()) assistant.toolCalls?.let {
                runCatching { Json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
            }.orEmpty() else emptyList()
            val previousIds = previousCalls.mapTo(mutableSetOf()) { it.id }
            check(messages.updateQuestionToolCalls(sessionId, assistantMessageId,
                Json.encodeToString(previousCalls + distinct.filterNot { it.id in previousIds })) == 1)
            val results = mutableListOf<Message>()
            distinct.forEach { call ->
                if (ledger.get(sessionId, assistantMessageId, call.id) != null) return@forEach
                val failure = when {
                    call.name != SecretIoLimits.REQUEST_TOOL_NAME -> "SECRET_INPUT_BATCH_NOT_EXECUTED"
                    call.id != first.id -> "SECRET_INPUT_MULTIPLE_CALLS"
                    availableTool == null -> "SECRET_INPUT_UNAVAILABLE"
                    decoded.isFailure -> (decoded.exceptionOrNull() as? SecretIoFailure)?.code ?: "SECRET_INVALID_ARGUMENTS"
                    identity == null -> "SECRET_INVALID_ARGUMENTS"
                    conflict -> "SECRET_INPUT_CONFLICT"
                    destinationFailure != null -> destinationFailure
                    else -> null
                }
                val now = clock()
                val result = failure?.let { Message(id = "secret_result_${UUID.randomUUID()}", role = MessageRole.TOOL,
                    content = SecretRequestProtocol.failure(it), toolCallId = call.id, name = call.name,
                    parentMessageId = assistantMessageId, thoughtSignature = assistant.thoughtSignature, createdAt = now) }
                val validIdentity = identity?.identity?.takeIf { call.id == first.id }
                check(ledger.insert(ToolExecutionLedgerEntity(sessionId = sessionId, assistantMessageId = assistantMessageId,
                    toolCallId = call.id, toolName = call.name, runtimeToolId = validIdentity?.runtimeToolId ?: "secret_input_rejected",
                    argumentsDigest = validIdentity?.argumentsDigest ?: digest(call.name + "\u0000" + call.arguments),
                    definitionDigest = validIdentity?.definitionDigest ?: digest("secret_input_rejected"), requiresApproval = false,
                    status = if (failure == null) ToolLedgerStatus.WAITING_INPUT else ToolLedgerStatus.FAILED,
                    resultMessageId = result?.id, error = failure, createdAt = now, updatedAt = now)) != -1L)
                if (result != null) { messages.insert(result.toEntity(sessionId)); results += result }
            }
            if (ledger.get(sessionId, assistantMessageId, first.id)?.status == ToolLedgerStatus.WAITING_INPUT) {
                check(sessions.updateToolApprovalState(sessionId, null, LoopStatus.WAITING_FOR_INPUT.toSerializedName(), clock()) == 1)
            }
            SecretInputBatchResult(pendingInTransaction(sessionId), results)
        }
    }

    override suspend fun submit(expected: PendingSecretInput, value: CharArray, username: String?): SecretInputOutcome? {
        var bytes: ByteArray? = null
        return try {
            writable(expected.sessionId) {
                val current = database.withTransaction { pendingInTransaction(expected.sessionId) } ?: return@writable null
                if (!sameRequest(current, expected)) return@writable null
                if (clock() >= current.expiresAtMillis) return@writable database.withTransaction { finish(current, SecretInputOutcome.Expired) }
                val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(value))
                bytes = ByteArray(encoded.remaining()).also(encoded::get)
                if (encoded.hasArray()) encoded.array().fill(0)
                if (bytes!!.isEmpty() || bytes!!.size > SecretIoLimits.MAX_SECRET_BYTES) return@writable failed("SECRET_INVALID_VALUE")
                try {
                    destinations.describe(expected.sessionId, current.request.destination)
                    // 会话修改门禁覆盖落地与结果提交；外部凭据仓库和终端 IO 不占用 Room 事务。
                    withContext(NonCancellable) {
                        destinations.write(expected.sessionId, current.request.destination, bytes!!, username)
                        database.withTransaction { finish(current, SecretInputOutcome.Saved(destinationType(current.request.destination))) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: SecretIoFailure) { failed(failure.code)
                } catch (_: Exception) { failed("SECRET_WRITE_FAILED") }
            }
        } finally { value.fill('\u0000'); bytes?.fill(0) }
    }

    override suspend fun cancel(expected: PendingSecretInput): SecretInputOutcome? = writable(expected.sessionId) {
        database.withTransaction {
            val current = pendingInTransaction(expected.sessionId) ?: return@withTransaction null
            if (!sameRequest(current, expected)) return@withTransaction null
            finish(current, if (clock() >= current.expiresAtMillis) SecretInputOutcome.Expired else SecretInputOutcome.Cancelled)
        }
    }

    override suspend fun completeWithGitHubLogin(expected: PendingSecretInput): SecretInputOutcome? = writable(expected.sessionId) {
        val current = database.withTransaction { pendingInTransaction(expected.sessionId) } ?: return@writable null
        if (!sameRequest(current, expected) || !destinations.satisfiedByGitHubLogin(current.request.destination)) return@writable null
        database.withTransaction {
            if (clock() >= current.expiresAtMillis) finish(current, SecretInputOutcome.Expired)
            else finish(current, SecretInputOutcome.Saved(destinationType(current.request.destination)))
        }
    }

    suspend fun expirePending() = withContext(Dispatchers.IO) {
        ledger.getAllInput().filter { it.toolName == SecretIoLimits.REQUEST_TOOL_NAME && it.status == ToolLedgerStatus.WAITING_INPUT &&
            clock() >= it.createdAt + SecretIoLimits.INPUT_TIMEOUT_MILLIS }.forEach { entry ->
            writable(entry.sessionId) {
                database.withTransaction {
                    pendingInTransaction(entry.sessionId)?.takeIf { clock() >= it.expiresAtMillis }?.let { finish(it, SecretInputOutcome.Expired) }
                }
            }
        }
    }

    /** 调用方已经持有会话事务；这里不接收任何保密值。 */
    private suspend fun finish(expected: PendingSecretInput, outcome: SecretInputOutcome): SecretInputOutcome? {
        val assistant = messages.getById(expected.assistantMessageId) ?: return null
        val user = assistant.parentMessageId?.let { messages.getById(it) }?.takeIf { it.sessionId == expected.sessionId && it.role == "user" }
            ?: messages.getBySession(expected.sessionId).takeWhile { it.id != assistant.id }.lastOrNull { it.role == "user" }
            ?: error("保密输入缺少所属用户消息")
        val now = clock()
        val result = Message(id = "secret_result_${UUID.randomUUID()}", role = MessageRole.TOOL,
            content = Json.encodeToString<SecretInputOutcome>(outcome), toolCallId = expected.toolCallId, name = SecretIoLimits.REQUEST_TOOL_NAME,
            parentMessageId = assistant.id, thoughtSignature = assistant.thoughtSignature, createdAt = now)
        if (ledger.decideTerminal(expected.sessionId, assistant.id, expected.toolCallId, setOf(ToolLedgerStatus.WAITING_INPUT),
                ToolLedgerStatus.INPUT_ANSWERED, result.id, null, now) != 1) return null
        val session = sessions.getById(expected.sessionId) ?: error("保密输入所属会话不存在")
        sessions.updateRow(session.copy(sessionAutoWakeCount = 0, sessionAutoProcessingPaused = false))
        messages.insert(result.toEntity(expected.sessionId))
        // ask_user 的观察器、首回合消费及启动恢复均以此锚点恢复，不需要界面包重复维护生成入口。
        messages.insert(Message(id = "ask_resume_${result.id}", role = MessageRole.ASSISTANT, content = "",
            parentMessageId = user.id, modelId = assistant.modelId, createdAt = now + 1).toEntity(expected.sessionId))
        check(sessions.updateToolApprovalState(expected.sessionId, null, LoopStatus.INPUT_ANSWERED.toSerializedName(), now) == 1)
        return outcome
    }

    private fun sameRequest(a: PendingSecretInput, b: PendingSecretInput) = a.sessionId == b.sessionId &&
        a.assistantMessageId == b.assistantMessageId && a.toolCallId == b.toolCallId && a.request == b.request && a.expiresAtMillis == b.expiresAtMillis
    private fun failed(code: String) = SecretInputOutcome.Failed(code, "保密信息未保存，请检查去向后重试。")
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal fun destinationType(destination: SecretDestination): String = when (destination) {
    is SecretDestination.GitCredential -> "git_credential"
    is SecretDestination.SshHost -> "ssh_host"
    is SecretDestination.ProviderApiKey -> "provider_api_key"
    is SecretDestination.JobInput -> "job_input"
    is SecretDestination.SessionEnv -> "session_env"
}
