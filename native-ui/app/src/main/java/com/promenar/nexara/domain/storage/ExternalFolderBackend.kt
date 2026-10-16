package com.promenar.nexara.domain.storage

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
        val folded = name.lowercase(Locale.ROOT)
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name ||
            folded.startsWith(".nexara") || folded == ".recycle_bin"
        ) invalid()
        return name
    }

    private fun invalid(): Nothing = throw ExternalFolderException("INVALID_PATH", "路径包含无效名称、越界片段或保留目录")
}
