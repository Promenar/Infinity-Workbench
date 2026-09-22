package com.promenar.nexara.data.local.db.recovery

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SnapshotFile(val relativePath: String, val size: Long, val sha256: String)

@Serializable
data class SourceSnapshotManifest(
    val transactionId: String,
    val sourceIdentitySha256: String,
    val sourceManifestSha256: String,
    val files: List<SnapshotFile>,
    val totalBytes: Long,
    val directories: List<String> = emptyList(),
    val formatVersion: Int = 2,
)

data class MaterializedRecoveryTree(val manifest: SourceSnapshotManifest, val rootIdentity: String)

internal enum class SnapshotPhase {
    SOURCE_SCANNED, BEFORE_COPY_FILE, COPY_CHUNK, COPIED, BEFORE_SOURCE_RECHECK,
    BEFORE_PUBLISH, AFTER_RENAME, BEFORE_ARCHIVE_FSYNC, BEFORE_REUSE_VERIFY,
}

/** rename已完成但持久化尚未确认；相同transactionId须验证已发布树并重新fsync。 */
class SnapshotPublicationUncertainException(val transactionId: String, cause: Throwable) :
    IOException("快照已发布但持久化待确认，请使用相同事务重试", cause)

/** 失败目录仅保留，不按路径猜测递归清理。 */
class SnapshotStagingRetainedException(val stagingDirectory: Path, cause: Throwable) :
    IllegalStateException("快照未发布，源或临时目录核验失败；临时目录已保留", cause)

