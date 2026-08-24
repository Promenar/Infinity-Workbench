package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkspaceTextContentPolicyTest {
    private val policy = WorkspaceTextContentPolicy()

    @Test
    fun `明确文本mime与扩展且内容为有效UTF8时允许编辑`() {
        val result = policy.validate(
            fileName = "notes.md",
            mimeType = "text/markdown",
            bytes = "第一行\nsecond line".toByteArray(),
            operation = WorkspaceTextOperation.READ,
        )

        assertThat(result.text).isEqualTo("第一行\nsecond line")
        assertThat(result.lineCount).isEqualTo(2)
    }

    @Test
    fun `PDF DOCX 图片及扩展与mime冲突都拒绝文本操作`() {
        val fixtures = listOf(
            Triple("paper.pdf", "application/pdf", "%PDF".toByteArray()),
            Triple("paper.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", byteArrayOf(0x50, 0x4b)),
            Triple("photo.png", "image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)),
            Triple("photo.png", "text/plain", "not really text".toByteArray()),
            Triple("notes.txt", "image/png", "not really an image".toByteArray()),
        )

        fixtures.forEach { (name, mime, bytes) ->
            val failure = runCatching {
                policy.validate(name, mime, bytes, WorkspaceTextOperation.READ)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(WorkspaceTextPolicyException::class.java)
            assertThat((failure as WorkspaceTextPolicyException).code)
                .isEqualTo(WorkspaceTextErrorCode.UNSUPPORTED_CONTENT_TYPE)
        }
    }

    @Test
    fun `NUL 与无效UTF8拒绝且不替换为乱码`() {
        val fixtures = listOf(
            "a\u0000b".toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28),
        )

        fixtures.forEach { bytes ->
            val failure = runCatching {
                policy.validate("notes.txt", "text/plain", bytes, WorkspaceTextOperation.READ)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(WorkspaceTextPolicyException::class.java)
            assertThat((failure as WorkspaceTextPolicyException).code)
                .isEqualTo(WorkspaceTextErrorCode.INVALID_TEXT_ENCODING)
        }
    }

    @Test
    fun `读取字节 行数与输出预算分别返回稳定错误码`() {
        val byteFailure = runCatching {
            policy.validate(
                "notes.txt",
                "text/plain",
                "12345".toByteArray(),
                WorkspaceTextOperation.READ,
                WorkspaceTextBudget(maxInputBytes = 4, maxLines = 10, maxOutputBytes = 10),
            )
        }.exceptionOrNull() as WorkspaceTextPolicyException
        assertThat(byteFailure.code).isEqualTo(WorkspaceTextErrorCode.INPUT_TOO_LARGE)

        val lineFailure = runCatching {
            policy.validate(
                "notes.txt",
                "text/plain",
                "1\n2\n3".toByteArray(),
                WorkspaceTextOperation.PATCH,
                WorkspaceTextBudget(maxInputBytes = 20, maxLines = 2, maxOutputBytes = 20),
            )
        }.exceptionOrNull() as WorkspaceTextPolicyException
        assertThat(lineFailure.code).isEqualTo(WorkspaceTextErrorCode.TOO_MANY_LINES)

        val outputFailure = runCatching {
            policy.validateOutput("中文", WorkspaceTextBudget(maxInputBytes = 20, maxLines = 2, maxOutputBytes = 5))
        }.exceptionOrNull() as WorkspaceTextPolicyException
        assertThat(outputFailure.code).isEqualTo(WorkspaceTextErrorCode.OUTPUT_TOO_LARGE)
        assertThat(outputFailure.safeMessage).contains("缩小")
    }

    @Test
    fun `无扩展文件可由明确text mime认定而markdown json code扩展可由扩展认定`() {
        val fixtures = listOf(
            Triple("LICENSE", "text/plain", "terms"),
            Triple("README.md", null, "# title"),
            Triple("config.json", "application/json", "{}"),
            Triple("Main.kt", "text/x-kotlin", "fun main() = Unit"),
        )

        fixtures.forEach { (name, mime, content) ->
            assertThat(
                policy.validate(name, mime, content.toByteArray(), WorkspaceTextOperation.EDITOR).text,
            ).isEqualTo(content)
        }
    }

    @Test
    fun `UTF8 BOM被安全移除而CRLF与空文件语义保持明确`() {
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "a\r\nb".toByteArray()
        val decoded = policy.validate("a.txt", "text/plain", bom, WorkspaceTextOperation.READ)
        val empty = policy.validate("empty.txt", "text/plain", byteArrayOf(), WorkspaceTextOperation.READ)

        assertThat(decoded.text).isEqualTo("a\r\nb")
        assertThat(decoded.lineCount).isEqualTo(2)
        assertThat(empty.text).isEmpty()
        assertThat(empty.lineCount).isEqualTo(0)
    }

    @Test
    fun `LF CRLF CR的20001行均以TOO_MANY_LINES拒绝且CRLF只计一次`() {
        listOf("\n", "\r\n", "\r").forEach { separator ->
            val content = (1..20_001).joinToString(separator) { "x" }

            val failure = runCatching {
                policy.validate(
                    "large.txt",
                    "text/plain",
                    content.toByteArray(),
                    WorkspaceTextOperation.READ,
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(WorkspaceTextPolicyException::class.java)
            assertThat((failure as WorkspaceTextPolicyException).code)
                .isEqualTo(WorkspaceTextErrorCode.TOO_MANY_LINES)
        }

        val crlf = policy.validate(
            "two-lines.txt",
            "text/plain",
            "a\r\nb".toByteArray(),
            WorkspaceTextOperation.READ,
            WorkspaceTextBudget(maxInputBytes = 20, maxLines = 2, maxOutputBytes = 20),
        )
        assertThat(crlf.lineCount).isEqualTo(2)
    }

    @Test
    fun `尾部LF CRLF CR都保留末尾空行且空文本仍为零行`() {
        val fixtures = listOf(
            "a\n" to 2,
            "a\r\n" to 2,
            "a\r" to 2,
            "a\rb\r" to 3,
            "" to 0,
        )

        fixtures.forEach { (content, expected) ->
            val result = policy.validate(
                "lines.txt",
                "text/plain",
                content.toByteArray(),
                WorkspaceTextOperation.READ,
            )
            assertThat(result.lineCount).isEqualTo(expected)
        }
    }
}
