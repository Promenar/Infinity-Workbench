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
    suspend fun readAll(): List<GenerationInteraction> = listOfNotNull(read())
    suspend fun writeAll(interactions: List<GenerationInteraction>) {
        require(interactions.size <= 1) { "持久化实现不支持多个确认" }
        write(interactions.singleOrNull())
    }
}

/** 应用级等待点按会话隔离，页面重建不会丢失决定。 */
class GenerationInteractionStore(private val persistence: HeadlessInteractionPersistence? = null) {
    private class Entry(
        val prompt: GenerationInteraction,
        val decision: CompletableDeferred<Boolean>?,
        val headless: Boolean,
        var visible: Boolean = true,
        var responding: Boolean = false,
    )

    private val lock = Any()
    private val persistenceMutex = Mutex()
    private val entries = linkedMapOf<String, Entry>()
    private val mutablePending = MutableStateFlow<GenerationInteraction?>(null)
    private val mutablePendingBySession = MutableStateFlow<Map<String, GenerationInteraction>>(emptyMap())
    val pending = mutablePending.asStateFlow()
    val pendingBySession = mutablePendingBySession.asStateFlow()
    var onHeadlessDecision: ((GenerationInteraction, Boolean) -> Unit)? = null
    private var selectedSessionId: String? = null

    fun selectSession(sessionId: String?) = synchronized(lock) {
        selectedSessionId = sessionId
        publishLocked()
    }

    suspend fun restoreHeadless(exists: suspend (String) -> Boolean = { true }) = persistenceMutex.withLock {
        val saved = persistence?.readAll().orEmpty()
        val retained = saved.filter { exists(it.sessionId) }
        if (retained.size != saved.size) persistence?.writeAll(retained)
        synchronized(lock) {
            saved.filterNot { it in retained }.forEach { prompt ->
                if (entries[prompt.sessionId]?.takeIf { it.headless }?.prompt?.id == prompt.id) entries.remove(prompt.sessionId)
            }
            retained.forEach { prompt ->
                if (entries[prompt.sessionId] == null) entries[prompt.sessionId] = Entry(prompt, null, true)
            }
            publishLocked()
        }
    }

    /** 在协调器准入回调中消耗目标确认，后继生成可立即登记新的等待点。 */
    fun consumeHeadlessResponse(prompt: GenerationInteraction): Boolean = synchronized(lock) {
        if (entries[prompt.sessionId]?.takeIf { it.headless && it.visible }?.prompt?.id != prompt.id) return false
        entries.remove(prompt.sessionId)
        publishLocked()
        true
    }

    suspend fun clearHeadless(sessionId: String, expectedPromptId: String? = null) = persistenceMutex.withLock {
        val entry = synchronized(lock) {
            entries[sessionId]?.takeIf { it.headless && (expectedPromptId == null || it.prompt.id == expectedPromptId) }
        }
        if (entry == null && expectedPromptId == null) return@withLock
        val remaining = synchronized(lock) {
            entries.values.filter { it.headless && it !== entry }.map { it.prompt }
        }
        persistence?.writeAll(remaining)
        synchronized(lock) {
            if (entry != null && entries[sessionId] === entry) entries.remove(sessionId)
            publishLocked()
        }
    }

    suspend fun awaitDecision(
        sessionId: String,
        kind: GenerationInteractionKind,
        mode: GenerationInteractionMode = GenerationInteractionMode.INTERACTIVE,
    ): Boolean {
        val prompt = GenerationInteraction(UUID.randomUUID().toString(), sessionId, kind)
        if (mode == GenerationInteractionMode.HEADLESS) {
            persistenceMutex.withLock {
                val entry = Entry(prompt, null, true, visible = false)
                val previous = synchronized(lock) {
                    check(entries[sessionId] == null) { "该会话已有等待用户处理的确认" }
                    val saved = entries.values.filter { it.headless }.map { it.prompt }
                    entries[sessionId] = entry
                    saved
                }
                try {
                    persistence?.writeAll(previous + prompt)
                    synchronized(lock) { entry.visible = true; publishLocked() }
                } catch (failure: Throwable) {
                    synchronized(lock) { if (entries[sessionId] === entry) entries.remove(sessionId); publishLocked() }
                    withContext(kotlinx.coroutines.NonCancellable) { persistence?.writeAll(previous) }
                    throw failure
                }
            }
            throw HeadlessInteractionRequired(prompt)
        }
        val deferred = CompletableDeferred<Boolean>()
        val entry = Entry(prompt, deferred, false)
        synchronized(lock) {
            check(entries[sessionId] == null) { "该会话已有等待用户处理的确认" }
            entries[sessionId] = entry
            publishLocked()
        }
        try {
            return deferred.await()
        } finally {
            synchronized(lock) {
                if (entries[sessionId] === entry) entries.remove(sessionId)
                publishLocked()
            }
            deferred.cancel()
        }
    }

    fun respond(id: String, allowed: Boolean): Boolean {
        val entry = synchronized(lock) {
            val found = entries.values.firstOrNull { it.visible && it.prompt.id == id } ?: return false
            if (found.headless) {
                if (found.responding || onHeadlessDecision == null) return false
                found.responding = true
            }
            found
        }
        if (entry.headless) {
            onHeadlessDecision?.invoke(entry.prompt, allowed)
            return true
        }
        return entry.decision?.complete(allowed) ?: false
    }

    fun retryHeadlessResponse(id: String? = null) = synchronized(lock) {
        entries.values.filter { it.headless && (id == null || it.prompt.id == id) }.forEach { it.responding = false }
    }

    private fun publishLocked() {
        val visible = entries.values.filter { it.visible }.associate { it.prompt.sessionId to it.prompt }
        mutablePendingBySession.value = visible
        mutablePending.value = selectedSessionId?.let(visible::get) ?: if (selectedSessionId == null) visible.values.firstOrNull() else null
    }
}

/** 危险组合的接受状态独立于审批授权，不随备份传播到其它设备。 */
class UnlimitedAutomationConsent(private val prefs: SharedPreferences) {
    fun requiresConfirmation(unlimited: Boolean, executionMode: String): Boolean =
        unlimited && com.promenar.nexara.domain.model.ExecutionModeCodec.parseOrWorkspace(executionMode) == com.promenar.nexara.domain.model.ExecutionMode.FULL_ACCESS && !prefs.getBoolean(KEY, false)

    suspend fun confirm() = withContext(Dispatchers.IO) {
        check(prefs.edit().putBoolean(KEY, true).commit()) { "无法保存自动执行确认" }
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        check(prefs.edit().remove(KEY).commit()) { "无法重置自动执行确认" }
    }

    companion object { const val KEY = "unlimited_auto_confirmed" }
}
