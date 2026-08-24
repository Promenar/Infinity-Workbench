package com.promenar.nexara.ui.rag

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.R
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.data.rag.KgDocumentOption
import com.promenar.nexara.ui.rag.canvas.GraphPhysicsSimulator
import com.promenar.nexara.ui.rag.canvas.InteractiveGraphCanvas
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry

class RagKnowledgeGraphTask10AccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun advancedRagDoesNotExposeFourRuntimeUnusedControls() {
        rule.setContent {
            NexaraTheme {
                RagAdvancedScreenContent(
                    state = RagAdvancedScreenState(
                        config = RagConfiguration(
                            jitMaxChunks = 128,
                            kgDomainAuto = true,
                            enableIncrementalHash = true,
                            enableLocalPreprocess = true,
                        ),
                        allModels = emptyList(),
                    ),
                )
            }
        }

        listOf(
            R.string.rag_advanced_jit_enable,
            R.string.rag_advanced_jit_domain,
            R.string.rag_advanced_incremental_hash,
            R.string.rag_advanced_rule_prefilter,
        ).forEach { label ->
            rule.onNodeWithText(resources.getString(label)).assertDoesNotExist()
        }
    }

    @Test
    fun ftsBodyResultShowsSnippetAnd48DpDiscoverableRow() {
        val opened = AtomicReference<String?>(null)
        val result = RagSearchResult(
            document = Document("doc-1", "root", "Roadmap.md", ""),
            snippet = "这里包含 needle 的正文上下文",
            matchKind = RagSearchMatchKind.FtsBody,
        )
        rule.setContent {
            NexaraTheme {
                RagSearchResults(
                    state = RagSearchUiState.Results("needle", listOf(result)),
                    onOpenDocument = { opened.set(it) },
                )
            }
        }

        rule.onNodeWithTag(UiTags.RAG_SEARCH_RESULT_PREFIX + "doc-1")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithText("这里包含 needle 的正文上下文").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RAG_SEARCH_BODY_MATCH).assertIsDisplayed()
        assertThat(opened.get()).isEqualTo("doc-1")
    }

    @Test
    fun ftsFailureShowsTypedWarningAndExplicitTitleFallback() {
        val fallback = RagSearchResult(
            document = Document("doc-2", "root", "Alpha.md", ""),
            matchKind = RagSearchMatchKind.TitleFallback,
        )
        rule.setContent {
            NexaraTheme {
                RagSearchResults(
                    state = RagSearchUiState.Warning(
                        query = "Alpha",
                        code = RagSearchErrorCode.FtsUnavailable,
                        fallbackItems = listOf(fallback),
                        technical = "IllegalStateException",
                    ),
                    onOpenDocument = {},
                )
            }
        }

        rule.onNodeWithTag(UiTags.RAG_SEARCH_WARNING).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RAG_SEARCH_TITLE_FALLBACK).assertIsDisplayed()
    }

    @Test
    fun documentOptionsFailureShowsRetryInsteadOfEmptyState() {
        val retries = AtomicInteger(0)
        rule.setContent {
            NexaraTheme {
                KnowledgeGraphDocumentOptionsContent(
                    options = emptyList(),
                    loadError = KgDocumentOptionsError(
                        code = KgDocumentOptionsErrorCode.LoadFailed,
                        canRetry = true,
                        technical = "IllegalStateException",
                    ),
                    onRetry = { retries.incrementAndGet() },
                    onSelect = {},
                )
            }
        }

        rule.onNodeWithTag(UiTags.KG_DOCUMENT_OPTIONS_ERROR).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.KG_DOCUMENT_OPTIONS_EMPTY).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.KG_DOCUMENT_OPTIONS_RETRY)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertThat(retries.get()).isEqualTo(1)
    }

    @Test
    fun trulyEmptyDocumentOptionsShowsEmptyStateWithoutError() {
        rule.setContent {
            NexaraTheme {
                KnowledgeGraphDocumentOptionsContent(
                    options = emptyList(),
                    loadError = null,
                    onRetry = {},
                    onSelect = {},
                )
            }
        }

        rule.onNodeWithTag(UiTags.KG_DOCUMENT_OPTIONS_EMPTY).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.KG_DOCUMENT_OPTIONS_ERROR).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.KG_DOCUMENT_OPTIONS_RETRY).assertDoesNotExist()
    }

    @Test
    fun loadedDocumentOptionRemainsSelectable() {
        val selected = AtomicReference<String?>(null)
        rule.setContent {
            NexaraTheme {
                KnowledgeGraphDocumentOptionsContent(
                    options = listOf(KgDocumentOption("doc-a", "Alpha")),
                    loadError = null,
                    onRetry = {},
                    onSelect = selected::set,
                )
            }
        }

        rule.onNodeWithText("Alpha").assertIsDisplayed().performClick()
        assertThat(selected.get()).isEqualTo("doc-a")
    }

    @Test
    fun graphListAlternativeReadsEachRelationshipAndUses48DpRows() {
        val nodes = listOf(
            GraphNode("a", "Alice", "person", 0f, 0f),
            GraphNode("b", "Project", "concept", 0f, 0f),
        )
        val edges = listOf(GraphEdge("a", "b", "参与"))
        rule.setContent {
            NexaraTheme {
                KnowledgeGraphAccessibilitySheet(
                    nodes = nodes,
                    edges = edges,
                    onDismiss = {},
                )
            }
        }

        rule.onNodeWithTag(UiTags.KG_ACCESSIBILITY_RELATION_PREFIX + "0:a-b")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assert(
                SemanticsMatcher("关系被逐项朗读") { node ->
                    val description = runCatching {
                        node.config[SemanticsProperties.ContentDescription]
                    }.getOrNull()?.joinToString().orEmpty()
                    description.contains("Alice") && description.contains("Project")
                },
            )
    }

    @Test
    fun graphCanvasExposesNodeEdgeScopeAndGestureSummary() {
        val nodes = listOf(
            GraphNode("a", "Alice", "person", 0f, 0f),
            GraphNode("b", "Project", "concept", 0f, 0f),
        )
        val edges = listOf(GraphEdge("a", "b", "参与"))
        rule.setContent {
            NexaraTheme {
                val scope = rememberCoroutineScope()
                val simulator = remember(scope) { GraphPhysicsSimulator(scope) }
                LaunchedEffect(nodes, edges) { simulator.setData(nodes, edges) }
                InteractiveGraphCanvas(
                    simulator = simulator,
                    edges = edges,
                    summary = "2 个节点，1 条关系，全局范围",
                    gestureSummary = "单指拖动，双指缩放",
                )
            }
        }

        rule.onNodeWithTag(UiTags.KG_CANVAS)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher("Canvas 摘要完整") { node ->
                    val description = runCatching {
                        node.config[SemanticsProperties.ContentDescription]
                    }.getOrNull()?.joinToString().orEmpty()
                    description.contains("2 个节点") &&
                        description.contains("1 条关系") &&
                        description.contains("双指缩放")
                },
            )
    }
}
