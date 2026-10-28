package com.promenar.infinity.domain.usecase

import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.agent.KnowledgeFileAccess

/** 新建智能体时可选的一次性模板；创建后写入的都是普通参数，不再保留“类型”。 */
enum class AgentCreationType { CHAT, AGENT }

/** 模板写入的三项参数：新会话默认开启工具、检索方式、知识库文件访问。 */
data class AgentCreationTemplate(
    val toolsEnabledByDefault: Boolean,
    val retrievalMode: AgentRetrievalMode,
    val knowledgeFileAccess: KnowledgeFileAccess,
) {
    /** 其余检索参数保持默认并继承全局设置，只覆盖模板涉及的两项。 */
    fun retrievalConfig(): AgentRetrievalConfig = AgentRetrievalConfig(
        retrievalMode = retrievalMode,
        knowledgeFileAccess = knowledgeFileAccess,
    )
}

fun AgentCreationType.template(): AgentCreationTemplate = when (this) {
    AgentCreationType.CHAT -> AgentCreationTemplate(
        toolsEnabledByDefault = false,
        retrievalMode = AgentRetrievalMode.AUTO,
        knowledgeFileAccess = KnowledgeFileAccess.OFF,
    )
    AgentCreationType.AGENT -> AgentCreationTemplate(
        toolsEnabledByDefault = true,
        retrievalMode = AgentRetrievalMode.TOOL,
        knowledgeFileAccess = KnowledgeFileAccess.ON_REQUEST,
    )
}
