package com.promenar.infinity.data.export

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExportLayoutTest {
    @Test fun `未超限仅一张且不加编号`() {
        assertEquals(listOf(ExportSlice(0, 800)), splitExportHeight(800, emptyList()))
        assertEquals(listOf("会话.png"), exportImageNames("会话", 1))
    }

    @Test fun `超长页面优先消息边界并连续覆盖全部内容`() {
        assertEquals(listOf(ExportSlice(0, 18_000), ExportSlice(18_000, 36_000), ExportSlice(36_000, 45_000)),
            splitExportHeight(45_000, listOf(36_000, 18_000, 0, -1, 99_000, 18_000)))
        assertEquals(listOf("会话_01.png", "会话_02.png", "会话_03.png"), exportImageNames("会话", 3))
    }

    @Test fun `无消息边界时硬切且每段不超限`() {
        assertEquals(listOf(ExportSlice(0, 20_000), ExportSlice(20_000, 40_000), ExportSlice(40_000, 40_001)),
            splitExportHeight(40_001, emptyList()))
        (1..100).forEach { size ->
            val slices = splitExportHeight(size * 137, listOf(5, 100, 10_000), 1000)
            assertEquals(0, slices.first().top)
            assertEquals(size * 137, slices.last().bottom)
            assertTrue(slices.all { it.height in 1..1000 })
            assertTrue(slices.zipWithNext().all { (a, b) -> a.bottom == b.top })
        }
        assertThrows(IllegalArgumentException::class.java) { splitExportHeight(10, emptyList(), 0) }
    }

    @Test fun `PDF分页避开短代码块和图片且超大块能完成分页`() {
        assertEquals(listOf(ExportSlice(0, 800), ExportSlice(800, 1800), ExportSlice(1800, 2500)),
            splitExportPdfHeight(2500, listOf(ExportSlice(800, 1200)), 1000))
        assertEquals(listOf(ExportSlice(0, 1000), ExportSlice(1000, 2000), ExportSlice(2000, 2500)),
            splitExportPdfHeight(2500, listOf(ExportSlice(0, 2500)), 1000))
    }

    @Test fun `长中文文件名按UTF8预算截断且保留后缀空间`() {
        val base = exportBaseName("会".repeat(150), 1)
        assertTrue((base + "_9999.png").toByteArray().size < 255)
        assertTrue(base.endsWith("000001") || base.contains("1970"))
    }

    @Test fun `zip文件名清除穿越路径且处理重复与碰撞`() {
        val names = safeExportEntryNames(listOf("../../a.png", "..\\a.png", "a.png", "a_2.png", "..", "/a.png", "A.png"))
        assertEquals(names.size, names.map { it.lowercase() }.distinct().size)
        assertTrue(names.all { '/' !in it && '\\' !in it && ".." !in it && it.isNotBlank() })
    }
}
