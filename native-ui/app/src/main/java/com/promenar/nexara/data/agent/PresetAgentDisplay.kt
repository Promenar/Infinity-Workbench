package com.promenar.nexara.data.agent

import com.promenar.nexara.domain.model.Agent

/**
 * 预置 Agent 显示叠加层（纯函数，无 Android 依赖，可纯 JVM 单测）。
 *
 * 判别规则：
 * - 预置 id 且字段未定制 → [TextSource.Resource]（UI 按当前 Locale 实时资源化）；
 * - 字段已定制或非预置 → [TextSource.Literal]（显示 DB 原文，用户内容不被覆盖）。
 *
 * 真正的 `Context.getString(...` 求值放在 UI 层（`AgentHubScreen`），此处只决定数据来源，
 * 从而保持本对象可测性与 Compose 重组友好。
 */
object PresetAgentDisplay {

    sealed interface TextSource {
        data class Resource(val res: Int) : TextSource
        data class Literal(val value: String) : TextSource
    }

    data class Fields(val name: TextSource, val description: TextSource)

    fun resolve(agent: Agent): Fields {
        if (!PresetAgents.isPreset(agent.id)) {
            return Fields(TextSource.Literal(agent.name), TextSource.Literal(agent.description))
        }
        return Fields(
            name = if (agent.nameCustomized) {
                TextSource.Literal(agent.name)
            } else {
                TextSource.Resource(PresetAgents.nameRes(agent.id))
            },
            description = if (agent.descriptionCustomized) {
                TextSource.Literal(agent.description)
            } else {
                TextSource.Resource(PresetAgents.descRes(agent.id))
            },
        )
    }
}
