package com.promenar.infinity.data.avatar

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AvatarSymbolCatalogTest {
    @Test
    fun `目录数量分类码位与必含符号满足契约`() {
        val symbols = AvatarSymbolCatalog.symbols
        assertTrue(symbols.size in 380..420)
        assertEquals(symbols.size, symbols.map { it.name }.toSet().size)
        assertEquals(symbols.size, symbols.map { it.codepoint }.toSet().size)
        AvatarSymbolCategory.entries.forEach { category -> assertTrue(AvatarSymbolCatalog.inCategory(category).size >= 30) }
        assertEquals(symbols.sortedBy { it.category.ordinal }, symbols)
        assertTrue(AvatarSymbolCatalog.popular.size in 16..24)
        assertTrue(symbols.containsAll(AvatarSymbolCatalog.popular))
        (com.promenar.infinity.domain.avatar.AgentAvatarCodec.LEGACY_SYMBOLS.values +
            listOf("auto_awesome", "all_inclusive", "terminal", "ink_pen")).forEach {
            assertNotNull(AvatarSymbolCatalog.find(it))
            assertTrue(AvatarSymbolCatalog.contains(it))
        }
    }

    @Test
    fun `中英文全角大小写多词均可搜索`() {
        assertTrue(AvatarSymbolCatalog.search("机器人").any { it.name == "smart_toy" })
        assertTrue(AvatarSymbolCatalog.search("  ＴＥＲＭＩＮＡＬ  ").any { it.name == "terminal" })
        assertTrue(AvatarSymbolCatalog.search("命令行 shell").any { it.name == "terminal" })
        assertTrue(AvatarSymbolCatalog.search("smart toy").any { it.name == "smart_toy" })
        assertTrue(AvatarSymbolCatalog.search("terminal 不存在的词").isEmpty())
        assertTrue(AvatarSymbolCatalog.search("  \t ").isEmpty())
    }

    @Test
    fun `精确匹配先于前缀包含且同档保持目录顺序`() {
        val results = AvatarSymbolCatalog.search("code")
        assertEquals("code", results.first().name)
        assertTrue(results.indexOfFirst { it.name == "code_blocks" } < results.indexOfFirst { it.name == "folder_code" })
        val expected = AvatarSymbolCatalog.symbols.filter { "a" in it.name.replace('_', ' ') ||
            "a" in it.labelEn.lowercase() || it.keywords.any { word -> "a" in word.lowercase() } }
        assertEquals(expected.toSet(), AvatarSymbolCatalog.search("a").toSet())
        val light = AvatarSymbolCatalog.search("light")
        assertEquals("light", light.first().name)
        assertTrue(light.indexOfFirst { it.name == "lightbulb" } < light.indexOfFirst { it.name == "nightlight" })
        val photos = AvatarSymbolCatalog.search("photo")
        assertEquals(listOf("photo_camera", "photo_library", "photo_filter"), photos.filter {
            it.name in setOf("photo_camera", "photo_library", "photo_filter")
        }.map { it.name })
    }
}
