package com.promenar.nexara.data.repository

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.io.OutputStream

/** Robolectric 会隔离 JDK 接口类加载器，因此仓储测试使用可回滚的真实磁盘夹具。 */
class TestWorkspaceFileOps(
    private val hook: ((WorkspaceFilePhase) -> Unit)? = null,
) : WorkspaceFileOps {
    override fun ensureRoot(
        root: Path,
        initializeIdentity: Boolean,
        expectedIdentity: String?,
        allowUnboundParent: Boolean,
    ): String {
        Files.createDirectories(root)
        requireSafeRoot(root)
        val key = Files.readAttributes(
            root,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).fileKey()?.toString() ?: throw SecurityException("测试工作区根缺少 fileKey")
        val marker = root.resolve(".nexara_root_identity")
        val actual = if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            Files.readAllBytes(marker).toString(Charsets.UTF_8)
        } else {
            if (!initializeIdentity) throw SecurityException("测试工作区根缺少持久身份标记")
            "${expectedIdentity ?: UUID.randomUUID()}\n$key".also {
                Files.write(marker, it.toByteArray(), java.nio.file.StandardOpenOption.CREATE_NEW)
            }
        }
        if (actual.substringAfter('\n', "") != key) throw SecurityException("测试工作区根 fileKey 不匹配")
        val nonce = actual.substringBefore('\n')
        val identity = "$nonce|${com.promenar.nexara.infra.util.Sha256Utils.hash(key)}"
        if (expectedIdentity != null && identity != expectedIdentity) throw SecurityException("测试工作区根认领不匹配")
        return identity
    }

    override fun read(root: Path, relative: List<String>): ByteArray =
        Files.readAllBytes(resolve(root, relative, requireTarget = true))

    override fun readLimited(root: Path, relative: List<String>, maxBytes: Long): ByteArray {
        val target = resolve(root, relative, requireTarget = true)
        if (Files.size(target) > maxBytes) throw WorkspaceFileTooLargeException(maxBytes)
        return Files.readAllBytes(target).also { bytes ->
            if (bytes.size.toLong() > maxBytes) throw WorkspaceFileTooLargeException(maxBytes)
        }
    }

    override fun exists(root: Path, relative: List<String>): Boolean =
        Files.exists(resolve(root, relative, requireTarget = true), LinkOption.NOFOLLOW_LINKS)

    override fun inspect(root: Path, relative: List<String>): WorkspaceNodeIdentity {
        val target = resolve(root, relative, requireTarget = true)
        val fileKey = Files.readAttributes(
            target,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).fileKey()?.toString() ?: throw SecurityException("测试节点缺少 fileKey")
        return if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            WorkspaceNodeIdentity("directory", 0, null, fileKey)
        } else {
            WorkspaceNodeIdentity(
                "file",
                Files.size(target),
                com.promenar.nexara.infra.util.Sha256Utils.hashFile(target.toFile()),
                fileKey,
            )
        }
    }

    override fun createFile(root: Path, relative: List<String>, bytes: ByteArray) {
        val target = resolve(root, relative)
        if (!Files.isDirectory(target.parent, LinkOption.NOFOLLOW_LINKS)) {
            throw java.nio.file.NoSuchFileException(target.parent.toString())
        }
        hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
        Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
    }

    override fun createFileStreaming(
        root: Path,
        relative: List<String>,
        maxBytes: Long,
        writer: (OutputStream) -> Unit,
    ): WorkspaceStreamWriteResult {
        val target = resolve(root, relative)
        val temporary = target.resolveSibling(".create-${UUID.randomUUID()}")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            Files.newOutputStream(temporary, java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                writer(object : OutputStream() {
                    override fun write(value: Int) {
                        ensureCapacity(1)
                        output.write(value)
                        digest.update(value.toByte())
                        size++
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        ensureCapacity(length)
                        output.write(bytes, offset, length)
                        digest.update(bytes, offset, length)
                        size += length
                    }
                    private fun ensureCapacity(incoming: Int) {
                        if (size > maxBytes - incoming) throw WorkspaceFileTooLargeException(maxBytes)
                    }
                })
            }
            hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
            Files.move(temporary, target)
            return WorkspaceStreamWriteResult(
                size,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun createDirectory(root: Path, relative: List<String>) {
        val target = resolve(root, relative)
        Files.createDirectory(target)
    }

    override fun ensureDirectory(root: Path, relative: List<String>) {
        val target = resolve(root, relative)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(target.parent, LinkOption.NOFOLLOW_LINKS)) {
                throw java.nio.file.NoSuchFileException(target.parent.toString())
            }
            Files.createDirectory(target)
        }
        requireSafeRoot(root)
        rejectSymlinkPath(root, relative)
    }

    override fun replaceFile(root: Path, relative: List<String>, bytes: ByteArray): WorkspaceFileRollback {
        val target = resolve(root, relative, requireTarget = true)
        val token = com.promenar.nexara.infra.util.Sha256Utils.hash(target.fileName.toString()).take(24)
        val backup = target.resolveSibling(".nexara-previous-$token")
        val replacement = target.resolveSibling(".nexara-write-$token")
        Files.write(replacement, bytes)
        hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
        Files.move(target, backup)
        Files.move(replacement, target)
        return rollback(
            commit = { Files.deleteIfExists(backup) },
            rollback = {
                val displaced = target.resolveSibling(".nexara-rollback-$token")
                Files.move(target, displaced)
                try {
                    hook?.invoke(WorkspaceFilePhase.ROLLBACK_BEFORE_RESTORE)
                    Files.move(backup, target)
                } catch (failure: Throwable) {
                    runCatching { Files.move(displaced, target) }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
                Files.deleteIfExists(displaced)
            },
        )
    }

    override fun reconcileFile(root: Path, relative: List<String>, expectedHash: String) {
        val target = resolve(root, relative)
        val token = com.promenar.nexara.infra.util.Sha256Utils.hash(target.fileName.toString()).take(24)
        val temporary = target.resolveSibling(".nexara-write-$token")
        val backup = target.resolveSibling(".nexara-previous-$token")
        val displaced = target.resolveSibling(".nexara-rollback-$token")
        if (listOf(temporary, backup, displaced).none { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }) return
        fun hash(path: Path): String? {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
            hook?.invoke(WorkspaceFilePhase.RECONCILE_CONTENT_READ)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, java.nio.file.StandardOpenOption.READ).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        when {
            hash(target) == expectedHash -> {
                Files.deleteIfExists(backup)
                Files.deleteIfExists(temporary)
                Files.deleteIfExists(displaced)
            }
            hash(backup) == expectedHash -> {
                Files.deleteIfExists(displaced)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.move(target, displaced)
                Files.move(backup, target)
                Files.deleteIfExists(displaced)
                Files.deleteIfExists(temporary)
            }
            else -> throw IllegalStateException("测试文件哈希与数据库不一致")
        }
    }

    override fun move(root: Path, source: List<String>, target: List<String>): WorkspaceFileRollback {
        val from = resolve(root, source, requireTarget = true)
        val to = resolve(root, target)
        if (!Files.isDirectory(to.parent, LinkOption.NOFOLLOW_LINKS)) {
            throw java.nio.file.NoSuchFileException(to.parent.toString())
        }
        Files.move(from, to)
        return rollback(rollback = {
            Files.move(to, from)
        })
    }

    override fun stageDelete(root: Path, source: List<String>, deletionToken: String): WorkspaceFileRollback {
        val from = resolve(root, source, requireTarget = true)
        val tombstones = resolve(root, listOf(".nexara_tombstones"))
        Files.createDirectories(tombstones)
        val staged = tombstones.resolve(deletionToken)
        Files.move(from, staged)
        return rollback(
            commit = { deleteTree(staged) },
            rollback = {
                Files.move(staged, from)
            },
        )
    }

    override fun recoverTombstones(
        root: Path,
        restorePath: (String) -> List<String>?,
    ): TombstoneRecoveryReport {
        val tombstones = resolve(root, listOf(".nexara_tombstones"))
        if (!Files.exists(tombstones, LinkOption.NOFOLLOW_LINKS)) return TombstoneRecoveryReport()
        val attention = mutableListOf<String>()
        Files.list(tombstones).use { entries ->
            entries.toList().forEach { staged ->
                try {
                    val source = restorePath(staged.fileName.toString())
                    if (source == null) deleteTree(staged)
                    else Files.move(staged, resolve(root, source))
                } catch (_: Exception) {
                    attention += staged.fileName.toString()
                }
            }
        }
        return TombstoneRecoveryReport(attention)
    }

    override fun delete(root: Path, source: List<String>) {
        deleteTree(resolve(root, source, requireTarget = true))
    }

    override fun cleanupTombstones(root: Path) {
        val tombstones = resolve(root, listOf(".nexara_tombstones"))
        if (Files.exists(tombstones, LinkOption.NOFOLLOW_LINKS)) deleteTree(tombstones)
    }

    private fun resolve(root: Path, relative: List<String>, requireTarget: Boolean = false): Path {
        requireSafeRoot(root)
        require(relative.isNotEmpty() && relative.none { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it })
        rejectSymlinkPath(root, if (requireTarget) relative else relative.dropLast(1))
        val target = relative.fold(root) { current, segment -> current.resolve(segment) }.normalize()
        if (!target.startsWith(root.normalize())) throw SecurityException("测试夹具路径越界")
        if (requireTarget && Files.isSymbolicLink(target)) throw SecurityException("测试夹具拒绝符号链接")
        return target
    }

    private fun requireSafeRoot(root: Path) {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw SecurityException("测试夹具工作区根无效")
        }
    }

    private fun rejectSymlinkPath(root: Path, relative: List<String>) {
        var current = root
        relative.forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) throw SecurityException("测试夹具拒绝符号链接路径")
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            Files.newDirectoryStream(path).use { entries -> entries.forEach(::deleteTree) }
        }
        Files.deleteIfExists(path)
    }

    private fun rollback(
        commit: () -> Unit = {},
        rollback: () -> Unit,
    ) = object : WorkspaceFileRollback {
        override fun commit() = commit.invoke()
        override fun rollback() = rollback.invoke()
    }
}
