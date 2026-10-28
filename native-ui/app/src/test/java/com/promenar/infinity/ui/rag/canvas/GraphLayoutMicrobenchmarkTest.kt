package com.promenar.infinity.ui.rag.canvas

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.rag.GraphEdge
import kotlin.random.Random
import kotlin.system.measureNanoTime
import org.junit.jupiter.api.Test

class GraphLayoutMicrobenchmarkTest {
    @Test
    fun `记录三种规模的一轮布局耗时而不设机器阈值`() {
        val engine = GraphLayoutEngine()
        println("KG_LAYOUT_ENV os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")} java=${System.getProperty("java.version")} processors=${Runtime.getRuntime().availableProcessors()}")
        listOf(300, 2_000, 10_000).forEach { count ->
            val random = Random(20261017)
            val nodes = List(count) { index ->
                SimNode("n-$index", "节点$index", "concept", random.nextFloat() * 4_000f - 2_000f,
                    random.nextFloat() * 4_000f - 2_000f)
            }
            val edges = nodes.zipWithNext { source, target -> GraphEdge(source.id, target.id) }
            lateinit var result: GraphLayoutStep
            val elapsed = measureNanoTime { result = engine.step(nodes, edges) }
            assertThat(result.nodes).hasSize(count)
            assertThat(result.nodes.all { it.x.isFinite() && it.y.isFinite() }).isTrue()
            println("KG_LAYOUT_BENCH nodes=$count edges=${edges.size} seed=20261017 iterations=1 elapsedNs=$elapsed elapsedMs=${elapsed / 1_000_000.0} repulsionEvaluations=${result.repulsionEvaluations}")
        }
    }
}
