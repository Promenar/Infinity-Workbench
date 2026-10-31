package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 跨命名空间只保存结构、身份和摘要；来源删除失败时从可信目标补回缺失项。 */
internal object ExternalStreamingMove {
    private data class Node(val relative: String, val entry: ExternalFolderEntry, val hash: String?)
    private data class Created(val node: Node, val receipt: ExternalFolderEntry)
    private fun path(root: String, relative: String) = root.trimEnd('/') + relative
    private fun conflict(): Nothing = throw ExternalFolderException("CONFLICT", "复制期间来源发生变化，移动已取消 / Source changed during move")

    suspend fun move(source: ExternalFolderBackend, sourcePath: String, target: ExternalFolderBackend, targetPath: String, expected: String?) {
        val from = ExternalFolderPaths.normalize(sourcePath)
        val to = ExternalFolderPaths.normalize(targetPath)
        source.validateTree(from)
        val nodes = scan(source, from)
        if (expected != null && nodes.singleOrNull()?.hash != expected) conflict()
        if (target.stat(to) != null) throw ExternalFolderException("CONFLICT", "目标已存在，不能覆盖")
        val size = nodes.fold(0L) { total, node ->
            val bytes = if (node.entry.isDirectory) 0L else node.entry.size
            if (bytes < 0 || bytes > Long.MAX_VALUE - total) throw ExternalFolderException("NO_SPACE", "移动大小超出存储容量 / Move exceeds storage capacity")
            total + bytes
        }
        target.availableBytes(to.substringBeforeLast('/').ifEmpty { "/" })?.let {
            if (it < size) throw ExternalFolderException("NO_SPACE", "目标可用空间不足 / Insufficient destination space")
        }
        val created = mutableListOf<Created>()
        var deleting = false
        var pending: String? = null
        try {
            for (node in nodes) {
                currentCoroutineContext().ensureActive()
                val destination = path(to, node.relative)
                pending = destination
                val receipt = if (node.entry.isDirectory) target.createDirectory(destination)
                    else transfer(source, path(from, node.relative), target, destination, node) { created.add(Created(node, it)) }
                if (node.entry.isDirectory) created.add(Created(node, receipt))
                pending = null
                verify(target, destination, node, receipt.identity)
            }
            // 删除前重新确认整棵目标，避免把外部已替换的副本当作恢复来源。
            verifyTarget(target, to, created)
            currentCoroutineContext().ensureActive()
            if (!sameTree(nodes, scan(source, from), identities = true)) conflict()
            val removalOrder = created.filterNot { it.node.entry.isDirectory } + created.asReversed().filter { it.node.entry.isDirectory }
            for ((node, receipt) in removalOrder) {
                val sourceItem = path(from, node.relative)
                val targetItem = path(to, node.relative)
                if (node.entry.isDirectory) {
                    verify(source, sourceItem, node, node.entry.identity)
                    if (source.list(sourceItem).isNotEmpty()) conflict()
                    deleting = true
                    source.deleteForTransfer(sourceItem, node.entry, null)
                } else removeFile(source, sourceItem, target, targetItem, node, receipt) { deleting = true }
            }
            if (source.stat(from) != null) throw ExternalFolderException("IO", "来源删除未完成")
            verifyTarget(target, to, created)
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                try {
                    val unconfirmed = pending
                    if (unconfirmed != null && created.none { path(to, it.node.relative) == unconfirmed } &&
                        (failure !is ExternalFolderException || failure.code != "CONFLICT") && target.stat(unconfirmed) != null) {
                        throw ExternalFolderException("RECOVERY_REQUIRED", "目标创建结果未知：$unconfirmed")
                    }
                    if (deleting) {
                        verifyTarget(target, to, created)
                        restore(source, from, target, to, nodes)
                    }
                    rollback(target, to, created)
                } catch (cleanup: Exception) {
                    val recovery = ExternalFolderException("RECOVERY_REQUIRED", "移动补偿无法安全完成，请核查来源 $sourcePath 与目标 $targetPath / Check source and destination")
                    recovery.addSuppressed(failure); recovery.addSuppressed(cleanup)
                    if (failure is CancellationException) failure.addSuppressed(recovery) else throw recovery
                }
            }
            if (failure is ExternalFolderException && failure.code == "RECOVERY_REQUIRED") {
                throw ExternalFolderException(failure.code, "请核查来源 $sourcePath 与目标 $targetPath / Check source and destination").apply { initCause(failure) }
            }
            throw failure
        }
    }

    private suspend fun removeFile(source: ExternalFolderBackend, from: String, target: ExternalFolderBackend, to: String,
        node: Node, receipt: ExternalFolderEntry, beforeDelete: () -> Unit) {
        var committed = false
        try {
            source.readStream(from, Long.MAX_VALUE) { rescue ->
                verify(target, to, node, receipt.identity)
                verify(source, from, node, node.entry.identity)
                beforeDelete()
                try {
                    source.deleteForTransfer(from, node.entry, node.hash)
                    if (source.stat(from) != null) throw ExternalFolderException("IO", "来源删除未完成")
                    verify(target, to, node, receipt.identity)
                    committed = true
                } catch (failure: Exception) {
                    withContext(NonCancellable) {
                        if (source.stat(from) == null && runCatching { verify(target, to, node, receipt.identity) }.isFailure) {
                            // 单个源读句柄保留到删除后校验结束，不随树条目数累积 FD。
                            val restored = source.writeStream(from, node.entry.size, createOnly = true) { output ->
                                if (copyExternalStream(rescue, output) != node.hash) conflict()
                            }
                            verify(source, from, node, restored.identity)
                        }
                    }
                    throw failure
                }
            }
        } catch (failure: Exception) {
            // 后端的读后路径复核会观察到我们主动删除的原名；目标已独立验证时提交成立。
            if (!committed || failure is CancellationException) throw failure
        }
    }

    private suspend fun scan(backend: ExternalFolderBackend, root: String): List<Node> {
        val nodes = mutableListOf<Node>()
        suspend fun visit(current: String, relative: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > 32) throw ExternalFolderException("TOO_LARGE", "目录超过32层深度上限")
            val before = backend.stat(current) ?: conflict()
            val hash = if (before.isDirectory) null else backend.streamHash(current)
            val after = backend.stat(current) ?: conflict()
            if (before.isDirectory != after.isDirectory || before.identity != after.identity || !before.isDirectory && before.size != after.size) conflict()
            nodes.add(Node(relative, after, hash))
            if (after.isDirectory) {
                val children = backend.list(current).sortedBy { it.name }
                if (children.map { it.name }.distinct().size != children.size) conflict()
                for (child in children) {
                    ExternalFolderPaths.validateName(child.name)
                    visit(current.trimEnd('/') + "/" + child.name, "$relative/${child.name}", depth + 1)
                }
            }
        }
        visit(root, "", 0)
        return nodes
    }

    private fun sameTree(before: List<Node>, after: List<Node>, identities: Boolean) = before.size == after.size && before.zip(after).all { (a, b) ->
        a.relative == b.relative && a.entry.isDirectory == b.entry.isDirectory && a.hash == b.hash &&
            (a.entry.isDirectory || a.entry.size == b.entry.size) && (!identities || a.entry.identity == b.entry.identity)
    }

    private suspend fun transfer(source: ExternalFolderBackend, from: String, target: ExternalFolderBackend, to: String, node: Node, onCreated: (ExternalFolderEntry) -> Unit = {}): ExternalFolderEntry =
        source.readStream(from, Long.MAX_VALUE) { input ->
            target.writeStream(to, node.entry.size, createOnly = true) { output ->
                var sourceHash: String? = null
                val writtenHash = produceExternalStream(output, node.entry.size) { checked -> sourceHash = copyExternalStream(input, checked) }
                if (sourceHash != node.hash || writtenHash != node.hash) conflict()
            }.also(onCreated)
        }

    private suspend fun verify(backend: ExternalFolderBackend, destination: String, node: Node, identity: String?) {
        val current = backend.stat(destination) ?: conflict()
        if (current.isDirectory != node.entry.isDirectory || identity != null && identity != current.identity ||
            !current.isDirectory && (current.size != node.entry.size || backend.streamHash(destination) != node.hash)) conflict()
    }

    private suspend fun verifyTarget(backend: ExternalFolderBackend, root: String, created: List<Created>) {
        // 先整棵检查再清理，若存在未知条目，不先删掉其它可信副本。
        val childrenByParent = created.filter { it.node.relative.isNotEmpty() }
            .groupBy { it.node.relative.substringBeforeLast('/', "") }
        for ((node, receipt) in created) {
            val destination = path(root, node.relative)
            verify(backend, destination, node, receipt.identity)
            if (node.entry.isDirectory) {
                val expected = childrenByParent[node.relative].orEmpty()
                    .map { it.node.relative.substringAfterLast('/') }.toSet()
                if (backend.list(destination).map { it.name }.toSet() != expected) conflict()
            }
        }
    }

    private suspend fun restore(source: ExternalFolderBackend, from: String, target: ExternalFolderBackend, to: String, nodes: List<Node>) {
        // 已存在的来源只校验，绝不覆盖并发写入；缺失目录按父到子的顺序补回。
        for (node in nodes) {
            val destination = path(from, node.relative)
            if (source.stat(destination) != null) verify(source, destination, node, node.entry.identity)
            else {
                val receipt = if (node.entry.isDirectory) source.createDirectory(destination)
                    else transfer(target, path(to, node.relative), source, destination, node)
                verify(source, destination, node, receipt.identity)
            }
        }
        if (!sameTree(nodes, scan(source, from), identities = false)) conflict()
    }

    private suspend fun rollback(target: ExternalFolderBackend, root: String, created: List<Created>) {
        if (created.isEmpty()) return
        verifyTarget(target, root, created)
        for ((node, receipt) in created.asReversed()) {
            val destination = path(root, node.relative)
            verify(target, destination, node, receipt.identity)
            if (node.entry.isDirectory && target.list(destination).isNotEmpty()) conflict()
            target.deleteForTransfer(destination, receipt, node.hash)
        }
    }
}
