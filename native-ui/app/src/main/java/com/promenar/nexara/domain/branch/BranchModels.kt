package com.promenar.nexara.domain.branch

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 分支的工作目录：共用主会话工作区，或按方案新建 git worktree。 */
@Serializable
enum class BranchWorkMode {
    @SerialName("shared") SHARED,
    @SerialName("worktree") WORKTREE,
}

/** 分支开局带多少上下文：只带方案，或额外带主会话的压缩摘要与最近若干条消息。 */
@Serializable
enum class BranchContextMode {
    @SerialName("fresh") FRESH,
    @SerialName("inherit") INHERIT,
}

/** 方案中的 worktree：批准方案即批准据此生成的那一条 `git worktree add` 命令。 */
@Serializable
data class BranchWorktree(
    /** 新分支名，例如 `feat/billing-tests`。 */
    val branch: String,
    /** 基线提交或分支。 */
    val base: String,
    /** worktree 目录，相对主会话工作区或绝对路径。 */
    val directory: String,
    /** Agent 建议新建 worktree 的理由，展示在方案卡片上。 */
    val reason: String = "",
)

/**
 * `session_branch` 提交的工作方案。[approvalMode] 取值同 `Session.executionMode`（`workspace` / `full_access`），
 * Agent 省略时取主会话当前的执行模式。
 */
@Serializable
data class BranchPlan(
    val title: String,
    val goal: String,
    val steps: List<String> = emptyList(),
    val exclusions: String = "",
    val workMode: BranchWorkMode = BranchWorkMode.SHARED,
    val worktree: BranchWorktree? = null,
    val communication: String = "",
    val contextMode: BranchContextMode = BranchContextMode.FRESH,
    val approvalMode: String,
)

/** 用户在方案卡片上的选择。 */
@Serializable
enum class BranchDecision {
    /** 派发到分支会话。 */
    @SerialName("dispatch") DISPATCH,
    /** 主 Agent 在主会话里按同一方案自己执行。 */
    @SerialName("here") HERE,
    /** 不需要。 */
    @SerialName("decline") DECLINE,
}

/**
 * 方案卡片的处理结果，原样作为 `session_branch` 的工具结果交回。
 * 三项可在卡片上改动的取值以用户最终选择为准；[branchSessionId] 只在派发成功后存在。
 */
@Serializable
data class BranchPlanAnswer(
    val decision: BranchDecision,
    val workMode: BranchWorkMode,
    val contextMode: BranchContextMode,
    val approvalMode: String,
    val branchSessionId: String? = null,
)

/** 会话中正在等待用户处理的方案：定位到所在助手消息与工具调用，界面据此渲染方案卡片。 */
data class PendingBranchPlan(
    val sessionId: String,
    val assistantMessageId: String,
    val toolCallId: String,
    val plan: BranchPlan,
    /** 主会话当前的执行模式，卡片据此提示分支的审批模式是否与主会话不同。 */
    val parentApprovalMode: String,
)

/** 分支在主会话卡片、首页列表与内屏会话列上的状态。 */
enum class BranchActivity {
    GENERATING,
    QUEUED,
    WAITING_INPUT,
    WAITING_APPROVAL,
    /** 没有在生成，也还没有完成过一轮。 */
    IDLE,
    /** 至少完成了一轮，当前空闲。 */
    ROUND_DONE,
}

/** 主会话下的一个分支。 */
data class BranchSummary(
    val sessionId: String,
    val parentSessionId: String,
    val title: String,
    val activity: BranchActivity,
    /** 绑定的 worktree 分支名；共用工作区时为空。 */
    val worktreeBranch: String? = null,
    /** 已完成的轮数，卡片据此显示“第 N 轮完成”。 */
    val completedRounds: Int = 0,
    val updatedAt: Long,
)

/** 会话记录中的一条会话间消息，界面渲染为“已发给「…」”或“来自分支「…」/来自主会话”。 */
@Serializable
data class SessionMessageEntry(
    val direction: Direction,
    /** 对方会话或子代理 id。 */
    val peerId: String,
    /** 对方相对本会话的关系，决定行首文字。 */
    val peerRelation: PeerRelation,
    /** 对方当时的标题，对方删除后仍能显示。 */
    val peerTitle: String,
    val text: String,
    val at: Long,
) {
    @Serializable
    enum class Direction { @SerialName("sent") SENT, @SerialName("received") RECEIVED }

    @Serializable
    enum class PeerRelation { @SerialName("parent") PARENT, @SerialName("branch") BRANCH, @SerialName("subagent") SUBAGENT }
}

/** 仓库的一个 worktree，Git 页据此列出并标出由哪个会话在用。 */
data class WorktreeEntry(
    val path: String,
    val branch: String?,
    val head: String,
    val isMain: Boolean,
    val sessionId: String? = null,
    val sessionTitle: String? = null,
)

/** 界面对分支的读取与操作，由应用级分支服务实现；预览与测试中为空。 */
interface BranchControls {
    /** 主会话下的全部分支，按创建先后排序。 */
    fun observeBranches(parentSessionId: String): Flow<List<BranchSummary>>

    /** 会话中等待处理的方案；没有时为 null。 */
    fun observePendingPlan(sessionId: String): Flow<PendingBranchPlan?>

    /** 用户在方案卡片上做出选择；派发时创建分支并立即开始生成，本轮随后继续。 */
    suspend fun resolvePlan(pending: PendingBranchPlan, answer: BranchPlanAnswer)

    /** 删除分支会话前用户确认移除其 worktree：执行 `git worktree remove`，确认即视为批准。 */
    suspend fun removeWorktree(branchSessionId: String): Result<Unit>

    /** 工作区所在仓库的全部 worktree。 */
    suspend fun listWorktrees(sessionId: String): Result<List<WorktreeEntry>>
}

object BranchLimits {
    const val TOOL_NAME = "session_branch"
    const val LIST_TOOL_NAME = "session_list"
    /** 没有用户输入时被会话消息连续唤醒的上限，之后只写入不唤醒。 */
    const val MAX_AUTO_WAKES = 8
    const val MAX_TITLE_CHARS = 60
    const val MAX_STEPS = 12
}
