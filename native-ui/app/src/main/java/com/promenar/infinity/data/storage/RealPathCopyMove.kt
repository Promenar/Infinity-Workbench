package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.ByteArrayOutputStream
import java.nio.file.FileAlreadyExistsException
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 仅在 rename 明确未提交时使用；创建回执与补偿均固定在原有目录句柄内。 */
internal object RealPathCopyMove {
    private class Node(val name: String, var source: RealPathFileState, val bytes: ByteArray?) {
        val children = mutableListOf<Node>()
        var target: RealPathFileState? = null
        var creationAttempted = false
        var written = false
        var deleted = false
    }
    private class Budget { var bytes = ExternalTreeTransfer.MAX_BYTES; var nodes = 0 }

    suspend fun move(source: BoundRealPathDirectory, sourceName: String, sourcePath: String, expected: RealPathFileState,
        target: BoundRealPathDirectory, targetName: String, targetPath: String) {
        checkRealPathReadState(expected, source.stat(sourceName), timestamps = !source.readTimestampsMayDrift)
        val node = capture(source, sourceName, Budget(), 0)
        checkRealPathReadState(expected, node.source, timestamps = !source.readTimestampsMayDrift)
        var deletingSource = false
        try {
            create(target, targetName, node)
            verify(source, sourceName, node, target = false)
            verify(target, targetName, node, target = true)
            currentCoroutineContext().ensureActive()
            deletingSource = true
            removeSource(source, sourceName, node, target, targetName)
            verify(target, targetName, node, target = true)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    if (node.deleted && source.stat(sourceName) == null && failure !is CancellationException) {
                        try {
                            verify(target, targetName, node, target = true)
                            return@withContext
                        } catch (changed: Throwable) { failure.addSuppressed(changed) }
                    }
                    if (deletingSource && (hasDeleted(node) || source.stat(sourceName) == null)) restoreMissing(source, sourceName, node)
                    rollback(target, targetName, node)
                } catch (cleanup: Throwable) {
                    val recovery = ExternalFolderException("RECOVERY_REQUIRED",
                        "移动补偿未完成，请核查来源 $sourcePath 与目标 $targetPath / Move recovery required: source=$sourcePath, target=$targetPath")
                    recovery.addSuppressed(failure)
                    recovery.addSuppressed(cleanup)
                    if (failure is CancellationException) { failure.addSuppressed(recovery); throw failure }
                    throw recovery
                }
                throw failure
            }
        }
    }

    private suspend fun capture(parent: BoundRealPathDirectory, name: String, budget: Budget, depth: Int): Node {
        currentCoroutineContext().ensureActive()
        if (++budget.nodes > 1000 || depth > 32) throw ExternalFolderException("TOO_LARGE", "目录超过条目或深度上限 / Tree limit exceeded")
        val state = parent.stat(name) ?: realPathReadConflict("identity=missing")
        requireSupported(state)
        if (state.isDirectory) {
            val node = Node(name, state, null)
            parent.openDirectory(name).use { directory ->
                checkIdentity(state, directory.state())
                for (child in directory.names().sorted()) {
                    com.promenar.infinity.domain.storage.ExternalFolderPaths.validateName(child)
                    node.children += capture(directory, child, budget, depth + 1)
                }
                checkIdentity(state, parent.stat(name))
            }
            return node
        }
        if (state.size > budget.bytes) throw ExternalFolderException("RECYCLE_LIMIT", "文件超过剩余快照空间 / File exceeds remaining snapshot space")
        val read = readVerifiedRealPath(parent, name, budget.bytes) { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
        checkIdentity(state, read.state)
        budget.bytes -= read.value.size
        return Node(name, read.state, read.value)
    }

    private suspend fun create(parent: BoundRealPathDirectory, name: String, node: Node) {
        currentCoroutineContext().ensureActive()
        node.creationAttempted = true
        try {
            if (node.bytes == null) {
                parent.createDirectory(name)
                node.target = parent.stat(name) ?: realPathReadConflict("identity=missing")
                parent.openDirectory(name).use { directory ->
                    checkIdentity(node.target!!, directory.state())
                    for (child in node.children) create(directory, child.name, child)
                }
            } else {
                parent.createFile(name).use { file ->
                    node.target = file.state()
                    try { file.output.write(node.bytes); node.written = true; file.sync() }
                    finally { node.target = file.state() }
                }
            }
        } catch (failure: Throwable) {
            // 明确的独占创建重名错误证明没有创建目标，不能接管并发占用者。
            if (node.target == null && (failure is FileAlreadyExistsException ||
                failure is ErrnoException && failure.errno == OsConstants.EEXIST)) node.creationAttempted = false
            throw failure
        }
        verify(parent, name, node, target = true)
    }

    private suspend fun verify(parent: BoundRealPathDirectory, name: String, node: Node, target: Boolean) {
        val expected = if (target) node.target ?: realPathReadConflict("identity=missing") else node.source
        val current = parent.stat(name)
        checkIdentity(expected, current)
        if (node.bytes == null) {
            parent.openDirectory(name).use { directory ->
                checkIdentity(expected, directory.state())
                if (directory.names().sorted() != node.children.map { it.name }.sorted()) realPathReadConflict("identity(children)")
                for (child in node.children) verify(directory, child.name, child, target)
            }
        } else {
            val read = readVerifiedRealPath(parent, name, ExternalTreeTransfer.MAX_BYTES) { }
            checkIdentity(expected, read.state)
            val hash = externalSha256(node.bytes)
            if (hash != read.sha256) realPathReadConflict("sha256=$hash -> ${read.sha256}")
            if (!target) node.source = read.state else node.target = read.state
        }
    }

    private suspend fun removeSource(parent: BoundRealPathDirectory, name: String, node: Node,
        target: BoundRealPathDirectory, targetName: String) {
        currentCoroutineContext().ensureActive()
        checkRealPathReadState(node.source, parent.stat(name), timestamps = !parent.readTimestampsMayDrift && node.bytes != null)
        if (node.bytes == null) {
            parent.openDirectory(name).use { directory ->
                checkIdentity(node.source, directory.state())
                target.openDirectory(targetName).use { targetDirectory ->
                    checkIdentity(node.target!!, targetDirectory.state())
                    for (child in node.children) removeSource(directory, child.name, child, targetDirectory, child.name)
                }
                if (directory.names().isNotEmpty()) realPathReadConflict("identity(children)")
                verify(target, targetName, node, target = true)
                checkIdentity(node.source, parent.stat(name))
                val links = directory.state().linkCount
                try { parent.deleteDirectory(name); node.deleted = true }
                catch (failure: Throwable) {
                    node.deleted = parent.stat(name) == null && directory.state().linkCount < links
                    throw failure
                }
            }
        } else parent.openRead(name).use { held ->
            val before = held.state()
            checkIdentity(node.source, before)
            verifyPair(parent, name, target, targetName, node)
            checkRealPathReadState(node.source, parent.stat(name))
            checkRealPathReadState(node.target!!, target.stat(targetName))
            val links = held.state().linkCount
            try { parent.deleteFile(name); node.deleted = true }
            catch (failure: Throwable) {
                // 路径消失也可能是外部改名；原 FD 链接数下降才证明原对象已解除链接。
                node.deleted = parent.stat(name) == null && held.state().linkCount < links
                throw failure
            }
        }
    }

    private suspend fun verifyPair(source: BoundRealPathDirectory, sourceName: String,
        target: BoundRealPathDirectory, targetName: String, node: Node) {
        repeat(3) {
            verify(source, sourceName, node, target = false)
            verify(target, targetName, node, target = true)
            val sourceNow = source.stat(sourceName)
            val targetNow = target.stat(targetName)
            checkRealPathReadState(node.source, sourceNow, timestamps = false)
            checkRealPathReadState(node.target!!, targetNow, timestamps = false)
            if (sourceNow!!.revision == node.source.revision && targetNow!!.revision == node.target!!.revision) return
        }
        realPathReadConflict("mtime/ctime=unstable")
    }

    private fun hasDeleted(node: Node): Boolean = node.deleted || node.children.any(::hasDeleted)

    private suspend fun restoreMissing(parent: BoundRealPathDirectory, name: String, node: Node) {
        if (parent.stat(name) == null) {
            if (!node.deleted) realPathReadConflict("identity=missing-before-delete")
            if (node.bytes == null) {
                parent.createDirectory(name)
                node.source = parent.stat(name) ?: realPathReadConflict("identity=missing")
            } else {
                parent.createFile(name).use { file ->
                    node.source = file.state()
                    file.output.write(node.bytes)
                    file.sync()
                    node.source = file.state()
                }
            }
        } else if (node.deleted) {
            realPathReadConflict("identity=recreated-after-delete")
        }
        checkIdentity(node.source, parent.stat(name))
        if (node.bytes == null) parent.openDirectory(name).use { directory ->
            checkIdentity(node.source, directory.state())
            for (child in node.children) restoreMissing(directory, child.name, child)
        }
        verify(parent, name, node, target = false)
    }

    private suspend fun rollback(parent: BoundRealPathDirectory, name: String, node: Node) {
        if (!node.creationAttempted) return
        val current = parent.stat(name) ?: return
        val expected = node.target ?: realPathReadConflict("identity=unknown")
        checkIdentity(expected, current)
        if (node.bytes == null) {
            parent.openDirectory(name).use { directory ->
                checkIdentity(expected, directory.state())
                for (child in node.children.asReversed()) rollback(directory, child.name, child)
                if (directory.names().isNotEmpty()) realPathReadConflict("identity(children)")
            }
            checkIdentity(expected, parent.stat(name))
            parent.deleteDirectory(name)
        } else {
            if (current.size != expected.size || node.written && current.size != node.bytes.size.toLong()) {
                realPathReadConflict("size=${expected.size} -> ${current.size}")
            }
            val read = readVerifiedRealPath(parent, name, node.bytes.size.toLong()) { }
            checkIdentity(expected, read.state)
            if (read.state.size != expected.size) realPathReadConflict("size=${expected.size} -> ${read.state.size}")
            // 中断写入只接受已记录长度的预定内容前缀，完整写入不得被外部截短。
            val hash = externalSha256(node.bytes.copyOf(read.state.size.toInt()))
            if (read.sha256 != hash) realPathReadConflict("sha256=$hash -> ${read.sha256}")
            checkRealPathReadState(read.state, parent.stat(name))
            parent.deleteFile(name)
        }
    }

    private fun checkIdentity(expected: RealPathFileState, actual: RealPathFileState?) {
        if (actual == null || actual.objectId != expected.objectId || actual.isDirectory != expected.isDirectory ||
            actual.isSymbolicLink || actual.isRegularFile != expected.isRegularFile) realPathReadConflict("identity=${expected.objectId} -> ${actual?.objectId ?: "missing"}")
    }
    private fun requireSupported(state: RealPathFileState) {
        if (state.isSymbolicLink || !state.isDirectory && !state.isRegularFile) {
            throw ExternalFolderException("INVALID_PATH", "不支持链接或特殊文件 / Links and special files are not supported")
        }
    }
}
