package com.promenar.nexara.data.rag

import android.content.ContentResolver
import android.net.Uri
import org.apache.poi.xwpf.usermodel.XWPFDocument

object DocumentImporter {

    data class WordResult(val paragraphs: List<String>, val rawText: String)

    /**
     * 从 Word (.docx) 文档提取纯文本。
     * 遍历段落提取文本，表格内容标记为 [TABLE]。
     */
    fun extractWord(uri: Uri, resolver: ContentResolver): String {
        return try {
            val inputStream = resolver.openInputStream(uri)
                ?: return "[Error] Cannot open Word document"

            val document = XWPFDocument(inputStream)
            inputStream.close()

            val sb = StringBuilder()
            var inTable = false

            for (element in document.bodyElements) {
                when (element.elementType.name) {
                    "PARAGRAPH" -> {
                        if (inTable) {
                            sb.appendLine()
                            inTable = false
                        }
                        val paragraph = element as org.apache.poi.xwpf.usermodel.XWPFParagraph
                        val text = paragraph.text.trim()
                        if (text.isNotBlank()) {
                            sb.appendLine(text)
                        }
                    }
                    "TABLE" -> {
                        inTable = true
                        val table = element as org.apache.poi.xwpf.usermodel.XWPFTable
                        sb.appendLine()
                        for (row in table.rows) {
                            val cells = row.tableCells.map { it.text.trim() }
                            sb.appendLine("[TABLE] ${cells.joinToString(" | ")}")
                        }
                    }
                }
            }

            if (sb.isEmpty()) {
                // 回退：直接调用 XWPFWordExtractor
                try {
                    val extractor = org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)
                    extractor.text.ifBlank { "[Empty] Word 文档无可提取文本" }
                } catch (_: Exception) {
                    "[Empty] Word 文档无可提取文本"
                }
            } else {
                sb.toString().trim()
            }
        } catch (e: Exception) {
            "[Error] Word 解析失败: ${e.message?.take(80)}"
        }
    }
}
