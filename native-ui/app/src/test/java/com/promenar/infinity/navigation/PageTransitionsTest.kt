package com.promenar.infinity.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageTransitionsTest {
    @Test
    fun `返回一律视差返回，前进时迁移初始层级优先于同级替换`() {
        assertThat(pageChange(pop = true, instant = true, sameLevel = true)).isEqualTo(PageChange.POP)
        assertThat(pageChange(pop = false, instant = true, sameLevel = true)).isEqualTo(PageChange.INSTANT)
        assertThat(pageChange(pop = false, instant = false, sameLevel = true)).isEqualTo(PageChange.SAME_LEVEL)
        assertThat(pageChange(pop = false, instant = false, sameLevel = false)).isEqualTo(PageChange.PUSH)
    }

    @Test
    fun `前进时新页在上，返回时被弹出的页在上，叠化与无动画都不分层`() {
        assertThat(PageChange.PUSH.layers()).isEqualTo(PageLayer.TOP to PageLayer.UNDER)
        assertThat(PageChange.POP.layers()).isEqualTo(PageLayer.UNDER to PageLayer.TOP)
        // 跟手返回与点按返回分层相同：被弹出的页始终在上。
        assertThat(PageChange.PREDICTIVE_POP.layers()).isEqualTo(PageLayer.UNDER to PageLayer.TOP)
        assertThat(PageChange.SAME_LEVEL.layers()).isEqualTo(PageLayer.NONE to PageLayer.NONE)
        assertThat(PageChange.INSTANT.layers()).isEqualTo(PageLayer.NONE to PageLayer.NONE)
    }

    @Test
    fun `只有下层页压暗，曲线跟该次换页的位移`() {
        val layers = PageLayers()
        layers.record(targetId = "b", initialId = "a", change = PageChange.PUSH)
        assertThat(pageDimFollows(layers.roleOf("a"))).isEqualTo(PageChange.PUSH)
        assertThat(pageDimFollows(layers.roleOf("b"))).isNull()

        layers.record(targetId = "a", initialId = "b", change = PageChange.POP)
        assertThat(pageDimFollows(layers.roleOf("a"))).isEqualTo(PageChange.POP)
        assertThat(pageDimFollows(layers.roleOf("b"))).isNull()

        // 跟手返回：下层页的压暗与位移一样按手势进度，不沿用点按返回的曲线。
        layers.record(targetId = "a", initialId = "b", change = PageChange.PREDICTIVE_POP)
        assertThat(pageDimFollows(layers.roleOf("a"))).isEqualTo(PageChange.PREDICTIVE_POP)
        assertThat(pageDimFollows(layers.roleOf("b"))).isNull()

        layers.record(targetId = "c", initialId = "a", change = PageChange.SAME_LEVEL)
        assertThat(pageDimFollows(layers.roleOf("a"))).isNull()
        assertThat(pageDimFollows(layers.roleOf("unknown"))).isNull()
    }

    @Test
    fun `跟手返回取消后不残留层次，下一次换页重新分层`() {
        val layers = PageLayers()
        // a 推入 b，再从 b 拖动返回：导航库按进度反复定位同一次转场，登记结果不变。
        layers.record(targetId = "b", initialId = "a", change = PageChange.PUSH)
        repeat(3) { layers.record(targetId = "a", initialId = "b", change = PageChange.PREDICTIVE_POP) }
        assertThat(layers.roleOf("a")).isEqualTo(PageRole(PageLayer.UNDER, PageChange.PREDICTIVE_POP))
        assertThat(layers.roleOf("b")).isEqualTo(PageRole(PageLayer.TOP, PageChange.PREDICTIVE_POP))

        // 取消：回弹结束后 a 离开组合，b 留在原位，转场落定时以 b 为起止重新求值一次。
        layers.clear("a")
        layers.record(targetId = "b", initialId = "b", change = PageChange.PUSH)
        assertThat(layers.layerOf("a")).isEqualTo(PageLayer.NONE)

        // 再推入 c：b 在下、c 在上，压暗跟前进曲线。
        layers.record(targetId = "c", initialId = "b", change = PageChange.PUSH)
        assertThat(layers.roleOf("b")).isEqualTo(PageRole(PageLayer.UNDER, PageChange.PUSH))
        assertThat(layers.roleOf("c")).isEqualTo(PageRole(PageLayer.TOP, PageChange.PUSH))

        // 从 c 拖动返回：c 在上跟手滑出，b 从下层回来。
        layers.record(targetId = "b", initialId = "c", change = PageChange.PREDICTIVE_POP)
        assertThat(layers.layerOf("b")).isEqualTo(PageLayer.UNDER)
        assertThat(layers.layerOf("c")).isEqualTo(PageLayer.TOP)
    }

    @Test
    fun `同一条目在连续换页中以最近一次的层为准`() {
        val layers = PageLayers()
        layers.record(targetId = "b", initialId = "a", change = PageChange.PUSH)
        assertThat(layers.layerOf("b")).isEqualTo(PageLayer.TOP)
        assertThat(layers.layerOf("a")).isEqualTo(PageLayer.UNDER)

        layers.record(targetId = "c", initialId = "b", change = PageChange.PUSH)
        assertThat(layers.layerOf("b")).isEqualTo(PageLayer.UNDER)

        // 栏内换根页面：叠化，两页都不画阴影与压暗。
        layers.record(targetId = "d", initialId = "c", change = PageChange.SAME_LEVEL)
        assertThat(layers.layerOf("c")).isEqualTo(PageLayer.NONE)
        assertThat(layers.layerOf("d")).isEqualTo(PageLayer.NONE)

        layers.clear("b")
        assertThat(layers.layerOf("b")).isEqualTo(PageLayer.NONE)
    }

    @Test
    fun `栏内只有根页面算同级替换，子页前进与再点根页面后退都不是`() {
        // 右列打开主题设置，再压入一层子页。
        var stack = trackSettingsPaneStack(emptyList(), SettingsPaneEntry("e1", NavDestinations.THEME_CONFIG), previousId = null)
        stack = trackSettingsPaneStack(stack, SettingsPaneEntry("e2", NavDestinations.PROVIDER_LIST), previousId = "e1")
        assertThat(isPaneRootEntry(stack, "e1")).isTrue()
        assertThat(isPaneRootEntry(stack, "e2")).isFalse()
        assertThat(pageChange(pop = false, instant = false, sameLevel = isPaneRootEntry(stack, "e2"))).isEqualTo(PageChange.PUSH)

        // 左列换一个根页面：清空右列后新页成为根，按同级叠化。
        stack = trackSettingsPaneStack(stack, SettingsPaneEntry("e3", NavDestinations.DEFAULT_MODELS), previousId = null)
        assertThat(stack.map { it.id }).containsExactly("e3")
        assertThat(pageChange(pop = false, instant = false, sameLevel = isPaneRootEntry(stack, "e3"))).isEqualTo(PageChange.SAME_LEVEL)

        // 空栈时没有根页面。
        assertThat(isPaneRootEntry(emptyList(), "e3")).isFalse()
    }
}
