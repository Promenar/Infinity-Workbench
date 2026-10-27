package com.promenar.nexara.data.subagent

import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.domain.tool.ToolRisk
import kotlinx.serialization.Serializable

@Serializable
enum class SubagentStatus { RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED, INTERRUPTED;
    val isActive: Boolean get() = this == RUNNING || this == WAITING_APPROVAL
}

/** 子代理可用工具的范围；两档都不含 `agent_task` 与修改父会话状态的工具。 */
@Serializable
enum class SubagentToolProfile(val wireName: String) {
    READ_ONLY("read_only"),
    INHERIT("inherit");

    companion object {
        fun parse(raw: String?): SubagentToolProfile? = entries.firstOrNull { it.wireName == raw?.trim()?.lowercase() }
    }
}

/** 预置子代理：固定名称、工具档位与额外说明。 */
enum class SubagentPreset(val wireName: String, val profile: SubagentToolProfile, val instruction: String) {
    EXPLORER(
        "explorer",
        SubagentToolProfile.READ_ONLY,
        "你是只读的探索者：查找、阅读并归纳信息，不修改任何文件或外部系统。结论中注明依据的文件路径或来源。",
    );

    companion object {
        fun parse(raw: String?): SubagentPreset? = entries.firstOrNull { it.wireName == raw?.trim()?.lowercase() }
    }
}

@Serializable
data class SubagentEvent(
    val kind: Kind,
    val text: String = "",
    val toolName: String? = null,
    val failed: Boolean = false,
    val at: Long,
) {
    @Serializable
    enum class Kind { TEXT, TOOL_CALL, TOOL_RESULT, APPROVAL, ERROR }
}

/** 一个子代理的任务、过程与交回结果；只保存展示所需内容，正文按上限截断。 */
@Serializable
data class SubagentRun(
    val index: Int,
    val preset: String? = null,
    val description: String,
    val prompt: String,
    val toolProfile: SubagentToolProfile,
    val modelId: String,
    /** 继承父会话的推理强度（取值同 [com.promenar.nexara.data.model.InferenceParams.reasoningEffort]）。 */
    val reasoningEffort: String? = null,
    val maxSteps: Int,
    /** 本子代理的合计 token 上限；父会话设置了任务 token 上限时按子代理数分摊。 */
    val maxTokens: Int = SubagentLimits.MAX_TOTAL_TOKENS,
    val status: SubagentStatus = SubagentStatus.RUNNING,
    val step: Int = 0,
    val currentTool: String? = null,
    /** 正在执行的子代理工具在实时输出存储中的键。 */
    val liveOutputKey: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val events: List<SubagentEvent> = emptyList(),
    val result: String? = null,
    val error: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val tokensEstimated: Boolean = false,
)

/** 父会话一次 `agent_task` 调用派生的全部子代理，按该调用的执行步骤 id 归档。 */
@Serializable
data class SubagentBatch(
    val sessionId: String,
    val stepId: String,
    val runs: List<SubagentRun>,
)

data class SubagentSpec(
    val description: String,
    val prompt: String,
    val preset: SubagentPreset?,
    val toolProfile: SubagentToolProfile,
)

object SubagentLimits {
    const val MAX_PARALLEL = 3
    const val DEFAULT_MAX_STEPS = 12
    const val MAX_TOTAL_TOKENS = 400_000
    const val MAX_OUTPUT_TOKENS = 8_192
    const val MAX_RESULT_CHARS = 12_000
    const val MAX_EVENT_CHARS = 2_000
    const val MAX_EVENTS = 160
    const val MAX_PROMPT_CHARS = 16_000
    const val APPROVAL_TIMEOUT_MS = 10 * 60_000L
    const val TOOL_NAME = "agent_task"
    /** 子代理只交回文本，看不到媒体，排除查看媒体的工具以免据此编造内容。 */
    private val EXCLUDED_TOOLS = setOf(TOOL_NAME, "view_media", com.promenar.nexara.domain.ask.AskUserLimits.TOOL_NAME)

    /** 子代理不能再派生子代理，也不能改动父会话的计划等内部状态。 */
    fun toolset(parentTools: List<ProtocolTool>, profile: SubagentToolProfile): List<ProtocolTool> = parentTools.filter { tool ->
        tool.function.name !in EXCLUDED_TOOLS && tool.runtimeToolId !in EXCLUDED_TOOLS && tool.risk != ToolRisk.SESSION_STATE &&
            (profile == SubagentToolProfile.INHERIT || tool.risk == ToolRisk.SAFE_READ)
    }

    /** 父会话的任务 token 上限（0 表示不限）按子代理数分摊，并不超过单个子代理的固定上限。 */
    fun tokenBudget(taskTokenLimit: Long, count: Int): Int =
        if (taskTokenLimit <= 0) MAX_TOTAL_TOKENS
        else (taskTokenLimit / count.coerceAtLeast(1)).coerceIn(1, MAX_TOTAL_TOKENS.toLong()).toInt()
}
