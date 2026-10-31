package com.promenar.infinity.startup

import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 保留只读能力；每个写原语在访问真实来源前重新检查当前会话与物理范围。 */
internal class StartupGuardedExternalFolderBackend(
    private val delegate: ExternalFolderBackend,
    private val sessionId: String,
    private val physicalRoot: suspend () -> File?,
) : ExternalFolderBackend by delegate {
    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) =
        writing(listOf(path)) { delegate.write(path, content, expectedSha256, createOnly) }

    override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (java.io.OutputStream) -> Unit) =
        writing(listOf(path)) { delegate.writeStream(path, expectedSize, createOnly, produce) }

    override suspend fun createDirectory(path: String) = writing(listOf(path)) { delegate.createDirectory(path) }

    override suspend fun deleteForTransfer(path: String, expected: com.promenar.infinity.domain.storage.ExternalFolderEntry, expectedSha256: String?) =
        writing(listOf(path)) { delegate.deleteForTransfer(path, expected, expectedSha256) }

    override suspend fun delete(path: String, expectedSha256: String?) = writing(listOf(path)) { delegate.delete(path, expectedSha256) }

    override suspend fun move(sourcePath: String, targetPath: String) =
        writing(listOf(sourcePath, targetPath)) { delegate.move(sourcePath, targetPath) }

    override suspend fun copy(sourcePath: String, targetPath: String) =
        writing(listOf(targetPath)) { delegate.copy(sourcePath, targetPath) }

    private suspend fun <T> writing(paths: List<String>, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        val root = physicalRoot()?.canonicalFile
        StartupWriteBarrier.requireWritable(sessionId = sessionId, path = root?.path)
        paths.forEach { path ->
            val relative = ExternalFolderPaths.normalize(path).removePrefix("/")
            val physicalPath = root?.let { File(it, relative).canonicalPath }
            StartupWriteBarrier.requireWritable(sessionId = sessionId, path = physicalPath)
        }
        action()
    }
}
