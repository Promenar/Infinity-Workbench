package com.promenar.nexara.ui.hub

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** 锁定助手会话与 Hub 低频搜索的 Material 3 迁移边界。 */
class AgentSessionsMaterialContractTest {
    private val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }
    private val sessionsSource = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/hub/AgentSessionsScreen.kt",
    )
    private val hubSource = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/hub/AgentHubScreen.kt",
    )

    @Test
    fun `agent sessions exposes a deterministic content seam`() {
        val source = sessionsSource.readText()

        assertThat(source).contains("data class AgentSessionsScreenState(")
        assertThat(source).contains("data class AgentSessionsScreenActions(")
        assertThat(source).contains("fun AgentSessionsScreenContent(")

        val content = source.substringAfter("fun AgentSessionsScreenContent(")
        assertThat(content).doesNotContain("viewModel(")
        assertThat(content).doesNotContain("SessionListViewModel.factory")
        assertThat(content).doesNotContain("LocalContext")
    }

    @Test
    fun `both low frequency lists use contextual top bar search`() {
        val sessions = sessionsSource.readText()
        val hub = hubSource.readText()

        assertThat(sessions).contains("NexaraSearchTopBar(")
        assertThat(hub).contains("NexaraSearchTopBar(")
        assertThat(sessions).doesNotContain("NexaraSearchBar(")
        assertThat(hub).doesNotContain("NexaraSearchBar(")
    }

    @Test
    fun `closing contextual search clears the hidden query`() {
        val sessions = sessionsSource.readText()
        val hub = hubSource.readText()

        assertThat(sessions).contains("if (!active) actions.onSearch(\"\")")
        assertThat(hub).contains("if (!active) actions.onSearch(\"\")")
    }

    @Test
    fun `session list keeps one lazy list state across search mode changes`() {
        val source = sessionsSource.readText()
        val hub = hubSource.readText()

        assertThat(source).contains("rememberLazyListState()")
        assertThat(source).contains("state = listState")
        assertThat(hub).contains("rememberLazyListState()")
        assertThat(hub).contains("state = listState")
    }

    @Test
    fun `session rows are continuous transparent Material list items`() {
        val source = sessionsSource.readText()
        val row = source.substringAfter("fun SessionListItem(")
            .substringBefore("private fun EmptySessionsState(")

        assertThat(row).contains("ListItem(")
        assertThat(row).contains("containerColor = Color.Transparent")
        assertThat(row).contains("Modifier.clickable(")
        assertThat(row).doesNotContain("NexaraGlassCard")
        assertThat(source).doesNotContain("fun SessionCard(")

        val list = source.substringAfter("LazyColumn(").substringBefore("itemsIndexed")
        assertThat(list).doesNotContain("Arrangement.spacedBy")
    }

    @Test
    fun `session rows preserve swipe pin delete and delete confirmation`() {
        val source = sessionsSource.readText()

        assertThat(source).contains("SwipeableItem(")
        assertThat(source).contains("onPin = { actions.onPinSession(session.id) }")
        assertThat(source).contains("onDelete = { actions.onRequestDelete(session.id) }")
        assertThat(source).contains("ConfirmDialog(")
        assertThat(source).contains("actions.onOpenSession(session.id)")
        assertThat(source).contains("CustomAccessibilityAction(")
        assertThat(source).contains("customActions = listOf(")
    }

    @Test
    fun `search with no matches renders an explicit empty result`() {
        val source = sessionsSource.readText()

        assertThat(source).contains("SearchEmptyState(")
        assertThat(source).contains("state.searchQuery.isNotBlank()")
    }
}
