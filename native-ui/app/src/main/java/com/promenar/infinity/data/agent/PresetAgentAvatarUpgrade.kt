package com.promenar.infinity.data.agent

import com.promenar.infinity.data.local.db.dao.AgentDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PresetAgentAvatarUpgrade {
    data class Avatar(val icon: String, val color: String)
    private data class Rule(val previous: Avatar, val replacement: Avatar)
    private val rules = mapOf(
        "default" to Rule(Avatar("✨", "#C0C1FF"), Avatar("sym:all_inclusive", "#6366F1")),
        "coder" to Rule(Avatar("💻", "#6366F1"), Avatar("sym:terminal", "#3B82F6")),
        "writer" to Rule(Avatar("📝", "#10B981"), Avatar("sym:ink_pen", "#10B981")),
    )

    fun replacement(id: String, icon: String, color: String): Avatar? {
        val rule = rules[id] ?: return null
        return rule.replacement.takeIf {
            icon.replace("\uFE0F", "") == rule.previous.icon && color.equals(rule.previous.color, ignoreCase = true)
        }
    }
}

/** 比较并仅更新头像两列，避免启动时读到的旧副本覆盖用户编辑。 */
suspend fun upgradePresetAgentAvatars(dao: AgentDao) = withContext(Dispatchers.IO) {
    listOf("default", "coder", "writer").forEach { id ->
        val current = dao.getById(id) ?: return@forEach
        val target = PresetAgentAvatarUpgrade.replacement(id, current.icon, current.color) ?: return@forEach
        dao.upgradePresetAvatarIfUnchanged(id, current.icon, current.color, target.icon, target.color)
    }
}
