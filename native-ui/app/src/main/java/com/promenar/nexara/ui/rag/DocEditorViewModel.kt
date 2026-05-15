package com.promenar.nexara.ui.rag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.WriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DocEditorViewModel(
    private val fileOperationRepository: IFileOperationRepository
) : ViewModel() {

    private val _document = MutableStateFlow<Document?>(null)
    val document: StateFlow<Document?> = _document.asStateFlow()

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _isLargeFile = MutableStateFlow(false)
    val isLargeFile: StateFlow<Boolean> = _isLargeFile.asStateFlow()

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    private val _warningDismissed = MutableStateFlow(false)
    val warningDismissed: StateFlow<Boolean> = _warningDismissed.asStateFlow()

    private val _fileName = MutableStateFlow("")
    val fileName: StateFlow<String> = _fileName.asStateFlow()

    private var currentHash = ""
    private var currentUuid = ""
    private var originalContent = ""

    fun loadFile(uuid: String) {
        viewModelScope.launch {
            try {
                val result = fileOperationRepository.readFileRange(uuid)
                currentUuid = uuid
                currentHash = result.hash
                _fileName.value = result.name
                _content.value = result.content
                originalContent = result.content
                _isLargeFile.value = result.totalLines > 10000

                _document.value = Document(
                    id = uuid,
                    folderId = "",
                    title = result.name,
                    content = result.content,
                    hash = result.hash,
                    createdAt = result.lastModified,
                    updatedAt = result.lastModified
                )
            } catch (_: Exception) {}
        }
    }

    fun loadDocument(docId: String) = loadFile(docId)

    fun saveDocument() {
        viewModelScope.launch {
            try {
                val result = fileOperationRepository.writeFileAtomic(
                    uuid = currentUuid,
                    newContent = _content.value,
                    sessionId = "editor",
                    expectedHash = currentHash
                )
                if (result is WriteResult.Success) {
                    currentHash = result.newHash
                    _isDirty.value = false
                    originalContent = _content.value
                }
            } catch (_: Exception) {}
        }
    }

    class Factory(
        private val fileOperationRepository: IFileOperationRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DocEditorViewModel(fileOperationRepository) as T
    }

    fun onContentChanged(newContent: String) {
        _content.value = newContent
        _isDirty.value = newContent != originalContent
    }

    fun toggleEditMode() {
        _isEditing.value = !_isEditing.value
    }

    fun dismissWarning() {
        _warningDismissed.value = true
    }

    fun updateTitle(newTitle: String) {
        val doc = _document.value ?: return
        _document.value = doc.copy(title = newTitle)
        _isDirty.value = true
    }
}
