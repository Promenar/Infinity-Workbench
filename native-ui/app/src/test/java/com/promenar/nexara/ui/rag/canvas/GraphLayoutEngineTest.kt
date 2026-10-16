package com.promenar.nexara.ui.rag.canvas

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.rag.GraphEdge
import kotlin.random.Random
import org.junit.jupiter.api.Test

class GraphLayoutEngineTest {
    @Test
    fun `固定输入的近似布局产生确定性的单轮结果`() {
        val nodes = fixture(300)
        val edges = nodes.zipWithNext { source, target -> GraphEdge(source.id, target.id) }
        val engine = GraphLayoutEngine()

        val first = engine.step(nodes, edges)
        val second = engine.step(nodes, edges)

        assertThat(first.nodes).containsExactlyElementsIn(second.nodes).inOrder()
        assertThat(first.maxVelocity).isEqualTo(second.maxVelocity)
        assertThat(first.nodes.any { it.x != nodes.first { old -> old.id == it.id }.x }).isTrue()
    }

    @Test
    fun `近似斥力显著减少大图的成对相互作用`() {
        val result = GraphLayoutEngine().step(fixture(2_000), emptyList())

        assertThat(result.repulsionEvaluations).isLessThan(2_000L * 2_000L / 4L)
        assertThat(result.nodes).hasSize(2_000)
        assertThat(result.nodes.all { it.x.isFinite() && it.y.isFinite() }).isTrue()
    }

    @Test
    fun `固定节点不移动且重合节点不会产生非有限坐标`() {
        val nodes = listOf(
            SimNode("pinned", "固定", "concept", 0f, 0f, isPinned = true),
            SimNode("free", "可动", "concept", 0f, 0f),
        )
        val result = GraphLayoutEngine().step(nodes, listOf(GraphEdge("pinned", "free")))

        assertThat(result.nodes.first().x).isEqualTo(0f)
        assertThat(result.nodes.first().y).isEqualTo(0f)
        assertThat(result.nodes.all { it.x.isFinite() && it.y.isFinite() }).isTrue()
        assertThat(result.nodes.last().x != 0f || result.nodes.last().y != 0f).isTrue()
    }

    @Test
    fun `空图单轮立即返回静止结果`() {
        val result = GraphLayoutEngine().step(emptyList(), emptyList())
        assertThat(result.nodes).isEmpty()
        assertThat(result.maxVelocity).isEqualTo(0f)
    }

    @Test
    fun `双节点斥力保留解析可核对的方向与幅度`() {
        val nodes = listOf(SimNode("a", "甲", "concept", -10f, 0f), SimNode("b", "乙", "concept", 10f, 0f))
        val result = GraphLayoutEngine(kr = 100f, ka = 0f, kg = 0f, friction = 1f).step(nodes, emptyList())
        assertThat(result.nodes[0].x).isWithin(0.0001f).of(-15f)
        assertThat(result.nodes[1].x).isWithin(0.0001f).of(15f)
    }

    @Test
    fun `大量重合节点使用聚合叶桶并保持有限坐标`() {
        val nodes = List(2_000) { SimNode("n-$it", "节点$it", "concept", 0f, 0f) }
        val result = GraphLayoutEngine().step(nodes, emptyList())
        assertThat(result.repulsionEvaluations).isLessThan(4_000L)
        assertThat(result.nodes.all { it.x.isFinite() && it.y.isFinite() }).isTrue()
    }

    private fun fixture(count: Int): List<SimNode> {
        val random = Random(20261017)
        return List(count) { index ->
            SimNode("n-$index", "节点$index", "concept", random.nextFloat() * 4_000f - 2_000f,
                random.nextFloat() * 4_000f - 2_000f)
        }
    }
}
