package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.ui.chat.components.RecycleBinPanel
import com.promenar.nexara.ui.chat.components.RecycleOperation
import com.promenar.nexara.ui.chat.components.RecycleOperationState
import com.promenar.nexara.ui.theme.NexaraTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class ResourceExplorerRecycleBinInteractionTest {
    @get:Rule
    val rule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun emptyRecycleBinDoesNotExposeBulkActions() {
        setPanel(files = emptyList())

        rule.onNodeWithTag(TAG_RESTORE_ALL).assertDoesNotExist()
        rule.onNodeWithTag(TAG_DELETE_ALL).assertDoesNotExist()
        rule.onNodeWithTag(TAG_EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun runningOperationDisablesEveryRestoreAndDeleteEntryAndAnnouncesProgress() {
        val file = entry("running")
        setPanel(
            files = listOf(file),
            operationState = RecycleOperationState.Running(
                operation = RecycleOperation.PermanentDelete,
                itemUuids = listOf(file.uuid),
            ),
        )

        listOf(
            TAG_RESTORE_ALL,
            TAG_DELETE_ALL,
            restoreTag(file.uuid),
            deleteTag(file.uuid),
        ).forEach { tag ->
            rule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertIsNotEnabled()
                .assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithTag(TAG_OPERATION_STATUS)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    resources.getString(R.string.recycle_bin_status_deleting),
                ),
            )
    }

    @Test
    fun partialFailureOffersOneRetryPathForFailedItemsAndCanBeCleared() {
        val retries = AtomicInteger()
        val clears = AtomicInteger()
        val failed = entry("failed")
        setPanel(
            files = listOf(failed),
            operationState = RecycleOperationState.PartialFailure(
                operation = RecycleOperation.Restore,
                succeededItemUuids = listOf("done"),
                failedItemUuids = listOf(failed.uuid),
            ),
            onRetryOperation = { retries.incrementAndGet() },
            onClearOperationState = { clears.incrementAndGet() },
        )

        rule.onNodeWithTag(TAG_OPERATION_STATUS)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            )
        rule.onNodeWithTag(TAG_RETRY)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        rule.onNodeWithTag(TAG_CLEAR_STATUS)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(retries.get()).isEqualTo(1)
        assertThat(clears.get()).isEqualTo(1)
    }

    @Test
    fun operationNoticeAnnouncesStateWithoutDuplicatingVisibleMessage() {
        val failed = entry("announce-once")
        val message = resources.getString(R.string.recycle_bin_status_restore_failed, 1)
        setPanel(
            files = listOf(failed),
            operationState = RecycleOperationState.Failure(
                operation = RecycleOperation.Restore,
                failedItemUuids = listOf(failed.uuid),
            ),
        )

        rule.onNodeWithTag(TAG_OPERATION_STATUS, useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    message,
                ),
            )
        rule.onAllNodes(hasText(message), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun singleItemActionsNameTheirDocument() {
        val file = entry("named-actions")
        setPanel(files = listOf(file))

        rule.onNodeWithTag(restoreTag(file.uuid))
            .assert(
                SemanticsMatcher("restore action names its document") { node ->
                    runCatching { node.config[SemanticsActions.OnClick].label }.getOrNull() ==
                        resources.getString(R.string.recycle_bin_restore_document, file.name)
                },
            )
        rule.onNodeWithTag(deleteTag(file.uuid))
            .assert(
                SemanticsMatcher("delete action names its document") { node ->
                    runCatching { node.config[SemanticsActions.OnClick].label }.getOrNull() ==
                        resources.getString(R.string.recycle_bin_delete_document, file.name)
                },
            )
    }

    @Test
    fun singlePermanentDeleteRequiresConfirmation() {
        val deleted = AtomicReference<List<FileEntry>>(emptyList())
        val file = entry("single")
        setPanel(
            files = listOf(file),
            onPermanentlyDeleteFiles = { deleted.set(it) },
        )

        rule.onNodeWithTag(deleteTag(file.uuid)).performClick()
        assertThat(deleted.get()).isEmpty()
        rule.onNodeWithTag(TAG_DELETE_CONFIRM)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()

        assertThat(deleted.get()).containsExactly(file)
    }

    @Test
    fun cancellingOrSystemDismissingSingleDeleteClosesDialogWithoutDeleting() {
        val deleted = AtomicReference<List<FileEntry>>(emptyList())
        val file = entry("cancel")
        setPanel(
            files = listOf(file),
            onPermanentlyDeleteFiles = { deleted.set(it) },
        )

        rule.onNodeWithTag(deleteTag(file.uuid)).performClick()
        rule.onNodeWithTag(TAG_DELETE_CONFIRM).assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.common_btn_cancel)).performClick()
        rule.onNodeWithTag(TAG_DELETE_CONFIRM).assertDoesNotExist()
        assertThat(deleted.get()).isEmpty()

        rule.onNodeWithTag(deleteTag(file.uuid)).performClick()
        rule.onNodeWithTag(TAG_DELETE_CONFIRM).assertIsDisplayed()
        pressBack()
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_DELETE_CONFIRM).assertDoesNotExist()
        assertThat(deleted.get()).isEmpty()
    }

    @Test
    fun bulkPermanentDeleteRequiresConfirmation() {
        val deleted = AtomicReference<List<FileEntry>>(emptyList())
        val files = listOf(entry("first"), entry("second"))
        setPanel(
            files = files,
            onEmptyRecycleBin = { deleted.set(files) },
        )

        rule.onNodeWithTag(TAG_DELETE_ALL).performClick()
        assertThat(deleted.get()).isEmpty()
        rule.onNodeWithTag(TAG_EMPTY_CONFIRM)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()

        assertThat(deleted.get()).containsExactlyElementsIn(files)
    }

    @Test
    fun doubleFontPhoneKeepsBulkAndSingleItemContentUsable() {
        val file = entry("narrow")
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                NexaraTheme(dynamicColor = false) {
                    Box(
                        Modifier
                            .requiredSize(width = 360.dp, height = 800.dp)
                            .testTag(TAG_TEST_ROOT),
                    ) {
                        RecycleBinPanel(
                            files = listOf(file),
                            operationState = RecycleOperationState.Idle,
                            onRestoreFiles = {},
                            onPermanentlyDeleteFiles = {},
                            onEmptyRecycleBin = {},
                            onRetryOperation = {},
                            onClearOperationState = {},
                            nowMillis = FIXED_NOW,
                        )
                    }
                }
            }
        }

        listOf(TAG_RESTORE_ALL, TAG_DELETE_ALL).forEach { tag ->
            rule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }

        val restoreBounds = rule.onNodeWithTag(restoreTag(file.uuid))
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag(deleteTag(file.uuid))
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

        val rootBounds = rule.onNodeWithTag(TAG_TEST_ROOT).fetchSemanticsNode().boundsInRoot
        val longTextBounds = listOf(
            file.name,
            resources.getString(R.string.recycle_bin_original_path, file.originalMaterializedPath.orEmpty()),
            resources.getString(R.string.files_time_minutes_ago, 1),
        ).map { text ->
            rule.onNodeWithText(text)
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
        }

        assertThat(longTextBounds.all { bounds ->
            bounds.left >= rootBounds.left &&
                bounds.top >= rootBounds.top &&
                bounds.right <= rootBounds.right &&
                bounds.bottom <= rootBounds.bottom
        }).isTrue()
        assertThat(longTextBounds.all { bounds ->
            bounds.bottom <= restoreBounds.top ||
                bounds.right <= restoreBounds.left ||
                bounds.left >= restoreBounds.right ||
                bounds.top >= restoreBounds.bottom
        }).isTrue()
    }

    @Test
    fun doubleFontFailureCanReachRetryClearAndCleanupNotice() {
        val failed = entry("double-font-failure")
        val retryCount = AtomicInteger()
        val clearCount = AtomicInteger()

        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                NexaraTheme(dynamicColor = false) {
                    RecycleBinPanel(
                        files = listOf(failed),
                        operationState = RecycleOperationState.Failure(
                            operation = RecycleOperation.Restore,
                            failedItemUuids = listOf(failed.uuid),
                        ),
                        onRestoreFiles = {},
                        onPermanentlyDeleteFiles = {},
                        onEmptyRecycleBin = {},
                        onRetryOperation = { retryCount.incrementAndGet() },
                        onClearOperationState = { clearCount.incrementAndGet() },
                        nowMillis = FIXED_NOW,
                    )
                }
            }
        }

        rule.onNodeWithTag(TAG_RECYCLE_LIST)
            .performScrollToNode(hasTestTag(TAG_RETRY))
        rule.onNodeWithTag(TAG_RETRY)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(TAG_RECYCLE_LIST)
            .performScrollToNode(hasTestTag(TAG_CLEAR_STATUS))
        rule.onNodeWithTag(TAG_CLEAR_STATUS)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(TAG_RECYCLE_LIST)
            .performScrollToNode(hasTestTag(TAG_CLEANUP_NOTICE))
        rule.onNodeWithTag(TAG_CLEANUP_NOTICE).assertIsDisplayed()

        assertThat(retryCount.get()).isEqualTo(1)
        assertThat(clearCount.get()).isEqualTo(1)
    }

    @Test
    fun landscapeLongRecycleListKeepsLastRestoreAndDeleteReachable() {
        val files = (0 until 24).map { entry("landscape-$it") }
        val last = files.last()
        val restored = AtomicReference<List<FileEntry>>(emptyList())

        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(800.dp, 360.dp)),
            ) {
                NexaraTheme(dynamicColor = false) {
                    RecycleBinPanel(
                        files = files,
                        operationState = RecycleOperationState.Idle,
                        onRestoreFiles = { restored.set(it) },
                        onPermanentlyDeleteFiles = {},
                        onEmptyRecycleBin = {},
                        onRetryOperation = {},
                        onClearOperationState = {},
                        nowMillis = FIXED_NOW,
                    )
                }
            }
        }

        rule.onNodeWithTag(TAG_RECYCLE_LIST)
            .performScrollToNode(hasTestTag(restoreTag(last.uuid)))
        rule.onNodeWithTag(restoreTag(last.uuid))
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(deleteTag(last.uuid))
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        assertThat(restored.get()).containsExactly(last)
    }

    private fun setPanel(
        files: List<FileEntry>,
        operationState: RecycleOperationState = RecycleOperationState.Idle,
        onRestoreFiles: (List<FileEntry>) -> Unit = {},
        onPermanentlyDeleteFiles: (List<FileEntry>) -> Unit = {},
        onEmptyRecycleBin: () -> Unit = {},
        onRetryOperation: () -> Unit = {},
        onClearOperationState: () -> Unit = {},
    ) {
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RecycleBinPanel(
                    files = files,
                    operationState = operationState,
                    onRestoreFiles = onRestoreFiles,
                    onPermanentlyDeleteFiles = onPermanentlyDeleteFiles,
                    onEmptyRecycleBin = onEmptyRecycleBin,
                    onRetryOperation = onRetryOperation,
                    onClearOperationState = onClearOperationState,
                    nowMillis = FIXED_NOW,
                )
            }
        }
    }

    private fun entry(id: String) = FileEntry(
        uuid = id,
        workspaceRootUuid = "workspace",
        parentUuid = "root",
        name = "很长的回收站文档名称-$id-用于验证文本截断.md",
        hash = "hash-$id",
        mimeType = "text/markdown",
        physicalRootPath = "/fixture",
        materializedPath = "/$id.md",
        inRecycleBin = true,
        recycledAt = FIXED_NOW - 60_000L,
        originalMaterializedPath = "/documents/$id.md",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private companion object {
        const val FIXED_NOW = 1_720_000_000_000L
        const val TAG_RESTORE_ALL = "recycle_bin_restore_all"
        const val TAG_DELETE_ALL = "recycle_bin_delete_all"
        const val TAG_EMPTY_STATE = "recycle_bin_empty_state"
        const val TAG_OPERATION_STATUS = "recycle_bin_operation_status"
        const val TAG_RETRY = "recycle_bin_operation_retry"
        const val TAG_CLEAR_STATUS = "recycle_bin_operation_clear"
        const val TAG_DELETE_CONFIRM = "recycle_bin_delete_confirm"
        const val TAG_EMPTY_CONFIRM = "recycle_bin_empty_confirm"
        const val TAG_TEST_ROOT = "recycle_bin_test_root"
        const val TAG_RECYCLE_LIST = "recycle_bin_list"
        const val TAG_CLEANUP_NOTICE = "recycle_bin_cleanup_notice"

        fun restoreTag(uuid: String) = "recycle_bin_restore:$uuid"
        fun deleteTag(uuid: String) = "recycle_bin_delete:$uuid"
    }
}
