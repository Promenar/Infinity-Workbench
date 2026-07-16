package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.WriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
) : ViewModel() {
    private val _uiState = MutableStateFlow(DocEditorUiState())
    val uiState: StateFlow<DocEditorUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private val saveMutex = Mutex()
    private val activeSaveIdentity = AtomicReference<SaveIdentity?>(null)

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
        _uiState.value = DocEditorUiState(
            phase = DocEditorPhase.Loading,
            workspaceRootUuid = workspaceRootUuid,
            documentId = uuid,
            documentEpoch = generation,
        )
        loadJob = viewModelScope.launch {
            try {
                val fileEntry = workspaceRepository.getByUuid(workspaceRootUuid, uuid)
                    ?: throw NoSuchElementException(uuid)
                if (fileEntry.sizeBytes > LARGE_FILE_SIZE_BYTES) {
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
                        )
                    }
                    return@launch
                }
                val result = fileOperationRepository.readFileRange(workspaceRootUuid, uuid)
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
        workspaceRepository.rename(
            workspaceRootUuid = snapshot.workspaceRootUuid,
            uuid = snapshot.documentId,
            newName = snapshot.title,
        )
        updateSameDocument(snapshot) { current ->
            current.copy(
                persistedTitle = snapshot.title,
                titleDirty = current.title != snapshot.title,
            )
        }
        true
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
                    current.copy(
                        phase = DocEditorPhase.Ready,
                        persistedContent = snapshot.content,
                        currentHash = result.newHash,
                        contentDirty = current.content != snapshot.content,
                        failureCode = null,
                        failureDetail = null,
                        conflictCurrentHash = null,
                    )
                }
            }

            is WriteResult.Conflict -> updateSameDocument(snapshot) { current ->
                current.copy(
                    phase = DocEditorPhase.SaveConflict,
                    failureCode = DocEditorFailureCode.ContentConflict,
                    failureDetail = null,
                    conflictCurrentHash = result.currentHash,
                )
            }

            WriteResult.NotFound -> updateSameDocument(snapshot) { current ->
                current.copy(
                    phase = DocEditorPhase.NotFound,
                    failureCode = DocEditorFailureCode.SaveNotFound,
                    failureDetail = null,
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

    fun dismissWarning() {
        _uiState.update { it.copy(warningDismissed = true) }
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
