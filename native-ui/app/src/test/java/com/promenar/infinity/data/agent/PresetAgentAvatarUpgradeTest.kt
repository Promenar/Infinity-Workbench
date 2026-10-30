package com.promenar.infinity.data.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PresetAgentAvatarUpgradeTest {
    @Test
    fun `只升级完整出厂组合且兼容选择符与颜色大小写`() {
        listOf(Triple("default", "✨", "#C0C1FF"), Triple("coder", "💻", "#6366F1"), Triple("writer", "📝", "#10B981"))
            .forEach { (id, icon, color) ->
                val upgraded = PresetAgentAvatarUpgrade.replacement(id, icon, color)!!
                assertEquals(upgraded, PresetAgentAvatarUpgrade.replacement(id, "$icon\uFE0F", color.lowercase()))
                assertNull(PresetAgentAvatarUpgrade.replacement(id, "sym:star", color))
                assertNull(PresetAgentAvatarUpgrade.replacement(id, icon, "#000000"))
                assertNull(PresetAgentAvatarUpgrade.replacement(id, upgraded.icon, upgraded.color))
            }
        assertEquals(PresetAgentAvatarUpgrade.Avatar("sym:all_inclusive", "#6366F1"),
            PresetAgentAvatarUpgrade.replacement("default", "✨", "#C0C1FF"))
        assertEquals(PresetAgentAvatarUpgrade.Avatar("sym:terminal", "#3B82F6"),
            PresetAgentAvatarUpgrade.replacement("coder", "💻", "#6366F1"))
        assertEquals(PresetAgentAvatarUpgrade.Avatar("sym:ink_pen", "#10B981"),
            PresetAgentAvatarUpgrade.replacement("writer", "📝", "#10B981"))
        assertNull(PresetAgentAvatarUpgrade.replacement("custom", "✨", "#C0C1FF"))
        assertNull(PresetAgentAvatarUpgrade.replacement("default", "✨", "invalid"))
    }
}
