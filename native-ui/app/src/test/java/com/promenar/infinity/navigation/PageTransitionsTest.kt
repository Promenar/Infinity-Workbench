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
        assertThat(PageChange.SAME_LEVEL.layers()).isEqualTo(PageLayer.NONE to PageLayer.NONE)
        assertThat(PageChange.INSTANT.layers()).isEqualTo(PageLayer.NONE to PageLayer.NONE)
    }

    @Test
    fun `只有下层页压暗，离开时跟前进曲线，进入时跟返回曲线`() {
        assertThat(pageDimFollowsPop(PageLayer.UNDER, entering = false)).isFalse()
        assertThat(pageDimFollowsPop(PageLayer.UNDER, entering = true)).isTrue()
        listOf(true, false).forEach { entering ->
            assertThat(pageDimFollowsPop(PageLayer.TOP, entering)).isNull()
            assertThat(pageDimFollowsPop(PageLayer.NONE, entering)).isNull()
        }
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
