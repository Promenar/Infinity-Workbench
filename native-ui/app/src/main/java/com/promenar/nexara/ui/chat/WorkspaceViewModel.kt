package com.promenar.nexara.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class WorkspaceFile(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<WorkspaceFile> = emptyList()
)

class WorkspaceViewModel(private val application: Application) : ViewModel() {
    private val _files = MutableStateFlow<List<WorkspaceFile>>(emptyList())
    val files: StateFlow<List<WorkspaceFile>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _selectedFileContent = MutableStateFlow<String?>(null)
    val selectedFileContent: StateFlow<String?> = _selectedFileContent.asStateFlow()

    fun loadWorkspaceFiles(workspacePath: String?) {
        if (workspacePath == null) {
            _files.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            val fileTree = withContext(Dispatchers.IO) {
                val rootDir = File(workspacePath)
                if (rootDir.exists() && rootDir.isDirectory) {
                    scanDirectory(rootDir)
                } else {
                    emptyList()
                }
            }
            _files.value = fileTree
            _isLoading.value = false
        }
    }
    
    fun loadFileContent(filePath: String) {
        viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    File(filePath).readText()
                }
                _selectedFileContent.value = content
            } catch (e: Exception) {
                _selectedFileContent.value = "Error loading file: ${e.message}"
            }
        }
    }
    
    fun clearFileContent() {
        _selectedFileContent.value = null
    }

    private fun scanDirectory(directory: File): List<WorkspaceFile> {
        return directory.listFiles()?.map { file ->
            WorkspaceFile(
                id = file.absolutePath,
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                children = if (file.isDirectory) scanDirectory(file) else emptyList()
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return WorkspaceViewModel(application) as T
                }
            }
    }
}
