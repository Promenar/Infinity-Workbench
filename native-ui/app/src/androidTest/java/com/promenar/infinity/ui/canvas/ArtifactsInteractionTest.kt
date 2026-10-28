package com.promenar.infinity.ui.canvas

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.artifact.ArtifactCandidate
import com.promenar.infinity.data.artifact.ArtifactKind
import com.promenar.infinity.data.artifact.ArtifactOrigin
import com.promenar.infinity.data.artifact.ArtifactSummary
import com.promenar.infinity.data.canvas.CanvasDocument
import com.promenar.infinity.data.canvas.CanvasKind
import com.promenar.infinity.data.canvas.CanvasMetadata
import com.promenar.infinity.data.canvas.CanvasScreenSource
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

class ArtifactsInteractionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun artifact(id: String, kind: ArtifactKind, title: String, screens: Int = 0) = ArtifactSummary(
        id = id, kind = kind, origin = ArtifactOrigin.CANVAS, title = title, createdAt = 0, updatedAt = 0,
        currentVersion = 1, createdByMessageId = "m", screenCount = screens,
    )

    private val artifacts = listOf(
        artifact("p1", ArtifactKind.PROTOTYPE, "记账原型", screens = 3),
        artifact("g1", ArtifactKind.PAGE, "经营看板"),
        artifact("d1", ArtifactKind.DOCUMENT, "需求说明.md"),
    )
    private val candidate = ArtifactCandidate("/home/session/a.png", "a.png", ArtifactKind.IMAGE, 10, "m", 0)

    @Test fun filtersNarrowToOneCategory_andItemsOpen_andCandidatesClaimOrIgnore() {
        val opened = mutableListOf<String>()
        val claimed = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        rule.setContent {
            InfinityTheme {
                ArtifactsContent(
                    artifacts = artifacts,
                    candidates = listOf(candidate),
                    onOpen = { opened += it.id },
                    onClaim = { claimed += it.path },
                    onIgnore = { ignored += it.path },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.onNodeWithTag(UiTags.artifactItem("p1")).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.CANVAS_LIST).performScrollToNode(hasTestTag(UiTags.artifactClaim(candidate.path)))
        rule.onNodeWithTag(UiTags.artifactClaim(candidate.path)).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag(UiTags.artifactIgnore(candidate.path)).assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle {
            assertThat(opened).containsExactly("p1")
            assertThat(claimed).containsExactly(candidate.path)
            assertThat(ignored).containsExactly(candidate.path)
        }

        rule.onNodeWithTag(UiTags.artifactFilter(ArtifactFilter.DOCUMENT.name)).performClick()
        rule.onNodeWithTag(UiTags.artifactItem("d1")).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.artifactItem("p1")).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.artifactItem("g1")).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.artifactCandidate(candidate.path)).assertDoesNotExist()
    }

    @Test fun prototypeBoard_opensScreenInDemo_andReturnsToBoard() {
        val metadata = CanvasMetadata("c", "记账原型", 0, 0, 1, "m", listOf(1), kind = CanvasKind.PROTOTYPE)
        val screens = listOf(CanvasScreenSource("home", "首页", "<p>首页</p>"), CanvasScreenSource("add", "记一笔", "<p>记一笔</p>"))
        rule.setContent {
            // 预览模式下网页显示为占位，只测画板与演示的切换。
            CompositionLocalProvider(LocalInspectionMode provides true) {
                InfinityTheme {
                    PrototypeBoardContent(
                        document = CanvasDocument(metadata, screens.first().html, 1, screens),
                        metadata = metadata,
                        selectedVersion = null,
                        onSelectVersion = {}, onCopy = {}, onClose = {}, onInsertText = {},
                    )
                }
            }
        }
        rule.onNodeWithTag(UiTags.prototypeScreen("add")).performClick()
        rule.onNodeWithTag(UiTags.PROTOTYPE_DEMO).assertIsDisplayed()
        rule.onNodeWithText("2 / 2 · 记一笔").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.PROTOTYPE_BACK_TO_BOARD).performClick()
        rule.onNodeWithTag(UiTags.PROTOTYPE_DEMO).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.PROTOTYPE_PLAY).performClick()
        rule.onNodeWithText("1 / 2 · 首页").assertIsDisplayed()
    }
}
