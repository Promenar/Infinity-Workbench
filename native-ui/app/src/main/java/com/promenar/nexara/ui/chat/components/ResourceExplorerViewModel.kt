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
import kotlinx.coroutines.launch

class ResourceExplorerViewModel(
    application: Application
) : ViewModel() {

    private val app = application as NexaraApplication
    val workspaceRepo: IWorkspaceRepository = app.workspaceRepository

    var workspaceRootUuid: String? = null
        private set

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _recycleBinCount = MutableStateFlow(0)
    val recycleBinCount: StateFlow<Int> = _recycleBinCount.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun loadWorkspaceRoot(uuid: String?) {
        workspaceRootUuid = uuid
        if (uuid != null) {
            viewModelScope.launch {
                workspaceRepo.observeRecycleBin(uuid).collect { files ->
                    _recycleBinCount.value = files.size
                }
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
