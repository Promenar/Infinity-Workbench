package com.promenar.nexara.data.agent

import com.promenar.nexara.R

/**
 * 预置 Agent 身份表：把稳定的预置 id 映射到本地化资源 key。
 *
 * 仅服务于「未定制」预置 Agent 的显示叠加层（见 [PresetAgentDisplay]）。
 * Room 同一行保存 name/description 原文与字段级定制标记，
 * 显示层仅在对应字段未定制时叠加当前 Locale 资源。
 */
object PresetAgents {
    /** 预置 Agent 的稳定 id 集合。 */
    val IDS: Set<String> = setOf("default", "coder", "writer")

    fun isPreset(id: String): Boolean = id in IDS

    /** 与 Locale 无关的 Room 新装稳定 fallback；实际显示始终走当前 Activity 资源。 */
    fun fallbackName(id: String): String = when (id) {
        "default" -> "Nexara Assistant"
        "coder" -> "Coding Expert"
        "writer" -> "Creative Writer"
        else -> id
    }

    fun fallbackDescription(id: String): String = when (id) {
        "default" -> "General AI assistant with streaming chat and knowledge retrieval"
        "coder" -> "Full-stack development and software architecture specialist"
        "writer" -> "Creative writing, translation, and editing specialist"
        else -> ""
    }

    /** 返回预置 name 的 @StringRes；未知 id 返回 0。 */
    fun nameRes(id: String): Int = when (id) {
        "default" -> R.string.preset_agent_default_name
        "coder" -> R.string.preset_agent_coder_name
        "writer" -> R.string.preset_agent_writer_name
        else -> 0
    }

    /** 返回预置 description 的 @StringRes；未知 id 返回 0。 */
    fun descRes(id: String): Int = when (id) {
        "default" -> R.string.preset_agent_default_desc
        "coder" -> R.string.preset_agent_coder_desc
        "writer" -> R.string.preset_agent_writer_desc
        else -> 0
    }
}
