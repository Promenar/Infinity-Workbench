package com.promenar.nexara.ui.rag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.domain.repository.IDocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DocEditorViewModel(
    private val documentRepository: IDocumentRepository
) : ViewModel() {

    private val _document = MutableStateFlow<com.promenar.nexara.domain.model.Document?>(null)
    val document: StateFlow<com.promenar.nexara.domain.model.Document?> = _document.asStateFlow()

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

    private var originalContent = ""

    fun loadDocument(docId: String) {
        viewModelScope.launch {
            try {
                val doc = documentRepository.getById(docId)
                _document.value = doc
                if (doc != null) {
                    val realContent = doc.content
                    val sizeMb = (realContent.length.toDouble()) / (1024.0 * 1024.0)
                    _isLargeFile.value = sizeMb > 10.0
                    _content.value = realContent
                    originalContent = realContent
                }
            } catch (_: Exception) {}
        }
    }

    fun saveDocument() {
        viewModelScope.launch {
            try {
                val doc = _document.value ?: return@launch
                documentRepository.update(doc.id, _content.value)
                _isDirty.value = false
                originalContent = _content.value
            } catch (_: Exception) {}
        }
    }

    class Factory(
        private val documentRepository: IDocumentRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DocEditorViewModel(documentRepository) as T
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
        viewModelScope.launch {
            try {
                documentRepository.updateTitle(doc.id, newTitle)
            } catch (_: Exception) {}
        }
    }
}
