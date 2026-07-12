package com.promenar.nexara.data.repository

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.util.UUID

enum class WorkspaceFilePhase {
    DESCRIPTOR_OPENED,
    DIRECTORY_TEMP_CREATED,
    BEFORE_MUTATION,
    ROLLBACK_BEFORE_RESTORE,
    RECONCILE_CONTENT_READ,
}

interface WorkspaceFileOps {
    fun ensureRoot(
        root: Path,
        initializeIdentity: Boolean = true,
        expectedIdentity: String? = null,
        allowUnboundParent: Boolean = false,
    ): String
    fun read(root: Path, relative: List<String>): ByteArray
    fun createFile(root: Path, relative: List<String>, bytes: ByteArray)
    fun createDirectory(root: Path, relative: List<String>)
    fun ensureDirectory(root: Path, relative: List<String>)
    fun replaceFile(root: Path, relative: List<String>, bytes: ByteArray): WorkspaceFileRollback
    fun reconcileFile(root: Path, relative: List<String>, expectedHash: String)
    fun move(root: Path, source: List<String>, target: List<String>): WorkspaceFileRollback
    fun stageDelete(root: Path, source: List<String>): WorkspaceFileRollback
    fun delete(root: Path, source: List<String>)
    fun cleanupTombstones(root: Path)
}

interface WorkspaceFileRollback {
    fun commit()
    fun rollback()
}

