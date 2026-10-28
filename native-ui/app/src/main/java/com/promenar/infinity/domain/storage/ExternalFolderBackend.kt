package com.promenar.infinity.domain.storage

import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import java.util.Locale

data class ExternalFolderEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val sha256: String? = null,
    val identity: String? = null,
)

interface ExternalFolderBackend {
    suspend fun stat(path: String): ExternalFolderEntry?
    suspend fun list(path: String): List<ExternalFolderEntry>
    suspend fun read(path: String, maxBytes: Long = 8L * 1024 * 1024): ByteArray
    suspend fun write(
        path: String,
        content: ByteArray,
        expectedSha256: String? = null,
        createOnly: Boolean = false,
    ): ExternalFolderEntry
    suspend fun createDirectory(path: String): ExternalFolderEntry
    suspend fun delete(path: String, expectedSha256: String? = null)

    /** 同一后端内移动，不覆盖目标；不具备安全移动原语的实现明确拒绝。 */
    suspend fun move(sourcePath: String, targetPath: String) {
        throw ExternalFolderException("UNSUPPORTED", "该存储后端不支持直接移动")
    }

    /** 同一后端内流式复制，不覆盖目标；失败或取消时清理本次创建的条目。 */
    suspend fun copy(sourcePath: String, targetPath: String) {
        throw ExternalFolderException("UNSUPPORTED", "该存储后端不支持流式复制")
    }

    /** 写入树操作前校验整个来源；真实路径实现还检查浏览列表隐藏的链接与受保护条目。 */
    suspend fun validateTree(path: String) {
        val entry = stat(path) ?: throw ExternalFolderException("NOT_FOUND", "源条目不存在")
        if (entry.isDirectory) list(path).forEach { validateTree(it.path) }
    }
}

class ExternalFolderException(val code: String, override val message: String) : Exception(message)

object ExternalFolderPaths {
    fun normalize(raw: String): String {
        if ('\u0000' in raw || '\\' in raw) invalid()
        val names = raw.split('/').filter { it.isNotEmpty() }
        names.forEach(::validateName)
        return if (names.isEmpty()) "/" else names.joinToString("/", prefix = "/")
    }

    fun validateName(name: String): String {
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name ||
            WorkspaceInternalPaths.isReservedName(name)
        ) invalid()
        return name
    }

    private fun invalid(): Nothing = throw ExternalFolderException("INVALID_PATH", "路径包含无效名称、越界片段或保留目录")
}
