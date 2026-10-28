package com.promenar.infinity.ui.rag.canvas

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.rag.GraphEdge
import org.junit.jupiter.api.Test

class KgGraphRenderModelTest {
    private val edges = listOf(GraphEdge("a", "b", "依赖"), GraphEdge("b", "c", "包含"), GraphEdge("c", "d", "引用"))
    private val kinds = mapOf("a" to KgEntityKind.ITEM, "b" to KgEntityKind.CONCEPT, "c" to KgEntityKind.PERSON, "d" to KgEntityKind.ITEM)
    private fun resolver(highlight: KgCanvasHighlight) = KgHighlightResolver(highlight, edges) { kinds[it] }

    @Test
    fun `抽取类型名归入八类，未知类型归为其它`() {
        assertThat(KgEntityKind.of("Person")).isEqualTo(KgEntityKind.PERSON)
        assertThat(KgEntityKind.of("organization")).isEqualTo(KgEntityKind.ORGANIZATION)
        assertThat(KgEntityKind.of("technology")).isEqualTo(KgEntityKind.ITEM)
        assertThat(KgEntityKind.of(" location ")).isEqualTo(KgEntityKind.LOCATION)
        assertThat(KgEntityKind.of("document")).isEqualTo(KgEntityKind.DOCUMENT)
        assertThat(KgEntityKind.of("夹杂类型")).isEqualTo(KgEntityKind.OTHER)
    }

    @Test
    fun `没有聚焦与筛选时全部实体与连线都亮`() {
        val r = resolver(KgCanvasHighlight())
        assertThat(listOf("a", "b", "c", "d").all(r::isLit)).isTrue()
        assertThat(edges.all(r::isEdgeLit)).isTrue()
        assertThat(edges.none(r::isEdgeEmphasized)).isTrue()
    }

    @Test
    fun `选中实体时只亮它与一跳邻居，以及与它相连的线`() {
        val r = resolver(KgCanvasHighlight(selectedId = "b"))
        assertThat(listOf("a", "b", "c").all(r::isLit)).isTrue()
        assertThat(r.isLit("d")).isFalse()
        assertThat(r.isEdgeEmphasized(edges[0])).isTrue()
        assertThat(r.isEdgeEmphasized(edges[1])).isTrue()
        // c-d 两端不全亮；即便两端都亮，未连到选中实体的线也不强调。
        assertThat(r.isEdgeLit(edges[2])).isFalse()
    }

    @Test
    fun `会话选中一轮时只亮该轮实体`() {
        val r = resolver(KgCanvasHighlight(turnNodeIds = setOf("c", "d")))
        assertThat(r.isLit("c")).isTrue()
        assertThat(r.isLit("a")).isFalse()
        assertThat(r.isEdgeEmphasized(edges[2])).isTrue()
        assertThat(r.isEdgeLit(edges[1])).isFalse()
    }

    @Test
    fun `类型筛选与聚焦叠加`() {
        val r = resolver(KgCanvasHighlight(selectedId = "b", typeFilter = setOf(KgEntityKind.ITEM)))
        assertThat(r.isLit("a")).isTrue()
        assertThat(r.isLit("c")).isFalse()
        assertThat(r.isLit("d")).isFalse()
    }

    @Test
    fun `标签按细节层级显示`() {
        val plain = resolver(KgCanvasHighlight())
        // 看全图时只标关系较多的实体，放大后全部标出。
        assertThat(plain.showsLabel("b", degree = 3, scale = 0.2f)).isTrue()
        assertThat(plain.showsLabel("a", degree = 1, scale = 0.3f)).isFalse()
        assertThat(plain.showsLabel("a", degree = 1, scale = 0.8f)).isTrue()
        val filtered = resolver(KgCanvasHighlight(typeFilter = setOf(KgEntityKind.ITEM)))
        assertThat(filtered.showsLabel("a", degree = 1, scale = 0.2f)).isTrue()
        val selected = resolver(KgCanvasHighlight(selectedId = "a"))
        assertThat(selected.showsLabel("a", degree = 1, scale = 0.2f)).isTrue()
        assertThat(selected.showsLabel("b", degree = 1, scale = 1f)).isTrue()
        assertThat(selected.showsLabel("c", degree = 9, scale = 1f)).isFalse()
    }

    @Test
    fun `节点半径随关系数增大且有上限，缩放只轻微影响屏幕尺寸`() {
        assertThat(kgNodeRadiusDp(0)).isEqualTo(11f)
        assertThat(kgNodeRadiusDp(4)).isGreaterThan(kgNodeRadiusDp(1))
        assertThat(kgNodeRadiusDp(400)).isEqualTo(22f)
        // 看全图缩到很小时仍保留基准的 75%，放大时最多 1.4 倍。
        assertThat(kgNodeScreenRadius(0, scale = 0.05f, densityPx = 2f)).isEqualTo(11f * 2f * 0.75f)
        assertThat(kgNodeScreenRadius(0, scale = 1f, densityPx = 2f)).isEqualTo(22f)
        assertThat(kgNodeScreenRadius(0, scale = 4f, densityPx = 2f)).isEqualTo(11f * 2f * 1.4f)
        assertThat(kgDegrees(edges)).containsExactly("a", 1, "b", 2, "c", 2, "d", 1)
    }

    @Test
    fun `方向点停在终点节点外缘之外且在弧线上`() {
        val start = Offset(0f, 0f)
        val end = Offset(200f, 0f)
        val point = kgEdgeArrowPoint(start, end, targetRadius = 20f)
        assertThat((end - point).getDistance()).isGreaterThan(20f)
        assertThat(point.x).isLessThan(200f)
        // 弧线向法线一侧偏移，平行关系不重叠。
        assertThat(kgEdgeControl(start, end).y).isNotEqualTo(0f)
        assertThat(kgOrbitAngle("x")).isEqualTo(kgOrbitAngle("x"))
    }

    @Test
    fun `命中检测取触控范围内最近的实体`() {
        val nodes = listOf(SimNode("a", "A", "item", 0f, 0f), SimNode("b", "B", "item", 60f, 0f))
        val degrees = mapOf("a" to 0, "b" to 0)
        val radius: (Int) -> Float = { 20f }
        assertThat(kgHitTest(nodes, degrees, Offset(40f, 0f), 1f, Offset.Zero, 48f, radius)?.id).isEqualTo("b")
        assertThat(kgHitTest(nodes, degrees, Offset(5f, 3f), 1f, Offset.Zero, 48f, radius)?.id).isEqualTo("a")
        assertThat(kgHitTest(nodes, degrees, Offset(200f, 200f), 1f, Offset.Zero, 48f, radius)).isNull()
        // 缩小后两实体在屏幕上只差 6px，仍按距离取最近的一个。
        assertThat(kgHitTest(nodes, degrees, Offset(-10f, 0f), 0.1f, Offset.Zero, 48f, radius)?.id).isEqualTo("a")
    }
}
