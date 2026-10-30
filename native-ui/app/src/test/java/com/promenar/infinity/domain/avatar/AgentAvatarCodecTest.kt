package com.promenar.infinity.domain.avatar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentAvatarCodecTest {
    @Test
    fun `空值未知值和不合法符号回退默认`() {
        listOf(null, "", "  ", "sym:", "sym:UNKNOWN", "sym:../code", "sym:not_a_catalog_symbol", "unknown")
            .forEach { assertEquals(AgentAvatarSpec.Symbol("auto_awesome"), AgentAvatarCodec.decode(it)) }
    }

    @Test
    fun `所有历史映射兼容变体选择符`() {
        val expected = mapOf("✨" to "auto_awesome", "💻" to "code", "📝" to "edit_note", "🤖" to "smart_toy",
            "🧠" to "psychology", "⭐" to "star", "🔥" to "local_fire_department", "💡" to "lightbulb",
            "⚡" to "bolt", "🎯" to "track_changes", "🌐" to "translate", "A" to "translate")
        assertEquals(expected, AgentAvatarCodec.LEGACY_SYMBOLS)
        expected.forEach { (old, name) ->
            assertEquals(AgentAvatarSpec.Symbol(name), AgentAvatarCodec.decode(old))
            assertEquals(AgentAvatarSpec.Symbol(name), AgentAvatarCodec.decode("$old\uFE0F"))
        }
    }

    @Test
    fun `有效符号与种子经过规范化往返`() {
        listOf(AgentAvatarSpec.Symbol("terminal"), AgentAvatarSpec.Generated(), AgentAvatarSpec.Generated("中文😀seed"))
            .forEach { assertEquals(it, AgentAvatarCodec.decode(AgentAvatarCodec.encode(it))) }
        assertEquals(AgentAvatarSpec.Generated("seed"), AgentAvatarCodec.decode("gen:  seed  "))
        assertEquals(AgentAvatarSpec.Generated("x".repeat(64)), AgentAvatarCodec.decode("gen:${"x".repeat(90)}"))
        assertEquals("gen:", AgentAvatarCodec.NEW_AGENT_DEFAULT)
    }
}
