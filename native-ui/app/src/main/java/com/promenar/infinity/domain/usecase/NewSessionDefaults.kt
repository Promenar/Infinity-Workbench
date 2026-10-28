package com.promenar.infinity.domain.usecase

import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.ExecutionModeCodec

fun defaultSessionRagOptions(agent: Agent?, global: RagConfiguration): RagOptions =
    (agent?.memoryPolicy ?: AgentMemoryPolicy()).defaultsFor(RagOptions(
        enableMemory = global.enableMemory,
        enableDocs = global.enableDocs,
        enableRerank = global.enableRerank,
        enableKnowledgeGraph = global.enableKnowledgeGraph,
    ))

/** 首页与斜杠命令共用的会话初值；时间和标识由调用方提供。 */
fun newSessionDefaults(
    sessionId: String,
    agentId: String,
    agent: Agent?,
    fallbackModelId: String?,
    globalRagConfig: RagConfiguration,
    now: Long,
): Session = Session(
    id = sessionId,
    agentId = agentId,
    modelId = agent?.modelId?.takeIf { it.isNotBlank() } ?: fallbackModelId,
    executionMode = agent?.executionMode?.let(ExecutionModeCodec::serialize) ?: "workspace",
    options = SessionOptions(toolsEnabled = agent?.toolsEnabledByDefault ?: true),
    activeMcpServerIds = agent?.mcpServerIds.orEmpty(),
    workspaceId = agent?.defaultWorkspaceId,
    ragOptions = defaultSessionRagOptions(agent, globalRagConfig),
    loopStatus = com.promenar.infinity.data.model.LoopStatus.COMPLETED,
    createdAt = now,
    updatedAt = now,
)
