package com.promenar.nexara.share.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.share.core.ShareIndexStatus
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.share.core.ShareImportTarget
import com.promenar.nexara.share.core.ShareRejectReason
import com.promenar.nexara.share.core.ShareTargetKind
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShareImportSheetTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun targetMustBeExplicitAndPerItemFailureIsVisible() {
        var imported = false
        rule.setContent {
            var selected by remember { mutableStateOf<String?>(null) }
            NexaraTheme {
                ShareImportSheet(
                    state = ShareImportUiState(
                        visible = true,
                        selectedWorkspaceRootUuid = selected,
                        targets = listOf(
                            ShareImportTarget("root", "Knowledge Base", ShareTargetKind.KnowledgeBase)
                        ),
                        items = listOf(
                            ShareImportItem(
                                uri = Uri.parse("content://fixture/unsafe.pdf"),
                                displayName = "unsafe.pdf",
                                mimeType = "application/pdf",
                                sizeBytes = 12,
                                status = ShareImportStatus.Rejected,
                                reason = ShareRejectReason.WriteFailed,
                            ),
                            ShareImportItem(
                                uri = Uri.parse("content://fixture/docx.docx"),
                                displayName = "report.docx",
                                mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                sizeBytes = 34567,
                                status = ShareImportStatus.Pending,
                            )
                        ),
                    ),
                    onSelectTarget = { selected = it },
                    onImport = { imported = true },
                    onRetry = {},
                    onClose = {},
                )
            }
        }

        rule.onNodeWithText("unsafe.pdf").assertIsDisplayed()
        rule.onNodeWithText("The file could not be saved").assertIsDisplayed()
        rule.onNodeWithText("DOCX", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            substring = true,
        ).assertDoesNotExist()
        rule.onNodeWithText("Retry failed").assertIsNotEnabled()
        rule.onNodeWithText("Knowledge Base").performClick()
        rule.onNodeWithText("Retry failed").assertIsEnabled()
        rule.runOnIdle { check(!imported) }
    }

    @Test
    fun actionButtonsRemainReachableAtTwoTimesFontScaleInNarrowWidth() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var imported = false
        var retried = false

        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(360.dp)) {
                    NexaraTheme {
                        ShareImportSheet(
                            state = ShareImportUiState(
                                visible = true,
                                selectedWorkspaceRootUuid = "root",
                                targets = listOf(
                                    ShareImportTarget("root", "Knowledge Base", ShareTargetKind.KnowledgeBase),
                                ),
                                items = listOf(
                                    ShareImportItem(
                                        uri = Uri.parse("content://fixture/fail.pdf"),
                                        displayName = "fail.pdf",
                                        mimeType = "application/pdf",
                                        sizeBytes = 12,
                                        status = ShareImportStatus.Created,
                                        indexStatus = ShareIndexStatus.Failed,
                                    ),
                                    ShareImportItem(
                                        uri = Uri.parse("content://fixture/pending.docx"),
                                        displayName = "pending.docx",
                                        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        sizeBytes = 34567,
                                        status = ShareImportStatus.Pending,
                                    ),
                                ),
                            ),
                            onSelectTarget = {},
                            onImport = { imported = true },
                            onRetry = { retried = true },
                            onClose = {},
                        )
                    }
                }
            }
        }

        val laterLabel = resources.getString(R.string.share_import_later)
        val cancelLabel = resources.getString(R.string.share_import_cancel)
        val retryLabel = resources.getString(R.string.share_import_retry)
        val importLabel = resources.getString(R.string.share_import_action)
        val rootBounds = rule.onNodeWithTag(UiTags.SHARE_IMPORT_SHEET).fetchSemanticsNode().boundsInRoot
        val minButtonPx = with(rule.density) { 48.dp.toPx() }

        listOf(laterLabel, cancelLabel, retryLabel, importLabel).forEach { label ->
            val node = rule.onNodeWithText(label)
            node.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "action $label should stay within sheet left/right bounds",
                bounds.left >= rootBounds.left && bounds.right <= rootBounds.right,
            )
            assertTrue("action $label should keep min height", bounds.height >= minButtonPx)
        }

        rule.runOnIdle {
            assertTrue(!imported)
            assertTrue(!retried)
        }
    }
}
