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
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

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

class KnowledgeGraphViewModel(application: Application) : ViewModel() {
    private val app = application as NexaraApplication
    private val database = app.database
    private val kgNodeDao = database.kgNodeDao()
    private val kgEdgeDao = database.kgEdgeDao()

    private val _nodes = MutableStateFlow<List<GraphNode>>(emptyList())
    val nodes: StateFlow<List<GraphNode>> = _nodes.asStateFlow()

    private val _edges = MutableStateFlow<List<GraphEdge>>(emptyList())
    val edges: StateFlow<List<GraphEdge>> = _edges.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadGraph()
    }

    fun loadGraph() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val nodeEntities = kgNodeDao.getAll()
                val edgeEntities = kgEdgeDao.getAll()

                val rng = Random(42)
                val mappedNodes = nodeEntities.map { entity ->
                    GraphNode(
                        id = entity.id,
                        label = entity.name,
                        type = entity.type,
                        x = rng.nextFloat() * 1200f - 600f,
                        y = rng.nextFloat() * 800f - 400f,
                        icon = when (entity.type) {
                            "concept" -> Icons.Rounded.Psychology
                            "document" -> Icons.Rounded.Description
                            "person" -> Icons.Rounded.Person
                            else -> Icons.Rounded.Hub
                        }
                    )
                }

                val mappedEdges = edgeEntities.map { entity ->
                    GraphEdge(
                        sourceId = entity.sourceId,
                        targetId = entity.targetId,
                        relation = entity.relation
                    )
                }

                _nodes.value = mappedNodes
                _edges.value = mappedEdges
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun injectMockData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val rng = Random(System.currentTimeMillis())
                val mockNodes = mutableListOf<KgNodeEntity>()
                val mockEdges = mutableListOf<KgEdgeEntity>()

                // Create some nodes
                for (i in 0..15) {
                    val id = UUID.randomUUID().toString()
                    val type = when (rng.nextInt(3)) {
                        0 -> "concept"
                        1 -> "document"
                        else -> "person"
                    }
                    mockNodes.add(
                        KgNodeEntity(
                            id = id,
                            name = "${type.replaceFirstChar { it.uppercase() }} ${('A' + rng.nextInt(26))}${rng.nextInt(100)}",
                            type = type,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }

                // Random Edges
                for (i in 1..25) {
                    val src = mockNodes[rng.nextInt(mockNodes.size)]
                    val tgt = mockNodes[rng.nextInt(mockNodes.size)]
                    if (src.id != tgt.id) {
                        mockEdges.add(
                            KgEdgeEntity(
                                id = UUID.randomUUID().toString(),
                                sourceId = src.id,
                                targetId = tgt.id,
                                relation = listOf("contains", "references", "depends_on", "authored").random(rng),
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                }

                // Save to DB
                mockNodes.forEach { kgNodeDao.insert(it) }
                mockEdges.forEach { kgEdgeDao.insert(it) }

                loadGraph()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearGraph() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                kgEdgeDao.clearAll()
                kgNodeDao.clearAll()
                loadGraph()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return KnowledgeGraphViewModel(application) as T
                }
            }
    }
}
