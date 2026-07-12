package com.promenar.nexara.data.rag

import com.promenar.nexara.data.local.db.entity.FileEntry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.nio.charset.CodingErrorAction

data class DocumentReferenceExtraction(
    val chunks: List<String>,
    val graphText: String,
    val truncated: Boolean,
)

/** 从工作区文件引用按增量读取，任何格式提取后的 UTF-8 文本最多 16 MiB。 */
class DocumentReferenceExtractor(
    private val chunkSize: Int,
    private val chunkOverlap: Int,
) {
    fun extract(entry: FileEntry): DocumentReferenceExtraction {
        val root = File(entry.physicalRootPath).canonicalFile
        val file = File(root, entry.materializedPath.trimStart('/')).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) {
            throw SecurityException("索引文件超出工作区或不存在")
        }
        if (file.length() > MAX_SOURCE_BYTES) throw IllegalArgumentException("索引源文件超过 100 MiB")
        val collector = IncrementalCollector(chunkSize, chunkOverlap, MAX_EXTRACTED_UTF8_BYTES)
        when (entry.mimeType) {
            "application/pdf" -> extractPdf(file, collector)
            DOCX_MIME -> extractDocx(file, collector)
            in TEXT_MIME_TYPES -> extractText(file, collector)
            else -> throw IllegalArgumentException("不支持索引 MIME: ${entry.mimeType}")
        }
        return collector.finish()
    }

    private fun extractText(file: File, collector: IncrementalCollector) {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        java.io.InputStreamReader(file.inputStream().buffered(), decoder).use { reader ->
            val buffer = CharArray(16 * 1024)
            while (!collector.truncated) {
                val count = reader.read(buffer)
                if (count < 0) break
                collector.append(String(buffer, 0, count))
            }
        }
    }

    private fun extractPdf(file: File, collector: IncrementalCollector) {
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            for (page in 1..document.numberOfPages) {
                if (collector.truncated) break
                stripper.startPage = page
                stripper.endPage = page
                collector.append(stripper.getText(document))
                collector.append("\n")
            }
        }
    }

    private fun extractDocx(file: File, collector: IncrementalCollector) {
        require(file.length() <= MAX_DOCX_SOURCE_BYTES) { "DOCX 源文件超过安全阈值" }
        validateDocxArchive(file)
        file.inputStream().buffered().use { input ->
            XWPFDocument(input).use { document ->
                document.bodyElements.forEach { element ->
                    if (collector.truncated) return@forEach
                    when (element) {
                        is org.apache.poi.xwpf.usermodel.XWPFParagraph -> {
                            collector.append(element.text)
                            collector.append("\n")
                        }
                        is org.apache.poi.xwpf.usermodel.XWPFTable -> element.rows.forEach { row ->
                            collector.append(row.tableCells.joinToString(" | ") { it.text })
                            collector.append("\n")
                        }
                    }
                }
            }
        }
    }

    private fun validateDocxArchive(file: File) {
        var entries = 0
        var expandedBytes = 0L
        var hasContentTypes = false
        var hasDocument = false
        java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_DOCX_ENTRIES) { "DOCX 条目数超过安全阈值" }
                if (entry.name == "[Content_Types].xml") hasContentTypes = true
                if (entry.name == "word/document.xml") hasDocument = true
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expandedBytes += count
                    require(expandedBytes <= MAX_DOCX_EXPANDED_BYTES) { "DOCX 解压体积超过安全阈值" }
                }
            }
        }
        require(hasContentTypes && hasDocument) { "DOCX 结构无效" }
    }

    private class IncrementalCollector(
        private val chunkSize: Int,
        private val overlap: Int,
        private val maxUtf8Bytes: Long,
    ) {
        private val pending = StringBuilder()
        private val graph = StringBuilder()
        private val chunks = mutableListOf<String>()
        private var acceptedBytes = 0L
        var truncated = false
            private set

        fun append(value: String) {
            if (truncated || value.isEmpty()) return
            var offset = 0
            while (offset < value.length) {
                val codePoint = value.codePointAt(offset)
                val text = String(Character.toChars(codePoint))
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (acceptedBytes > maxUtf8Bytes - bytes) {
                    truncated = true
                    break
                }
                pending.append(text)
                graph.append(text)
                acceptedBytes += bytes
                offset += Character.charCount(codePoint)
                emitFullChunks()
            }
        }

        private fun emitFullChunks() {
            while (pending.length >= chunkSize) {
                chunks += pending.substring(0, chunkSize)
                val keep = overlap.coerceIn(0, chunkSize - 1)
                pending.delete(0, chunkSize - keep)
            }
        }

        fun finish(): DocumentReferenceExtraction {
            if (pending.isNotBlank()) chunks += pending.toString()
            return DocumentReferenceExtraction(chunks, graph.toString(), truncated)
        }
    }

    companion object {
        const val MAX_EXTRACTED_UTF8_BYTES = 16L * 1024 * 1024
        private const val MAX_SOURCE_BYTES = 100L * 1024 * 1024
        private const val MAX_DOCX_SOURCE_BYTES = 50L * 1024 * 1024
        private const val MAX_DOCX_EXPANDED_BYTES = 64L * 1024 * 1024
        private const val MAX_DOCX_ENTRIES = 2_048
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val TEXT_MIME_TYPES = setOf("text/plain", "text/markdown", "text/csv", "application/json")
        val SUPPORTED_MIME_TYPES = TEXT_MIME_TYPES + setOf("application/pdf", DOCX_MIME)
    }
}
