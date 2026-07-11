package com.promenar.nexara.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Serializable
enum class RestoreJournalPhase { PREPARED, COMMITTED }

@Serializable
data class RestoreJournalRecord(
    val txId: String,
    val expectedDatabaseFingerprint: String,
    val oldRootIdentity: String,
    val newRootIdentity: String,
    val newRootFileKey: String?,
    val phase: RestoreJournalPhase,
)

internal interface RestoreJournal {
    fun read(): RestoreJournalRecord?
    fun write(record: RestoreJournalRecord)
    fun delete()
}

internal class FileRestoreJournal(base: Path) : RestoreJournal {
    private val trustedBase = requireTrustedDirectory(base)
    private val journalPath = trustedBase.resolve(FILE_NAME)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override fun read(): RestoreJournalRecord? {
        cleanupTemporaryFiles()
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(journalPath) || !Files.isRegularFile(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupValidationException("恢复 journal 不是受信普通文件")
        }
        if (Files.size(journalPath) > MAX_JOURNAL_BYTES) throw BackupValidationException("恢复 journal 超过大小限制")
        return try {
            json.decodeFromString(Files.readAllBytes(journalPath).toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw BackupValidationException("恢复 journal 损坏，拒绝继续恢复", error)
        }
    }

    override fun write(record: RestoreJournalRecord) {
        validateRecord(record)
        val temporary = trustedBase.resolve("$FILE_NAME.${record.txId}.tmp")
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupValidationException("恢复 journal 临时文件已存在")
        }
        val bytes = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            try {
                Files.move(
                    temporary,
                    journalPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                throw BackupValidationException("恢复 journal 文件系统不支持原子替换", error)
            }
            syncDirectory(trustedBase)
        } finally {
            bytes.fill(0)
            Files.deleteIfExists(temporary)
        }
    }

    override fun delete() {
        if (Files.isSymbolicLink(journalPath)) throw BackupValidationException("拒绝删除符号链接 journal")
        Files.deleteIfExists(journalPath)
        cleanupTemporaryFiles()
        syncDirectory(trustedBase)
    }

    private fun cleanupTemporaryFiles() {
        Files.newDirectoryStream(trustedBase, "$FILE_NAME.*.tmp").use { stream ->
            stream.forEach { temporary ->
                if (Files.isSymbolicLink(temporary) || !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
                    throw BackupValidationException("恢复 journal 临时文件身份异常")
                }
                Files.delete(temporary)
            }
        }
    }

    private fun validateRecord(record: RestoreJournalRecord) {
        if (!TX_ID.matches(record.txId) || !ROOT_ID.matches(record.newRootIdentity) ||
            !SHA_256.matches(record.expectedDatabaseFingerprint) || record.oldRootIdentity.length > 4096 ||
            record.newRootFileKey?.length.orZero() > 512
        ) throw BackupValidationException("恢复 journal 元数据无效")
    }

    companion object {
        const val FILE_NAME = ".restore-journal.json"
        private const val MAX_JOURNAL_BYTES = 16L * 1024
        private val TX_ID = Regex("[A-Za-z0-9-]{1,64}")
        private val ROOT_ID = Regex("restore-[A-Za-z0-9-]{1,72}")
        private val SHA_256 = Regex("[0-9a-f]{64}")

        fun requireTrustedDirectory(path: Path): Path {
            val absolute = path.toAbsolutePath().normalize()
            if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                throw BackupValidationException("受信恢复目录不存在或不是目录")
            }
            rejectSymlinkAncestors(absolute)
            return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
        }

        fun rejectSymlinkAncestors(path: Path) {
            var current: Path? = path.root
            path.forEach { segment ->
                current = current!!.resolve(segment)
                if (Files.isSymbolicLink(current)) throw BackupValidationException("受信路径祖先包含符号链接")
            }
        }

        fun syncDirectory(path: Path) {
            try {
                FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
            } catch (error: Exception) {
                throw BackupValidationException("当前平台无法 fsync 恢复目录，拒绝降低持久性保证", error)
            }
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0
