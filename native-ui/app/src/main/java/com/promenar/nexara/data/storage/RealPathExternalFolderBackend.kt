package com.promenar.nexara.data.storage

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.promenar.nexara.data.repository.AndroidWorkspaceFileMoves
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderEntry
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import com.promenar.nexara.domain.workspace.WorkspaceInternalPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RealPathExternalFolderBackend internal constructor(
    root: File,
    private val forbiddenRoots: Set<Path>,
    private val permissionCheck: () -> Boolean,
    private val fileSystem: RealPathFileSystem,
) : ExternalFolderBackend {
    constructor(root: File) : this(root, emptySet(), { true }, AndroidRealPathFileSystem)

    private val rootPath = root.absoluteFile.toPath().normalize()
    private val lock = locks.getOrPut(rootPath.toString()) { Mutex() }

    override suspend fun stat(path: String): ExternalFolderEntry? = io {
        val normalized = ExternalFolderPaths.normalize(path)
        withRoot { root ->
            if (normalized == "/") return@withRoot entry(normalized, root.state())
            try {
                withParent(root, normalized) { parent, name -> snapshot(parent, name, normalized, hash = true, smallOnly = true) }
            } catch (exception: ExternalFolderException) {
                if (exception.code == "NOT_FOUND") null else throw exception
            }
        }
    }

    override suspend fun list(path: String): List<ExternalFolderEntry> = io {
        val normalized = ExternalFolderPaths.normalize(path)
        withRoot { root ->
            withDirectory(root, normalized) { directory ->
                directory.names().mapNotNull { name ->
                    currentCoroutineContext().ensureActive()
                    val childPath = join(normalized, name)
                    if (!visibleName(name) || isForbidden(logical(childPath))) return@mapNotNull null
                    val state = directory.stat(name) ?: return@mapNotNull null
                    if (state.isSymbolicLink) return@mapNotNull null
                    requireSupported(state)
                    entry(childPath, state)
                }.sortedWith(compareByDescending<ExternalFolderEntry> { it.isDirectory }.thenBy { it.name })
            }
        }
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray = io {
        if (maxBytes < 0 || maxBytes > Int.MAX_VALUE - 8L) fail("INVALID_ARGUMENT", "读取上限无效")
        val normalized = ExternalFolderPaths.normalize(path)
        if (normalized == "/") fail("IS_DIRECTORY", "目标是目录")
        withRoot { root ->
            withParent(root, normalized) { parent, name ->
                requireFile(parent.stat(name) ?: fail("NOT_FOUND", "文件不存在"))
                parent.openRead(name).use { source ->
                    val before = source.state()
                    requireFile(before)
                    if (before.size > maxBytes) fail("TOO_LARGE", "文件超过读取上限")
                    val output = ByteArrayOutputStream()
                    copyBounded(source.input, output, maxBytes)
                    if (source.state().revision != before.revision) fail("CONFLICT", "文件在读取期间发生变化")
                    output.toByteArray()
                }
            }
        }
    }

    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry = io {
        val normalized = mutationPath(path)
        withRoot { root ->
            withParent(root, normalized) { parent, name ->
                val original = snapshot(parent, name, normalized, hash = true)
                checkVersion(original, expectedSha256, createOnly)
                val originalState = original?.let { stateFor(parent, name, it) }
                withInternalDirectory(root) { internal ->
                    val temporary = ".nexara-write-${UUID.randomUUID()}"
                    var temporaryId: String? = null
                    var isolated: String? = null
                    try {
                        internal.createFile(temporary).use { destination ->
                            temporaryId = destination.state().objectId
                            copyBounded(content.inputStream(), destination.output, content.size.toLong())
                            destination.sync()
                        }
                        currentCoroutineContext().ensureActive()
                        val current = snapshot(parent, name, normalized, hash = true)
                        checkVersion(current, expectedSha256, createOnly)
                        if (current?.identity != original?.identity || current?.sha256 != original?.sha256) {
                            fail("CONFLICT", "文件在写入期间发生变化，请重新读取")
                        }
                        if (original != null) {
                            val quarantine = ".nexara-replace-${UUID.randomUUID()}"
                            try { parent.moveNew(name, internal, quarantine) }
                            catch (failure: Throwable) {
                                if (internal.stat(quarantine) != null) recoverOrThrow(internal, quarantine, parent, name, normalized, failure)
                                throw moveFailure(failure)
                            }
                            isolated = quarantine
                            verifyIsolated(internal, quarantine, normalized, original, originalState!!)
                        }
                        val publishedId = publishNew(internal, temporary, parent, name)
                        // 只清理本次创建且身份仍匹配的临时副本。
                        cleanupOwnedFile(internal, temporary, temporaryId)
                        temporaryId = null
                        val published = snapshot(parent, name, normalized, hash = true) ?: fail("UNAVAILABLE", "写入后的文档不可用")
                        if (parent.stat(name)?.objectId != publishedId || published.sha256 != sha256(content)) fail("CONFLICT", "发布文档在校验期间发生变化")
                        isolated?.let { quarantine ->
                            verifyIsolated(internal, quarantine, normalized, original!!, originalState!!)
                            internal.deleteFile(quarantine)
                            isolated = null
                        }
                        published
                    } catch (exception: Throwable) {
                        isolated?.let { recoverOrThrow(internal, it, parent, name, normalized, exception) }
                        throw exception
                    } finally {
                        cleanupOwnedFile(internal, temporary, temporaryId)
                    }
                }
            }
        }
    }

    override suspend fun createDirectory(path: String): ExternalFolderEntry = io {
        val normalized = mutationPath(path)
        withRoot { root ->
            withParent(root, normalized) { parent, name ->
                if (parent.stat(name) != null) fail("CONFLICT", "同名文件或目录已经存在")
                parent.createDirectory(name)
                parent.openDirectory(name).use { entry(normalized, it.state()) }
            }
        }
    }

    override suspend fun delete(path: String, expectedSha256: String?) = io {
        val normalized = mutationPath(path)
        if (forbiddenRoots.any { withinIgnoringCase(it, logical(normalized)) }) fail("INVALID_PATH", "删除范围包含受保护目录")
        withRoot { root ->
            withParent(root, normalized) { parent, name ->
                val original = snapshot(parent, name, normalized, hash = true) ?: fail("NOT_FOUND", "文件或目录不存在")
                val originalState = stateFor(parent, name, original)
                if (expectedSha256 != null && (original.isDirectory || original.sha256 != expectedSha256)) fail("CONFLICT", "文件版本已经变化，请重新读取")
                preflightDelete(parent, name, normalized)
                val current = snapshot(parent, name, normalized, hash = true)
                if (current?.identity != original.identity || current?.sha256 != original.sha256) fail("CONFLICT", "文件在删除期间发生变化")
                withInternalDirectory(root) { internal -> deleteBound(parent, name, normalized, internal, original, originalState) }
            }
        }
    }

    override suspend fun validateTree(path: String) = io {
        val normalized = mutationPath(path)
        withRoot { root ->
            withParent(root, normalized) { parent, name -> preflightDelete(parent, name, normalized) }
        }
    }

    override suspend fun move(sourcePath: String, targetPath: String) = io {
        val source = mutationPath(sourcePath)
        val target = mutationPath(targetPath)
        if (forbiddenRoots.any { withinIgnoringCase(it, logical(source)) }) fail("INVALID_PATH", "移动范围包含受保护目录")
        withRoot { root ->
            withParent(root, source) { sourceParent, sourceName ->
                val original = sourceParent.stat(sourceName) ?: fail("NOT_FOUND", "源条目不存在")
                requireSupported(original)
                rejectInsideSource(original, source, target)
                preflightDelete(sourceParent, sourceName, source)
                withParent(root, target, original.takeIf { it.isDirectory }?.objectId) { targetParent, targetName ->
                    if (targetParent.stat(targetName) != null) fail("NAME_EXISTS", "同名文件或目录已经存在")
                    if (sourceParent.stat(sourceName)?.revision != original.revision) fail("CONFLICT", "移动来源在校验期间发生变化")
                    currentCoroutineContext().ensureActive()
                    var moved: RealPathFileState? = null
                    try {
                        sourceParent.moveNew(sourceName, targetParent, targetName)
                        moved = targetParent.stat(targetName) ?: fail("CONFLICT", "移动目标在提交后消失")
                        requireSupported(moved)
                        if (!sameMovedObject(moved, original)) fail("CONFLICT", "移动来源在提交期间被替换或修改")
                    } catch (raw: Throwable) {
                        val failure = moveFailure(raw)
                        if (moved == null && (raw is FileAlreadyExistsException || raw is NoSuchFileException ||
                                raw is ErrnoException && raw.errno == OsConstants.EXDEV)) throw failure
                        withContext(NonCancellable) {
                            val current = targetParent.stat(targetName)
                            if (current != null && current.objectId == (moved ?: original).objectId && sourceParent.stat(sourceName) == null) {
                                restoreMoved(targetParent, targetName, sourceParent, sourceName, current, failure)
                            } else if (moved != null) {
                                recoveryRequired(failure)
                            }
                        }
                        throw failure
                    }
                }
            }
        }
    }

    override suspend fun copy(sourcePath: String, targetPath: String) = io {
        val source = mutationPath(sourcePath)
        val target = mutationPath(targetPath)
        withRoot { root ->
            withParent(root, source) { sourceParent, sourceName ->
                val original = sourceParent.stat(sourceName) ?: fail("NOT_FOUND", "源条目不存在")
                requireSupported(original)
                rejectInsideSource(original, source, target)
                preflightDelete(sourceParent, sourceName, source)
                withParent(root, target, original.takeIf { it.isDirectory }?.objectId) { targetParent, targetName ->
                    if (targetParent.stat(targetName) != null) fail("NAME_EXISTS", "同名文件或目录已经存在")
                    withInternalDirectory(root) { internal ->
                        val created = CopiedNode(".nexara-copy-${UUID.randomUUID()}")
                        var createdParent = internal
                        try {
                            copyNode(sourceParent, sourceName, source, internal, created)
                            currentCoroutineContext().ensureActive()
                            try { internal.moveNew(created.name, targetParent, targetName) }
                            catch (failure: Throwable) {
                                if (internal.stat(created.name) == null && targetParent.stat(targetName)?.objectId == created.state?.objectId) {
                                    created.name = targetName
                                    createdParent = targetParent
                                }
                                throw moveFailure(failure)
                            }
                            created.name = targetName
                            createdParent = targetParent
                            val published = targetParent.stat(targetName) ?: fail("CONFLICT", "复制目标在发布后消失")
                            if (!sameMovedObject(published, checkNotNull(created.state))) fail("CONFLICT", "复制目标在发布期间被替换或修改")
                        } catch (failure: Throwable) {
                            withContext(NonCancellable) {
                                try { rollbackCopy(createdParent, created, internal) }
                                catch (cleanup: Throwable) { failure.addSuppressed(cleanup); recoveryRequired(failure) }
                            }
                            throw failure
                        }
                    }
                }
            }
        }
    }

    private fun rejectInsideSource(state: RealPathFileState, source: String, target: String) {
        if (state.isDirectory && (target == source || target.startsWith("$source/"))) {
            fail("TARGET_INSIDE_SOURCE", "不能将目录放入自身或子目录")
        }
    }

    private class CopiedNode(var name: String) {
        var state: RealPathFileState? = null
        val children = mutableListOf<CopiedNode>()
    }

    private suspend fun copyNode(
        sourceParent: BoundRealPathDirectory, sourceName: String, sourcePath: String,
        targetParent: BoundRealPathDirectory, created: CopiedNode,
    ) {
        currentCoroutineContext().ensureActive()
        ensureAllowed(sourcePath)
        val original = sourceParent.stat(sourceName) ?: fail("NOT_FOUND", "源条目不存在")
        requireSupported(original)
        if (original.isDirectory) {
            sourceParent.openDirectory(sourceName).use { source ->
                if (source.state().objectId != original.objectId) fail("CONFLICT", "复制来源目录被替换")
                targetParent.createDirectory(created.name)
                created.state = targetParent.stat(created.name) ?: fail("CONFLICT", "新建目标目录消失")
                targetParent.openDirectory(created.name).use { target ->
                    if (target.state().objectId != created.state?.objectId) fail("CONFLICT", "复制目标目录被替换")
                    for (name in source.names().sorted()) {
                        if (!visibleName(name)) fail("INVALID_PATH", "复制范围包含内部恢复文件或保留目录")
                        val child = CopiedNode(name).also { created.children += it }
                        copyNode(source, name, join(sourcePath, name), target, child)
                    }
                    created.state = target.state()
                }
                if (targetParent.stat(created.name)?.objectId != created.state?.objectId) fail("CONFLICT", "复制目标目录被替换")
            }
        } else {
            sourceParent.openRead(sourceName).use { source ->
                val before = source.state()
                requireFile(before)
                if (before.revision != original.revision) fail("CONFLICT", "复制来源文件被替换")
                targetParent.createFile(created.name).use { target ->
                    created.state = target.state()
                    try {
                        copyBounded(source.input, target.output, Long.MAX_VALUE)
                        target.sync()
                        if (source.state().revision != before.revision) fail("CONFLICT", "复制期间来源文件发生变化")
                        if (target.state().size != before.size) fail("IO", "目标副本长度不一致")
                    } finally {
                        created.state = target.state()
                    }
                }
                if (targetParent.stat(created.name)?.revision != created.state?.revision) fail("CONFLICT", "复制目标文件被替换或修改")
            }
        }
    }

    private fun rollbackCopy(parent: BoundRealPathDirectory, created: CopiedNode, internal: BoundRealPathDirectory) {
        val expected = created.state ?: return
        val current = parent.stat(created.name) ?: return
        if (current.objectId != expected.objectId || current.isSymbolicLink || current.isDirectory != expected.isDirectory) {
            fail("RECOVERY_REQUIRED", "复制目标身份发生变化，已保留供人工核查")
        }
        val quarantine = ".nexara-copy-cleanup-${UUID.randomUUID()}"
        try {
            parent.moveNew(created.name, internal, quarantine)
            val isolated = internal.stat(quarantine) ?: fail("RECOVERY_REQUIRED", "复制清理目标消失")
            if (!sameMovedObject(isolated, expected)) fail("RECOVERY_REQUIRED", "复制清理目标被替换或修改")
            if (isolated.isDirectory) {
                internal.openDirectory(quarantine).use { directory ->
                    if (directory.state().objectId != expected.objectId) fail("RECOVERY_REQUIRED", "复制目标目录被替换")
                    var failure: Throwable? = null
                    for (child in created.children.asReversed()) {
                        try { rollbackCopy(directory, child, internal) }
                        catch (cleanup: Throwable) { if (failure == null) failure = cleanup else failure.addSuppressed(cleanup) }
                    }
                    failure?.let { throw it }
                    if (directory.names().isNotEmpty() || internal.stat(quarantine)?.objectId != expected.objectId) {
                        fail("RECOVERY_REQUIRED", "复制目标目录发生变化，已保留供人工核查")
                    }
                    internal.deleteDirectory(quarantine)
                }
            } else {
                internal.deleteFile(quarantine)
            }
        } catch (failure: Throwable) {
            internal.stat(quarantine)?.let { isolated -> restoreMoved(internal, quarantine, parent, created.name, isolated, failure) }
            throw failure
        }
    }

    private fun sameMovedObject(actual: RealPathFileState, expected: RealPathFileState): Boolean =
        actual.objectId == expected.objectId && actual.isSymbolicLink == expected.isSymbolicLink &&
            actual.isDirectory == expected.isDirectory && actual.isRegularFile == expected.isRegularFile &&
            (actual.isDirectory || actual.contentVersion == expected.contentVersion)

    private fun restoreMoved(
        source: BoundRealPathDirectory, sourceName: String, target: BoundRealPathDirectory, targetName: String,
        expected: RealPathFileState, failure: Throwable,
    ) {
        try {
            if (source.stat(sourceName)?.objectId != expected.objectId) fail("RECOVERY_REQUIRED", "待恢复目标身份发生变化")
            source.moveNew(sourceName, target, targetName)
        } catch (recovery: Throwable) {
            if (source.stat(sourceName) == null && target.stat(targetName)?.let { sameMovedObject(it, expected) } == true) return
            failure.addSuppressed(recovery)
            recoveryRequired(failure)
        }
        val restored = target.stat(targetName)
        if (restored != null && sameMovedObject(restored, expected)) return
        if (restored != null && source.stat(sourceName) == null) {
            try { target.moveNew(targetName, source, sourceName) }
            catch (recovery: Throwable) { failure.addSuppressed(recovery) }
        }
        recoveryRequired(failure)
    }

    private fun recoveryRequired(failure: Throwable): Nothing {
        if (failure is CancellationException) throw failure
        throw ExternalFolderException("RECOVERY_REQUIRED", "文件操作补偿未完成，请核查来源、目标及内部隔离副本").apply { addSuppressed(failure) }
    }

    private fun moveFailure(failure: Throwable): Throwable = when (failure) {
        is FileAlreadyExistsException -> ExternalFolderException("NAME_EXISTS", "同名文件或目录已经存在").apply { initCause(failure) }
        is NoSuchFileException -> ExternalFolderException("NOT_FOUND", "移动源或目标父目录不存在").apply { initCause(failure) }
        is IOException -> ExternalFolderException("IO", "原子移动失败，请检查存储卷与目录").apply { initCause(failure) }
        is ErrnoException -> if (failure.errno == OsConstants.EXDEV) ExternalFolderException("IO", "不能跨存储卷原子移动").apply { initCause(failure) } else failure
        else -> failure
    }

    private suspend fun publishNew(sourceParent: BoundRealPathDirectory, temporary: String, parent: BoundRealPathDirectory, name: String,
        restoringOriginal: Boolean = false): String {
        val captured = snapshot(sourceParent, temporary, name, hash = true) ?: fail("NOT_FOUND", "发布源文档不存在")
        val original = stateFor(sourceParent, temporary, captured)
        try {
            sourceParent.moveNew(temporary, parent, name)
            return original.objectId
        } catch (failure: Throwable) {
            val remaining = sourceParent.stat(temporary)
            if (remaining == null) {
                val current = parent.stat(name)
                if (current != null && sameMovedObject(current, original) &&
                    snapshot(parent, name, name, hash = true)?.sha256 == captured.sha256) {
                    // 原文补偿已提交时保留唯一原文；新内容发布失败才清理本次发布物。
                    if (restoringOriginal) return original.objectId
                    cleanupOwnedFile(parent, name, original.objectId)
                }
                throw failure
            }
            val unsupported = failure is ExternalFolderException && failure.code == "UNSUPPORTED" ||
                failure is ErrnoException && failure.errno in setOf(OsConstants.EXDEV, OsConstants.ENOSYS, OsConstants.EOPNOTSUPP)
            if (!unsupported || !sameMovedObject(remaining, original)) throw failure
        }
        var createdId: String? = null
        try {
            // 无跨目录移动原语的卷使用独占新建，不覆盖并发新增的目录项。
            parent.createFile(name).use { destination ->
                createdId = destination.state().objectId
                sourceParent.openRead(temporary).use { source -> copyBounded(source.input, destination.output, Long.MAX_VALUE) }
                destination.sync()
            }
            return createdId!!
        } catch (exception: Throwable) {
            cleanupOwnedFile(parent, name, createdId)
            throw exception
        }
    }

    private suspend fun preflightDelete(parent: BoundRealPathDirectory, name: String, path: String) {
        currentCoroutineContext().ensureActive()
        val state = parent.stat(name) ?: fail("NOT_FOUND", "删除目标不存在")
        requireSupported(state)
        if (state.isDirectory) parent.openDirectory(name).use { directory ->
            for (child in directory.names()) {
                if (!visibleName(child)) fail("INVALID_PATH", "删除范围包含内部恢复文件或保留目录")
                val childPath = join(path, child)
                ensureAllowed(childPath)
                preflightDelete(directory, child, childPath)
            }
        }
    }

    private suspend fun deleteBound(parent: BoundRealPathDirectory, name: String, path: String, internal: BoundRealPathDirectory,
        expected: ExternalFolderEntry? = null, expectedState: RealPathFileState? = null) {
        currentCoroutineContext().ensureActive()
        ensureAllowed(path)
        val original = expected ?: snapshot(parent, name, path, hash = true) ?: fail("NOT_FOUND", "删除目标不存在")
        val state = expectedState ?: stateFor(parent, name, original)
        val quarantine = ".nexara-delete-${UUID.randomUUID()}"
        var isolated = false
        try {
            try { parent.moveNew(name, internal, quarantine) }
            catch (failure: Throwable) {
                if (internal.stat(quarantine) != null) recoverOrThrow(internal, quarantine, parent, name, path, failure)
                throw moveFailure(failure)
            }
            isolated = true
            verifyIsolated(internal, quarantine, path, original, state)
            if (state.isDirectory) {
                internal.openDirectory(quarantine).use { directory ->
                    for (child in directory.names()) {
                        if (!visibleName(child)) fail("INVALID_PATH", "删除范围包含内部恢复文件或保留目录")
                        deleteBound(directory, child, join(path, child), internal)
                    }
                    if (internal.stat(quarantine)?.objectId != state.objectId) fail("CONFLICT", "隔离目录在删除期间被替换")
                    internal.deleteDirectory(quarantine)
                }
            } else {
                verifyIsolated(internal, quarantine, path, original, state)
                internal.deleteFile(quarantine)
            }
            isolated = false
        } catch (exception: Throwable) {
            if (isolated) recoverOrThrow(internal, quarantine, parent, name, path, exception)
            throw exception
        }
    }

    private fun stateFor(parent: BoundRealPathDirectory, name: String, entry: ExternalFolderEntry): RealPathFileState {
        val state = parent.stat(name) ?: fail("CONFLICT", "目标在校验期间消失")
        val identity = if (state.isDirectory) state.objectId else state.revision
        if (state.isDirectory != entry.isDirectory || identity != entry.identity) fail("CONFLICT", "目标在校验期间被修改或替换")
        return state
    }

    private suspend fun verifyIsolated(parent: BoundRealPathDirectory, name: String, path: String, expected: ExternalFolderEntry, state: RealPathFileState) {
        val isolated = parent.stat(name) ?: fail("CONFLICT", "隔离目标已经消失")
        requireSupported(isolated)
        if (isolated.objectId != state.objectId || isolated.isDirectory != state.isDirectory || isolated.contentVersion != state.contentVersion) {
            fail("CONFLICT", "目标在提交期间被修改或替换")
        }
        if (!isolated.isDirectory && snapshot(parent, name, path, hash = true)?.sha256 != expected.sha256) {
            fail("CONFLICT", "目标内容在提交期间发生变化")
        }
    }

    private suspend fun recoverOrThrow(internal: BoundRealPathDirectory, quarantine: String, parent: BoundRealPathDirectory, originalName: String, path: String, failure: Throwable) {
        var restored = false
        withContext(NonCancellable) {
            try { restored = restoreNoReplace(internal, quarantine, parent, originalName, path) }
            catch (recoveryFailure: Throwable) { failure.addSuppressed(recoveryFailure) }
        }
        if (restored) return
        val location = "/${WorkspaceInternalPaths.DIRECTORY}/$quarantine"
        val recovery = ExternalFolderException("RECOVERY_REQUIRED", "未能安全恢复原目标；请核查隔离副本 $location 与 $path")
        if (failure is CancellationException) { failure.addSuppressed(recovery); throw failure }
        recovery.addSuppressed(failure)
        throw recovery
    }

    private suspend fun restoreNoReplace(internal: BoundRealPathDirectory, quarantine: String, parent: BoundRealPathDirectory, name: String, path: String): Boolean {
        if (parent.stat(name) != null) return false
        val isolated = internal.stat(quarantine) ?: return false
        // 递归删除失败时保留隔离目录，避免把不完整目录当作已恢复目标。
        if (isolated.isDirectory || isolated.isSymbolicLink || !isolated.isRegularFile) return false
        val captured = snapshot(internal, quarantine, path, hash = true) ?: return false
        val restoredId = publishNew(internal, quarantine, parent, name, restoringOriginal = true)
        val restored = snapshot(parent, name, path, hash = true) ?: return false
        if (parent.stat(name)?.objectId != restoredId || restored.sha256 != captured.sha256) return false
        val current = internal.stat(quarantine) ?: return restoredId == isolated.objectId
        if (current.objectId != isolated.objectId || current.contentVersion != isolated.contentVersion ||
            snapshot(internal, quarantine, path, hash = true)?.sha256 != captured.sha256) return false
        internal.deleteFile(quarantine)
        return true
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun snapshot(parent: BoundRealPathDirectory, name: String, path: String, hash: Boolean, smallOnly: Boolean = false): ExternalFolderEntry? {
        val metadata = parent.stat(name) ?: return null
        requireSupported(metadata)
        if (metadata.isDirectory || !hash || (smallOnly && metadata.size > STAT_HASH_LIMIT)) return entry(path, metadata)
        parent.openRead(name).use { source ->
            val before = source.state()
            requireFile(before)
            if (smallOnly && before.size > STAT_HASH_LIMIT) return entry(path, before)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            if (source.state().revision != before.revision) fail("CONFLICT", "文件在校验期间发生变化")
            return entry(path, before, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    private suspend fun copyBounded(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count.toLong() > limit - total) fail("TOO_LARGE", "文件超过读取上限")
            output.write(buffer, 0, count)
            total += count
        }
        output.flush()
    }

    private fun cleanupOwnedFile(parent: BoundRealPathDirectory, name: String, objectId: String?) {
        if (objectId == null) return
        try {
            val current = parent.stat(name)
            if (current?.objectId == objectId && !current.isDirectory && !current.isSymbolicLink) parent.deleteFile(name)
        } catch (_: IOException) { Unit } catch (_: ErrnoException) { Unit } catch (_: ExternalFolderException) { Unit }
    }

    private suspend fun <T> withInternalDirectory(root: BoundRealPathDirectory, action: suspend (BoundRealPathDirectory) -> T): T {
        val name = WorkspaceInternalPaths.DIRECTORY
        ensureAllowed("/$name")
        val existing = root.stat(name)
        if (existing != null) requireSupported(existing) else root.createDirectory(name)
        root.openDirectory(name).use { directory ->
            if (root.stat(name)?.objectId != directory.state().objectId) fail("CONFLICT", "内部暂存目录在打开期间被替换")
            if (existing == null) {
                directory.createFile(".gitignore").use { ignore ->
                    ignore.output.write("*\n".toByteArray())
                    ignore.sync()
                }
            }
            return action(directory)
        }
    }

    private suspend fun <T> withRoot(action: suspend (BoundRealPathDirectory) -> T): T {
        val opened = mutableListOf<BoundRealPathDirectory>()
        var rootResolved = false
        try {
            var directory = fileSystem.openFileSystemRoot().also { opened.add(it) }
            for (component in rootPath) {
                currentCoroutineContext().ensureActive()
                directory = directory.openDirectory(component.toString()).also { opened.add(it) }
            }
            rootResolved = true
            migrateLegacyTemporaries(directory, directory, "/")
            return action(directory)
        } catch (exception: ExternalFolderException) {
            if (!rootResolved && exception.code in setOf("NOT_FOUND", "NOT_DIRECTORY")) {
                throw ExternalFolderException("UNAVAILABLE", "挂载目录或存储卷不可用").apply { initCause(exception) }
            }
            throw exception
        } catch (exception: ErrnoException) {
            if (!rootResolved && exception.errno in setOf(OsConstants.ENOENT, OsConstants.ENOTDIR)) {
                throw ExternalFolderException("UNAVAILABLE", "挂载目录或存储卷不可用").apply { initCause(exception) }
            }
            throw exception
        } finally { opened.asReversed().forEach { it.close() } }
    }

    private suspend fun <T> withDirectory(root: BoundRealPathDirectory, path: String, excludedDirectoryId: String? = null, action: suspend (BoundRealPathDirectory) -> T): T {
        ensureAllowed(path)
        val opened = mutableListOf<BoundRealPathDirectory>()
        try {
            var directory = root
            fun checkTarget() {
                if (excludedDirectoryId != null && directory.state().objectId == excludedDirectoryId) fail("TARGET_INSIDE_SOURCE", "目标目录位于来源内部")
            }
            checkTarget()
            if (path != "/") for (name in path.removePrefix("/").split('/')) {
                currentCoroutineContext().ensureActive()
                val state = directory.stat(name) ?: fail("NOT_FOUND", "目录不存在")
                requireSupported(state)
                if (!state.isDirectory) fail("NOT_DIRECTORY", "目标不是目录")
                directory = directory.openDirectory(name).also { opened.add(it) }
                checkTarget()
            }
            if (path != "/") migrateLegacyTemporaries(root, directory, path)
            return action(directory)
        } finally { opened.asReversed().forEach { it.close() } }
    }

    private suspend fun migrateLegacyTemporaries(root: BoundRealPathDirectory, parent: BoundRealPathDirectory, parentPath: String) {
        val names = try { parent.names().filter(ExternalFolderLegacyTemporaries::matches) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ExternalFolderLegacyTemporaries.failed("real_path", failure); return }
        for (name in names) {
            currentCoroutineContext().ensureActive()
            try {
                val original = parent.stat(name) ?: continue
                requireSupported(original)
                withInternalDirectory(root) { internal ->
                    withLegacyDirectory(internal, parentPath) { target ->
                        if (target.stat(name) != null) fail("CONFLICT", "旧暂存迁移目标已经存在，已保留原副本")
                        parent.moveNew(name, target, name)
                        val moved = target.stat(name) ?: fail("CONFLICT", "旧暂存迁移后消失")
                        if (!sameMovedObject(moved, original)) {
                            val failure = ExternalFolderException("CONFLICT", "旧暂存在迁移期间发生变化")
                            restoreMoved(target, name, parent, name, moved, failure)
                            throw failure
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { ExternalFolderLegacyTemporaries.failed("real_path", failure) }
        }
    }

    private fun <T> withLegacyDirectory(internal: BoundRealPathDirectory, parentPath: String, action: (BoundRealPathDirectory) -> T): T {
        val opened = mutableListOf<BoundRealPathDirectory>()
        try {
            var directory = internal
            for (name in listOf("legacy") + parentPath.split('/').filter { it.isNotEmpty() }) {
                val existing = directory.stat(name)
                if (existing == null) directory.createDirectory(name) else requireSupported(existing)
                val child = directory.openDirectory(name)
                if (directory.stat(name)?.objectId != child.state().objectId) {
                    child.close()
                    fail("CONFLICT", "旧暂存目标目录在打开期间被替换")
                }
                opened += child
                directory = child
            }
            return action(directory)
        } finally { opened.asReversed().forEach { it.close() } }
    }

    private suspend fun <T> withParent(root: BoundRealPathDirectory, path: String, excludedDirectoryId: String? = null, action: suspend (BoundRealPathDirectory, String) -> T): T {
        ensureAllowed(path)
        return withDirectory(root, path.substringBeforeLast('/').ifEmpty { "/" }, excludedDirectoryId) { action(it, path.substringAfterLast('/')) }
    }
    private fun checkVersion(current: ExternalFolderEntry?, expected: String?, createOnly: Boolean) {
        if (current?.isDirectory == true || (createOnly && current != null)) fail("CONFLICT", "同名文件或目录已经存在")
        if (expected != null && current?.sha256 != expected) fail("CONFLICT", "文件版本已经变化，请重新读取")
    }
    private fun entry(path: String, state: RealPathFileState, sha256: String? = null): ExternalFolderEntry =
        ExternalFolderEntry(path, if (path == "/") rootPath.fileName.toString() else path.substringAfterLast('/'), state.isDirectory,
            if (state.isDirectory) 0 else state.size, state.lastModified, sha256, if (state.isDirectory) state.objectId else state.revision)
    private fun requireFile(state: RealPathFileState) { requireSupported(state); if (state.isDirectory) fail("IS_DIRECTORY", "目标是目录") }
    private fun requireSupported(state: RealPathFileState) {
        if (state.isSymbolicLink) fail("INVALID_PATH", "不允许通过符号链接访问目录")
        if (!state.isDirectory && !state.isRegularFile) fail("UNSUPPORTED", "仅支持普通文件和目录")
    }
    private fun logical(path: String): Path = if (path == "/") rootPath else rootPath.resolve(path.removePrefix("/"))
    private fun ensureAllowed(path: String) { if (isForbidden(logical(path))) fail("INVALID_PATH", "路径位于允许目录之外") }
    private fun isForbidden(path: Path): Boolean = forbiddenRoots.any { withinIgnoringCase(path, it) }
    private fun withinIgnoringCase(path: Path, parent: Path): Boolean = path.nameCount >= parent.nameCount && path.root == parent.root &&
        (0 until parent.nameCount).all { path.getName(it).toString().equals(parent.getName(it).toString(), ignoreCase = true) }
    private fun visibleName(name: String): Boolean = try { ExternalFolderPaths.validateName(name); true } catch (_: ExternalFolderException) { false }
    private fun mutationPath(path: String): String = ExternalFolderPaths.normalize(path).also { if (it == "/") fail("INVALID_PATH", "不能修改挂载根目录") }
    private fun join(parent: String, name: String): String = if (parent == "/") "/$name" else "$parent/$name"

    private suspend fun <T> io(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            currentCoroutineContext().ensureActive()
            if (!permissionCheck()) fail("UNAVAILABLE", "所有文件访问授权不可用，请重新授权")
            try { action() } catch (exception: CancellationException) { throw exception } catch (exception: ExternalFolderException) { throw exception
            } catch (exception: ErrnoException) {
                val code = when (exception.errno) {
                    OsConstants.ELOOP -> "INVALID_PATH"
                    OsConstants.ENOENT -> "NOT_FOUND"
                    OsConstants.ENOTDIR -> "NOT_DIRECTORY"
                    OsConstants.EEXIST -> "CONFLICT"
                    OsConstants.EROFS -> "READ_ONLY"
                    OsConstants.ENOSYS, OsConstants.EOPNOTSUPP, OsConstants.EXDEV -> "UNSUPPORTED"
                    else -> "UNAVAILABLE"
                }
                throw ExternalFolderException(code, "存储操作失败，请检查目录与授权").apply { initCause(exception) }
            } catch (exception: SecurityException) {
                throw ExternalFolderException("UNAVAILABLE", "存储授权不可用").apply { initCause(exception) }
            } catch (exception: IOException) {
                throw ExternalFolderException("UNAVAILABLE", "存储操作失败，请检查目录与授权").apply { initCause(exception) }
            }
        }
    }
    companion object {
        private const val STAT_HASH_LIMIT = 1024L * 1024
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)
    }
}

/** 只接管已发布版本使用的完整暂存名称，未知内部名称保持原位。 */
internal object ExternalFolderLegacyTemporaries {
    private val pattern = Regex("\\.nexara-(?:write|replace|delete|copy|copy-cleanup|backup)-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    fun matches(name: String): Boolean = pattern.matches(name)
    fun failed(backend: String, failure: Exception) {
        com.promenar.nexara.utils.NexaraLogger.diagnostic("workspace.external_legacy_migration_failed", mapOf(
            "backend" to backend,
            "exception" to failure.javaClass.simpleName,
            "code" to (failure as? ExternalFolderException)?.code,
        ))
    }
}

/** 句柄操作只接受单段名称，必须保持目录绑定，不能重新解析原绝对路径。 */
internal interface RealPathFileSystem { fun openFileSystemRoot(): BoundRealPathDirectory }
internal interface BoundRealPathDirectory : Closeable {
    fun state(): RealPathFileState
    fun stat(name: String): RealPathFileState?
    fun names(): List<String>
    fun openDirectory(name: String): BoundRealPathDirectory
    fun openRead(name: String): BoundRealPathRead
    fun createFile(name: String): BoundRealPathWrite
    fun createDirectory(name: String)
    fun atomicReplace(source: String, target: String)
    fun moveNew(source: String, targetDirectory: BoundRealPathDirectory, target: String)
    fun linkNew(source: String, target: String): Boolean
    fun deleteFile(name: String)
    fun deleteDirectory(name: String)
}
internal interface BoundRealPathRead : Closeable { val input: InputStream; fun state(): RealPathFileState }
internal interface BoundRealPathWrite : Closeable { val output: OutputStream; fun state(): RealPathFileState; fun sync() }
internal data class RealPathFileState(val isDirectory: Boolean, val isRegularFile: Boolean, val isSymbolicLink: Boolean,
    val size: Long, val lastModified: Long, val objectId: String, val revision: String, val contentVersion: String = "$lastModified:$size")

/** 从系统根逐段打开目录，全部子操作固定在持有的目录 fd，避免中间目录交换后逃逸。 */
internal object AndroidRealPathFileSystem : RealPathFileSystem {
    // 已核验 Android NDK 的 Linux UAPI：O_PATH=010000000，遍历父目录不要求列举权限。
    private const val OPEN_PATH = 0x200000
    override fun openFileSystemRoot(): BoundRealPathDirectory = openDirectory("/")
    private fun openDirectory(path: String): BoundRealPathDirectory {
        val fd = Os.open(path, OPEN_PATH or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            val mode = Os.fstat(fd).st_mode
            if (OsConstants.S_ISLNK(mode)) throw ExternalFolderException("INVALID_PATH", "不允许通过符号链接访问目录")
            if (!OsConstants.S_ISDIR(mode)) throw ExternalFolderException("NOT_DIRECTORY", "目标不是目录")
            return AndroidDirectory(ParcelFileDescriptor.dup(fd))
        } finally { Os.close(fd) }
    }
    private class AndroidDirectory(private val descriptor: ParcelFileDescriptor) : BoundRealPathDirectory {
        private val base: String get() = "/proc/self/fd/${descriptor.fd}"
        private fun child(name: String): String {
            if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name) throw ExternalFolderException("INVALID_PATH", "目录句柄只接受单段名称")
            return "$base/$name"
        }
        override fun state(): RealPathFileState = state(Os.fstat(descriptor.fileDescriptor))
        override fun stat(name: String): RealPathFileState? = try { state(Os.lstat(child(name))) } catch (exception: ErrnoException) {
            if (exception.errno == OsConstants.ENOENT) null else throw exception
        }
        override fun names(): List<String> = File(base).list()?.toList() ?: throw ExternalFolderException("UNAVAILABLE", "目录句柄不可枚举")
        override fun openDirectory(name: String): BoundRealPathDirectory = AndroidRealPathFileSystem.openDirectory(child(name))
        override fun openRead(name: String): BoundRealPathRead {
            val descriptor = openOwnedFile(child(name), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            return try {
                val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                object : BoundRealPathRead {
                    override val input: InputStream = stream
                    override fun state(): RealPathFileState = state(Os.fstat(descriptor.fileDescriptor))
                    override fun close() = stream.close()
                }
            } catch (exception: Throwable) { descriptor.close(); throw exception }
        }
        override fun createFile(name: String): BoundRealPathWrite {
            val descriptor = openOwnedFile(child(name), OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 384)
            return try {
                val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                object : BoundRealPathWrite {
                    override val output: OutputStream = stream
                    override fun state(): RealPathFileState = state(Os.fstat(descriptor.fileDescriptor))
                    override fun sync() = Os.fsync(descriptor.fileDescriptor)
                    override fun close() = stream.close()
                }
            } catch (exception: Throwable) { descriptor.close(); throw exception }
        }
        override fun createDirectory(name: String) = Os.mkdir(child(name), 448)
        override fun atomicReplace(source: String, target: String) = Os.rename(child(source), child(target))
        override fun moveNew(source: String, targetDirectory: BoundRealPathDirectory, target: String) {
            val destination = targetDirectory as? AndroidDirectory ?: throw ExternalFolderException("UNSUPPORTED", "移动目标不是同类目录句柄")
            // 原有句柄使用 O_PATH；桥接还需 fsync，另开绑定到同一目录的可读 FD。
            val sourceFd = Os.open(base, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            try {
                val targetFd = Os.open(destination.base, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
                try {
                    val sourceDescriptor = ParcelFileDescriptor.dup(sourceFd)
                    sourceDescriptor.use { from ->
                        ParcelFileDescriptor.dup(targetFd).use { to -> AndroidWorkspaceFileMoves.moveNoReplace(from.fd, source, to.fd, target) }
                    }
                } finally { Os.close(targetFd) }
            } finally { Os.close(sourceFd) }
        }
        override fun linkNew(source: String, target: String): Boolean = try { Os.link(child(source), child(target)); true } catch (exception: ErrnoException) {
            // Android 的 SELinux 策略禁止应用创建硬链接，返回 EACCES；与其它“不支持硬链接”的错误一样回退到独占新建。
            if (exception.errno in setOf(OsConstants.EACCES, OsConstants.EPERM, OsConstants.EOPNOTSUPP, OsConstants.ENOSYS, OsConstants.EXDEV)) false else throw exception
        }
        override fun deleteFile(name: String) = Os.remove(child(name))
        override fun deleteDirectory(name: String) = Os.remove(child(name))
        override fun close() = descriptor.close()
    }
    private fun openOwnedFile(path: String, flags: Int, mode: Int): ParcelFileDescriptor {
        val fd = Os.open(path, flags, mode)
        try {
            requireRegular(fd)
            return ParcelFileDescriptor.dup(fd)
        } finally { Os.close(fd) }
    }
    private fun requireRegular(fd: FileDescriptor) {
        if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) throw ExternalFolderException("UNSUPPORTED", "仅支持普通文件")
    }
    private fun state(stat: StructStat): RealPathFileState {
        val objectId = "${stat.st_dev}:${stat.st_ino}"
        val revision = "$objectId:${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}:${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}:${stat.st_size}"
        return RealPathFileState(OsConstants.S_ISDIR(stat.st_mode), OsConstants.S_ISREG(stat.st_mode), OsConstants.S_ISLNK(stat.st_mode),
            stat.st_size, stat.st_mtim.tv_sec * 1000 + stat.st_mtim.tv_nsec / 1_000_000, objectId, revision,
            "${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}:${stat.st_size}")
    }
}
