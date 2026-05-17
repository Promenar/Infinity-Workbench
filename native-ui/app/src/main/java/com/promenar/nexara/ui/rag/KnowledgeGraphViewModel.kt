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
import com.promenar.nexara.data.repository.KnowledgeGraphRepository
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.promenar.nexara.NexaraApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.promenar.nexara.utils.NexaraLogger
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

enum class KgViewMode { GLOBAL, DOCUMENT, CONCEPT }

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

    private val _graphHtml = MutableStateFlow<String?>(null)
    val graphHtml: StateFlow<String?> = _graphHtml.asStateFlow()

    private val _viewMode = MutableStateFlow(KgViewMode.GLOBAL)
    val viewMode: StateFlow<KgViewMode> = _viewMode.asStateFlow()

    private var cachedGraphData: GraphData? = null

    init {
        loadGraph()
    }

    fun setViewMode(mode: KgViewMode) {
        _viewMode.value = mode
        renderFromCache()
    }

    fun loadGraph() {
        loadGraphInternal(null)
        NexaraLogger.log("[KG] loadGraph triggered, will query graphStore.getGraphData()")
    }

    fun loadGraphByDoc(docId: String) {
        _viewMode.value = KgViewMode.DOCUMENT
        loadGraphInternal(listOf(docId))
    }

    private fun loadGraphInternal(docIds: List<String>?) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val data = graphStore.getGraphData(docIds = docIds)
                NexaraLogger.log("[KG] graphStore returned: ${data.nodes.size} nodes, ${data.edges.size} edges")
                cachedGraphData = data
                val mappedNodes = data.nodes.map { node ->
                    GraphNode(
                        id = node.id,
                        label = node.name,
                        type = node.type,
                        x = Random.nextFloat() * 1200f - 600f,
                        y = Random.nextFloat() * 800f - 400f,
                        icon = when (node.type) {
                            "concept" -> Icons.Rounded.Psychology
                            "document" -> Icons.Rounded.Description
                            "person" -> Icons.Rounded.Person
                            else -> Icons.Rounded.Hub
                        }
                    )
                }
                val mappedEdges = data.edges.map { edge ->
                    GraphEdge(
                        sourceId = edge.sourceId,
                        targetId = edge.targetId,
                        relation = edge.relation
                    )
                }
                _nodes.value = mappedNodes
                _edges.value = mappedEdges
                renderFromCache()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun renderFromCache() {
        val data = cachedGraphData ?: return
        val filteredData = when (_viewMode.value) {
            KgViewMode.GLOBAL -> data
            KgViewMode.DOCUMENT -> {
                val filteredEdges = data.edges.filter { it.docId != null }
                val nodeIds = filteredEdges.flatMap { listOf(it.sourceId, it.targetId) }.toSet()
                val filteredNodes = data.nodes.filter { it.id in nodeIds }
                GraphData(filteredNodes, filteredEdges)
            }
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
        val json = buildGraphJson(filteredData)
        try {
            val template = application.assets.open("kg_template.html").bufferedReader().use { it.readText() }
            _graphHtml.value = template.replace("__GRAPH_DATA__", json)
            NexaraLogger.log("[KG] render success: html=${_graphHtml.value?.length ?: 0} bytes")
        } catch (e: Exception) {
            NexaraLogger.logError("[KG] renderFromCache failed", e)
            e.printStackTrace()
        }
    }

    private fun buildGraphJson(data: GraphData): String {
        val nodesArray = buildJsonArray {
            data.nodes.forEach { node ->
                add(buildJsonObject {
                    put("id", node.id)
                    put("name", node.name)
                    put("type", node.type)
                })
            }
        }
        val edgesArray = buildJsonArray {
            data.edges.forEach { edge ->
                add(buildJsonObject {
                    put("sourceId", edge.sourceId)
                    put("targetId", edge.targetId)
                    put("relation", edge.relation)
                })
            }
        }
        return buildJsonObject {
            put("nodes", nodesArray)
            put("edges", edgesArray)
        }.toString()
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
