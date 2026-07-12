package com.promenar.nexara.ui.chat.components

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ResourceExplorerViewModel(
    application: Application,
    injectedWorkspaceRepository: IWorkspaceRepository? = null,
) : ViewModel() {

    val workspaceRepo: IWorkspaceRepository = injectedWorkspaceRepository
        ?: (application as NexaraApplication).workspaceRepository

    private val _workspaceRootUuid = MutableStateFlow<String?>(null)
    val workspaceRootUuid: StateFlow<String?> = _workspaceRootUuid.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _recycleBinCount = MutableStateFlow(0)
    val recycleBinCount: StateFlow<Int> = _recycleBinCount.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private var recycleJob: Job? = null

    fun loadSession(sessionId: String) {
        recycleJob?.cancel()
        recycleJob = viewModelScope.launch {
            val uuid = workspaceRepo.ensureSessionRoot(sessionId).uuid
            _workspaceRootUuid.value = uuid
            try {
                workspaceRepo.observeRecycleBin(uuid).collect { files ->
                    _recycleBinCount.value = files.size
                }
            } finally {
                if (_workspaceRootUuid.value == uuid) _recycleBinCount.value = 0
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ResourceExplorerViewModel(application) as T
                }
            }
    }
}
