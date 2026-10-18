package com.promenar.nexara.ui.common

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownNestedWideContentTest {
    @Test
    fun `引用与列表中的代码通过第一方宽内容宿主保留横向可达性`() {
        for (source in listOf(
            "> ```kotlin\n> println(\"${"long-code-".repeat(20)}\")\n> ```",
            "- 代码\n\n  ```kotlin\n  println(\"${"long-code-".repeat(20)}\")\n  ```",
            ">     ${"code ".repeat(30)}",
        )) {
            val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
            assertTrue(source, root.containsWideContentDescendant())
        }
    }

    @Test
    fun `普通长段落不进入横向滚动容器`() {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString("普通正文".repeat(80))
        assertFalse(root.containsWideContentDescendant())
    }
}
