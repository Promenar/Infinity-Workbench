package com.promenar.infinity.ui.rag.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GraphGeometryTest {
    private val viewport = Rect(0f, 0f, 100f, 100f)

    @Test
    fun `两端均在视口外的穿屏线段仍可见`() {
        assertThat(segmentIntersectsRect(Offset(-20f, 50f), Offset(120f, 50f), viewport)).isTrue()
        assertThat(segmentIntersectsRect(Offset(-20f, -20f), Offset(120f, 120f), viewport)).isTrue()
    }

    @Test
    fun `视口外平行线与包围盒相交但线段不相交的边不可见`() {
        assertThat(segmentIntersectsRect(Offset(-20f, -1f), Offset(120f, -1f), viewport)).isFalse()
        assertThat(segmentIntersectsRect(Offset(-30f, 20f), Offset(20f, -30f), viewport)).isFalse()
    }

    @Test
    fun `视口边界与零长度线段按真实交点判定`() {
        assertThat(segmentIntersectsRect(Offset(-20f, 0f), Offset(0f, 0f), viewport)).isTrue()
        assertThat(segmentIntersectsRect(Offset(50f, 50f), Offset(50f, 50f), viewport)).isTrue()
        assertThat(segmentIntersectsRect(Offset(-1f, 50f), Offset(-1f, 50f), viewport)).isFalse()
    }

    @Test
    fun `适配视图把非对称边界中心移到画布中心并容纳全部节点`() {
        val points = listOf(Offset(200f, -50f), Offset(600f, 150f))
        val fitted = fitGraphToViewport(points, 500f, 400f, padding = 40f)

        val center = Offset(400f, 50f) * fitted.scale + fitted.offset
        assertThat(center.x).isWithin(0.001f).of(250f)
        assertThat(center.y).isWithin(0.001f).of(200f)
        points.forEach { point ->
            val screen = point * fitted.scale + fitted.offset
            // Float 矩阵允许千分之一像素舍入误差，边界仍按 40px 留白验收。
            val rounding = 0.001f
            assertThat(screen.x).isAtLeast(40f - rounding)
            assertThat(screen.x).isAtMost(460f + rounding)
            assertThat(screen.y).isAtLeast(40f - rounding)
            assertThat(screen.y).isAtMost(360f + rounding)
        }
    }

    @Test
    fun `空图与单节点适配保持有限矩阵`() {
        val empty = fitGraphToViewport(emptyList(), 400f, 300f)
        val single = fitGraphToViewport(listOf(Offset(900f, -800f)), 400f, 300f)
        assertThat(empty.scale.isFinite()).isTrue()
        assertThat(single.scale.isFinite()).isTrue()
        assertThat(Offset(900f, -800f) * single.scale + single.offset).isEqualTo(Offset(200f, 150f))
    }
}
