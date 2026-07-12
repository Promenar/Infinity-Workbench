package com.promenar.nexara.share.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.share.core.ShareImportTarget
import com.promenar.nexara.share.core.ShareRejectReason
import com.promenar.nexara.share.core.ShareTargetKind
import com.promenar.nexara.ui.theme.NexaraTheme
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
        rule.onNodeWithText("Retry failed").assertIsNotEnabled()
        rule.onNodeWithText("Knowledge Base").performClick()
        rule.onNodeWithText("Retry failed").assertIsEnabled()
        rule.runOnIdle { check(!imported) }
    }
}