/** 调用者必须冻结所有writer并持有跨进程锁；本类不提供SQLite在线一致性。 */
class RecoverySnapshotStore private constructor(
    allowedSourceRoots: Set<Path>, private val maxFiles: Int, private val maxTotalBytes: Long,
    private val fileSystem: SnapshotFileSystem, private val beforePublish: (() -> Unit)?,
    private val hook: ((SnapshotPhase) -> Unit)?,
) {
    constructor(allowedSourceRoots: Set<Path>, maxFiles: Int, maxTotalBytes: Long, trustedAppDataRoot: Path) :
        this(allowedSourceRoots, maxFiles, maxTotalBytes, AndroidSnapshotFileSystem(trustedAppDataRoot), null, null)

    private val allowedRoots = allowedSourceRoots.map(::snapshotAbsolutePath).associateWith { path ->
        fileSystem.openDirectory(path).use { it.key }
    }
    init {
        require(allowedRoots.isNotEmpty()) { "至少需要一个显式允许根" }
        require(allowedRoots.size == allowedSourceRoots.size) { "允许根不能重复" }
        require(maxFiles > 0 && maxTotalBytes >= 0)
    }

    fun createSnapshot(transactionId: String, sourceRoot: Path, databaseFile: Path, archiveRoot: Path): SourceSnapshotManifest =
        create(transactionId, sourceRoot, databaseFile, archiveRoot)
    fun createTreeSnapshot(transactionId: String, sourceRoot: Path, archiveRoot: Path): SourceSnapshotManifest =
        create(transactionId, sourceRoot, null, archiveRoot)

    /** 只认证永久归档，不再扫描可能已有本事务输出或业务新写入的原source。摘要来自已认证journal。 */
    fun readVerifiedArchive(
        transactionId: String, archiveRoot: Path, expectedSourceManifestSha256: String,
        expectedSourceIdentitySha256: String? = null,
    ): SourceSnapshotManifest {
        require(transactionId.matches(Regex("[A-Za-z0-9_-]{1,96}"))) { "快照事务编号无效" }
        require(expectedSourceManifestSha256.matches(Regex("[0-9a-f]{64}"))) { "归档预期摘要无效" }
        val archivePath = snapshotAbsolutePath(archiveRoot)
        require(allowedRoots.keys.none { archivePath.startsWith(it) || it.startsWith(archivePath) }) {
            "归档必须与全部允许源根隔离"
        }
        return fileSystem.openDirectory(archivePath).use { archive ->
            archive.openDirectory(transactionId).use { transaction ->
                val manifest = readManifest(transaction)
                check(manifest.transactionId == transactionId &&
                    manifest.sourceManifestSha256 == expectedSourceManifestSha256 &&
                    (expectedSourceIdentitySha256 == null || manifest.sourceIdentitySha256 == expectedSourceIdentitySha256)) {
                    "归档manifest与认证journal不一致"
                }
                verifyArchive(transaction, manifest)
                check(archive.node(transactionId)?.key == transaction.key) { "归档事务目录绑定变化" }
                requireBinding(archivePath, archive.key)
                manifest
            }
        }
    }

    /**
     * 从认证归档物化精确私有树。未指定子集时保留完整工作区；指定子集只复制精确普通文件和父链。
     * 只排除旧根identity，生成新nonce与inode绑定；
     * onPrepared必须在返回前把transaction、target和identity写入认证journal，才允许rename。
     */
    fun materializeVerifiedArchive(
        transactionId: String, archiveRoot: Path, expectedSourceManifestSha256: String,
        targetDirectory: Path, expectedTargetIdentity: String? = null,
        selectedRelativeFiles: Set<String>? = null,
        onPrepared: (String) -> Unit,
    ): MaterializedRecoveryTree {
        val original = readVerifiedArchive(transactionId, archiveRoot, expectedSourceManifestSha256)
        check(original.directories.none { it == ROOT_IDENTITY || it.startsWith("$ROOT_IDENTITY/") }) {
            "归档根身份位置不是普通文件"
        }
        val selected = selectedRelativeFiles?.also { selection ->
            require(selection.size <= maxFiles) { "物化文件选择超过限额" }
            selection.forEach { relativeParts(it) }
            val available = original.files.map { it.relativePath }.toSet()
            require(selection.all { it != ROOT_IDENTITY && it in available }) {
                "物化选择包含缺失文件、目录或保留身份位置"
            }
        }
        val filtered = original.files.filter { it.relativePath != ROOT_IDENTITY &&
            (selected == null || it.relativePath in selected) }
        val directories = if (selected == null) original.directories else selected.flatMap { relative ->
            val parts = relativeParts(relative)
            (1 until parts.size).map { parts.take(it).joinToString("/") }
        }.distinct().sorted().also { parents ->
            check(parents.all { it in original.directories }) { "物化文件父链不在认证清单内" }
        }
        val expected = original.copy(files = filtered, totalBytes = filtered.sumOf { it.size },
            directories = directories, sourceManifestSha256 = inventoryHash(filtered, directories))
        val archivePath = snapshotAbsolutePath(archiveRoot)
        val targetPath = snapshotAbsolutePath(targetDirectory)
        require(!targetPath.startsWith(archivePath) && !archivePath.startsWith(targetPath)) { "物化目标与永久归档必须隔离" }
        val parentPath = requireNotNull(targetPath.parent)
        val targetName = targetPath.fileName.toString().also(::safeSnapshotName)
        return fileSystem.openDirectory(archivePath).use { archive ->
            archive.openDirectory(transactionId).use { transaction ->
                verifyArchive(transaction, original)
                fileSystem.openDirectory(parentPath, createParents = true).use publication@{ parent ->
                    fun verifyTarget(identity: String): MaterializedRecoveryTree = parent.openDirectory(targetName).use { target ->
                        verifyMaterializedIdentity(target, identity)
                        val actual = scan(target, transactionId, null, ignoreRootIdentity = true)
                        check(actual.files == expected.files && actual.directories == expected.directories &&
                            actual.totalBytes == expected.totalBytes) { "物化目标与认证归档清单不符" }
                        check(parent.node(targetName)?.key == target.key) { "物化目标绑定变化" }
                        requireBinding(parentPath, parent.key)
                        requireBinding(archivePath, archive.key)
                        check(archive.node(transactionId)?.key == transaction.key) { "永久归档绑定变化" }
                        MaterializedRecoveryTree(actual, identity)
                    }
                    if (parent.node(targetName) != null) {
                        val identity = expectedTargetIdentity ?: throw SecurityException("已有物化目标缺少本事务认证回执")
                        verifyTarget(identity)
                        parent.force()
                        return@publication verifyTarget(identity)
                    }
                    val stagingName = ".recovery-materialize-${UUID.randomUUID()}"
                    val stagingPath = parentPath.resolve(stagingName)
                    var renamed = false
                    var stagedKey: String? = null
                    try {
                        parent.createDirectory(stagingName).use { staging ->
                            stagedKey = staging.key
                            expected.directories.forEach { ensureRelativeDirectory(staging, it) }
                            transaction.openDirectory(FILES).use { source -> copyFiles(source, staging, expected.files) }
                            val identity = "${UUID.randomUUID()}|${hash(staging.key.toByteArray(Charsets.UTF_8))}"
                            writeFile(staging, ROOT_IDENTITY,
                                "${identity.substringBefore('|')}\n${staging.key}".toByteArray(Charsets.UTF_8))
                            forceTree(staging, expected.directories)
                            staging.force()
                            verifyArchive(transaction, original)
                            val copied = scan(staging, transactionId, null, ignoreRootIdentity = true)
                            check(copied.files == expected.files && copied.directories == expected.directories) { "物化副本校验失败" }
                            verifyMaterializedIdentity(staging, identity)
                            onPrepared(identity)
                            hook?.invoke(SnapshotPhase.BEFORE_PUBLISH)
                            requireBinding(parentPath, parent.key)
                            check(parent.node(stagingName)?.key == staging.key) { "物化临时目录已替换" }
                            parent.move(stagingName, parent, targetName)
                            renamed = true
                            hook?.invoke(SnapshotPhase.AFTER_RENAME)
                            verifyTarget(identity)
                            hook?.invoke(SnapshotPhase.BEFORE_ARCHIVE_FSYNC)
                            parent.force()
                            verifyTarget(identity)
                        }
                    } catch (failure: Throwable) {
                        val moved = stagedKey != null && runCatching { parent.node(targetName)?.key == stagedKey }.getOrDefault(false)
                        if (renamed || moved) throw SnapshotPublicationUncertainException(transactionId, failure)
                        throw SnapshotStagingRetainedException(stagingPath, failure)
                    }
                }
            }
        }
    }

    private fun verifyMaterializedIdentity(directory: SnapshotDirectory, expected: String) {
        check(expected.matches(Regex("[0-9a-f-]{36}[|][0-9a-f]{64}"))) { "物化目标回执身份格式无效" }
        val node = directory.node(ROOT_IDENTITY)?.takeUnless { it.directory } ?: error("物化目标缺少根身份")
        check(node.size in 1..1024) { "物化根身份大小无效" }
        val bytes = java.io.ByteArrayOutputStream(node.size.toInt())
        directory.read(ROOT_IDENTITY).use { input ->
            transferBounded(input, node.size, 1024) { buffer, count -> bytes.write(buffer, 0, count) }
        }
        check(directory.node(ROOT_IDENTITY) == node && bytes.toString("UTF-8") ==
            "${expected.substringBefore('|')}\n${directory.key}" && expected.substringAfter('|') ==
            hash(directory.key.toByteArray(Charsets.UTF_8))) { "物化根身份与认证回执或inode不符" }
    }

    private fun create(tx: String, sourceRoot: Path, databaseFile: Path?, archiveRoot: Path): SourceSnapshotManifest {
        require(tx.matches(Regex("[A-Za-z0-9_-]{1,96}"))) { "快照事务编号无效" }
        val sourcePath = snapshotAbsolutePath(sourceRoot)
        val archivePath = snapshotAbsolutePath(archiveRoot)
        val allowed = allowedRoots.keys.firstOrNull { sourcePath.startsWith(it) }
            ?: throw IllegalArgumentException("源根不在显式允许根内")
        require(allowedRoots.keys.none { archivePath.startsWith(it) || it.startsWith(archivePath) }) {
            "归档必须与全部允许源根隔离"
        }
        val databaseRelative = databaseFile?.let {
            val databasePath = snapshotAbsolutePath(it)
            require(databasePath.startsWith(sourcePath) && databasePath != sourcePath) { "数据库必须位于源根内" }
            sourcePath.relativize(databasePath).joinToString("/")
        }
        openAllowedSource(allowed, sourcePath).use { source ->
            databaseRelative?.let { path -> withFileParent(source, path) { parent, name ->
                require(parent.node(name)?.directory == false) { "数据库主文件不存在或类型无效" }
            } }
            val before = scan(source, tx, databaseRelative)
            hook?.invoke(SnapshotPhase.SOURCE_SCANNED)
            fileSystem.openDirectory(archivePath, createParents = true).use { archive ->
                if (archive.node(tx) != null) {
                    hook?.invoke(SnapshotPhase.BEFORE_REUSE_VERIFY)
                    var existingKey: String? = null
                    archive.openDirectory(tx).use { existing ->
                        existingKey = existing.key
                        val stored = readManifest(existing)
                        check(stored == before) { "源与既有manifest不一致，拒绝复用" }
                        verifyArchive(existing, stored)
                    }
                    check(scan(source, tx, databaseRelative) == before) { "复用期间源发生变化" }
                    requireBinding(sourcePath, source.key)
                    requireBinding(allowed, allowedRoots.getValue(allowed))
                    requireBinding(archivePath, archive.key)
                    try { hook?.invoke(SnapshotPhase.BEFORE_ARCHIVE_FSYNC); archive.force() }
                    catch (failure: Throwable) { throw SnapshotPublicationUncertainException(tx, failure) }
                    try {
                        verifyPublished(archive, tx, requireNotNull(existingKey), before)
                        requireBinding(sourcePath, source.key)
                        requireBinding(allowed, allowedRoots.getValue(allowed))
                        requireBinding(archivePath, archive.key)
                    } catch (failure: Throwable) { throw SnapshotPublicationUncertainException(tx, failure) }
                    return before
                }
                val stagingName = ".$tx.tmp-${UUID.randomUUID()}"
                val stagingPath = archivePath.resolve(stagingName)
                var renamed = false
                var stagingKey: String? = null
                try {
                    archive.createDirectory(stagingName).use { staging ->
                        stagingKey = staging.key
                        staging.createDirectory(FILES).use { destination ->
                            before.directories.forEach { path -> ensureRelativeDirectory(destination, path) }
                            copyFiles(source, destination, before.files)
                            verifyTree(destination, before)
                            forceTree(destination, before.directories)
                        }
                        hook?.invoke(SnapshotPhase.COPIED)
                        beforePublish?.invoke()
                        hook?.invoke(SnapshotPhase.BEFORE_SOURCE_RECHECK)
                        check(scan(source, tx, databaseRelative) == before) { "源在复制期间发生变化" }
                        requireBinding(sourcePath, source.key)
                        requireBinding(allowed, allowedRoots.getValue(allowed))
                        writeFile(staging, MANIFEST, json.encodeToString(before).toByteArray(Charsets.UTF_8))
                        verifyArchive(staging, before)
                        staging.force()
                        hook?.invoke(SnapshotPhase.BEFORE_PUBLISH)
                        requireBinding(archivePath, archive.key)
                        verifyArchive(staging, before)
                        check(archive.node(stagingName)?.key == staging.key) { "临时目录路径绑定已替换" }
                        check(archive.node(tx) == null) { "目标快照已存在，拒绝覆盖" }
                        archive.move(stagingName, archive, tx)
                        renamed = true
                    }
                    hook?.invoke(SnapshotPhase.AFTER_RENAME)
                    verifyPublished(archive, tx, requireNotNull(stagingKey), before)
                    requireBinding(sourcePath, source.key)
                    requireBinding(allowed, allowedRoots.getValue(allowed))
                    requireBinding(archivePath, archive.key)
                    hook?.invoke(SnapshotPhase.BEFORE_ARCHIVE_FSYNC)
                    archive.force()
                    verifyPublished(archive, tx, requireNotNull(stagingKey), before)
                    requireBinding(sourcePath, source.key)
                    requireBinding(allowed, allowedRoots.getValue(allowed))
                    requireBinding(archivePath, archive.key)
                    return before
                } catch (failure: Throwable) {
                    val published = renamed || (stagingKey != null && runCatching { archive.node(tx)?.key == stagingKey }.getOrDefault(false))
                    if (published) throw SnapshotPublicationUncertainException(tx, failure)
                    throw SnapshotStagingRetainedException(stagingPath, failure)
                }
            }
        }
    }

    private fun scan(source: SnapshotDirectory, tx: String, database: String?, ignoreRootIdentity: Boolean = false): SourceSnapshotManifest {
        val files = mutableListOf<SnapshotFile>()
        val directories = mutableListOf<String>()
        var total = 0L
        fun visit(directory: SnapshotDirectory, prefix: String) {
            val names = directory.names(minOf(maxFiles * 2L + if (ignoreRootIdentity) 1 else 0, Int.MAX_VALUE.toLong()).toInt()).sorted()
            names.forEach { name ->
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                relativeParts(relative)
                val node = directory.node(name) ?: error("源节点在枚举期间消失")
                if (ignoreRootIdentity && relative == ROOT_IDENTITY) {
                    check(!node.directory) { "物化根身份不是文件" }
                    return@forEach
                }
                if (node.directory) {
                    check(directories.size < maxFiles) { "源目录数超过限额" }
                    directories += relative
                    directory.openDirectory(name).use { visit(it, relative) }
                } else if (relative != database?.plus("-shm")) {
                    check(files.size < maxFiles) { "源文件数超过文件数限额" }
                    check(node.size >= 0 && node.size <= maxTotalBytes - total) { "源文件总字节数超过字节限额" }
                    val digest = digest(directory, name, node.size, node.key)
                    files += SnapshotFile(relative, node.size, digest)
                    total += node.size
                }
            }
        }
        visit(source, "")
        val sortedFiles = files.sortedBy { it.relativePath }
        val sortedDirectories = directories.sorted()
        return SourceSnapshotManifest(tx, hash((source.path.toString() + "\n" + source.key).toByteArray()),
            inventoryHash(sortedFiles, sortedDirectories), sortedFiles, total, sortedDirectories)
    }

    private fun copyFiles(source: SnapshotDirectory, target: SnapshotDirectory, files: List<SnapshotFile>) {
        var remaining = maxTotalBytes
        files.forEach { expected ->
            hook?.invoke(SnapshotPhase.BEFORE_COPY_FILE)
            check(expected.size <= remaining) { "复制总字节超过预算" }
            withFileParent(source, expected.relativePath) { sourceParent, name ->
                val original = sourceParent.node(name)?.takeUnless { it.directory } ?: error("复制源文件不存在")
                check(original.size == expected.size) { "复制源大小已变化" }
                withFileParent(target, expected.relativePath) { destinationParent, destinationName ->
                    sourceParent.read(name).use { input ->
                        destinationParent.writeNew(destinationName).use { output ->
                            val digest = transferBounded(input, expected.size, remaining) { bytes, count ->
                                var offset = 0
                                while (offset < count) offset += output.write(ByteBuffer.wrap(bytes, offset, count - offset))
                                hook?.invoke(SnapshotPhase.COPY_CHUNK)
                            }
                            check(digest == expected.sha256) { "复制字节摘要与来源manifest不一致" }
                            output.force(true)
                        }
                    }
                }
                check(sourceParent.node(name) == original) { "复制源身份或大小已变化" }
            }
            remaining -= expected.size
        }
    }

    private fun verifyArchive(directory: SnapshotDirectory, manifest: SourceSnapshotManifest) {
        check(directory.names(3).toSet() == setOf(FILES, MANIFEST)) { "归档事务目录含未知内容" }
        check(readManifest(directory) == manifest) { "归档manifest不一致" }
        directory.openDirectory(FILES).use { verifyTree(it, manifest) }
    }
    private fun verifyPublished(archive: SnapshotDirectory, tx: String, expectedKey: String, manifest: SourceSnapshotManifest) {
        archive.openDirectory(tx).use { published ->
            check(published.key == expectedKey) { "已发布目录身份不匹配" }
            verifyArchive(published, manifest)
        }
    }
    private fun verifyTree(directory: SnapshotDirectory, expected: SourceSnapshotManifest) {
        val actual = scan(directory, expected.transactionId, null)
        check(actual.files == expected.files && actual.directories == expected.directories &&
            actual.totalBytes == expected.totalBytes) { "归档文件/目录清单或字节摘要不一致" }
    }
    private fun readManifest(directory: SnapshotDirectory): SourceSnapshotManifest {
        val node = directory.node(MANIFEST)?.takeUnless { it.directory } ?: error("目标快照缺少manifest")
        check(node.size in 1..MAX_MANIFEST_BYTES) { "目标manifest超过读取限额" }
        val bytes = java.io.ByteArrayOutputStream(node.size.toInt())
        directory.read(MANIFEST).use { input -> transferBounded(input, node.size, MAX_MANIFEST_BYTES) { buffer, count -> bytes.write(buffer, 0, count) } }
        check(directory.node(MANIFEST) == node) { "目标manifest读取期间变化" }
        val manifest = try { json.decodeFromString<SourceSnapshotManifest>(bytes.toString("UTF-8")) }
            catch (failure: Exception) { throw IllegalStateException("目标manifest无法解析", failure) }
        check(manifest.formatVersion == 2 && manifest.files.size <= maxFiles && manifest.directories.size <= maxFiles) { "manifest版本或条目限额无效" }
        check(manifest.files == manifest.files.sortedBy { it.relativePath } && manifest.directories == manifest.directories.sorted()) { "manifest顺序无效" }
        check(manifest.files.map { it.relativePath }.distinct().size == manifest.files.size && manifest.directories.distinct().size == manifest.directories.size) { "manifest路径重复" }
        var total = 0L
        manifest.files.forEach { file ->
            relativeParts(file.relativePath)
            check(file.size >= 0 && file.size <= maxTotalBytes - total && file.sha256.matches(Regex("[0-9a-f]{64}"))) { "manifest字节预算或摘要无效" }
            total += file.size
        }
        manifest.directories.forEach(::relativeParts)
        check(total == manifest.totalBytes && manifest.sourceManifestSha256 == inventoryHash(manifest.files, manifest.directories)) { "manifest总量或清单摘要不一致" }
        return manifest
    }
    private fun digest(parent: SnapshotDirectory, name: String, size: Long, key: String): String = parent.read(name).use { input ->
        transferBounded(input, size, maxTotalBytes) { _, _ -> }.also {
            val after = parent.node(name)
            check(after != null && !after.directory && after.key == key && after.size == size) { "源文件在扫描期间发生变化" }
        }
    }
    private fun transferBounded(input: SeekableByteChannel, expectedSize: Long, budget: Long, consume: (ByteArray, Int) -> Unit): String {
        check(expectedSize >= 0 && expectedSize <= budget && input.size() == expectedSize) { "文件实际大小超过预算或发生变化" }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(64 * 1024)
        var read = 0L
        while (read < expectedSize) {
            val requested = minOf(bytes.size.toLong(), expectedSize - read, budget - read).toInt()
            check(requested > 0) { "文件实际读取超过预算" }
            val count = input.read(ByteBuffer.wrap(bytes, 0, requested))
            check(count > 0) { "文件读取提前结束" }
            consume(bytes, count)
            digest.update(bytes, 0, count)
            read += count
        }
        check(input.size() == expectedSize) { "文件读取期间增长或缩短" }
        return digest.digest().hex()
    }
    private fun ensureRelativeDirectory(root: SnapshotDirectory, path: String) {
        var current = root
        try {
            relativeParts(path).forEach { name ->
                val next = if (current.node(name) == null) current.createDirectory(name) else current.openDirectory(name)
                if (current !== root) current.close()
                current = next
            }
        } finally { if (current !== root) current.close() }
    }
    private fun forceTree(root: SnapshotDirectory, directories: List<String>) {
        directories.sortedByDescending { it.count { character -> character == '/' } }.forEach { path ->
            withFileParent(root, path) { parent, name -> parent.openDirectory(name).use { it.force() } }
        }
        root.force()
    }
    private fun writeFile(parent: SnapshotDirectory, name: String, bytes: ByteArray) {
        check(bytes.size.toLong() <= MAX_MANIFEST_BYTES) { "manifest超过写入限额" }
        parent.writeNew(name).use { output ->
            var offset = 0
            while (offset < bytes.size) offset += output.write(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            output.force(true)
        }
    }
    private fun requireBinding(path: Path, expected: String) {
        fileSystem.openDirectory(path).use { check(it.key == expected) { "快照根路径绑定发生变化" } }
    }
    private fun openAllowedSource(allowed: Path, source: Path): SnapshotDirectory {
        var current = fileSystem.openDirectory(allowed)
        try {
            require(current.key == allowedRoots.getValue(allowed)) { "允许源根已替换" }
            if (source != allowed) allowed.relativize(source).forEach { segment ->
                val next = current.openDirectory(segment.toString())
                current.close()
                current = next
            }
            return current
        } catch (failure: Throwable) { current.close(); throw failure }
    }
    private fun <T> withFileParent(root: SnapshotDirectory, path: String, block: (SnapshotDirectory, String) -> T): T {
        val parts = relativeParts(path)
        var current = root
        try {
            parts.dropLast(1).forEach { part ->
                val next = current.openDirectory(part)
                if (current !== root) current.close()
                current = next
            }
            return block(current, parts.last())
        } finally { if (current !== root) current.close() }
    }
    private fun relativeParts(path: String): List<String> = path.split('/').also { parts ->
        require(parts.size <= 128) { "快照目录深度超过限额" }
        parts.forEach(::safeSnapshotName)
    }
    private fun inventoryHash(files: List<SnapshotFile>, directories: List<String>): String =
        hash((json.encodeToString(files) + "\n" + json.encodeToString(directories)).toByteArray(Charsets.UTF_8))
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    companion object {
        private const val FILES = "files"
        private const val MANIFEST = "manifest.json"
        private const val ROOT_IDENTITY = ".nexara_root_identity"
        private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        internal fun forTest(allowedSourceRoots: Set<Path>, maxFiles: Int, maxTotalBytes: Long,
            fileSystem: SnapshotFileSystem, beforePublish: (() -> Unit)? = null, hook: ((SnapshotPhase) -> Unit)? = null) =
            RecoverySnapshotStore(allowedSourceRoots, maxFiles, maxTotalBytes, fileSystem, beforePublish, hook)
    }
}

/** 只展开系统约定的精确alias；普通用户路径仍由逐段NOFOLLOW打开拒绝。 */
private fun snapshotAbsolutePath(path: Path): Path {
    var normalized = path.toAbsolutePath().normalize()
    listOf("/tmp" to "/private/tmp", "/var" to "/private/var").forEach { (alias, destination) ->
        val prefix = Paths.get(alias)
        if (normalized.startsWith(prefix) && Files.isSymbolicLink(prefix)) {
            check(prefix.parent.resolve(Files.readSymbolicLink(prefix)).normalize() == Paths.get(destination)) { "系统alias映射异常" }
            normalized = Paths.get(destination).resolve(prefix.relativize(normalized))
        }
    }
    return normalized
}
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
