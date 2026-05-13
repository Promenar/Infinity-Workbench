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
                    val sizeMb = (doc.content.length.toDouble()) / (1024.0 * 1024.0)
                    _isLargeFile.value = sizeMb > 10.0
                    val mockContent = generateMockContent(doc.title.ifBlank { "Untitled" })
                    _content.value = mockContent
                    originalContent = mockContent
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
        _document.value = _document.value?.copy(title = newTitle)
        _isDirty.value = true
    }

    private fun generateMockContent(title: String): String {
        return """# $title

## Overview
This document provides a comprehensive overview of the system architecture and design decisions.

## Components
- **Ingestion Layer**: Handles high-throughput data streams with automatic scaling.
- **Processing Matrix**: GPU-accelerated node clusters for real-time computation.
- **Storage Substrate**: Immutable ledger for state preservation and audit trails.

## Configuration
```json
{
  "version": "3.0.0",
  "clusters": 4,
  "replication_factor": 3,
  "max_connections": 10000
}
```

## API Endpoints
| Method | Path | Description |
|--------|------|-------------|
| GET | /api/v1/status | System health check |
| POST | /api/v1/query | Submit a processing query |
| GET | /api/v1/results/:id | Retrieve results |

## Deployment
The system is deployed across multiple regions with automatic failover. Each region maintains a complete replica of the state ledger with eventual consistency guarantees.

## Security
All communications are encrypted using TLS 1.3. Authentication is handled through OAuth 2.0 with JWT tokens. Role-based access control (RBAC) governs resource permissions.
""".trimIndent()
    }
}
