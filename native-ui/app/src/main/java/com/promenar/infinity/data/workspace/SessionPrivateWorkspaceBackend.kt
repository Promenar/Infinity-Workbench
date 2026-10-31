package com.promenar.infinity.data.workspace

import com.promenar.infinity.domain.storage.ExternalFolderBackend

/** 跨根传输沿用私有树事务，仅转换传输协议的虚拟路径。 */
internal class SessionPrivateWorkspaceBackend(private val base: ExternalFolderBackend) : ExternalFolderBackend by base {
    private fun relative(path: String) = SessionPrivateWorkspacePaths.relative(path) ?: path
    override suspend fun stat(path: String) = base.stat(relative(path))?.copy(path = path)
    override suspend fun list(path: String) = base.list(relative(path)).map {
        it.copy(path = if (SessionPrivateWorkspacePaths.contains(path)) SessionPrivateWorkspacePaths.virtual(it.path) else it.path)
    }
    override suspend fun read(path: String, maxBytes: Long) = base.read(relative(path), maxBytes)
    override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T =
        base.readStream(relative(path), maxBytes, consume)
    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) =
        base.write(relative(path), content, expectedSha256, createOnly).copy(path = path)
    override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (java.io.OutputStream) -> Unit) =
        base.writeStream(relative(path), expectedSize, createOnly, produce).copy(path = path)
    override suspend fun availableBytes(path: String) = base.availableBytes(relative(path))
    override suspend fun validateTree(path: String) = base.validateTree(relative(path))
    override suspend fun createDirectory(path: String) = base.createDirectory(relative(path)).copy(path = path)
    override suspend fun deleteForTransfer(path: String, expected: com.promenar.infinity.domain.storage.ExternalFolderEntry, expectedSha256: String?) =
        base.deleteForTransfer(relative(path), expected.copy(path = relative(path)), expectedSha256)
    override suspend fun delete(path: String, expectedSha256: String?) = base.delete(relative(path), expectedSha256)
}
