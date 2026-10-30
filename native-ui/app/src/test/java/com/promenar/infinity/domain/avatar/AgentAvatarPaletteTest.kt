package com.promenar.infinity.domain.avatar

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentAvatarPaletteTest {
    @Test
    fun `色板按契约排列且使用大写十六进制`() {
        assertEquals(listOf("indigo", "blue", "cyan", "emerald", "lime", "yellow", "amber", "orange", "rose",
            "pink", "fuchsia", "violet", "slate"), AgentAvatarPalette.swatches.map { it.key })
        assertEquals(listOf("#6366F1", "#3B82F6", "#06B6D4", "#10B981", "#65A30D", "#EAB308", "#F59E0B",
            "#F97316", "#F43F5E", "#EC4899", "#D946EF", "#8B5CF6", "#64748B"), AgentAvatarPalette.swatches.map { it.hex })
    }

    @Test
    fun `UTF16种子符合FNV1a固定向量且不选灰色`() {
        // 固定向量由独立 Python UTF-16LE 码元实现生成。
        mapOf("" to 1, "a" to 4, "hello" to 3, "😀" to 8).forEach { (seed, index) ->
            assertEquals(AgentAvatarPalette.swatches[index], AgentAvatarPalette.forSeed(seed))
        }
        (0..1000).forEach { i ->
            val first = AgentAvatarPalette.forSeed("智能体$i")
            assertEquals(first, AgentAvatarPalette.forSeed("智能体$i"))
            assertTrue(first in AgentAvatarPalette.swatches.take(12))
        }
    }
}
