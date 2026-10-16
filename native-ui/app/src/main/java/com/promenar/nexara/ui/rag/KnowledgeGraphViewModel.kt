package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.data.rag.GraphData
import com.promenar.nexara.data.rag.GraphStore
import com.promenar.nexara.data.rag.GraphSubgraphRequest
import com.promenar.nexara.data.rag.GraphEntitySearchRequest
import com.promenar.nexara.data.rag.DEFAULT_GRAPH_EDGE_LIMIT
import com.promenar.nexara.data.rag.GRAPH_EXPANSION_BATCH_SIZE
import com.promenar.nexara.data.rag.MAX_GRAPH_NODE_LIMIT
import com.promenar.nexara.data.rag.MAX_GRAPH_EDGE_LIMIT
import com.promenar.nexara.data.rag.KgDocumentOption
import com.promenar.nexara.data.rag.KgNode
import com.promenar.nexara.data.repository.KnowledgeGraphRepository
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.promenar.nexara.NexaraApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

import com.promenar.nexara.utils.NexaraLogger

enum class KgViewMode { GLOBAL, DOCUMENT, CONCEPT }

enum class KgLoadErrorCode { LoadFailed }

enum class KgDocumentOptionsErrorCode { LoadFailed }

data class KgLoadError(
    val code: KgLoadErrorCode,
    val canRetry: Boolean,
    val technical: String?,
)

data class KgDocumentOptionsError(
    val code: KgDocumentOptionsErrorCode,
    val canRetry: Boolean,
    val technical: String?,
)

data class KgGraphState(
    val scopeKey: String? = null,
    val isLoading: Boolean = false,
    val isStale: Boolean = false,
)

data class GraphNode(
    val id: String,
    val label: String,
    val type: String,
    val x: Float,
    val y: Float,
    val icon: ImageVector = Icons.Rounded.Hub
)

data class GraphEdge(
    val sourceId: String,
    val targetId: String,
    val relation: String = ""
)

