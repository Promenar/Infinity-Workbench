package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.domain.generation.GenerationToolCall
import com.promenar.nexara.domain.generation.GenerationInteractionMode
import com.promenar.nexara.domain.tool.ToolArgumentsValidation
import com.promenar.nexara.domain.tool.ToolArgumentsValidator
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 只比较连续的调用，调用 ID 不属于执行语义。 */
class ConsecutiveToolLoopDetector(private val threshold: Int = 3) {
    private var previous: Pair<String, String>? = null
    private var count = 0

    init { require(threshold >= 2) }

    fun record(call: GenerationToolCall): Boolean {
        val parsed = ToolArgumentsValidator().validate(call.arguments)
        val canonical = (parsed as? ToolArgumentsValidation.Valid)?.canonicalJson ?: call.arguments
        val key = call.name to canonical
        count = if (key == previous) count + 1 else 1
        previous = key
        return count >= threshold
    }

    fun reset() { previous = null; count = 0 }
}

enum class GenerationInteractionKind { REPEATED_TOOL, UNLIMITED_AUTO }
@kotlinx.serialization.Serializable
data class GenerationInteraction(val id: String, val sessionId: String, val kind: GenerationInteractionKind)

/** 无界面确认点是可恢复的暂停，不属于生成失败。 */
class HeadlessInteractionRequired(val interaction: GenerationInteraction) : Exception("headless_interaction_required")

interface HeadlessInteractionPersistence {
    suspend fun read(): GenerationInteraction?
    suspend fun write(interaction: GenerationInteraction?)
}

/** 应用级等待点，页面重建不会丢失决定；任务取消会清除卡片并取消等待。 */
class GenerationInteractionStore(private val persistence: HeadlessInteractionPersistence? = null) {
    private val mutex = Mutex()
    private val mutablePending = MutableStateFlow<GenerationInteraction?>(null)
    val pending = mutablePending.asStateFlow()
    private var decision: CompletableDeferred<Boolean>? = null
    var onHeadlessDecision: ((GenerationInteraction, Boolean) -> Unit)? = null
    private var headless = false
    private var respondingId: String? = null

    suspend fun restoreHeadless() = mutex.withLock {
        if (mutablePending.value == null) persistence?.read()?.let {
            headless = true
            mutablePending.value = it
        }
    }

    suspend fun clearHeadless(sessionId: String) = mutex.withLock {
        if (headless && mutablePending.value?.sessionId == sessionId) {
            persistence?.write(null)
            headless = false
            respondingId = null
            mutablePending.value = null
        }
    }

    suspend fun awaitDecision(
        sessionId: String,
        kind: GenerationInteractionKind,
        mode: GenerationInteractionMode = GenerationInteractionMode.INTERACTIVE,
    ): Boolean = mutex.withLock {
        check(mutablePending.value == null) { "已有等待用户处理的确认" }
        val prompt = GenerationInteraction(UUID.randomUUID().toString(), sessionId, kind)
        if (mode == GenerationInteractionMode.HEADLESS) {
            check(mutablePending.value == null) { "已有等待用户处理的确认" }
            try { persistence?.write(prompt) }
            catch (failure: Throwable) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { persistence?.write(null) }
                throw failure
            }
            headless = true
            mutablePending.value = prompt
            throw HeadlessInteractionRequired(prompt)
        }
        val deferred = CompletableDeferred<Boolean>()
        decision = deferred
        mutablePending.value = prompt
        try {
            deferred.await()
        } finally {
            mutablePending.value = null
            decision = null
            deferred.cancel()
        }
    }

    fun respond(id: String, allowed: Boolean): Boolean {
        if (mutablePending.value?.id != id) return false
        if (headless) {
            val prompt = mutablePending.value ?: return false
            val responder = onHeadlessDecision ?: return false
            synchronized(this) {
                if (respondingId != null) return false
                respondingId = id
            }
            responder(prompt, allowed)
            return true
        }
        return decision?.complete(allowed) ?: false
    }

    fun retryHeadlessResponse() { synchronized(this) { respondingId = null } }
}

/** 危险组合的接受状态独立于审批授权，不随备份传播到其它设备。 */
class UnlimitedAutomationConsent(private val prefs: SharedPreferences) {
    fun requiresConfirmation(unlimited: Boolean, executionMode: String): Boolean =
        unlimited && executionMode == "auto" && !prefs.getBoolean(KEY, false)

    suspend fun confirm() = withContext(Dispatchers.IO) {
        check(prefs.edit().putBoolean(KEY, true).commit()) { "无法保存自动执行确认" }
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        check(prefs.edit().remove(KEY).commit()) { "无法重置自动执行确认" }
    }

    companion object { const val KEY = "unlimited_auto_confirmed" }
}
