package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.rag.FileIndexEvent
import com.promenar.nexara.data.rag.PendingDocumentIndexCoordinator
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.RenameResult
import com.promenar.nexara.domain.repository.RenameIndexTarget
import com.promenar.nexara.domain.repository.WriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicReference

enum class DocEditorPhase {
    Loading,
    Ready,
    LoadError,
    Saving,
    SaveConflict,
    SaveError,
    NotFound,
}

enum class DocEditorFailureCode {
    LoadFailed,
    LoadNotFound,
    TitleRenameFailed,
    ContentSaveFailed,
    ContentConflict,
    TitleConflict,
    SaveNotFound,
    SaveCancelled,
}

data class DocEditorUiState(
    val phase: DocEditorPhase = DocEditorPhase.Loading,
    val workspaceRootUuid: String = "",
    val documentId: String = "",
    val documentEpoch: Long = 0L,
    val title: String = "",
    val content: String = "",
    val persistedTitle: String = "",
    val persistedContent: String = "",
    val currentHash: String = "",
    val totalLines: Int = 0,
    val lastModified: Long = 0L,
    val sizeBytes: Long = 0L,
    val titleDirty: Boolean = false,
    val contentDirty: Boolean = false,
    val isLargeFile: Boolean = false,
    val warningDismissed: Boolean = false,
    val hasLoadedDocument: Boolean = false,
    val failureCode: DocEditorFailureCode? = null,
    /** 仅保留给未来经脱敏的技术诊断；绝不直接写入异常原文。 */
    val failureDetail: String? = null,
    val conflictCurrentHash: String? = null,
    val titleConflictCurrentName: String? = null,
    val indexPendingTargets: List<RenameIndexTarget> = emptyList(),
    val indexQueueFailed: Boolean = false,
) {
    val isDirty: Boolean
        get() = titleDirty || contentDirty

    val document: Document?
        get() = if (hasLoadedDocument) {
            Document(
                id = documentId,
                folderId = "",
                title = title,
                content = content,
                hash = currentHash,
                createdAt = lastModified,
                updatedAt = lastModified,
            )
        } else {
            null
        }
}