class KnowledgeGraphViewModel(
    private val repo: IKnowledgeGraphRepository,
    private val graphStore: GraphStore,
    private val application: Application
) : ViewModel() {

    private val _nodes = MutableStateFlow<List<GraphNode>>(emptyList())
    val nodes: StateFlow<List<GraphNode>> = _nodes.asStateFlow()

    private val _edges = MutableStateFlow<List<GraphEdge>>(emptyList())
    val edges: StateFlow<List<GraphEdge>> = _edges.asStateFlow()

    private val _totalNodeCount = MutableStateFlow(0)
    val totalNodeCount: StateFlow<Int> = _totalNodeCount.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _searchResults = MutableStateFlow<List<KgNode>>(emptyList())
    val searchResults: StateFlow<List<KgNode>> = _searchResults.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    private val _searchFailed = MutableStateFlow(false)
    val searchFailed: StateFlow<Boolean> = _searchFailed.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _graphState = MutableStateFlow(KgGraphState())
    val graphState: StateFlow<KgGraphState> = _graphState.asStateFlow()

    private val _viewMode = MutableStateFlow(KgViewMode.GLOBAL)
    val viewMode: StateFlow<KgViewMode> = _viewMode.asStateFlow()

    private val _selectedDocumentId = MutableStateFlow<String?>(null)
    val selectedDocumentId: StateFlow<String?> = _selectedDocumentId.asStateFlow()

    private val _documentSelectionRequired = MutableStateFlow(false)
    val documentSelectionRequired: StateFlow<Boolean> = _documentSelectionRequired.asStateFlow()

    private val _loadError = MutableStateFlow<KgLoadError?>(null)
    val loadError: StateFlow<KgLoadError?> = _loadError.asStateFlow()

    private val _documentOptions = MutableStateFlow<List<KgDocumentOption>>(emptyList())
    val documentOptions: StateFlow<List<KgDocumentOption>> = _documentOptions.asStateFlow()

    private val _documentOptionsError = MutableStateFlow<KgDocumentOptionsError?>(null)
    val documentOptionsError: StateFlow<KgDocumentOptionsError?> =
        _documentOptionsError.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var lastSuccessfulScopeKey: String? = null
    private var documentOptionsJob: Job? = null
    private var documentOptionsGeneration = 0L
    private var searchJob: Job? = null
    private var searchGeneration = 0L

    init {
        loadDocumentOptions()
        loadGraph()
    }

    fun loadDocumentOptions() {
        val generation = ++documentOptionsGeneration
        documentOptionsJob?.cancel()
        documentOptionsJob = viewModelScope.launch {
            try {
                val options = graphStore.getDocumentOptions()
                if (generation != documentOptionsGeneration) return@launch
                _documentOptions.value = options
                _documentOptionsError.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation != documentOptionsGeneration) return@launch
                NexaraLogger.logError("[KG] loadDocumentOptions failed", failure)
                _documentOptionsError.value = KgDocumentOptionsError(
                    code = KgDocumentOptionsErrorCode.LoadFailed,
                    canRetry = true,
                    technical = failure::class.simpleName?.take(80),
                )
            }
        }
    }

    fun retryDocumentOptions() = loadDocumentOptions()

    fun setViewMode(mode: KgViewMode) {
        clearSearch()
        _viewMode.value = mode
        _loadError.value = null
        when (mode) {
            KgViewMode.GLOBAL, KgViewMode.CONCEPT -> {
                _documentSelectionRequired.value = false
                loadGraphInternal(docId = null, mode = mode, preserveSameScope = false)
            }
            KgViewMode.DOCUMENT -> {
                val docId = _selectedDocumentId.value
                _documentSelectionRequired.value = docId == null
                if (docId == null) {
                    cancelPendingLoad()
                    _nodes.value = emptyList()
                    _edges.value = emptyList()
                    _totalNodeCount.value = 0
                    lastSuccessfulScopeKey = null
                    _graphState.value = KgGraphState()
                } else {
                    loadGraphInternal(
                        docId = docId,
                        mode = KgViewMode.DOCUMENT,
                        preserveSameScope = false,
                    )
                }
            }
        }
    }

    fun loadGraph() {
        clearSearch()
        _viewMode.value = KgViewMode.GLOBAL
        _documentSelectionRequired.value = false
        loadGraphInternal(
            docId = null,
            mode = KgViewMode.GLOBAL,
            preserveSameScope = false,
        )
    }

    fun loadGraphByDoc(docId: String) {
        require(docId.isNotBlank()) { "文档标识不能为空" }
        clearSearch()
        _viewMode.value = KgViewMode.DOCUMENT
        _selectedDocumentId.value = docId
        _documentSelectionRequired.value = false
        _loadError.value = null
        loadGraphInternal(
            docId = docId,
            mode = KgViewMode.DOCUMENT,
            preserveSameScope = false,
        )
    }

    fun retryLoad() {
        reloadCurrentScope(preserveSameScope = _graphState.value.isStale)
    }

    fun refreshCurrentScope() {
        reloadCurrentScope(preserveSameScope = true)
    }

    fun searchEntities(query: String) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        _searchQuery.value = query.take(512)
        _searchFailed.value = false
        _searchResults.value = emptyList()
        if (query.isBlank() || _documentSelectionRequired.value) {
            _isSearching.value = false
            return
        }
        val expectedScope = _graphState.value.scopeKey
        val request = GraphEntitySearchRequest(
            query = _searchQuery.value,
            docId = if (_viewMode.value == KgViewMode.DOCUMENT) _selectedDocumentId.value else null,
            conceptOnly = _viewMode.value == KgViewMode.CONCEPT,
        )
        _isSearching.value = true
        searchJob = viewModelScope.launch {
            try {
                delay(200)
                val found = graphStore.searchGraphEntities(request)
                if (generation == searchGeneration && expectedScope == _graphState.value.scopeKey) _searchResults.value = found
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == searchGeneration) _searchFailed.value = true
                NexaraLogger.logError("[KG] 实体搜索失败", failure)
            } finally {
                if (generation == searchGeneration) _isSearching.value = false
            }
        }
    }

    fun expandNode(nodeId: String) {
        if (_isLoading.value || _documentSelectionRequired.value || _nodes.value.size >= MAX_GRAPH_NODE_LIMIT) return
        val mode = _viewMode.value
        val docId = if (mode == KgViewMode.DOCUMENT) _selectedDocumentId.value else null
        loadGraphInternal(
            docId = docId,
            mode = mode,
            preserveSameScope = true,
            request = GraphSubgraphRequest(
                docId = docId,
                conceptOnly = mode == KgViewMode.CONCEPT,
                nodeLimit = (_nodes.value.size + GRAPH_EXPANSION_BATCH_SIZE + 1).coerceAtMost(MAX_GRAPH_NODE_LIMIT),
                edgeLimit = maxOf(DEFAULT_GRAPH_EDGE_LIMIT, (_nodes.value.size + GRAPH_EXPANSION_BATCH_SIZE) * 4).coerceAtMost(MAX_GRAPH_EDGE_LIMIT),
                retainedNodeIds = _nodes.value.map { it.id },
                expandNodeId = nodeId,
            ),
        )
    }

    private fun clearSearch() {
        searchGeneration++
        searchJob?.cancel()
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _isSearching.value = false
        _searchFailed.value = false
    }

    private fun reloadCurrentScope(preserveSameScope: Boolean) {
        val mode = _viewMode.value
        val docId = if (mode == KgViewMode.DOCUMENT) _selectedDocumentId.value else null
        if (mode == KgViewMode.DOCUMENT && docId == null) {
            _documentSelectionRequired.value = true
            return
        }
        loadGraphInternal(
            docId = docId,
            mode = mode,
            preserveSameScope = preserveSameScope,
        )
    }

    private fun loadGraphInternal(
        docId: String?,
        mode: KgViewMode,
        preserveSameScope: Boolean,
        request: GraphSubgraphRequest? = null,
    ) {
        val scopeKey = scopeKey(mode, docId)
        val preserveLastGood = preserveSameScope &&
            _graphState.value.scopeKey == scopeKey &&
            lastSuccessfulScopeKey == scopeKey
        val generation = ++loadGeneration
        loadJob?.cancel()
        if (!preserveLastGood) {
            _nodes.value = emptyList()
            _edges.value = emptyList()
            _totalNodeCount.value = 0
            lastSuccessfulScopeKey = null
        }
        _loadError.value = null
        _isLoading.value = true
        _graphState.value = KgGraphState(
            scopeKey = scopeKey,
            isLoading = true,
            isStale = false,
        )
        loadJob = viewModelScope.launch {
            try {
                val result = graphStore.getGraphSubgraph(request ?: GraphSubgraphRequest(docId = docId, conceptOnly = mode == KgViewMode.CONCEPT))
                val data = result.data
                if (generation != loadGeneration) return@launch
                NexaraLogger.log("[KG] graphStore returned: ${data.nodes.size} nodes, ${data.edges.size} edges (docId=$docId)")
                if (data.nodes.isEmpty() && data.edges.isEmpty()) {
                    NexaraLogger.log("[KG] WARNING: No graph data available — KG extraction may not have been completed, or all extractions failed")
                }
                if (data.nodes.isNotEmpty() && data.edges.isEmpty()) {
                    NexaraLogger.log("[KG] INFO: ${data.nodes.size} orphan nodes found (no edges) — these will be displayed as isolated points")
                }
                _loadError.value = null
                render(data)
                _totalNodeCount.value = result.totalNodeCount
                lastSuccessfulScopeKey = scopeKey
                _graphState.value = KgGraphState(scopeKey = scopeKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                NexaraLogger.logError("[KG] loadGraphInternal failed", e)
                _loadError.value = KgLoadError(
                    code = KgLoadErrorCode.LoadFailed,
                    canRetry = true,
                    technical = e::class.simpleName?.take(80),
                )
                _graphState.value = KgGraphState(
                    scopeKey = scopeKey,
                    isLoading = false,
                    isStale = preserveLastGood,
                )
            } finally {
                if (generation == loadGeneration) {
                    _isLoading.value = false
                    if (_graphState.value.isLoading) {
                        _graphState.value = _graphState.value.copy(isLoading = false)
                    }
                }
            }
        }
    }

    private fun scopeKey(mode: KgViewMode, docId: String?): String = when (mode) {
        KgViewMode.GLOBAL -> "global"
        KgViewMode.DOCUMENT -> "document:${requireNotNull(docId)}"
        KgViewMode.CONCEPT -> "concept:mode"
    }

    private fun cancelPendingLoad() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        _isLoading.value = false
        _graphState.value = _graphState.value.copy(isLoading = false)
    }

    private fun render(data: GraphData) {
        // GraphStore 已按存活证据筛选；同名不同类型必须保留独立实体 ID。
        val mappedNodes = data.nodes.distinctBy { it.id }.map { node ->
            GraphNode(
                id = node.id,
                label = node.name,
                type = node.type,
                x = 0f,
                y = 0f,
                icon = when (node.type) {
                    "concept" -> Icons.Rounded.Psychology
                    "document" -> Icons.Rounded.Description
                    "person" -> Icons.Rounded.Person
                    else -> Icons.Rounded.Hub
                }
            )
        }
        val ids = mappedNodes.map { it.id }.toSet()
        val mappedEdges = data.edges.filter { it.sourceId in ids && it.targetId in ids }.map { edge ->
            GraphEdge(
                sourceId = edge.sourceId,
                targetId = edge.targetId,
                relation = edge.relation
            )
        }

        _nodes.value = mappedNodes
        _edges.value = mappedEdges
        NexaraLogger.log("[KG] render success: mapped ${mappedNodes.size} nodes, ${mappedEdges.size} edges")
    }



    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as NexaraApplication
                    val repo = KnowledgeGraphRepository(
                        kgNodeDao = app.database.kgNodeDao(),
                        kgEdgeDao = app.database.kgEdgeDao()
                    )
                    return KnowledgeGraphViewModel(repo, app.graphStore, application) as T
                }
            }
    }
}
