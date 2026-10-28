package com.promenar.infinity.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class AgentAvatarTest {

    @Test
    fun `every preset icon maps to its own vector instead of the default`() {
        AgentPresetIcons.forEach { option ->
            assertSame(option.icon, agentIconVector(option.id), "预设 ${option.id} 映射不一致")
            if (option.id != "✨") {
                assertNotEquals(DefaultAgentIcon, agentIconVector(option.id), "预设 ${option.id} 落入了默认图标")
            }
        }
    }

    @Test
    fun `preset icon ids and vectors are unique`() {
        assertEquals(AgentPresetIcons.size, AgentPresetIcons.map { it.id }.toSet().size)
        assertEquals(AgentPresetIcons.size, AgentPresetIcons.map { it.icon }.toSet().size)
        assertEquals(10, AgentPresetIcons.size)
    }

    @Test
    fun `legacy preset agent icons keep their translate vector`() {
        assertSame(Icons.Rounded.Translate, agentIconVector("🌐"))
        assertSame(Icons.Rounded.Translate, agentIconVector("A"))
    }

    @Test
    fun `unknown icon falls back to the database default icon`() {
        assertSame(DefaultAgentIcon, agentIconVector("🦄"))
        assertSame(DefaultAgentIcon, agentIconVector(""))
        assertSame(agentIconVector("✨"), DefaultAgentIcon)
    }

    @Test
    fun `avatar color uses the stored theme color without blending`() {
        val fallback = Color(0xFF123456)
        assertEquals(Color(0xFFC0C1FF), agentAvatarColor("#C0C1FF", fallback))
        assertEquals(Color(0x80112233), agentAvatarColor("#80112233", fallback))
        assertEquals(Color(0xFFC0C1FF), agentAvatarColor(" #c0c1ff ", fallback))
    }

    @Test
    fun `invalid color text falls back to the supplied color`() {
        val fallback = Color(0xFF123456)
        assertNull(parseAgentColor(""))
        assertNull(parseAgentColor("#12345"))
        assertNull(parseAgentColor("#GGGGGG"))
        assertEquals(fallback, agentAvatarColor("not-a-color", fallback))
    }
}
