package com.promenar.nexara.data.repository

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class WorkspaceTextOperation {
    READ,
    WRITE,
    PATCH,
    DIFF,
    EDITOR,
}

enum class WorkspaceTextErrorCode {
    UNSUPPORTED_CONTENT_TYPE,
    INVALID_TEXT_ENCODING,
    INPUT_TOO_LARGE,
    TOO_MANY_LINES,
    OUTPUT_TOO_LARGE,
}

data class WorkspaceTextBudget(
    val maxInputBytes: Long,
    val maxLines: Int,
    val maxOutputBytes: Long,
) {
    init {
        require(maxInputBytes > 0) { "maxInputBytes 必须大于 0" }
        require(maxLines > 0) { "maxLines 必须大于 0" }
        require(maxOutputBytes > 0) { "maxOutputBytes 必须大于 0" }
    }
}

data class ValidatedWorkspaceText(
    val text: String,
    val byteCount: Long,
    val lineCount: Int,
)

class WorkspaceTextPolicyException(
    val code: WorkspaceTextErrorCode,
    val safeMessage: String,
) : IllegalArgumentException(safeMessage)

/** 编辑器与文件工具共享的文本内容门禁。 */
class WorkspaceTextContentPolicy {
    fun validate(
        fileName: String,
        mimeType: String?,
        bytes: ByteArray,
        operation: WorkspaceTextOperation,
        budget: WorkspaceTextBudget = budgetFor(operation),
    ): ValidatedWorkspaceText {
        requireSupportedType(fileName, mimeType)
        if (bytes.size.toLong() > budget.maxInputBytes) {
            fail(
                WorkspaceTextErrorCode.INPUT_TOO_LARGE,
                "文件超过本次操作的读取上限，请缩小读取范围或使用专用文件处理能力。",
            )
        }
        if (bytes.any { it == 0.toByte() }) {
            fail(WorkspaceTextErrorCode.INVALID_TEXT_ENCODING, "文件不是可安全编辑的 UTF-8 文本。")
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail(WorkspaceTextErrorCode.INVALID_TEXT_ENCODING, "文件不是可安全编辑的 UTF-8 文本。")
        }
        val lineCount = when {
            text.isEmpty() -> 0
            else -> 1 + text.count { it == '\n' }
        }
        if (lineCount > budget.maxLines) {
            fail(
                WorkspaceTextErrorCode.TOO_MANY_LINES,
                "文件行数超过本次操作上限，请缩小读取范围或拆分操作。",
            )
        }
        return ValidatedWorkspaceText(text, bytes.size.toLong(), lineCount)
    }

    fun validateOutput(
        output: String,
        budget: WorkspaceTextBudget,
    ) {
        if (output.toByteArray(Charsets.UTF_8).size.toLong() > budget.maxOutputBytes) {
            fail(
                WorkspaceTextErrorCode.OUTPUT_TOO_LARGE,
                "结果超过输出上限，请缩小读取或差异范围后重试。",
            )
        }
    }

    fun requireSupportedType(fileName: String, mimeType: String?) {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val extensionAllowed = extension in TEXT_EXTENSIONS
        val explicitTextMime = normalizedMime.startsWith("text/") ||
            normalizedMime in TEXT_APPLICATION_MIME_TYPES
        val explicitBinaryMime = normalizedMime.isNotEmpty() && !explicitTextMime
        val supported = when {
            extension in BINARY_EXTENSIONS -> false
            explicitBinaryMime -> false
            explicitTextMime -> true
            normalizedMime.isEmpty() -> extensionAllowed
            else -> false
        }
        if (!supported) {
            fail(
                WorkspaceTextErrorCode.UNSUPPORTED_CONTENT_TYPE,
                "该文件类型仅支持导入或索引，不支持文本读取、编辑、写入、补丁或差异操作。",
            )
        }
    }

    companion object {
        val DEFAULT_TOOL_BUDGET = WorkspaceTextBudget(
            maxInputBytes = 1L * 1024L * 1024L,
            maxLines = 20_000,
            maxOutputBytes = 256L * 1024L,
        )
        val DEFAULT_EDITOR_BUDGET = WorkspaceTextBudget(
            maxInputBytes = 1L * 1024L * 1024L,
            maxLines = 20_000,
            maxOutputBytes = 1L * 1024L * 1024L,
        )

        fun budgetFor(operation: WorkspaceTextOperation): WorkspaceTextBudget =
            if (operation == WorkspaceTextOperation.EDITOR) DEFAULT_EDITOR_BUDGET else DEFAULT_TOOL_BUDGET

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "jsonl", "xml", "yaml", "yml", "toml",
            "csv", "tsv", "log", "ini", "conf", "properties", "kt", "kts", "java",
            "js", "jsx", "ts", "tsx", "css", "scss", "html", "htm", "py", "rb",
            "go", "rs", "c", "h", "cpp", "hpp", "swift", "sh", "zsh", "fish", "sql",
        )
        private val TEXT_APPLICATION_MIME_TYPES = setOf(
            "application/json",
            "application/ld+json",
            "application/xml",
            "application/javascript",
            "application/x-javascript",
            "application/yaml",
            "application/x-yaml",
            "application/toml",
            "application/sql",
        )
        private val BINARY_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "gz", "7z", "rar",
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svgz", "mp3", "wav", "ogg",
            "mp4", "mkv", "mov", "avi", "bin", "dex", "so", "class", "jar", "apk", "aab",
        )

        private fun fail(code: WorkspaceTextErrorCode, message: String): Nothing =
            throw WorkspaceTextPolicyException(code, message)
    }
}
