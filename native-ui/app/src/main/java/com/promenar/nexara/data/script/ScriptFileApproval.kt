package com.promenar.nexara.data.script

import com.promenar.nexara.domain.tool.ToolRisk
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ScriptFileApproval(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val risk: ToolRisk,
    val target: String,
    val preview: String,
)

data class ScriptApprovalDecision(val approved: Boolean, val remember: Boolean = false)

/** 仅保存正在运行的审批；取消执行即撤销等待，进程恢复不重放文件副作用。 */
class ScriptFileApprovalBroker {
    private val decisions = ConcurrentHashMap<String, CompletableDeferred<ScriptApprovalDecision>>()
    private val _pending = MutableStateFlow<List<ScriptFileApproval>>(emptyList())
    val pending = _pending.asStateFlow()

    suspend fun request(sessionId: String, toolName: String, risk: ToolRisk, target: String, preview: String): ScriptApprovalDecision {
        val item = ScriptFileApproval(UUID.randomUUID().toString(), sessionId, toolName, risk, target, preview.take(1024))
        val decision = CompletableDeferred<ScriptApprovalDecision>()
        decisions[item.id] = decision
        _pending.update { it + item }
        return try { decision.await() }
        finally { decisions.remove(item.id); _pending.update { items -> items.filterNot { it.id == item.id } } }
    }

    fun resolve(id: String, approved: Boolean, remember: Boolean = false) {
        decisions[id]?.complete(ScriptApprovalDecision(approved, approved && remember))
    }
}
