package com.promenar.nexara.ui.hub

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * 契约测试：锁定 Agent Hub 的状态提升 seam、可达操作入口、稳定测试锚点与无障碍资源化。
 * 采用源码扫描方式（与 ChatRouteContractTest 一致），不引用尚不存在的生产类型，
 * 仅断言源码文本内容，确保编译通过并以断言失败形式呈现预期 RED。
 */
class AgentHubScreenContractTest {
    private val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }
    private val hubSource = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/hub/AgentHubScreen.kt",
    )
    private val swipeSource = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/common/SwipeableItem.kt",
    )
    private val uiTagsSource = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/testing/UiTags.kt",
    )

    @Test
    fun `content seam exposes state actions and content without view model or providers`() {
        val source = hubSource.readText()
        assertThat(source).contains("data class AgentHubScreenState(")
        assertThat(source).contains("data class AgentHubScreenActions(")
        assertThat(source).contains("fun AgentHubScreenContent(")

        val content = source.substringAfter("fun AgentHubScreenContent(")
        assertThat(content).doesNotContain("viewModel(")
        assertThat(content).doesNotContain("ProviderManager")
        assertThat(content).doesNotContain("NexaraApplication")
        assertThat(content).doesNotContain("LocalContext")
    }

    @Test
    fun `agent card exposes always visible dropdown menu entry with localized items`() {
        val source = hubSource.readText()
        assertThat(source).contains("MoreVert")
        assertThat(source).contains("DropdownMenu")
        assertThat(source).contains("DropdownMenuItem")
    }

    @Test
    fun `hub exposes stable ui tags for root add search empty list card and actions`() {
        val source = uiTagsSource.readText()
        assertThat(source).contains("HUB_ROOT")
        assertThat(source).contains("HUB_ADD_AGENT")
        assertThat(source).contains("HUB_SEARCH")
        assertThat(source).contains("HUB_EMPTY_STATE")
        assertThat(source).contains("HUB_EMPTY_ADD_AGENT")
        assertThat(source).contains("HUB_AGENT_LIST")
        assertThat(source).contains("HUB_AGENT_CARD")
        assertThat(source).contains("HUB_AGENT_ACTIONS")
        assertThat(source).contains("hubAgentActions")
    }

    @Test
    fun `empty state does not render emoji text`() {
        val source = hubSource.readText()
        assertThat(source).doesNotContain("text = \"\u2728\"")
    }

    @Test
    fun `swipeable item has no hardcoded english content descriptions`() {
        val source = swipeSource.readText()
        assertThat(source).doesNotContain("contentDescription = \"Delete\"")
        assertThat(source).doesNotContain("contentDescription = \"Edit\"")
        assertThat(source).doesNotContain("\"Unpin\"")
    }
}