class DocEditorViewModel(
    private val fileOperationRepository: IFileOperationRepository,
    private val workspaceRepository: IWorkspaceRepository,
    private val indexEventSink: com.promenar.nexara.data.rag.FileIndexEventSink? = null,
    private val pendingIndexCoordinator: PendingDocumentIndexCoordinator? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DocEditorUiState())
    val uiState: StateFlow<DocEditorUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private val saveMutex = Mutex()
    private val activeSaveIdentity = AtomicReference<SaveIdentity?>(null)

    init {
        pendingIndexCoordinator?.let { coordinator ->
            viewModelScope.launch {
                coordinator.pendingTargets.collect {
                    synchronizeSharedPendingTargets()
                }
            }
        }
    }

    fun loadFile(workspaceRootUuid: String, uuid: String) {
        val activeSave = activeSaveIdentity.get()
        val current = _uiState.value
        if (activeSave != null &&
            activeSave.workspaceRootUuid == workspaceRootUuid &&
            activeSave.documentId == uuid &&
            current.workspaceRootUuid == activeSave.workspaceRootUuid &&
            current.documentId == activeSave.documentId &&
            current.documentEpoch == activeSave.documentEpoch
        ) {
            return
        }

        loadJob?.cancel()
        val generation = ++loadGeneration
        val loadingPendingTargets = sharedPendingTargets(workspaceRootUuid, uuid)
        _uiState.value = DocEditorUiState(
            phase = DocEditorPhase.Loading,
            workspaceRootUuid = workspaceRootUuid,
            documentId = uuid,
            documentEpoch = generation,
            indexPendingTargets = loadingPendingTargets,
            indexQueueFailed = loadingPendingTargets.isNotEmpty(),
        )
        loadJob = viewModelScope.launch {
            try {
                val fileEntry = workspaceRepository.getByUuid(workspaceRootUuid, uuid)
                    ?: throw NoSuchElementException(uuid)
                if (fileEntry.sizeBytes > LARGE_FILE_SIZE_BYTES) {
                    val readyPendingTargets = sharedPendingTargets(workspaceRootUuid, uuid)
                    updateCurrentLoad(generation, workspaceRootUuid, uuid) {
                        DocEditorUiState(
                            phase = DocEditorPhase.Ready,
                            workspaceRootUuid = workspaceRootUuid,
                            documentId = uuid,
                            documentEpoch = generation,
                            title = fileEntry.name,
                            persistedTitle = fileEntry.name,
                            currentHash = fileEntry.hash,
                            lastModified = fileEntry.updatedAt,
                            sizeBytes = fileEntry.sizeBytes,
                            isLargeFile = true,
                            hasLoadedDocument = true,
                            indexPendingTargets = readyPendingTargets,
                            indexQueueFailed = readyPendingTargets.isNotEmpty(),
                        )
                    }
                    return@launch
                }
                val result = fileOperationRepository.readFileRange(workspaceRootUuid, uuid)
                val readyPendingTargets = sharedPendingTargets(workspaceRootUuid, uuid)
                updateCurrentLoad(generation, workspaceRootUuid, uuid) {
                    DocEditorUiState(
                        phase = DocEditorPhase.Ready,
                        workspaceRootUuid = workspaceRootUuid,
                        documentId = uuid,
                        documentEpoch = generation,
                        title = result.name,
                        content = result.content,
                        persistedTitle = result.name,
                        persistedContent = result.content,
                        currentHash = result.hash,
                        totalLines = result.totalLines,
                        lastModified = result.lastModified,
                        sizeBytes = fileEntry.sizeBytes,
                        hasLoadedDocument = true,
                        indexPendingTargets = readyPendingTargets,
                        indexQueueFailed = readyPendingTargets.isNotEmpty(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NoSuchElementException) {
                updateCurrentLoad(generation, workspaceRootUuid, uuid) {
                    it.copy(
                        phase = DocEditorPhase.NotFound,
                        failureCode = DocEditorFailureCode.LoadNotFound,
                        failureDetail = null,
                    )
                }
            } catch (_: Exception) {
                updateCurrentLoad(generation, workspaceRootUuid, uuid) {
                    it.copy(
                        phase = DocEditorPhase.LoadError,
                        failureCode = DocEditorFailureCode.LoadFailed,
                        failureDetail = null,
                    )
                }
            }
        }
    }

    fun loadDocument(workspaceRootUuid: String, docId: String) = loadFile(workspaceRootUuid, docId)

    fun reload() {
        val current = _uiState.value
        if (current.workspaceRootUuid.isBlank() || current.documentId.isBlank()) return
        loadFile(current.workspaceRootUuid, current.documentId)
    }

    fun saveDocument() {
        val snapshot = _uiState.value
        if (!snapshot.hasLoadedDocument ||
            snapshot.workspaceRootUuid.isBlank() ||
            snapshot.documentId.isBlank() ||
            snapshot.phase == DocEditorPhase.SaveConflict ||
            snapshot.isLargeFile ||
            !snapshot.isDirty ||
            !saveMutex.tryLock()
        ) {
            return
        }
        val saveIdentity = SaveIdentity(
            workspaceRootUuid = snapshot.workspaceRootUuid,
            documentId = snapshot.documentId,
            documentEpoch = snapshot.documentEpoch,
        )
        activeSaveIdentity.set(saveIdentity)

        _uiState.update {
            it.copy(
                phase = DocEditorPhase.Saving,
                failureCode = null,
                failureDetail = null,
                conflictCurrentHash = null,
                titleConflictCurrentName = null,
            )
        }
        viewModelScope.launch {
            try {
                if (snapshot.titleDirty) {
                    val renamed = renameTitle(snapshot)
                    if (!renamed) return@launch
                }

                if (snapshot.contentDirty) {
                    saveContent(snapshot)
                } else {
                    markReadyFor(snapshot)
                }
            } catch (cancelled: CancellationException) {
                recoverCancelledSave(snapshot)
                throw cancelled
            } finally {
                activeSaveIdentity.compareAndSet(saveIdentity, null)
                saveMutex.unlock()
            }
        }
    }

    private suspend fun renameTitle(snapshot: DocEditorUiState): Boolean = try {
        when (val result = workspaceRepository.rename(
            workspaceRootUuid = snapshot.workspaceRootUuid,
            uuid = snapshot.documentId,
            newName = snapshot.title,
            expectedName = snapshot.persistedTitle,
        )) {
            is RenameResult.Success -> {
                updateSameDocument(snapshot) { current ->
                    current.copy(
                        persistedTitle = result.name,
                        titleDirty = current.title != result.name,
                        lastModified = result.targetEpoch,
                        titleConflictCurrentName = null,
                    )
                }
                if (pendingIndexCoordinator == null) {
                    mergePendingIndexTargets(snapshot, result.affectedTargets)
                }
                result.affectedTargets.forEach { target ->
                    try {
                        val event = FileIndexEvent.Changed(
                            workspaceRootUuid = snapshot.workspaceRootUuid,
                            fileUuid = target.fileUuid,
                            contentHash = target.targetHash,
                            targetEpoch = target.targetEpoch,
                        )
                        if (pendingIndexCoordinator != null) {
                            pendingIndexCoordinator.publish(event)
                        } else {
                            indexEventSink?.publish(event)
                            removePendingIndexTarget(snapshot, target)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 重命名已经提交；精确 target 留在补偿队列中。
                    }
                }
                true
            }

            is RenameResult.Conflict -> {
                updateSameDocument(snapshot) { current ->
                    current.copy(
                        phase = DocEditorPhase.SaveConflict,
                        failureCode = DocEditorFailureCode.TitleConflict,
                        failureDetail = null,
                        conflictCurrentHash = null,
                        titleConflictCurrentName = result.current,
                    )
                }
                false
            }

            RenameResult.NotFound -> {
                updateSameDocument(snapshot) { current ->
                    current.copy(
                        phase = DocEditorPhase.NotFound,
                        failureCode = DocEditorFailureCode.SaveNotFound,
                        failureDetail = null,
                        conflictCurrentHash = null,
                        titleConflictCurrentName = null,
                    )
                }
                false
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        updateSameDocument(snapshot) { current ->
            current.copy(
                phase = DocEditorPhase.SaveError,
                failureCode = DocEditorFailureCode.TitleRenameFailed,
                failureDetail = null,
            )
        }
        false
    }

    private suspend fun saveContent(snapshot: DocEditorUiState) {
        val result = try {
            fileOperationRepository.writeFileAtomic(
                workspaceRootUuid = snapshot.workspaceRootUuid,
                uuid = snapshot.documentId,
                newContent = snapshot.content,
                sessionId = EDITOR_SESSION_ID,
                expectedHash = snapshot.currentHash,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            updateSameDocument(snapshot) { current ->
                current.copy(
                    phase = DocEditorPhase.SaveError,
                    failureCode = DocEditorFailureCode.ContentSaveFailed,
                    failureDetail = null,
                )
            }
            return
        }

        when (result) {
            is WriteResult.Success -> {
                updateSameDocument(snapshot) { current ->
                    val pending = if (pendingIndexCoordinator != null) {
                        sharedPendingTargets(snapshot.workspaceRootUuid, snapshot.documentId)
                    } else {
                        current.indexPendingTargets
                            .filterNot { it.fileUuid == snapshot.documentId }
                            .toMutableList()
                            .apply {
                                if (!result.indexQueued && result.targetEpoch != null) {
                                    add(
                                        RenameIndexTarget(
                                            fileUuid = snapshot.documentId,
                                            targetHash = result.newHash,
                                            targetEpoch = result.targetEpoch,
                                        ),
                                    )
                                }
                            }
                    }
                    current.copy(
                        phase = DocEditorPhase.Ready,
                        persistedContent = snapshot.content,
                        currentHash = result.newHash,
                        lastModified = result.targetEpoch ?: current.lastModified,
                        contentDirty = current.content != snapshot.content,
                        failureCode = null,
                        failureDetail = null,
                        conflictCurrentHash = null,
                        titleConflictCurrentName = null,
                        indexPendingTargets = pending,
                        indexQueueFailed = pending.isNotEmpty(),
                    )
                }
            }

            is WriteResult.Conflict -> updateSameDocument(snapshot) { current ->
                current.copy(
                    phase = DocEditorPhase.SaveConflict,
                    failureCode = DocEditorFailureCode.ContentConflict,
                    failureDetail = null,
                    conflictCurrentHash = result.currentHash,
                    titleConflictCurrentName = null,
                )
            }

            WriteResult.NotFound -> updateSameDocument(snapshot) { current ->
                current.copy(
                    phase = DocEditorPhase.NotFound,
                    failureCode = DocEditorFailureCode.SaveNotFound,
                    failureDetail = null,
                    titleConflictCurrentName = null,
                )
            }
        }
    }

    private fun markReadyFor(snapshot: DocEditorUiState) {
        updateSameDocument(snapshot) { current ->
            current.copy(
                phase = DocEditorPhase.Ready,
                failureCode = null,
                failureDetail = null,
                conflictCurrentHash = null,
                titleConflictCurrentName = null,
            )
        }
    }

    private fun recoverCancelledSave(snapshot: DocEditorUiState) {
        if (viewModelScope.coroutineContext[Job]?.isActive != true) return
        updateSameDocument(snapshot) { current ->
            if (current.phase != DocEditorPhase.Saving) {
                current
            } else {
                current.copy(
                    phase = DocEditorPhase.SaveError,
                    failureCode = DocEditorFailureCode.SaveCancelled,
                    failureDetail = null,
                    conflictCurrentHash = null,
                    titleConflictCurrentName = null,
                )
            }
        }
    }

    private inline fun updateSameDocument(
        snapshot: DocEditorUiState,
        transform: (DocEditorUiState) -> DocEditorUiState,
    ) {
        _uiState.update { current ->
            if (current.workspaceRootUuid == snapshot.workspaceRootUuid &&
                current.documentId == snapshot.documentId &&
                current.documentEpoch == snapshot.documentEpoch
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    private inline fun updateCurrentLoad(
        generation: Long,
        workspaceRootUuid: String,
        documentId: String,
        transform: (DocEditorUiState) -> DocEditorUiState,
    ) {
        _uiState.update { current ->
            if (generation == loadGeneration &&
                current.workspaceRootUuid == workspaceRootUuid &&
                current.documentId == documentId &&
                current.documentEpoch == generation
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    fun onContentChanged(newContent: String) {
        _uiState.update { current ->
            if (!current.hasLoadedDocument || current.isLargeFile) current else current.copy(
                content = newContent,
                contentDirty = newContent != current.persistedContent,
                totalLines = newContent.lines().size,
                failureCode = current.failureCode.takeIf {
                    current.phase == DocEditorPhase.SaveConflict
                },
                failureDetail = null,
                conflictCurrentHash = current.conflictCurrentHash.takeIf {
                    current.phase == DocEditorPhase.SaveConflict
                },
                titleConflictCurrentName = current.titleConflictCurrentName.takeIf {
                    current.phase == DocEditorPhase.SaveConflict
                },
            )
        }
    }

    fun updateTitle(newTitle: String) {
        _uiState.update { current ->
            if (!current.hasLoadedDocument || current.isLargeFile) current else current.copy(
                title = newTitle,
                titleDirty = newTitle != current.persistedTitle,
                failureCode = current.failureCode.takeIf {
                    current.phase == DocEditorPhase.SaveConflict
                },
                failureDetail = null,
            )
        }
    }

    fun useWorkspaceTitle() {
        _uiState.update { current ->
            val workspaceTitle = current.titleConflictCurrentName
            if (current.phase != DocEditorPhase.SaveConflict ||
                current.failureCode != DocEditorFailureCode.TitleConflict ||
                workspaceTitle == null
            ) current else current.copy(
                phase = DocEditorPhase.Ready,
                title = workspaceTitle,
                persistedTitle = workspaceTitle,
                titleDirty = false,
                failureCode = null,
                failureDetail = null,
                conflictCurrentHash = null,
                titleConflictCurrentName = null,
            )
        }
    }

    fun retryMyTitle() {
        _uiState.update { current ->
            val workspaceTitle = current.titleConflictCurrentName
            if (current.phase != DocEditorPhase.SaveConflict ||
                current.failureCode != DocEditorFailureCode.TitleConflict ||
                workspaceTitle == null
            ) current else current.copy(
                phase = DocEditorPhase.Ready,
                persistedTitle = workspaceTitle,
                titleDirty = current.title != workspaceTitle,
                failureCode = null,
                failureDetail = null,
                conflictCurrentHash = null,
                titleConflictCurrentName = null,
            )
        }
    }

    fun dismissWarning() {
        _uiState.update { it.copy(warningDismissed = true) }
    }

    fun retryPendingIndex() {
        if (_uiState.value.indexPendingTargets.isEmpty()) return
        viewModelScope.launch {
            retryPendingIndexNow()
        }
    }

    internal suspend fun retryPendingIndexNow() {
        val snapshot = _uiState.value
        if (pendingIndexCoordinator != null) {
            pendingIndexCoordinator.snapshot()
                .filter {
                    it.workspaceRootUuid == snapshot.workspaceRootUuid &&
                        it.fileUuid == snapshot.documentId
                }
                .forEach { pendingIndexCoordinator.retry(it) }
            synchronizeSharedPendingTargets()
            return
        }
        snapshot.indexPendingTargets.forEach { target ->
            try {
                indexEventSink?.publish(
                    com.promenar.nexara.data.rag.FileIndexEvent.Changed(
                        workspaceRootUuid = snapshot.workspaceRootUuid,
                        fileUuid = target.fileUuid,
                        contentHash = target.targetHash,
                        targetEpoch = target.targetEpoch,
                    ),
                )
                removePendingIndexTarget(snapshot, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 单个 target 失败不阻止其它独立 target；失败项保持原样。
            }
        }
    }

    private fun mergePendingIndexTargets(
        snapshot: DocEditorUiState,
        targets: List<RenameIndexTarget>,
    ) {
        if (targets.isEmpty()) return
        updateSameDocument(snapshot) { current ->
            val merged = LinkedHashMap<String, RenameIndexTarget>()
            current.indexPendingTargets.forEach { merged[it.fileUuid] = it }
            targets.forEach { incoming ->
                val existing = merged[incoming.fileUuid]
                if (existing == null || incoming.targetEpoch >= existing.targetEpoch) {
                    merged[incoming.fileUuid] = incoming
                }
            }
            current.copy(
                indexPendingTargets = merged.values.toList(),
                indexQueueFailed = merged.isNotEmpty(),
            )
        }
    }

    private fun removePendingIndexTarget(
        snapshot: DocEditorUiState,
        target: RenameIndexTarget,
    ) {
        updateSameDocument(snapshot) { current ->
            val pending = current.indexPendingTargets.filterNot { it == target }
            current.copy(
                indexPendingTargets = pending,
                indexQueueFailed = pending.isNotEmpty(),
            )
        }
    }

    private fun sharedPendingTargets(
        workspaceRootUuid: String,
        documentId: String,
    ): List<RenameIndexTarget> = pendingIndexCoordinator?.snapshot().orEmpty()
        .asSequence()
        .filter { it.workspaceRootUuid == workspaceRootUuid && it.fileUuid == documentId }
        .map { event ->
            RenameIndexTarget(
                fileUuid = event.fileUuid,
                targetHash = event.contentHash,
                targetEpoch = event.targetEpoch,
            )
        }
        .toList()

    private fun synchronizeSharedPendingTargets() {
        if (pendingIndexCoordinator == null) return
        _uiState.update { current ->
            val pending = sharedPendingTargets(current.workspaceRootUuid, current.documentId)
            current.copy(
                indexPendingTargets = pending,
                indexQueueFailed = pending.isNotEmpty(),
            )
        }
    }

    class Factory(
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as NexaraApplication
            return DocEditorViewModel(
                fileOperationRepository = app.fileOperationRepository,
                workspaceRepository = app.workspaceRepository,
                pendingIndexCoordinator = app.pendingDocumentIndexCoordinator,
            ) as T
        }
    }

    private companion object {
        const val EDITOR_SESSION_ID = "editor"
        const val LARGE_FILE_SIZE_BYTES = 1_048_576L
    }

    private data class SaveIdentity(
        val workspaceRootUuid: String,
        val documentId: String,
        val documentEpoch: Long,
    )
}
