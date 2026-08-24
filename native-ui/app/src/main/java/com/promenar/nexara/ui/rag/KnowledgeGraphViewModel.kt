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
import com.promenar.nexara.data.rag.KgDocumentOption
import com.promenar.nexara.data.repository.KnowledgeGraphRepository
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.promenar.nexara.NexaraApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import com.promenar.nexara.utils.NexaraLogger
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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

    private var globalCache: GraphData? = null
    private val documentCache = mutableMapOf<String, GraphData>()
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var documentOptionsJob: Job? = null
    private var documentOptionsGeneration = 0L

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
        _viewMode.value = mode
        _loadError.value = null
        when (mode) {
            KgViewMode.GLOBAL, KgViewMode.CONCEPT -> {
                _documentSelectionRequired.value = false
                globalCache?.let { renderCached(it, mode) } ?: loadGraphInternal(null)
            }
            KgViewMode.DOCUMENT -> {
                val docId = _selectedDocumentId.value
                _documentSelectionRequired.value = docId == null
                if (docId == null) {
                    cancelPendingLoad()
                    _nodes.value = emptyList()
                    _edges.value = emptyList()
                } else {
                    documentCache[docId]?.let { renderCached(it, KgViewMode.DOCUMENT) }
                        ?: loadGraphByDoc(docId)
                }
            }
        }
    }

    fun loadGraph() {
        _viewMode.value = KgViewMode.GLOBAL
        _documentSelectionRequired.value = false
        globalCache?.let { renderCached(it, KgViewMode.GLOBAL) } ?: loadGraphInternal(null)
        NexaraLogger.log("[KG] loadGraph triggered, will query graphStore.getGraphData()")
    }

    fun loadGraphByDoc(docId: String) {
        require(docId.isNotBlank()) { "docId must not be blank" }
        _viewMode.value = KgViewMode.DOCUMENT
        _selectedDocumentId.value = docId
        _documentSelectionRequired.value = false
        _loadError.value = null
        documentCache[docId]?.let { renderCached(it, KgViewMode.DOCUMENT) }
            ?: loadGraphInternal(docId)
    }

    fun retryLoad() {
        when (_viewMode.value) {
            KgViewMode.DOCUMENT -> _selectedDocumentId.value?.let {
                documentCache.remove(it)
                loadGraphInternal(it)
            }
            KgViewMode.GLOBAL, KgViewMode.CONCEPT -> {
                globalCache = null
                loadGraphInternal(null)
            }
        }
    }

    private fun loadGraphInternal(docId: String?) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val data = if (docId == null) {
                    graphStore.getGraphData()
                } else {
                    graphStore.getGraphData(docIds = listOf(docId))
                }
                if (generation != loadGeneration) return@launch
                NexaraLogger.log("[KG] graphStore returned: ${data.nodes.size} nodes, ${data.edges.size} edges (docId=$docId)")
                if (data.nodes.isEmpty() && data.edges.isEmpty()) {
                    NexaraLogger.log("[KG] WARNING: No graph data available — KG extraction may not have been completed, or all extractions failed")
                }
                if (data.nodes.isNotEmpty() && data.edges.isEmpty()) {
                    NexaraLogger.log("[KG] INFO: ${data.nodes.size} orphan nodes found (no edges) — these will be displayed as isolated points")
                }
                if (docId == null) globalCache = data else documentCache[docId] = data
                _loadError.value = null
                render(data, if (docId == null) _viewMode.value else KgViewMode.DOCUMENT)
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
            } finally {
                if (generation == loadGeneration) _isLoading.value = false
            }
        }
    }

    private fun renderCached(data: GraphData, mode: KgViewMode) {
        cancelPendingLoad()
        render(data, mode)
    }

    private fun cancelPendingLoad() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        _isLoading.value = false
    }

    private fun render(data: GraphData, mode: KgViewMode) {
        val filteredData = when (mode) {
            KgViewMode.GLOBAL -> data
            KgViewMode.DOCUMENT -> data
            KgViewMode.CONCEPT -> {
                val conceptNodes = data.nodes.filter { it.type == "concept" }
                val conceptIds = conceptNodes.map { it.id }.toSet()
                val filteredEdges = data.edges.filter {
                    it.sourceId in conceptIds || it.targetId in conceptIds
                }
                val connectedIds = filteredEdges.flatMap { listOf(it.sourceId, it.targetId) }.toSet()
                GraphData(data.nodes.filter { it.id in connectedIds }, filteredEdges)
            }
        }

        val mappedNodes = filteredData.nodes.map { node ->
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
        val mappedEdges = filteredData.edges.map { edge ->
            GraphEdge(
                sourceId = edge.sourceId,
                targetId = edge.targetId,
                relation = edge.relation
            )
        }

        _nodes.value = mappedNodes
        _edges.value = mappedEdges
        NexaraLogger.log("[KG] renderFromCache success: mapped ${mappedNodes.size} nodes, ${mappedEdges.size} edges")
    }



    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as NexaraApplication
                    val repo = KnowledgeGraphRepository(
                        kgNodeDao = app.database.kgNodeDao(),
                        kgEdgeDao = app.database.kgEdgeDao(),
                        graphExtractor = app.graphExtractor
                    )
                    return KnowledgeGraphViewModel(repo, app.graphStore, application) as T
                }
            }
    }
}
