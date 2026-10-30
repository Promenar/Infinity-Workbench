package com.promenar.infinity.ui.hub

import com.promenar.infinity.data.avatar.AvatarSymbolCatalog
import com.promenar.infinity.data.avatar.AvatarSymbolCategory
import com.promenar.infinity.domain.avatar.AgentAvatarPalette
import com.promenar.infinity.domain.avatar.AgentAvatarSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentAvatarPickerLogicTest {

    @Test
    fun `search query takes precedence over category filter`() {
        val coding = AvatarSymbolFilter.Category(AvatarSymbolCategory.CODING)
        assertEquals(AvatarSymbolCatalog.search("翻译"), avatarPickerSymbols("翻译", coding))
        assertEquals(AvatarSymbolCatalog.inCategory(AvatarSymbolCategory.CODING), avatarPickerSymbols("  ", coding))
        assertEquals(AvatarSymbolCatalog.popular, avatarPickerSymbols("", AvatarSymbolFilter.Popular))
        assertEquals(AvatarSymbolCatalog.symbols, avatarPickerSymbols("", AvatarSymbolFilter.All))
    }

    @Test
    fun `filter keys round trip and unknown keys fall back to popular`() {
        AvatarSymbolFilter.chips.forEach { assertEquals(it, AvatarSymbolFilter.fromKey(AvatarSymbolFilter.key(it))) }
        assertEquals(AvatarSymbolFilter.Popular, AvatarSymbolFilter.fromKey("removed_category"))
        assertEquals(AvatarSymbolCategory.entries.size + 2, AvatarSymbolFilter.chips.size)
    }

    @Test
    fun `picker opens on the category that contains the current symbol`() {
        val popular = AvatarSymbolCatalog.popular.first()
        assertEquals(AvatarSymbolFilter.Popular, initialAvatarFilter(AgentAvatarSpec.Symbol(popular.name)))
        val outside = AvatarSymbolCatalog.symbols.first { candidate -> AvatarSymbolCatalog.popular.none { it.name == candidate.name } }
        assertEquals(AvatarSymbolFilter.Category(outside.category), initialAvatarFilter(AgentAvatarSpec.Symbol(outside.name)))
        assertEquals(AvatarSymbolFilter.Popular, initialAvatarFilter(AgentAvatarSpec.Generated()))
    }

    @Test
    fun `generated batches start with the default seed and never repeat within a batch`() {
        val first = generatedAvatarSeeds(0)
        assertEquals(GENERATED_BATCH_SIZE, first.size)
        assertEquals("", first.first())
        assertEquals(first.size, first.toSet().size)
        val second = generatedAvatarSeeds(1)
        assertTrue(second.none { it.isEmpty() })
        assertTrue(second.intersect(first.toSet()).isEmpty())
        assertTrue(second.all { it.length <= 64 })
    }

    @Test
    fun `color options keep a legacy custom color selectable`() {
        val palette = AgentAvatarPalette.swatches.map { it.hex }
        assertEquals(palette, avatarColorOptions("#6366f1").map { it.hex })
        val withCustom = avatarColorOptions("#C0C1FF")
        assertEquals(AvatarColorOption("#C0C1FF", null), withCustom.first())
        assertEquals(palette, withCustom.drop(1).map { it.hex })
        assertEquals(palette, avatarColorOptions("garbage").map { it.hex })
    }
}
