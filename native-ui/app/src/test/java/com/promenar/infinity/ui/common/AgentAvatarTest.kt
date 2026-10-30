package com.promenar.infinity.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Hct
import com.promenar.infinity.domain.avatar.AgentAvatarPalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentAvatarTest {

    private val fallback = Color(0xFF3D9BE9)

    @Test
    fun `parses six and eight digit hex and trims whitespace`() {
        assertEquals(Color(0xFFC0C1FF), parseAgentColor("#C0C1FF"))
        assertEquals(Color(0x80112233), parseAgentColor("#80112233"))
        assertEquals(Color(0xFFC0C1FF), parseAgentColor(" #c0c1ff "))
    }

    @Test
    fun `rejects malformed colors`() {
        assertNull(parseAgentColor(""))
        assertNull(parseAgentColor("#12345"))
        assertNull(parseAgentColor("#GGGGGG"))
    }

    @Test
    fun `symbol tones keep readable contrast for every swatch and legacy color in both themes`() {
        val colors = AgentAvatarPalette.swatches.map { it.hex } + listOf("#C0C1FF", "#FFFFFF", "#000000", "not-a-color")
        colors.forEach { hex ->
            listOf(false, true).forEach { dark ->
                val tones = agentAvatarTones(hex, dark, fallback)
                val ratio = contrast(tones.container, tones.content)
                assertTrue(ratio >= 4.5, "$hex dark=$dark 对比度只有 $ratio")
                if (dark) assertTrue(tones.container.luminance() < tones.content.luminance(), "$hex 深色主题应是深底浅字")
                else assertTrue(tones.container.luminance() > tones.content.luminance(), "$hex 浅色主题应是浅底深字")
            }
        }
    }

    @Test
    fun `light lavender legacy default no longer renders white on pale fill`() {
        val tones = agentAvatarTones("#C0C1FF", dark = false, fallback = fallback)
        assertNotEquals(Color.White, tones.content)
    }

    @Test
    fun `initial takes the first grapheme and uppercases latin letters`() {
        assertEquals("编", agentAvatarInitial("编程专家"))
        assertEquals("A", agentAvatarInitial("  atlas"))
        assertEquals("👩‍💻", agentAvatarInitial("👩‍💻 Dev"))
        assertEquals("", agentAvatarInitial("   "))
    }

    @Test
    fun `generated style is deterministic and stays in range`() {
        assertEquals(generatedAvatarStyle("coder"), generatedAvatarStyle("coder"))
        val styles = (0 until 200).map { generatedAvatarStyle("agent-$it") }
        styles.forEach {
            assertTrue(kotlin.math.abs(it.hueShift) in 25..54)
            assertTrue(it.angleDegrees in 110..249)
        }
        assertTrue(styles.map { it.hueShift }.toSet().size > 20, "种子应带来足够的色相差异")
        assertTrue(styles.any { it.hueShift < 0 } && styles.any { it.hueShift > 0 }, "偏移方向应由种子决定")
    }

    @Test
    fun `generated gradient end never lands in the muddy yellow green band`() {
        AgentAvatarPalette.swatches.map { it.hex }.forEach { hex ->
            (0 until 200).forEach { i ->
                listOf(false, true).forEach { dark ->
                    val end = generatedAvatarColors(hex, "agent-$i", dark, fallback).second
                    val hue = Hct.fromInt(end.toArgb()).hue
                    assertTrue(hue !in MUDDY_HUES, "$hex agent-$i dark=$dark 终点色相 $hue 落在黄绿区")
                }
            }
        }
    }

    @Test
    fun `seed hash matches reference fnv1a values`() {
        assertEquals(2166136261u, avatarSeedHash(""))
        assertEquals(0xE40C292Cu, avatarSeedHash("a"))
    }

    @Test
    fun `generated colors differ between seeds and between themes`() {
        val a = generatedAvatarColors("#6366F1", "a", dark = false, fallback = fallback)
        val b = generatedAvatarColors("#6366F1", "b", dark = false, fallback = fallback)
        val aDark = generatedAvatarColors("#6366F1", "a", dark = true, fallback = fallback)
        assertEquals(a.first, b.first)
        assertNotEquals(a.second, b.second)
        assertTrue(aDark.first.luminance() < a.first.luminance())
    }

    private companion object {
        /** HCT 色相约 85–130 的黄绿段：压到渐变终点的低 tone 后呈橄榄色。 */
        val MUDDY_HUES = 85.0..130.0
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (maxOf(la, lb) / minOf(la, lb)).toDouble()
    }
}
