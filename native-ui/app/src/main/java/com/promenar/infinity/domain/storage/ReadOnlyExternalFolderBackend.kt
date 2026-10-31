package com.promenar.infinity.domain.storage

/** 只读来源保留查询能力，所有修改原语在访问底层后端前拒绝。 */
class ReadOnlyExternalFolderBackend(private val delegate: ExternalFolderBackend) : ExternalFolderBackend by delegate {
    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry = readOnly()
    override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (java.io.OutputStream) -> Unit): ExternalFolderEntry = readOnly()
    override suspend fun createDirectory(path: String): ExternalFolderEntry = readOnly()
    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?): Unit = readOnly()
    override suspend fun delete(path: String, expectedSha256: String?): Unit = readOnly()
    override suspend fun move(sourcePath: String, targetPath: String): Unit = readOnly()
    override suspend fun copy(sourcePath: String, targetPath: String): Unit = readOnly()

    private fun readOnly(): Nothing = throw ExternalFolderException("READ_ONLY", "知识库挂载为只读")
}
