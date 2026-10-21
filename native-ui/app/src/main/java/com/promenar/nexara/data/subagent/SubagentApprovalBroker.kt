package com.promenar.nexara.data.subagent

import com.promenar.nexara.domain.tool.ToolExecutionTargets
import com.promenar.nexara.domain.tool.ToolRisk
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

data class SubagentApproval(
    val id: String,
    val sessionId: String,
    val subagentIndex: Int,
    val subagentPreset: String?,
    val subagentDescription: String,
    val toolName: String,
    val risk: ToolRisk,
    /** 可写入白名单的授权目标；无法确定目标时不提供“记住”。 */
    val target: String?,
    val preview: String,
    val requestedAt: Long,
) {
    /** 逐次授权的目标（技能目录、不可恢复删除等）不能记住。 */
    val canRemember: Boolean get() = target?.let(ToolExecutionTargets::canRemember) == true
}

enum class SubagentApprovalOutcome { APPROVED, APPROVED_REMEMBER, DENIED, TIMED_OUT }

/** 子代理运行中出现的审批提醒；应用在后台时才需要系统通知。 */
interface SubagentApprovalNotifier {
    fun notify(approval: SubagentApproval)
    fun cancel(approval: SubagentApproval)

    object None : SubagentApprovalNotifier {
        override fun notify(approval: SubagentApproval) = Unit
        override fun cancel(approval: SubagentApproval) = Unit
    }
}

/**
 * 子代理在原地挂起等待主会话的审批决定；只保存在内存，取消运行即撤销等待。
 * 超过时限未处理按超时返回，由子代理以失败交回主 Agent。
 */
class SubagentApprovalBroker(
    private val timeoutMs: Long = SubagentLimits.APPROVAL_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    var notifier: SubagentApprovalNotifier = SubagentApprovalNotifier.None
    private val decisions = ConcurrentHashMap<String, CompletableDeferred<SubagentApprovalOutcome>>()
    private val _pending = MutableStateFlow<List<SubagentApproval>>(emptyList())
    val pending = _pending.asStateFlow()

    suspend fun request(
        sessionId: String,
        run: SubagentRun,
        toolName: String,
        risk: ToolRisk,
        target: String?,
        preview: String,
    ): SubagentApprovalOutcome {
        val item = SubagentApproval(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            subagentIndex = run.index,
            subagentPreset = run.preset,
            subagentDescription = run.description.take(200),
            toolName = toolName,
            risk = risk,
            target = target,
            preview = if (preview.length <= MAX_PREVIEW_CHARS) preview
            else preview.take(MAX_PREVIEW_CHARS) + "\n…（预览过长已截断，共 ${preview.length} 字符）",
            requestedAt = clock(),
        )
        val decision = CompletableDeferred<SubagentApprovalOutcome>()
        decisions[item.id] = decision
        _pending.update { it + item }
        notifier.notify(item)
        return try {
            withTimeoutOrNull(timeoutMs) { decision.await() } ?: SubagentApprovalOutcome.TIMED_OUT
        } finally {
            decisions.remove(item.id)
            _pending.update { items -> items.filterNot { it.id == item.id } }
            notifier.cancel(item)
        }
    }

    private companion object {
        const val MAX_PREVIEW_CHARS = 16_000
    }

    fun resolve(id: String, approved: Boolean, remember: Boolean = false) {
        val outcome = when {
            !approved -> SubagentApprovalOutcome.DENIED
            remember && _pending.value.firstOrNull { it.id == id }?.canRemember == true -> SubagentApprovalOutcome.APPROVED_REMEMBER
            else -> SubagentApprovalOutcome.APPROVED
        }
        decisions[id]?.complete(outcome)
    }
}
