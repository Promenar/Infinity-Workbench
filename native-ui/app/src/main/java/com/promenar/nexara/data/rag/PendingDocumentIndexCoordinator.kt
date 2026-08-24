package com.promenar.nexara.data.rag

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DocumentIndexTargetConflictException(
    val current: FileIndexEvent.Changed,
    val rejected: FileIndexEvent.Changed,
) : IllegalStateException(
    "同一文件 epoch=${current.targetEpoch} 对应不同内容哈希，已拒绝覆盖待补偿目标",
)

/**
 * 保存尚未被当前索引队列确认接收的完整文档目标。
 *
 * 下游由调用方动态解析，避免应用重置 VectorizationQueue 后持有旧实例。
 */
class PendingDocumentIndexCoordinator(
    private val downstream: FileIndexEventSink,
    private val resolveCurrentTarget: suspend (FileIndexEvent.Changed) -> FileIndexEvent.Changed? = { it },
) : FileIndexEventSink {
    private data class Key(
        val workspaceRootUuid: String,
        val fileUuid: String,
    )

    private val mutex = Mutex()
    private val targets = linkedMapOf<Key, FileIndexEvent.Changed>()
    private val latestKnownTargets = mutableMapOf<Key, FileIndexEvent.Changed>()
    private val committedDeletedKeys = mutableSetOf<Key>()
    private val _pendingTargets = MutableStateFlow<List<FileIndexEvent.Changed>>(emptyList())
    val pendingTargets: StateFlow<List<FileIndexEvent.Changed>> = _pendingTargets.asStateFlow()

    private sealed interface RetryPreparation {
        data object Missing : RetryPreparation
        data object Deleted : RetryPreparation
        data class Dispatch(val target: FileIndexEvent.Changed) : RetryPreparation
    }

    fun snapshot(): List<FileIndexEvent.Changed> = pendingTargets.value

    override suspend fun publish(event: FileIndexEvent) {
        when (event) {
            is FileIndexEvent.Changed -> {
                val resolved = preparePublish(event) ?: return
                downstream.publish(resolved)
                removeIfExact(resolved)
            }
            is FileIndexEvent.Deleted -> {
                downstream.publish(event)
                clearCommitted(event.workspaceRootUuid, listOf(event.fileUuid))
            }
        }
    }

    /**
     * 删除事务已提交后，清理待补偿与水位并保留进程内 key tombstone，不再向下游发布事件。
     *
     * 目录删除应传入完整子树 ids，以批量释放对应 latest 水位；tombstone 会在进程重启时自然清空。
     * 已删除 UUID 在同一进程内不得复用，重新创建文件或目录必须分配新 UUID。
     */
    suspend fun clearCommitted(workspaceRootUuid: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            var pendingChanged = false
            ids.forEach { fileUuid ->
                val key = Key(workspaceRootUuid, fileUuid)
                pendingChanged = targets.remove(key) != null || pendingChanged
                latestKnownTargets.remove(key)
                committedDeletedKeys += key
            }
            if (pendingChanged) publishStateLocked()
        }
    }

    /** 回收提交后清除待处理水位，但不永久封禁 UUID，允许随后恢复同一文件。 */
    suspend fun clearForRecycle(workspaceRootUuid: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            var pendingChanged = false
            ids.forEach { fileUuid ->
                val key = Key(workspaceRootUuid, fileUuid)
                pendingChanged = targets.remove(key) != null || pendingChanged
                latestKnownTargets.remove(key)
                committedDeletedKeys.remove(key)
            }
            if (pendingChanged) publishStateLocked()
        }
    }

    /** 恢复事务提交后解除回收期间可能由迟到事件建立的进程内 tombstone。 */
    suspend fun resumeAfterRestore(workspaceRootUuid: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            ids.forEach { fileUuid ->
                committedDeletedKeys.remove(Key(workspaceRootUuid, fileUuid))
            }
        }
    }

    /** 普通失败保留目标并返回 false；取消必须继续向调用方传播。 */
    suspend fun retry(target: FileIndexEvent.Changed): Boolean {
        val preparation = try {
            prepareRetry(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        when (preparation) {
            RetryPreparation.Missing -> return false
            RetryPreparation.Deleted -> return true
            is RetryPreparation.Dispatch -> Unit
        }
        val resolved = (preparation as RetryPreparation.Dispatch).target
        return try {
            downstream.publish(resolved)
            removeIfExact(resolved)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun preparePublish(event: FileIndexEvent.Changed): FileIndexEvent.Changed? {
        val registered = withContext(NonCancellable) {
            mutex.withLock { registerRequestLocked(event) }
        }
        currentCoroutineContext().ensureActive()
        registered ?: return null
        return mutex.withLock {
            val current = targets[registered.key()]
                ?.takeIf { pending -> pending.sameTargetAs(registered) }
                ?: return@withLock null
            val resolved = resolveCurrentTarget(current)
            if (resolved == null) {
                tombstoneIfExactLocked(current)
                return@withLock null
            }
            require(resolved.key() == current.key()) {
                "当前索引目标解析结果不得改变 workspace 或 file 标识"
            }
            registerNewerWinsLocked(resolved)
        }
    }

    private fun registerRequestLocked(event: FileIndexEvent.Changed): FileIndexEvent.Changed? {
        val key = event.key()
        if (key in committedDeletedKeys) return null
        val latest = latestKnownTargets[key]
        return when {
            latest == null || event.targetEpoch > latest.targetEpoch -> acceptNewTargetLocked(event)
            event.targetEpoch < latest.targetEpoch -> null
            event.contentHash == latest.contentHash ->
                targets[key]?.takeIf { pending -> pending.sameTargetAs(latest) }
            else -> throw DocumentIndexTargetConflictException(latest, event)
        }
    }

    private suspend fun prepareRetry(target: FileIndexEvent.Changed): RetryPreparation =
        mutex.withLock {
            val current = targets[target.key()]
                ?.takeIf { pending -> pending.sameTargetAs(target) }
                ?: return@withLock RetryPreparation.Missing
            val resolved = resolveCurrentTarget(current)
            if (resolved == null) {
                tombstoneIfExactLocked(current)
                return@withLock RetryPreparation.Deleted
            }
            require(resolved.key() == current.key()) {
                "当前索引目标解析结果不得改变 workspace 或 file 标识"
            }
            RetryPreparation.Dispatch(registerNewerWinsLocked(resolved))
        }

    private suspend fun removeIfExact(target: FileIndexEvent.Changed) {
        mutex.withLock {
            removeIfExactLocked(target)
        }
    }

    private fun registerNewerWinsLocked(event: FileIndexEvent.Changed): FileIndexEvent.Changed {
        val key = event.key()
        check(key !in committedDeletedKeys) { "已删除目标不得重新登记" }
        val latest = latestKnownTargets[key]
        return when {
            latest == null || event.targetEpoch > latest.targetEpoch -> acceptNewTargetLocked(event)
            event.targetEpoch < latest.targetEpoch -> targets[key] ?: latest
            event.contentHash == latest.contentHash -> targets[key] ?: latest
            else -> throw DocumentIndexTargetConflictException(latest, event)
        }
    }

    private fun acceptNewTargetLocked(event: FileIndexEvent.Changed): FileIndexEvent.Changed {
        val key = event.key()
        latestKnownTargets[key] = event
        targets[key] = event
        publishStateLocked()
        return event
    }

    private fun removeIfExactLocked(target: FileIndexEvent.Changed) {
        val key = target.key()
        val current = targets[key]
        if (current?.sameTargetAs(target) == true) {
            targets.remove(key)
            publishStateLocked()
        }
    }

    private fun tombstoneIfExactLocked(target: FileIndexEvent.Changed) {
        val key = target.key()
        if (targets[key]?.sameTargetAs(target) != true) return
        targets.remove(key)
        latestKnownTargets.remove(key)
        committedDeletedKeys += key
        publishStateLocked()
    }

    private fun publishStateLocked() {
        _pendingTargets.value = targets.values.toList()
    }

    private fun FileIndexEvent.Changed.key(): Key = Key(workspaceRootUuid, fileUuid)

    private fun FileIndexEvent.Changed.sameTargetAs(other: FileIndexEvent.Changed): Boolean =
        contentHash == other.contentHash && targetEpoch == other.targetEpoch
}
