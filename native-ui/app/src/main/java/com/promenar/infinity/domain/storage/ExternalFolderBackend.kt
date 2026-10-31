package com.promenar.infinity.domain.storage

import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
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

    /**
     * 流式读取：把文件内容交给 [consume] 逐块处理，适合大文件复制。[maxBytes] 与 [read] 同义，超出时抛出 `TOO_LARGE`。
     * 默认实现退回 [read]，仍会把整个文件载入内存；能直接打开文件句柄的后端应覆盖。
     */
    suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (InputStream) -> T): T =
        consume(read(path, maxBytes).inputStream())
    suspend fun write(
        path: String,
        content: ByteArray,
        expectedSha256: String? = null,
        createOnly: Boolean = false,
    ): ExternalFolderEntry
    /**
     * 流式新建文件，必须精确写出 [expectedSize] 字节；失败或取消时清理自己创建的内容，不覆盖同名条目。
     * 默认实现使用内存缓冲再调用 [write]，仅供兼容旧后端；大文件后端必须覆盖。
     * 此接口不接受无条件覆盖，已有文件修改仍经带版本参数的 [write]。
     */
    suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean = true, produce: suspend (OutputStream) -> Unit): ExternalFolderEntry {
        if (!createOnly) throw ExternalFolderException("UNSUPPORTED", "流式传输只允许新建文件")
        if (expectedSize !in 0..(Int.MAX_VALUE - 8L)) throw ExternalFolderException("TOO_LARGE", "后端缓冲写入容量不足")
        val normalized = ExternalFolderPaths.normalize(path)
        if (normalized == "/") throw ExternalFolderException("INVALID_PATH", "不能修改存储根目录")
        if (stat(normalized) != null) throw ExternalFolderException("CONFLICT", "目标已存在")
        val output = ByteArrayOutputStream()
        com.promenar.infinity.data.storage.produceExternalStream(output, expectedSize, produce)
        return write(path, output.toByteArray(), createOnly = true)
    }

    /** 目标目录可用字节数；后端无法查询时返回 null，实际写入仍需处理空间不足。 */
    suspend fun availableBytes(path: String): Long? = null

    suspend fun createDirectory(path: String): ExternalFolderEntry
    suspend fun delete(path: String, expectedSha256: String? = null)

    /** 传输条件删除必须绑定对象身份与摘要；目录只能删除空目录，不能递归删除未知内容。 */
    suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) {
        val current = stat(path) ?: throw ExternalFolderException("CONFLICT", "删除来源已不存在")
        if (current.identity != expected.identity || current.isDirectory != expected.isDirectory) throw ExternalFolderException("CONFLICT", "删除来源身份已变化")
        if (current.isDirectory) throw ExternalFolderException("UNSUPPORTED", "后端不提供空目录条件删除")
        delete(path, expectedSha256)
    }

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
        suspend fun visit(current: String, depth: Int) {
            if (depth > 32) throw ExternalFolderException("TOO_LARGE", "目录超过32层深度上限")
            val entry = stat(current) ?: throw ExternalFolderException("NOT_FOUND", "源条目不存在")
            if (entry.isDirectory) list(current).forEach { visit(it.path, depth + 1) }
        }
        visit(path, 0)
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
