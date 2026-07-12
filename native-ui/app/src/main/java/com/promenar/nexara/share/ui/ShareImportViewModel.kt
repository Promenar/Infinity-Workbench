package com.promenar.nexara.share.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.ShareIntentQueue
import com.promenar.nexara.ShareLease
import com.promenar.nexara.share.core.ShareImportBatchResult
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.share.core.ShareImportTarget
import com.promenar.nexara.share.core.ShareIndexStatus
import com.promenar.nexara.share.core.SharedFileImporter
import com.promenar.nexara.data.rag.VectorizationQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

enum class SharePresentationState { Idle, Presenting, Visible }

data class ShareImportUiState(
    val visible: Boolean = false,
    val items: List<ShareImportItem> = emptyList(),
    val targets: List<ShareImportTarget> = emptyList(),
    val selectedWorkspaceRootUuid: String? = null,
    val importing: Boolean = false,
    val presentation: SharePresentationState = SharePresentationState.Idle,
    val pendingCount: Int = 0,
    val errorMessage: String? = null,
)

class ShareImportViewModel(
    private val queue: ShareIntentQueue,
    private val importer: SharedFileImporter,
    private val targetProvider: suspend () -> List<ShareImportTarget>,
    private val indexQueueState: StateFlow<VectorizationQueue.QueueState>? = null,
    private val retryIndex: suspend (String, String) -> Boolean = { _, _ -> false },
) : ViewModel() {
    private val _state = MutableStateFlow(ShareImportUiState())
    val state: StateFlow<ShareImportUiState> = _state.asStateFlow()
    private var lease: ShareLease? = null
    private var completedRequest: com.promenar.nexara.ShareRequest? = null
    private val presentMutex = Mutex()

    init {
        viewModelScope.launch {
            queue.refreshDurableCount()
            queue.durablePendingCount.collectLatest { count ->
                _state.value = _state.value.copy(pendingCount = count)
            }
        }
        indexQueueState?.let { states ->
            viewModelScope.launch {
                states.collectLatest { queueState -> reconcileIndexState(queueState) }
            }
        }
    }

    fun presentNext() {
        viewModelScope.launch {
            presentMutex.withLock {
                if (_state.value.presentation != SharePresentationState.Idle || _state.value.importing) return@withLock
                _state.value = _state.value.copy(presentation = SharePresentationState.Presenting)
                val claimed = queue.claimNextDurably()
                if (claimed == null) {
                    _state.value = _state.value.copy(presentation = SharePresentationState.Idle)
                    return@withLock
                }
                lease = claimed
                try {
                    val targets = targetProvider().distinctBy { it.workspaceRootUuid }
                    val items = importer.inspect(claimed.request)
                    _state.value = ShareImportUiState(
                        visible = true,
                        items = items,
                        targets = targets,
                        selectedWorkspaceRootUuid = claimed.request.targetWorkspaceRootUuid
                            ?.takeIf { saved -> targets.any { it.workspaceRootUuid == saved } }
                            ?: targets.singleOrNull()?.workspaceRootUuid,
                        presentation = SharePresentationState.Visible,
                        pendingCount = _state.value.pendingCount,
                    )
                    indexQueueState?.value?.let(::reconcileIndexState)
                } catch (cancelled: CancellationException) {
                    nackAfterCancellation(claimed)
                    throw cancelled
                } catch (failure: Exception) {
                    val released = tryNack(claimed)
                    if (released) lease = null
                    _state.value = _state.value.copy(
                        visible = false,
                        presentation = SharePresentationState.Idle,
                        errorMessage = failure.toShareError(),
                    )
                }
            }
        }
    }

    fun selectTarget(workspaceRootUuid: String) {
        if (_state.value.targets.none { it.workspaceRootUuid == workspaceRootUuid }) return
        _state.value = _state.value.copy(selectedWorkspaceRootUuid = workspaceRootUuid)
    }

    fun importAll() {
        val active = lease ?: return
        val root = _state.value.selectedWorkspaceRootUuid ?: return
        if (_state.value.importing) return
        _state.value = _state.value.copy(importing = true, errorMessage = null)
        viewModelScope.launch {
            try {
                queue.recordTargetDurably(active.request.requestId, root)
                val result = importer.import(active.request, root)
                completedRequest = active.request
                // 先把实际导入结果呈现出来；即使后续 manifest/ack 失败，也不会伪装成未创建。
                _state.value = _state.value.copy(items = result.items)
                indexQueueState?.value?.let(::reconcileIndexState)
                queue.recordCreatedDurably(active.request.requestId, result.created)
                if (result.rejected.isEmpty()) {
                    if (!queue.ackDurably(active.token)) throw ShareImportPersistenceException("确认导入结果失败")
                    lease = null
                }
            } catch (cancelled: CancellationException) {
                nackAfterCancellation(active)
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(errorMessage = failure.toShareError())
            } finally {
                _state.value = _state.value.copy(importing = false)
            }
        }
    }

    fun retryRejected() {
        val request = completedRequest ?: lease?.request ?: return
        val root = _state.value.selectedWorkspaceRootUuid ?: return
        val failed = _state.value.items.filter {
            it.status == ShareImportStatus.Rejected && it.reason?.retryable == true
        }.mapTo(linkedSetOf()) { it.uri }
        val failedIndexes = _state.value.items.filter {
            it.status == ShareImportStatus.Created &&
                it.indexStatus in setOf(ShareIndexStatus.Failed, ShareIndexStatus.Partial) &&
                it.fileUuid != null
        }
        if (_state.value.importing) return
        if (failed.isEmpty() && failedIndexes.isNotEmpty()) {
            _state.value = _state.value.copy(importing = true, errorMessage = null)
            viewModelScope.launch {
                try {
                    val retried = failedIndexes.all { item -> retryIndex(root, item.fileUuid!!) }
                    if (!retried) throw ShareImportPersistenceException("索引任务暂时无法重试")
                    _state.value = _state.value.copy(
                        items = _state.value.items.map { item ->
                            if (item in failedIndexes) item.copy(indexStatus = ShareIndexStatus.Pending) else item
                        },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _state.value = _state.value.copy(errorMessage = failure.toShareError())
                } finally {
                    _state.value = _state.value.copy(importing = false)
                }
            }
            return
        }
        if (failed.isEmpty()) {
            // recordTarget/recordCreated/ack 任一步失败都可安全重放；importer 以 staged SHA 幂等复用已创建文件。
            importAll()
            return
        }
        _state.value = _state.value.copy(importing = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val retried = importer.import(request, root, failed)
                val merged = mergeRetry(_state.value.items, retried)
                _state.value = _state.value.copy(items = merged)
                queue.recordCreatedDurably(request.requestId, retried.created)
                if (merged.none { it.status == ShareImportStatus.Rejected }) {
                    lease?.let { active ->
                        if (!queue.ackDurably(active.token)) throw ShareImportPersistenceException("确认重试结果失败")
                        lease = null
                    }
                }
            } catch (cancelled: CancellationException) {
                lease?.let { nackAfterCancellation(it) }
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(errorMessage = failure.toShareError())
            } finally {
                _state.value = _state.value.copy(importing = false)
            }
        }
    }

    fun postpone(): Boolean {
        if (_state.value.importing) return false
        val shouldAdvance = lease == null
        val active = lease
        viewModelScope.launch {
            try {
                if (active == null || queue.nackDurably(active.token)) {
                    if (lease === active) lease = null
                    completedRequest = null
                    _state.value = ShareImportUiState(pendingCount = queue.durablePendingCount.value)
                } else {
                    _state.value = _state.value.copy(errorMessage = "暂时无法保存稍后处理状态，请重试")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(errorMessage = failure.toShareError())
            }
        }
        return shouldAdvance
    }

    fun cancelConfirmed() {
        if (_state.value.importing) return
        val active = lease ?: return
        viewModelScope.launch {
            try {
                if (queue.dropDurably(active.token)) {
                    lease = null
                    completedRequest = null
                    _state.value = ShareImportUiState(pendingCount = queue.durablePendingCount.value)
                } else {
                    _state.value = _state.value.copy(errorMessage = "取消导入失败，请重试")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(errorMessage = failure.toShareError())
            }
        }
    }

    @Deprecated("使用 postpone 明确表达稍后处理")
    fun close(): Boolean = postpone()

    private fun mergeRetry(
        previous: List<ShareImportItem>,
        retry: ShareImportBatchResult,
    ): List<ShareImportItem> {
        val replacements = retry.items.associateBy { it.uri }
        return previous.map { replacements[it.uri] ?: it }
    }

    private suspend fun nackAfterCancellation(active: ShareLease) {
        try {
            if (queue.nackDurably(active.token) && lease === active) lease = null
        } catch (_: IOException) {
            // 取消路径不得覆盖原始 CancellationException；durable lease 可由新进程按 token/超时恢复。
        } catch (_: IllegalArgumentException) {
            // 同上。
        } catch (_: IllegalStateException) {
            // 取消路径不得覆盖原始 CancellationException；durable lease 可由新进程按 token/超时恢复。
        }
    }

    private suspend fun tryNack(active: ShareLease): Boolean = try {
        queue.nackDurably(active.token)
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    private fun Exception.toShareError(): String = message?.takeIf { it.isNotBlank() }
        ?.let { "导入状态保存失败：${it.take(120)}" }
        ?: "导入状态保存失败，请重试"

    private fun reconcileIndexState(queueState: VectorizationQueue.QueueState) {
        if (!queueState.restored && queueState.queue.isEmpty()) return
        val byId = queueState.queue.associateBy { it.id }
        _state.value = _state.value.copy(
            items = _state.value.items.map { item ->
                val taskId = item.indexTaskId ?: return@map item
                val task = byId[taskId]
                val status = when (task?.status) {
                    "failed" -> ShareIndexStatus.Failed
                    "partial" -> ShareIndexStatus.Partial
                    "completed" -> ShareIndexStatus.Completed
                    null -> if (queueState.restored) ShareIndexStatus.Completed else item.indexStatus
                    else -> ShareIndexStatus.Pending
                }
                item.copy(indexStatus = status)
            },
        )
    }

    companion object {
        fun factory(
            queue: ShareIntentQueue,
            importer: SharedFileImporter,
            targetProvider: suspend () -> List<ShareImportTarget>,
            indexQueueState: StateFlow<VectorizationQueue.QueueState>? = null,
            retryIndex: suspend (String, String) -> Boolean = { _, _ -> false },
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ShareImportViewModel(queue, importer, targetProvider, indexQueueState, retryIndex) as T
        }
    }
}

private class ShareImportPersistenceException(message: String) : Exception(message)
