package com.promenar.nexara.data.subagent

import com.promenar.nexara.data.model.Session
import com.promenar.nexara.domain.trigger.*
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 应用级 FIFO 运行池。父工具只等待回执，执行者始终由应用作用域持有。 */
class SubagentLauncher(
    private val runner: SubagentRunner,
    private val store: SubagentRunStore,
    private val session: suspend (String) -> Session?,
    private val modelFor: suspend (Session) -> String?,
    private val reasoningEffortFor: suspend (Session) -> String? = { it.inferenceParams?.reasoningEffort },
    private val taskTokenLimit: () -> Long = { 0L },
    private val clock: () -> Long = System::currentTimeMillis,
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val dispatcher: () -> SystemTurnDispatcher? = { null },
    private val contextWindow: suspend (String) -> Int? = { null },
    private val writeResult: suspend (Session, SubagentRun, String) -> String = { _, _, _ -> error("结论文件存储不可用") },
    private val texts: SubagentRuntimeTexts = SubagentRuntimeTexts(),
) : SubagentControls {
    private data class Work(val sessionId: String, val stepId: String, val id: String, val parent: SkillExecutionContext, val resume: Boolean = false)
    private class Waiter(val ids: Set<String>) { val release = CompletableDeferred<SubagentEvent.Actor>() }
    private val mutex = Mutex()
    private val batches = linkedMapOf<String, SubagentBatch>()
    private val queue = ArrayDeque<Work>()
    private val jobs = mutableMapOf<String, Job>()
    private val waits = ConcurrentHashMap<String, Waiter>()
    private val reservedWaits = mutableSetOf<String>()
    private val notifying = mutableMapOf<String, Job>()
    private val deleted = mutableSetOf<String>()
    private val changes = MutableStateFlow(0L)
    private val mutableActiveRuns = MutableStateFlow<List<SubagentRun>>(emptyList())
    val activeRuns: StateFlow<List<SubagentRun>> = mutableActiveRuns.asStateFlow()
    private var initialized = false
    private var nextNoticeOrder = 0L

    /** 在启动恢复门禁通过后调用，补送持久化的通知并将失去执行者的任务标为中断。 */
    suspend fun recover() {
        mutex.withLock { initializeLocked() }
        notifyPending()
    }

    suspend fun launch(
        context: SkillExecutionContext,
        stepId: String,
        specs: List<SubagentSpec>,
        background: Boolean = true,
        timeoutMillis: Long = SubagentLimits.DEFAULT_WAIT_TIMEOUT_MS,
        maxTurns: Int? = null,
    ): List<SubagentRun> {
        require(specs.size in 1..SubagentLimits.MAX_PARALLEL) { "一次最多派生 ${SubagentLimits.MAX_PARALLEL} 个子代理" }
        require(maxTurns == null || maxTurns > 0) { "max_turns 必须为正整数" }
        val parent = session(context.sessionId) ?: throw SubagentModelUnavailable("会话不存在")
        val model = modelFor(parent)?.takeIf(String::isNotBlank) ?: throw SubagentModelUnavailable("会话没有可用模型")
        val effort = reasoningEffortFor(parent)
        val tokenLimit = taskTokenLimit()
        val ignoredTools = specs.map { runner.ignoredTools(parent, it) }
        val runs = withContext(NonCancellable) { mutex.withLock {
            initializeLocked()
            check(parent.id !in deleted) { "父会话已删除" }
            val key = key(parent.id, stepId)
            require(key !in batches) { "子代理批次已存在" }
            val allocated = batches.values.flatMap { it.runs }.mapTo(mutableSetOf()) { it.id }
            val created = specs.mapIndexed { index, spec ->
                var id: String
                do { id = "sa_" + kotlin.random.Random.nextLong(2_176_782_336L).toString(36).padStart(6, '0') } while (!allocated.add(id))
                SubagentRun(index, id = id, preset = spec.preset?.wireName, description = spec.description, prompt = spec.prompt,
                    toolProfile = spec.toolProfile, modelId = model, reasoningEffort = effort, maxSteps = 0, maxTurns = maxTurns,
                    maxTokens = if (tokenLimit <= 0) 0 else (tokenLimit / specs.size).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                    status = SubagentStatus.QUEUED, startedAt = clock(),
                    allowedTools = spec.allowedTools?.distinct()?.filterNot { it in ignoredTools[index] },
                    ignoredTools = ignoredTools[index])
            }
            putLocked(SubagentBatch(parent.id, stepId, created))
            created.forEach { run ->
                if (!background) reservedWaits += run.id
                queue += Work(parent.id, stepId, run.id, detachedContext(context))
            }
            pumpLocked()
            created
        } }
        return if (background) mutex.withLock { runs.map { findLocked(parent.id, it.id).second } }
        else try { waitFor(parent.id, runs.map { it.id }, timeoutMillis) }
        finally { withContext(NonCancellable) {
            mutex.withLock { reservedWaits.removeAll(runs.map { it.id }.toSet()) }
            notifyPending()
        } }
    }

    suspend fun waitFor(sessionId: String, ids: List<String>? = null, timeoutMillis: Long = SubagentLimits.DEFAULT_WAIT_TIMEOUT_MS): List<SubagentRun> {
        require(timeoutMillis > 0) { "等待超时必须为正数" }
        lateinit var waiter: Waiter
        var selected = emptyList<Pair<String, SubagentRun>>()
        val installed = mutableListOf<String>()
        var by: SubagentEvent.Actor? = null
        var setupComplete = false
        try {
            mutex.withLock {
                initializeLocked()
                selected = if (ids == null) batches.values.filter { it.sessionId == sessionId }.flatMap { b -> b.runs.filter { it.status.isActive }.map { b.stepId to it } }
                    else ids.distinct().map { id -> findLocked(sessionId, id) }
                waiter = Waiter(selected.mapTo(mutableSetOf()) { it.second.id })
                val now = clock()
                val steps = selected.map { it.first }.distinct()
                check(steps.none { waits.containsKey(key(sessionId, it)) }) { "这批子代理已有等待" }
                steps.forEach { step ->
                    installed += step
                    waits[key(sessionId, step)] = waiter
                    putLocked(checkNotNull(batches[key(sessionId, step)]).copy(wait = SubagentWait(now, now + timeoutMillis)))
                }
                setupComplete = true
            }
            val completed = withTimeoutOrNull(timeoutMillis) {
                changes.first {
                    waiter.release.isCompleted || mutex.withLock { selected.all { (_, run) -> !findLocked(sessionId, run.id).second.status.isActive } }
                }
                true
            } ?: false
            by = if (!completed) SubagentEvent.Actor.TIMEOUT else if (waiter.release.isCompleted) waiter.release.await() else null
        } catch (cancelled: CancellationException) {
            by = SubagentEvent.Actor.PARENT
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    // 先撤销内存等待所有权，磁盘失败也不能把后台通知永久压住。
                    installed.forEach { step -> waits.remove(key(sessionId, step), waiter) }
                    selected.forEach { (step, initial) ->
                        if (step !in installed) return@forEach
                        reservedWaits -= initial.id
                        val run = batches[key(sessionId, step)]?.runs?.find { it.id == initial.id } ?: return@forEach
                        val next = if (by != null && run.status.isActive) run.copy(events = (run.events + SubagentEvent(SubagentEvent.Kind.DETACH, at = clock(), by = by)).takeLast(SubagentLimits.MAX_EVENTS))
                            else if (setupComplete && by != SubagentEvent.Actor.PARENT && !run.status.isActive) run.copy(deliveredSequence = run.completionSequence, pendingNotices = emptyList()) else run
                        try { replaceLocked(sessionId, step, next) } catch (_: Exception) { replaceLocked(sessionId, step, next, persist = false) }
                    }
                    installed.forEach { step ->
                        batches[key(sessionId, step)]?.let { batch ->
                            try { putLocked(batch.copy(wait = null)) } catch (_: Exception) { putLocked(batch.copy(wait = null), persist = false) }
                        }
                    }
                }
                notifyPending()
            }
        }
        return mutex.withLock { selected.map { (_, run) -> findLocked(sessionId, run.id).second } }
    }

    override fun releaseWait(sessionId: String, stepId: String) { waits[key(sessionId, stepId)]?.release?.complete(SubagentEvent.Actor.USER); changes.update { it + 1 } }

    suspend fun read(sessionId: String, id: String): SubagentRun = mutex.withLock { initializeLocked(); findLocked(sessionId, id).second }

    suspend fun send(sessionId: String, id: String, message: String, wrapUp: Boolean = false): SubagentRun {
        require(message.isNotBlank() || wrapUp) { "message 不能为空" }
        return mutex.withLock {
            initializeLocked()
            val (step, run) = findLocked(sessionId, id)
            require(run.status != SubagentStatus.STOPPED && run.status != SubagentStatus.CANCELLED) { "已停止或取消的子代理不能恢复" }
            val instruction = SubagentInstruction(message, wrapUp, clock(), UUID.randomUUID().toString())
            store.enqueue(sessionId, step, id, instruction)
            if (run.status.isActive) {
                val next = if (wrapUp) run.copy(status = SubagentStatus.WRAPPING_UP,
                    events = (run.events + SubagentEvent(SubagentEvent.Kind.WRAP_UP, message.take(SubagentLimits.MAX_EVENT_CHARS), at = instruction.at, by = SubagentEvent.Actor.PARENT)).takeLast(SubagentLimits.MAX_EVENTS)) else run
                replaceLocked(sessionId, step, next)
                next
            } else {
                val parent = session(sessionId) ?: throw SubagentModelUnavailable("父会话已删除")
                val next = run.copy(status = SubagentStatus.QUEUED, result = null, resultPath = null, error = null, endedAt = null,
                    events = (run.events + SubagentEvent(SubagentEvent.Kind.RESUME, message.take(SubagentLimits.MAX_EVENT_CHARS), at = clock(), by = SubagentEvent.Actor.PARENT)).takeLast(SubagentLimits.MAX_EVENTS))
                replaceLocked(sessionId, step, next)
                queue += Work(sessionId, step, id, contextFor(parent), resume = true)
                pumpLocked()
                next
            }
        }
    }

    override suspend fun stop(sessionId: String, runId: String) = stop(sessionId, runId, SubagentEvent.Actor.USER)

    suspend fun stop(sessionId: String, runId: String, by: SubagentEvent.Actor) {
        val job = mutex.withLock {
            initializeLocked()
            val (step, run) = findLocked(sessionId, runId)
            if (!run.status.isActive) return
            queue.removeAll { it.id == runId }
            val running = jobs[runId]
            // 先取消执行，磁盘阻塞或失败不能延长网络请求与工具的生命期。
            running?.cancel()
            val stopped = run.copy(status = SubagentStatus.STOPPED, currentTool = null, liveOutputKey = null, endedAt = clock(),
                pendingNotices = emptyList(), deliveredSequence = run.completionSequence,
                events = (run.events + SubagentEvent(SubagentEvent.Kind.STOP, at = clock(), by = by)).takeLast(SubagentLimits.MAX_EVENTS))
            try { replaceLocked(sessionId, step, stopped) }
            catch (_: Exception) { replaceLocked(sessionId, step, stopped.copy(error = texts.stopPersistenceFailed), persist = false) }
            running
        }
        job?.join()
    }

    suspend fun deleteSession(sessionId: String) {
        val cancelled = mutex.withLock {
            initializeLocked()
            deleted += sessionId
            queue.removeAll { it.sessionId == sessionId }
            val ids = batches.values.filter { it.sessionId == sessionId }.flatMap { it.runs }.map { it.id }.toSet()
            notifying.keys.filter { it.substringBefore(':') in ids }.forEach { key -> notifying.remove(key)?.cancel() }
            waits.filterKeys { it.startsWith("$sessionId\u0000") }.values.forEach { it.release.complete(SubagentEvent.Actor.PARENT) }
            val active = ids.mapNotNull { jobs[it] }
            active.forEach { it.cancel() }
            batches.entries.removeAll { it.value.sessionId == sessionId }
            reservedWaits.removeAll(ids)
            store.deleteSession(sessionId)
            changedLocked()
            active
        }
        cancelled.joinAll()
    }

    private suspend fun initializeLocked() {
        if (initialized) return
        val stored = store.all()
        nextNoticeOrder = stored.flatMap { it.runs }.flatMap { it.pendingNotices }.maxOfOrNull { it.order } ?: 0L
        stored.forEach { batch ->
            val recovered = batch.copy(wait = null, runs = batch.runs.map { run ->
                when {
                    run.status.isActive -> run.copy(status = SubagentStatus.INTERRUPTED, endedAt = clock(), currentTool = null, liveOutputKey = null,
                        completionSequence = run.completionSequence + 1, error = texts.interrupted)
                    run.id.isNotBlank() && run.completionSequence == 0 && run.status != SubagentStatus.STOPPED && run.status != SubagentStatus.CANCELLED -> run.copy(completionSequence = 1)
                    else -> run
                }
            })
            val durable = recovered.copy(runs = recovered.runs.map { run ->
                if (run.id.isNotEmpty() && run.completionSequence > run.deliveredSequence && run.pendingNotices.none { it.sequence == run.completionSequence })
                    run.copy(pendingNotices = run.pendingNotices + notice(run)) else run
            })
            putLocked(durable)
        }
        initialized = true
    }

    private fun pumpLocked() {
        while (jobs.size < SubagentLimits.MAX_BACKGROUND_CONCURRENT && queue.isNotEmpty()) {
            if (queue.first().id in jobs) break
            val work = queue.removeFirst()
            val job = applicationScope.launch(start = CoroutineStart.LAZY) { execute(work) }
            jobs[work.id] = job
            job.invokeOnCompletion {
                applicationScope.launch {
                    mutex.withLock {
                        if (jobs[work.id] === job) jobs.remove(work.id)
                        pumpLocked()
                        changedLocked()
                    }
                    notifyPending()
                }
            }
            job.start()
        }
    }

    private suspend fun execute(work: Work) {
        try {
            val initial = mutex.withLock {
                val run = findLocked(work.sessionId, work.id).second
                run.copy(status = if (run.status == SubagentStatus.WRAPPING_UP) run.status else SubagentStatus.RUNNING).also { replaceLocked(work.sessionId, work.stepId, it) }
            }
            val execution = SubagentExecution(
                load = { store.execution(work.sessionId, work.stepId, work.id) },
                save = { store.saveExecution(work.sessionId, work.stepId, work.id, it) },
                drainInbox = { store.execution(work.sessionId, work.stepId, work.id)?.pendingInstructions.orEmpty() },
                contextWindow = contextWindow, resume = work.resume,
            )
            val finished = runner.run(work.parent, { session(work.sessionId) }, work.stepId, initial, SubagentPreset.parse(initial.preset), execution) { run, persist ->
                mutex.withLock {
                    if (work.sessionId in deleted) return@withLock
                    val current = findLocked(work.sessionId, work.id).second
                    val merged = if (current.status == SubagentStatus.STOPPED) current.copy(
                        inputTokens = maxOf(current.inputTokens, run.inputTokens), outputTokens = maxOf(current.outputTokens, run.outputTokens), totalTokens = maxOf(current.totalTokens, run.totalTokens))
                    else run.copy(events = mergeEvents(current.events, run.events),
                        completionSequence = current.completionSequence, deliveredSequence = current.deliveredSequence,
                        pendingNotices = current.pendingNotices, dailyUsage = current.dailyUsage,
                        status = if (!run.status.isActive || current.status == SubagentStatus.WRAPPING_UP) current.status else run.status)
                    replaceLocked(work.sessionId, work.stepId, merged, persist)
                }
            }
            var result = finished
            val parent = session(work.sessionId)
            val text = result.result
            val threshold = resultThreshold(parent?.let { modelFor(it) })
            if (parent != null && text != null && text.length > threshold) {
                try {
                    val path = writeResult(parent, result, text)
                    result = result.copy(resultPath = path, result = text.take(minOf(2_000, threshold / 2)) + "\n\n完整结论：$path")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { result = result.copy(status = SubagentStatus.FAILED, error = texts.resultWriteFailed, result = null) }
            }
            mutex.withLock {
                if (work.sessionId in deleted) return@withLock
                val current = findLocked(work.sessionId, work.id).second
                if (current.status != SubagentStatus.STOPPED) {
                    val pending = store.execution(work.sessionId, work.stepId, work.id)?.pendingInstructions.orEmpty()
                    if (pending.isNotEmpty()) {
                        replaceLocked(work.sessionId, work.stepId, result.copy(status = SubagentStatus.QUEUED, result = null,
                            resultPath = null, error = null, endedAt = null, events = current.events,
                            completionSequence = current.completionSequence, deliveredSequence = current.deliveredSequence,
                            pendingNotices = current.pendingNotices, dailyUsage = current.dailyUsage))
                        queue += work.copy(resume = true)
                    } else replaceLocked(work.sessionId, work.stepId,
                        result.copy(events = current.events, completionSequence = current.completionSequence + 1, deliveredSequence = current.deliveredSequence,
                            dailyUsage = current.dailyUsage, pendingNotices = current.pendingNotices + notice(result.copy(completionSequence = current.completionSequence + 1))))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutex.withLock {
                if (work.sessionId !in deleted) {
                    val current = findLocked(work.sessionId, work.id).second
                    if (current.status != SubagentStatus.STOPPED) {
                        val failed = current.copy(status = SubagentStatus.FAILED, currentTool = null, liveOutputKey = null,
                            error = texts.executionFailed, endedAt = clock(), completionSequence = current.completionSequence + 1)
                        val next = failed.copy(pendingNotices = current.pendingNotices + notice(failed))
                        try { replaceLocked(work.sessionId, work.stepId, next) }
                        catch (_: Exception) { replaceLocked(work.sessionId, work.stepId,
                            next.copy(error = texts.persistenceFailed), persist = false) }
                    }
                }
            }
        }
    }

    private suspend fun resultThreshold(modelId: String?): Int = ((modelId?.let { contextWindow(it) }?.takeIf { it > 0 } ?: 32_768) / 8).coerceAtLeast(512)

    private fun notice(run: SubagentRun): SubagentCompletionNotice {
        nextNoticeOrder = maxOf(nextNoticeOrder + 1, clock())
        return SubagentCompletionNotice(run.completionSequence, run.status,
        "状态：${run.status}；用时：${((run.endedAt ?: clock()) - run.startedAt).coerceAtLeast(0) / 1000} 秒\n\n" +
            (run.result ?: run.error.orEmpty()) + (run.resultPath?.let { "\n完整结论：$it" }.orEmpty()), run.endedAt ?: clock(), order = nextNoticeOrder)
    }

    /** 每个完成序号独立持久化；投递回执只在消息已持久化后消费。 */
    private suspend fun notifyPending() {
        mutex.withLock {
            if (dispatcher() == null) return
            batches.values.flatMap { b -> b.runs.flatMap { run -> run.pendingNotices.map { Triple(b, run, it) } } }
                .sortedBy { it.third.order }.forEach { (batch, run, pending) ->
                val noticeKey = "${run.id}:${pending.sequence}"
                if (run.id.isBlank() || run.status == SubagentStatus.STOPPED || run.status == SubagentStatus.CANCELLED ||
                    run.id in reservedWaits || waits[key(batch.sessionId, batch.stepId)]?.ids?.contains(run.id) == true || noticeKey in notifying) return@forEach
                notifying[noticeKey] = Job()
                val job = applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val outcome = dispatcher()!!.request(SystemTurnRequest(batch.sessionId, SystemTurnKind.SUBAGENT_DONE, run.id, run.description,
                            pending.body, sequence = pending.sequence))
                        if (outcome == SystemTurnOutcome.Completed || outcome == SystemTurnOutcome.NeedsUser || outcome == SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING)) {
                            mutex.withLock {
                                if (batch.sessionId !in deleted) {
                                    val current = findLocked(batch.sessionId, run.id).second
                                    replaceLocked(batch.sessionId, batch.stepId, current.copy(deliveredSequence = maxOf(current.deliveredSequence, pending.sequence),
                                        pendingNotices = current.pendingNotices.filter { it.sequence != pending.sequence }))
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        // 未获持久消息回执或水位写入失败时保留 outbox，稍后按相同序号重试。
                    } finally {
                        mutex.withLock { notifying.remove(noticeKey) }
                        delay(250)
                        notifyPending()
                    }
                }
                notifying[noticeKey] = job
            }
        }
    }

    /** 仅抵消已发布的快照事件；同时间、同正文的多条真实事件保留次数。 */
    private fun mergeEvents(current: List<SubagentEvent>, incoming: List<SubagentEvent>): List<SubagentEvent> {
        val existing = current.groupingBy { it }.eachCount().toMutableMap()
        val additions = incoming.filter { event ->
            val count = existing[event] ?: 0
            if (count > 0) { existing[event] = count - 1; false } else true
        }
        return (current + additions).takeLast(SubagentLimits.MAX_EVENTS)
    }

    private fun findLocked(sessionId: String, id: String): Pair<String, SubagentRun> {
        require(id.matches(Regex("sa_[a-z0-9]{6}"))) { "未找到该会话或子代理" }
        return batches.values.firstNotNullOfOrNull { batch ->
            if (batch.sessionId == sessionId) batch.runs.find { it.id == id }?.let { batch.stepId to it } else null
        } ?: throw IllegalArgumentException("未找到该会话或子代理")
    }

    private suspend fun replaceLocked(sessionId: String, stepId: String, run: SubagentRun, persist: Boolean = true) {
        val batch = batches[key(sessionId, stepId)] ?: return
        val current = batch.runs.first { it.id == run.id }
        val input = (run.inputTokens - current.inputTokens).coerceAtLeast(0)
        val output = (run.outputTokens - current.outputTokens).coerceAtLeast(0)
        val day = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val old = current.dailyUsage[day] ?: com.promenar.nexara.data.model.TokenUsage()
        val next = run.copy(dailyUsage = if (input + output == 0) current.dailyUsage else current.dailyUsage +
            (day to old.copy(input = old.input + input, output = old.output + output, total = old.total + input + output, estimated = old.estimated || run.tokensEstimated)))
        putLocked(batch.copy(runs = batch.runs.map { if (it.id == run.id) next else it }), persist)
    }
    private suspend fun putLocked(batch: SubagentBatch, persist: Boolean = true) {
        store.update(batch, persist)
        batches[key(batch.sessionId, batch.stepId)] = batch
        changedLocked()
    }
    private fun changedLocked() {
        mutableActiveRuns.value = batches.values.flatMap { it.runs }.filter { it.status.isActive }
        changes.update { it + 1 }
    }
    private fun key(sessionId: String, stepId: String) = "$sessionId\u0000$stepId"
    private fun detachedContext(parent: SkillExecutionContext) = object : SkillExecutionContext {
        override val sessionId = parent.sessionId
        override val agentId = parent.agentId
        override val workspacePath = parent.workspacePath
        override val workspaceRootUuid = parent.workspaceRootUuid
        override val runMode = parent.runMode
    }
    private fun contextFor(parent: Session) = object : SkillExecutionContext {
        override val sessionId = parent.id
        override val agentId = parent.agentId
        override val workspacePath = parent.workspacePath
        override val workspaceRootUuid = parent.workspaceRootUuid.orEmpty()
        override val runMode = parent.runMode
    }
}
