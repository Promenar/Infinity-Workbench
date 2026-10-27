package com.promenar.nexara.data.branch

import com.promenar.nexara.domain.branch.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** 工具参数与卡片答案共用校验，省略审批模式时在创建卡片前固定主会话快照。 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object BranchPlanProtocol {
    private val json = Json { encodeDefaults = true }
    fun decode(arguments: String, parentApprovalMode: String): Result<BranchPlan> = runCatching {
        val element = try { Json.parseToJsonElement(arguments) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("工作方案必须是合法 JSON") }
        val root = element as? JsonObject ?: throw IllegalArgumentException("工作方案参数根节点必须是对象")
        require(root.keys.all { it in setOf("title", "goal", "steps", "exclusions", "workMode", "worktree", "communication", "contextMode", "approvalMode") }) { "工作方案含未声明的字段" }
        val plan = try { json.decodeFromJsonElement<BranchPlan>(JsonObject(root +
            ("approvalMode" to (root["approvalMode"] ?: JsonPrimitive(parentApprovalMode))))) }
        catch (failure: kotlinx.serialization.MissingFieldException) { throw IllegalArgumentException("缺少工作方案必填字段：${failure.missingFields.joinToString()}") }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("工作方案字段类型或枚举不合法，请核对工具定义") }
        validate(plan)?.let { throw IllegalArgumentException(it) }
        plan
    }
    fun encode(plan: BranchPlan): String = json.encodeToString(plan)
    fun validate(plan: BranchPlan): String? = when {
        plan.title.isBlank() || plan.title.codePointCount(0, plan.title.length) > BranchLimits.MAX_TITLE_CHARS -> "title 必须为 1–60 个字符"
        plan.goal.isBlank() -> "goal 必须包含目标与验收标准"
        plan.steps.isEmpty() || plan.steps.size > BranchLimits.MAX_STEPS || plan.steps.any(String::isBlank) -> "steps 必须为 1–12 个非空步骤"
        plan.approvalMode !in setOf("workspace", "full_access") -> "approvalMode 必须为 workspace 或 full_access"
        plan.workMode == BranchWorkMode.WORKTREE && plan.worktree?.reason?.isBlank() == true -> "worktree.reason 必须说明建议隔离工作区的理由"
        plan.workMode == BranchWorkMode.WORKTREE && plan.worktree == null -> "worktree 工作方式必须提供 branch、base、directory"
        plan.worktree?.let { it.branch.isBlank() || it.base.isBlank() || it.directory.isBlank() ||
            listOf(it.branch, it.base, it.directory).any { value -> value.any(Character::isISOControl) } ||
            it.branch.startsWith('-') || it.base.startsWith('-') || it.directory.startsWith('-') } == true -> "worktree 分支、基线或目录不合法"
        plan.worktree != null -> runCatching { BranchWorktreeCommands.plan("/workspace", plan.copy(workMode = BranchWorkMode.WORKTREE)) }.exceptionOrNull()?.message
        else -> null
    }
    fun validateAnswer(plan: BranchPlan, answer: BranchPlanAnswer): String? = when {
        answer.branchSessionId != null -> "分支 id 由后端分配"
        else -> validate(plan.copy(workMode = answer.workMode, contextMode = answer.contextMode, approvalMode = answer.approvalMode))
    }
    fun result(plan: BranchPlan, answer: BranchPlanAnswer) = buildJsonObject {
        put("status", if (answer.decision == BranchDecision.DECLINE) "declined" else "success")
        put("answer", json.encodeToJsonElement(answer))
        put("plan", json.encodeToJsonElement(plan.copy(workMode = answer.workMode, contextMode = answer.contextMode, approvalMode = answer.approvalMode)))
        put("message", when (answer.decision) {
            BranchDecision.DISPATCH -> "已派发到分支会话"
            BranchDecision.HERE -> "用户选择在主会话按此方案执行"
            BranchDecision.DECLINE -> "用户拒绝了工作方案"
        })
    }.toString()
}
