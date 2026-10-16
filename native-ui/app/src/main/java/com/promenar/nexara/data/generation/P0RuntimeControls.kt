package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.domain.generation.GenerationToolCall
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
data class GenerationInteraction(val id: String, val sessionId: String, val kind: GenerationInteractionKind)

/** 应用级等待点，页面重建不会丢失决定；任务取消会清除卡片并取消等待。 */
class GenerationInteractionStore {
    private val mutex = Mutex()
    private val mutablePending = MutableStateFlow<GenerationInteraction?>(null)
    val pending = mutablePending.asStateFlow()
    private var decision: CompletableDeferred<Boolean>? = null

    suspend fun awaitDecision(sessionId: String, kind: GenerationInteractionKind): Boolean = mutex.withLock {
        val prompt = GenerationInteraction(UUID.randomUUID().toString(), sessionId, kind)
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
        return decision?.complete(allowed) ?: false
    }
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
