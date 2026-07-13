package com.promenar.nexara.ui.chat.components

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.ShareRequest
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.share.core.AndroidShareIndexScheduler
import com.promenar.nexara.share.core.AndroidSafContentSource
import com.promenar.nexara.share.core.AndroidSafImportRequestFactory
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.share.core.ShareRejectReason
import com.promenar.nexara.share.core.SharedFileImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ResourceExplorerViewModel(
    application: Application,
    injectedWorkspaceRepository: IWorkspaceRepository? = null,
    injectedImporter: SharedFileImporter? = null,
) : ViewModel() {

    val workspaceRepo: IWorkspaceRepository = injectedWorkspaceRepository
        ?: (application as NexaraApplication).workspaceRepository

    private val importer by lazy {
        injectedImporter ?: SharedFileImporter(
            source = AndroidSafContentSource(application.contentResolver),
            workspace = workspaceRepo,
            indexScheduler = AndroidShareIndexScheduler(application as NexaraApplication),
        )
    }
    private val contentResolver = application.contentResolver
    private val requestFactory = AndroidSafImportRequestFactory(contentResolver)

    private val _workspaceRootUuid = MutableStateFlow<String?>(null)
    val workspaceRootUuid: StateFlow<String?> = _workspaceRootUuid.asStateFlow()

    private val _rootFiles = MutableStateFlow<List<FileEntry>>(emptyList())
    val rootFiles: StateFlow<List<FileEntry>> = _rootFiles.asStateFlow()

    private val _recycledFiles = MutableStateFlow<List<FileEntry>>(emptyList())
    val recycledFiles: StateFlow<List<FileEntry>> = _recycledFiles.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _recycleBinCount = MutableStateFlow(0)
    val recycleBinCount: StateFlow<Int> = _recycleBinCount.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _importItems = MutableStateFlow<List<ShareImportItem>>(emptyList())
    val importItems: StateFlow<List<ShareImportItem>> = _importItems.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private var currentSessionId: String? = null
    private var loadGeneration = 0L
    private var sessionJob: Job? = null
    private var importJob: Job? = null
    private val importRequests = linkedMapOf<Uri, ShareRequest>()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun loadSession(sessionId: String) {
        currentSessionId = sessionId
        val generation = ++loadGeneration
        sessionJob?.cancel()
        importJob?.cancel()
        clearSessionState()
        _isLoading.value = true
        sessionJob = viewModelScope.launch {
            try {
                val rootUuid = workspaceRepo.ensureSessionRoot(sessionId).uuid
                if (generation != loadGeneration) return@launch
                _workspaceRootUuid.value = rootUuid
                _isLoading.value = false
                coroutineScope {
                    launch {
                        workspaceRepo.observeChildren(rootUuid, rootUuid).collect { files ->
                            if (generation == loadGeneration) _rootFiles.value = files
                        }
                    }
                    launch {
                        workspaceRepo.observeRecycleBin(rootUuid).collect { files ->
                            if (generation == loadGeneration) {
                                _recycledFiles.value = files
                                _recycleBinCount.value = files.size
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == loadGeneration) {
                    clearSessionState()
                    _loadError.value = "无法加载会话工作区：${failure.message ?: "未知错误"}"
                    _isLoading.value = false
                }
            }
        }
    }

    fun retryLoadSession() {
        currentSessionId?.let(::loadSession)
    }

    fun importDocuments(uris: List<Uri>) {
        val rootUuid = _workspaceRootUuid.value
        if (rootUuid == null) {
            _loadError.value = "会话工作区尚未就绪，请重试"
            return
        }
        importJob?.cancel()
        val generation = loadGeneration
        importJob = viewModelScope.launch {
            _isImporting.value = true
            try {
                val requests = uris.distinct().map { uri -> uri to createRequest(uri, rootUuid) }
                importRequests.clear()
                importRequests.putAll(requests)
                val inspectedItems = requests.map { (_, request) ->
                    try {
                        importer.inspect(request).singleOrNull()
                            ?: ShareImportItem(
                                uri = request.uris.single(),
                                displayName = request.uris.single().lastPathSegment ?: "selected-file",
                                mimeType = request.mimeType,
                                sizeBytes = null,
                            )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ShareImportItem(
                            uri = request.uris.single(),
                            displayName = request.uris.single().lastPathSegment ?: "selected-file",
                            mimeType = request.mimeType,
                            sizeBytes = null,
                        ).unexpectedFailure(ShareRejectReason.ReadFailed)
                    }
                }
                ensureCurrentImport(generation, rootUuid)
                _importItems.value = inspectedItems
                requests.forEachIndexed { index, (_, request) ->
                    ensureCurrentImport(generation, rootUuid)
                    if (_importItems.value.getOrNull(index)?.status != ShareImportStatus.Pending) {
                        return@forEachIndexed
                    }
                    updateImportItem(index) { it.copy(status = ShareImportStatus.Importing, reason = null) }
                    try {
                        val result = importer.import(request, rootUuid)
                        updateImportItem(index) { result.items.singleOrNull() ?: it }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ensureCurrentImport(generation, rootUuid)
                        updateImportItem(index) { it.unexpectedFailure(ShareRejectReason.WriteFailed) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (generation == loadGeneration) _isImporting.value = false
            }
        }
    }

    fun retryImport(uri: Uri) {
        if (_isImporting.value) return
        val rootUuid = _workspaceRootUuid.value ?: return
        val request = importRequests[uri] ?: return
        val index = _importItems.value.indexOfFirst { it.uri == uri }
        if (index < 0) return
        importJob?.cancel()
        val generation = loadGeneration
        importJob = viewModelScope.launch {
            _isImporting.value = true
            try {
                updateImportItem(index) { it.copy(status = ShareImportStatus.Importing, reason = null) }
                try {
                    val result = importer.import(request, rootUuid, setOf(uri))
                    ensureCurrentImport(generation, rootUuid)
                    updateImportItem(index) { result.items.singleOrNull() ?: it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ensureCurrentImport(generation, rootUuid)
                    updateImportItem(index) { it.unexpectedFailure(ShareRejectReason.WriteFailed) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (generation == loadGeneration) _isImporting.value = false
            }
        }
    }

    fun clearImportResults() {
        if (!_isImporting.value) {
            importRequests.clear()
            _importItems.value = emptyList()
        }
    }

    private fun createRequest(uri: Uri, rootUuid: String): ShareRequest {
        return requestFactory.create(uri, rootUuid)
    }

    private fun updateImportItem(index: Int, transform: (ShareImportItem) -> ShareImportItem) {
        _importItems.value = _importItems.value.toMutableList().also { items ->
            if (index in items.indices) items[index] = transform(items[index])
        }
    }

    private fun ShareImportItem.unexpectedFailure(reason: ShareRejectReason): ShareImportItem = copy(
        status = ShareImportStatus.Rejected,
        reason = reason,
        created = null,
        indexStatus = null,
        fileUuid = null,
        indexTaskId = null,
    )

    private fun ensureCurrentImport(generation: Long, rootUuid: String) {
        if (generation != loadGeneration || _workspaceRootUuid.value != rootUuid) {
            throw CancellationException("会话工作区已切换")
        }
    }

    private fun clearSessionState() {
        _workspaceRootUuid.value = null
        _rootFiles.value = emptyList()
        _recycledFiles.value = emptyList()
        _recycleBinCount.value = 0
        _loadError.value = null
        _isImporting.value = false
        _importItems.value = emptyList()
        importRequests.clear()
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
