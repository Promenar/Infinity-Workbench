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
import java.util.Base64

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

fun interface RestoreJournalAuthenticator {
    fun sign(payload: ByteArray): ByteArray

    fun verify(payload: ByteArray, signature: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(sign(payload), signature)
}

@Serializable
private data class AuthenticatedRestoreJournal(
    val payloadBase64: String,
    val signatureBase64: String,
)

internal interface RestoreJournal {
    fun read(): RestoreJournalRecord?
    fun write(record: RestoreJournalRecord)
    fun delete()
}

internal class FileRestoreJournal(
    base: Path,
    private val authenticator: RestoreJournalAuthenticator,
) : RestoreJournal {
    private val trustedBase = requireTrustedDirectory(base)
    private val journalPath = trustedBase.resolve(FILE_NAME)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override fun read(): RestoreJournalRecord? {
        cleanupTemporaryFiles()
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = readBoundedNoFollow(journalPath, MAX_JOURNAL_BYTES, "恢复 journal")
        return try {
            val envelope = json.decodeFromString<AuthenticatedRestoreJournal>(bytes.toString(Charsets.UTF_8))
            val payload = Base64.getDecoder().decode(envelope.payloadBase64)
            val signature = Base64.getDecoder().decode(envelope.signatureBase64)
            try {
                if (!authenticator.verify(payload, signature)) {
                    throw BackupValidationException("恢复 journal 认证失败")
                }
                json.decodeFromString<RestoreJournalRecord>(payload.toString(Charsets.UTF_8)).also(::validateRecord)
            } finally {
                payload.fill(0)
                signature.fill(0)
            }
        } catch (error: Exception) {
            if (error is BackupValidationException) throw error
            throw BackupValidationException("恢复 journal 损坏，拒绝继续恢复", error)
        } finally {
            bytes.fill(0)
        }
    }

    override fun write(record: RestoreJournalRecord) {
        validateRecord(record)
        val temporary = trustedBase.resolve("$FILE_NAME.${record.txId}.tmp")
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupValidationException("恢复 journal 临时文件已存在")
        }
        val payload = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        val signature = authenticator.sign(payload)
        val bytes = json.encodeToString(
            AuthenticatedRestoreJournal(
                payloadBase64 = Base64.getEncoder().encodeToString(payload),
                signatureBase64 = Base64.getEncoder().encodeToString(signature),
            )
        ).toByteArray(Charsets.UTF_8)
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
            payload.fill(0)
            signature.fill(0)
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
            record.oldRootIdentity.split(',').filter(String::isNotBlank).any { !OLD_ROOT_ID.matches(it) } ||
            record.newRootFileKey?.length.orZero() > 512
        ) throw BackupValidationException("恢复 journal 元数据无效")
    }

    private fun readBoundedNoFollow(path: Path, limit: Long, label: String): ByteArray {
        try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val before = Files.readAttributes(
                    path,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (!before.isRegularFile || before.isSymbolicLink || before.size() > limit) {
                    throw BackupValidationException("$label 不是受信的有界普通文件")
                }
                val output = java.io.ByteArrayOutputStream(before.size().toInt())
                val buffer = java.nio.ByteBuffer.allocate(4096)
                var total = 0L
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw BackupValidationException("$label 超过大小限制")
                    output.write(buffer.array(), 0, count)
                }
                val after = Files.readAttributes(
                    path,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (before.fileKey() != after.fileKey() || before.size() != after.size() || total != after.size()) {
                    throw BackupValidationException("$label 读取期间身份发生变化")
                }
                return output.toByteArray()
            }
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Exception) {
            throw BackupValidationException("无法安全读取$label", error)
        }
    }

    companion object {
        const val FILE_NAME = ".restore-journal.json"
        private const val MAX_JOURNAL_BYTES = 16L * 1024
        private val TX_ID = Regex("[A-Za-z0-9-]{1,64}")
        private val ROOT_ID = Regex("restore-[A-Za-z0-9-]{1,72}")
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val OLD_ROOT_ID = Regex("-?[0-9]+:[A-Za-z0-9_-]{1,768}")

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