/** 工作区 descriptor-relative 文件操作；文件系统不支持 SecureDirectoryStream 时失败关闭。 */
class SecureWorkspaceFileOps(
    private val hook: ((WorkspaceFilePhase) -> Unit)? = null,
) : WorkspaceFileOps {
    override fun ensureRoot(
        root: Path,
        initializeIdentity: Boolean,
        expectedIdentity: String?,
        allowUnboundParent: Boolean,
    ): String {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return openSecure(root, requireIdentity = false).use { secure ->
                ensureRootIdentity(secure, initializeIdentity, expectedIdentity)
            }
        }
        if (!initializeIdentity) throw SecurityException("已认领工作区根目录不存在")
        val parent = root.parent ?: throw SecurityException("工作区根缺少受信父目录")
        try {
            createDirectoryInternal(parent, listOf(root.fileName.toString()), requireIdentity = !allowUnboundParent)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // 并发创建者获胜后仍必须按 descriptor 身份重新验证。
        }
        return openSecure(root, requireIdentity = false).use { secure ->
            ensureRootIdentity(secure, initialize = true, expectedIdentity = expectedIdentity)
        }
    }

    override fun read(root: Path, relative: List<String>): ByteArray =
        withParent(root, relative) { parent, name ->
            parent.newByteChannel(
                name,
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    output.write(buffer.array(), 0, count)
                }
                output.toByteArray()
            }
        }

    override fun createFile(root: Path, relative: List<String>, bytes: ByteArray) {
        withParent(root, relative) { parent, name ->
            val temporary = Paths.get(".create-${UUID.randomUUID()}")
            try {
                writeNew(parent, temporary, bytes)
                hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
                verifyDirectoryBinding(root, relative.dropLast(1), directoryKey(parent))
                parent.move(temporary, parent, name)
            } finally {
                runCatching { parent.deleteFile(temporary) }
            }
        }
    }

    override fun createDirectory(root: Path, relative: List<String>) {
        createDirectoryInternal(root, relative, requireIdentity = true)
    }

    private fun createDirectoryInternal(root: Path, relative: List<String>, requireIdentity: Boolean) {
        requireSafeRelative(relative)
        val temporaryName = ".mkdir-${UUID.randomUUID()}"
        openSecure(root, requireIdentity).use { rootStream ->
            val rootKey = directoryKey(rootStream)
            hook?.invoke(WorkspaceFilePhase.DESCRIPTOR_OPENED)
            try {
                // JDK 缺少 mkdirat；仅以不可预测的空临时目录桥接，并在每个边界复核已认领 root fileKey。
                Files.createDirectory(root.resolve(temporaryName))
                hook?.invoke(WorkspaceFilePhase.DIRECTORY_TEMP_CREATED)
                verifyDirectoryBinding(root, emptyList(), rootKey, requireIdentity)
                rootStream.newDirectoryStream(Paths.get(temporaryName), LinkOption.NOFOLLOW_LINKS).use { }
                openDirectory(rootStream, relative.dropLast(1)).use { parent ->
                    val parentKey = directoryKey(parent)
                    hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
                    verifyDirectoryBinding(root, emptyList(), rootKey, requireIdentity)
                    verifyDirectoryBinding(root, relative.dropLast(1), parentKey, requireIdentity)
                    rootStream.move(Paths.get(temporaryName), parent, Paths.get(relative.last()))
                    try {
                        verifyDirectoryBinding(root, emptyList(), rootKey, requireIdentity)
                    } catch (failure: Throwable) {
                        runCatching { parent.deleteDirectory(Paths.get(relative.last())) }
                            .exceptionOrNull()?.let(failure::addSuppressed)
                        throw failure
                    }
                }
            } finally {
                // move 失败、目标冲突及 root swap 均不得遗留临时目录。
                runCatching { rootStream.deleteDirectory(Paths.get(temporaryName)) }
                runCatching { Files.deleteIfExists(root.resolve(temporaryName)) }
            }
        }
    }

    override fun ensureDirectory(root: Path, relative: List<String>) {
        requireSafeRelative(relative)
        val present = try {
            openSecure(root).use { rootStream -> openDirectory(rootStream, relative).use { } }
            true
        } catch (_: java.nio.file.NoSuchFileException) {
            false
        }
        if (!present) {
            try {
                createDirectory(root, relative)
            } catch (race: java.nio.file.FileAlreadyExistsException) {
                openSecure(root).use { rootStream -> openDirectory(rootStream, relative).use { } }
            }
        }
    }

    override fun replaceFile(
        root: Path,
        relative: List<String>,
        bytes: ByteArray,
    ): WorkspaceFileRollback = withParent(root, relative) { parent, name ->
        val token = stableNameToken(name.toString())
        val temporary = Paths.get(".nexara-write-$token")
        val backup = Paths.get(".nexara-previous-$token")
        writeNew(parent, temporary, bytes)
        try {
            hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
            verifyDirectoryBinding(root, relative.dropLast(1), directoryKey(parent))
            parent.move(name, parent, backup)
            try {
                parent.move(temporary, parent, name)
            } catch (failure: Throwable) {
                runCatching { parent.move(backup, parent, name) }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            DescriptorRollback(
                commitAction = { withParent(root, relative) { current, _ -> current.deleteFile(backup) } },
                rollbackAction = {
                    withParent(root, relative) { current, currentName ->
                        val displaced = Paths.get(".nexara-rollback-$token")
                        current.move(currentName, current, displaced)
                        try {
                            hook?.invoke(WorkspaceFilePhase.ROLLBACK_BEFORE_RESTORE)
                            current.move(backup, current, currentName)
                        } catch (failure: Throwable) {
                            // 恢复旧版失败时先恢复新版，保证规范路径始终存在；backup 保留供同一 rollback 重试。
                            runCatching { current.move(displaced, current, currentName) }
                                .exceptionOrNull()?.let(failure::addSuppressed)
                            throw failure
                        }
                        runCatching { current.deleteFile(displaced) }
                    }
                },
            )
        } finally {
            runCatching { parent.deleteFile(temporary) }
        }
    }

    override fun reconcileFile(root: Path, relative: List<String>, expectedHash: String) {
        withParent(root, relative) { parent, name ->
            val token = stableNameToken(name.toString())
            val temporary = Paths.get(".nexara-write-$token")
            val backup = Paths.get(".nexara-previous-$token")
            val displaced = Paths.get(".nexara-rollback-$token")
            if (!nodeExists(parent, temporary) && !nodeExists(parent, backup) && !nodeExists(parent, displaced)) {
                return@withParent
            }
            val targetHash = hashIfPresent(parent, name)
            val backupHash = hashIfPresent(parent, backup)
            when {
                targetHash == expectedHash -> {
                    runCatching { parent.deleteFile(backup) }
                    runCatching { parent.deleteFile(temporary) }
                    runCatching { parent.deleteFile(displaced) }
                }
                backupHash == expectedHash -> {
                    runCatching { parent.deleteFile(displaced) }
                    if (targetHash != null) parent.move(name, parent, displaced)
                    parent.move(backup, parent, name)
                    runCatching { parent.deleteFile(displaced) }
                    runCatching { parent.deleteFile(temporary) }
                }
                else -> throw IllegalStateException("工作区文件无法按数据库哈希恢复")
            }
        }
    }

    override fun move(
        root: Path,
        source: List<String>,
        target: List<String>,
    ): WorkspaceFileRollback {
        requireSafeRelative(source)
        requireSafeRelative(target)
        descriptorMove(root, source, target)
        return DescriptorRollback(
            commitAction = {},
            rollbackAction = { descriptorMove(root, target, source) },
        )
    }

    override fun stageDelete(root: Path, source: List<String>): WorkspaceFileRollback {
        requireSafeRelative(source)
        val tombstone = listOf(".nexara_tombstones", UUID.randomUUID().toString())
        ensureSystemDirectory(root, tombstone.first())
        descriptorMove(root, source, tombstone)
        return DescriptorRollback(
            commitAction = { deleteNode(root, tombstone) },
            rollbackAction = { descriptorMove(root, tombstone, source) },
        )
    }

    override fun delete(root: Path, source: List<String>) {
        deleteNode(root, source)
    }

    override fun cleanupTombstones(root: Path) {
        openSecure(root).use { secure ->
            val tombstones = try {
                secure.newDirectoryStream(Paths.get(".nexara_tombstones"), LinkOption.NOFOLLOW_LINKS)
            } catch (_: java.nio.file.NoSuchFileException) {
                return
            }
            tombstones.use(::deleteChildren)
        }
    }

    private fun descriptorMove(root: Path, source: List<String>, target: List<String>) {
        openSecure(root).use { rootStream ->
            openDirectory(rootStream, source.dropLast(1)).use { sourceParent ->
                openDirectory(rootStream, target.dropLast(1)).use { targetParent ->
                    hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
                    verifyDirectoryBinding(root, source.dropLast(1), directoryKey(sourceParent))
                    verifyDirectoryBinding(root, target.dropLast(1), directoryKey(targetParent))
                    sourceParent.move(
                        Paths.get(source.last()),
                        targetParent,
                        Paths.get(target.last()),
                    )
                }
            }
        }
    }

    private fun ensureSystemDirectory(root: Path, name: String) {
        openSecure(root).use { secure ->
            val exists = try {
                secure.newDirectoryStream(Paths.get(name), LinkOption.NOFOLLOW_LINKS).use { }
                true
            } catch (_: java.nio.file.NoSuchFileException) {
                false
            }
            if (!exists) createDirectory(root, listOf(name))
        }
    }

    private fun deleteNode(root: Path, relative: List<String>) {
        withParent(root, relative) { parent, name ->
            val directory = runCatching {
                parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
            if (directory == null) {
                parent.deleteFile(name)
            } else {
                directory.use(::deleteChildren)
                parent.deleteDirectory(name)
            }
        }
    }

    private fun deleteChildren(directory: SecureDirectoryStream<Path>) {
        directory.toList().forEach { childPath ->
            val name = childPath.fileName
            val child = runCatching {
                directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
            if (child == null) {
                directory.deleteFile(name)
            } else {
                child.use(::deleteChildren)
                directory.deleteDirectory(name)
            }
        }
    }

    private inline fun <T> withParent(
        root: Path,
        relative: List<String>,
        block: (SecureDirectoryStream<Path>, Path) -> T,
    ): T {
        requireSafeRelative(relative)
        openSecure(root).use { rootStream ->
            openDirectory(rootStream, relative.dropLast(1)).use { parent ->
                hook?.invoke(WorkspaceFilePhase.DESCRIPTOR_OPENED)
                return block(parent, Paths.get(relative.last()))
            }
        }
    }

    private fun openDirectory(
        root: SecureDirectoryStream<Path>,
        relative: List<String>,
    ): SecureDirectoryStream<Path> {
        var current = root.newDirectoryStream(Paths.get("."), LinkOption.NOFOLLOW_LINKS)
        try {
            relative.forEach { segment ->
                val next = current.newDirectoryStream(Paths.get(segment), LinkOption.NOFOLLOW_LINKS)
                current.close()
                current = next
            }
            return current
        } catch (failure: Throwable) {
            runCatching { current.close() }
            throw failure
        }
    }

    private fun writeNew(directory: SecureDirectoryStream<Path>, name: Path, bytes: ByteArray) {
        directory.newByteChannel(
            name,
            setOf<OpenOption>(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ),
        ).use { channel ->
            var offset = 0
            while (offset < bytes.size) {
                offset += channel.write(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            }
            (channel as? FileChannel)?.force(true)
                ?: throw IllegalStateException("当前文件系统不支持工作区文件 fsync")
        }
    }

    private fun nodeExists(directory: SecureDirectoryStream<Path>, name: Path): Boolean = try {
        directory.getFileAttributeView(
            name,
            java.nio.file.attribute.BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).readAttributes()
        true
    } catch (_: java.nio.file.NoSuchFileException) {
        false
    }

    private fun hashIfPresent(directory: SecureDirectoryStream<Path>, name: Path): String? = try {
        directory.newByteChannel(
            name,
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            hook?.invoke(WorkspaceFilePhase.RECONCILE_CONTENT_READ)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                digest.update(buffer.array(), 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    private fun stableNameToken(value: String): String = sha256(value.toByteArray()).take(24)

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun directoryKey(directory: SecureDirectoryStream<Path>): Any =
        directory.getFileAttributeView(
            Paths.get("."),
            java.nio.file.attribute.BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).readAttributes().fileKey()
            ?: throw SecurityException("工作区目录缺少稳定 fileKey")

    private fun verifyDirectoryBinding(
        root: Path,
        relative: List<String>,
        expectedKey: Any,
        requireIdentity: Boolean = true,
    ) {
        try {
            openSecure(root, requireIdentity).use { rootStream ->
                openDirectory(rootStream, relative).use { current ->
                    if (directoryKey(current) != expectedKey) {
                        throw SecurityException("工作区目录路径绑定发生变化")
                    }
                }
            }
        } catch (security: SecurityException) {
            throw security
        } catch (failure: Throwable) {
            throw SecurityException("工作区目录路径绑定无法复核", failure)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun openSecure(root: Path, requireIdentity: Boolean = true): SecureDirectoryStream<Path> {
        val expected = Files.readAttributes(
            root,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (expected.isSymbolicLink || !expected.isDirectory || expected.fileKey() == null) {
            throw SecurityException("工作区根目录身份无效")
        }
        val stream: DirectoryStream<Path> = Files.newDirectoryStream(root)
        val secure = stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            throw SecurityException("当前文件系统不支持 SecureDirectoryStream，拒绝降低路径安全保证")
        }
        val actual = try {
            secure.getFileAttributeView(
                Paths.get("."),
                java.nio.file.attribute.BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
        } catch (failure: Throwable) {
            secure.close()
            throw failure
        }
        if (!actual.isDirectory || actual.fileKey() != expected.fileKey()) {
            secure.close()
            throw SecurityException("工作区根目录在打开期间发生替换")
        }
        if (requireIdentity) verifyRootIdentity(root, secure, actual.fileKey())
        return secure
    }

    private fun ensureRootIdentity(
        root: SecureDirectoryStream<Path>,
        initialize: Boolean,
        expectedIdentity: String?,
    ): String {
        val key = directoryKey(root).toString()
        val existing = readIdentity(root)
        val actual = when {
            existing != null -> existing
            !initialize -> throw SecurityException("工作区根缺少持久身份标记")
            else -> "${UUID.randomUUID()}\n$key".also {
                writeNew(root, Paths.get(ROOT_IDENTITY_FILE), it.toByteArray(Charsets.UTF_8))
            }
        }
        if (actual.substringAfter('\n', "") != key) throw SecurityException("工作区根身份标记与目录 fileKey 不一致")
        val nonce = actual.substringBefore('\n')
        val identity = "$nonce|${sha256(key.toByteArray(Charsets.UTF_8))}"
        if (expectedIdentity != null && identity != expectedIdentity) {
            throw SecurityException("工作区根身份与数据库认领不一致")
        }
        return identity
    }

    private fun verifyRootIdentity(path: Path, root: SecureDirectoryStream<Path>, fileKey: Any?) {
        val expected = readIdentity(root) ?: throw SecurityException("工作区根缺少持久身份标记")
        val trusted = WorkspaceMutationCoordinator.expectedIdentity(path)
            ?: throw SecurityException("工作区根缺少进程内数据库身份绑定")
        val actualKey = fileKey?.toString()
        val actualIdentity = actualKey?.let {
            "${expected.substringBefore('\n')}|${sha256(it.toByteArray(Charsets.UTF_8))}"
        }
        if (actualKey == null || expected.substringAfter('\n', "") != actualKey || actualIdentity != trusted) {
            throw SecurityException("工作区根身份标记与目录 fileKey 不一致")
        }
    }

    private fun readIdentity(root: SecureDirectoryStream<Path>): String? = try {
        root.newByteChannel(
            Paths.get(ROOT_IDENTITY_FILE),
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(256)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (output.size() + count > 1024) throw SecurityException("工作区根身份标记过大")
                output.write(buffer.array(), 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    private fun requireSafeRelative(relative: List<String>) {
        require(relative.isNotEmpty()) { "工作区相对路径不能为空" }
        if (relative.any { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it || '\u0000' in it }) {
            throw SecurityException("工作区相对路径无效")
        }
    }

    private class DescriptorRollback(
        private val commitAction: () -> Unit,
        private val rollbackAction: () -> Unit,
    ) : WorkspaceFileRollback {
        private var completed = false

        @Synchronized
        override fun commit() {
            if (completed) return
            commitAction()
            completed = true
        }

        @Synchronized
        override fun rollback() {
            if (completed) return
            rollbackAction()
            completed = true
        }
    }

    private companion object {
        const val ROOT_IDENTITY_FILE = ".nexara_root_identity"
    }
}
