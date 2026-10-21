package com.promenar.nexara.data.subagent

import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一次 `agent_task` 调用的子代理并行执行与归档；任一子代理失败不影响其它子代理交回结果。 */
class SubagentLauncher(
    private val runner: SubagentRunner,
    private val store: SubagentRunStore,
    private val session: suspend (String) -> Session?,
    private val modelFor: suspend (Session) -> String?,
    /** 父会话的任务 token 上限，0 表示不限。 */
    private val taskTokenLimit: () -> Long = { 0L },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun launch(context: SkillExecutionContext, stepId: String, specs: List<SubagentSpec>): List<SubagentRun> {
        require(specs.size in 1..SubagentLimits.MAX_PARALLEL) { "一次最多派生 ${SubagentLimits.MAX_PARALLEL} 个子代理" }
        val parent = session(context.sessionId) ?: throw SubagentModelUnavailable("会话不存在")
        val modelId = modelFor(parent)?.takeIf(String::isNotBlank) ?: throw SubagentModelUnavailable("会话没有可用模型")
        val startedAt = clock()
        val maxTokens = SubagentLimits.tokenBudget(taskTokenLimit(), specs.size)
        val runs = specs.mapIndexed { index, spec ->
            SubagentRun(
                index = index,
                preset = spec.preset?.wireName,
                description = spec.description,
                prompt = spec.prompt,
                toolProfile = spec.toolProfile,
                modelId = modelId,
                maxSteps = SubagentLimits.DEFAULT_MAX_STEPS,
                maxTokens = maxTokens,
                startedAt = startedAt,
            )
        }.toMutableList()
        val lock = Mutex()
        suspend fun batch() = lock.withLock { SubagentBatch(parent.id, stepId, runs.toList()) }
        // 写入也在锁内，并行子代理的较早快照不会覆盖较新的状态。
        suspend fun publish(run: SubagentRun, persist: Boolean) = lock.withLock {
            runs[run.index] = run
            store.update(SubagentBatch(parent.id, stepId, runs.toList()), persist)
        }
        store.update(batch(), persist = true)
        try {
            return coroutineScope {
                specs.mapIndexed { index, spec ->
                    async { runner.run(context, { session(parent.id) }, stepId, runs[index], spec.preset, ::publish) }
                }.awaitAll()
            }
        } finally {
            withContext(NonCancellable) {
                // 尚未开始就被取消的子代理也要有终态，不留下“运行中”。
                lock.withLock {
                    runs.replaceAll { run ->
                        if (run.status.isActive) run.copy(status = SubagentStatus.CANCELLED, currentTool = null, liveOutputKey = null, endedAt = clock()) else run
                    }
                }
                val finished = batch()
                store.finish(finished)
                // 子代理用量计入父会话本轮，由父轮次在工具结束后汇入其助手消息。
                store.add(
                    parent.id,
                    TokenUsage(
                        input = finished.runs.sumOf { it.inputTokens.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        output = finished.runs.sumOf { it.outputTokens.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        total = finished.runs.sumOf { it.totalTokens.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        estimated = finished.runs.any { it.tokensEstimated },
                    ),
                )
            }
        }
    }
}
