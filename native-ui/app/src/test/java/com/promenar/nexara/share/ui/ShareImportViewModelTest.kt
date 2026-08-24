package com.promenar.nexara.share.ui

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ShareIntentQueue
import com.promenar.nexara.ShareLease
import com.promenar.nexara.ShareRequest
import com.promenar.nexara.share.core.ShareImportBatchResult
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.share.core.ShareImportTarget
import com.promenar.nexara.share.core.ShareTargetKind
import com.promenar.nexara.share.core.SharedFileImporter
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import com.promenar.nexara.data.rag.VectorizationQueue
import com.promenar.nexara.data.rag.VectorizationTask
import com.promenar.nexara.share.core.ShareIndexStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareImportViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `分享错误状态只暴露稳定类型而不暴露运行时字符串`() {
        val fields = ShareImportUiState::class.java.declaredFields.map { it.name }

        assertThat(fields).contains("error")
        assertThat(fields).doesNotContain("errorMessage")
    }

    @Test
    fun `持久化结果完成前不ack且导入中不可关闭`() = runTest(dispatcher) {
        val uri = Uri.parse("content://fixture/shared.txt")
        val queue = mockk<ShareIntentQueue>()
        val request = ShareRequest(
            uris = listOf(uri),
            mimeType = "text/plain",
            fingerprint = "0".repeat(64),
            canonicalSizeBytes = 1,
            requestId = "request",
        )
        val pendingCount = MutableStateFlow(1)
        every { queue.durablePendingCount } returns pendingCount
        coJustRun { queue.refreshDurableCount() }
        coEvery { queue.claimNextDurably() } returns ShareLease("lease", request)
        coJustRun { queue.recordTargetDurably(any(), any()) }
        coJustRun { queue.recordCreatedDurably(any(), any()) }
        coEvery { queue.ackDurably("lease") } answers { pendingCount.value = 0; true }
        val importer = mockk<SharedFileImporter>()
        val pending = ShareImportItem(uri, "shared.txt", "text/plain", 4)
        val result = CompletableDeferred<ShareImportBatchResult>()
        coEvery { importer.inspect(any()) } returns listOf(pending)
        coEvery { importer.import(any(), ROOT, null, ROOT) } coAnswers { result.await() }
        val viewModel = ShareImportViewModel(
            queue,
            importer,
            targetProvider = {
                listOf(ShareImportTarget(ROOT, "知识库", ShareTargetKind.KnowledgeBase))
            },
        )

        repeat(10) { viewModel.presentNext() }
        assertThat(viewModel.state.value.visible).isTrue()
        assertThat(viewModel.state.value.presentation).isEqualTo(SharePresentationState.Visible)
        coVerify(exactly = 1) { queue.claimNextDurably() }
        coVerify(exactly = 1) { importer.inspect(any()) }
        viewModel.importAll()
        coVerify(exactly = 0) { queue.ackDurably(any()) }
        assertThat(viewModel.close()).isFalse()

        result.complete(
            ShareImportBatchResult(listOf(pending.copy(status = ShareImportStatus.Created)))
        )

        coVerify(exactly = 1) { queue.ackDurably("lease") }
        assertThat(viewModel.state.value.importing).isFalse()
        assertThat(viewModel.close()).isTrue()
    }

    @Test
    fun `recordTarget import recordCreated ack任一步失败都退出importing并显示可重试错误`() = runTest(dispatcher) {
        FailurePoint.entries.forEach { failurePoint ->
            val fixture = fixture(failurePoint)
            fixture.viewModel.presentNext()
            fixture.viewModel.importAll()

            assertThat(fixture.viewModel.state.value.importing).isFalse()
            assertThat(fixture.viewModel.state.value.error).isEqualTo(ShareImportErrorCode.IMPORT_FAILED)
            assertThat(fixture.viewModel.state.value.visible).isTrue()
        }
    }

    @Test
    fun `ack失败后重试保留Created结果且最终可确认`() = runTest(dispatcher) {
        val fixture = fixture(FailurePoint.AckReturnsFalse)
        fixture.viewModel.presentNext()
        fixture.viewModel.importAll()
        assertThat(fixture.viewModel.state.value.items.single().status).isEqualTo(ShareImportStatus.Created)
        assertThat(fixture.viewModel.state.value.error).isEqualTo(ShareImportErrorCode.IMPORT_FAILED)

        coEvery { fixture.queue.ackDurably("lease") } returns true
        fixture.viewModel.retryRejected()

        assertThat(fixture.viewModel.state.value.error).isNull()
        assertThat(fixture.viewModel.state.value.items.single().status).isEqualTo(ShareImportStatus.Created)
        coVerify(exactly = 2) { fixture.queue.recordCreatedDurably("request", any()) }
        coVerify(exactly = 2) { fixture.queue.ackDurably("lease") }
    }

    @Test
    fun `打开分享页立即映射已持久化的后台索引失败`() = runTest(dispatcher) {
        val fixture = fixture(null)
        val failedTask = VectorizationTask(
            id = TASK,
            type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            docId = FILE,
            workspaceRootUuid = ROOT,
            status = "failed",
            error = "embedding unavailable",
        )
        fixture.indexState.value = VectorizationQueue.QueueState(
            queue = listOf(failedTask),
            currentTask = failedTask,
            isProcessing = false,
            restored = true,
        )

        fixture.viewModel.presentNext()

        assertThat(fixture.viewModel.state.value.items.single().indexStatus)
            .isEqualTo(ShareIndexStatus.Failed)
    }

    private fun fixture(failurePoint: FailurePoint?): Fixture {
        val uri = Uri.parse("content://fixture/shared.txt")
        val request = ShareRequest(
            uris = listOf(uri),
            mimeType = "text/plain",
            fingerprint = "0".repeat(64),
            canonicalSizeBytes = 1,
            requestId = "request",
        )
        val queue = mockk<ShareIntentQueue>()
        every { queue.durablePendingCount } returns MutableStateFlow(1)
        coJustRun { queue.refreshDurableCount() }
        coEvery { queue.claimNextDurably() } returns ShareLease("lease", request)
        if (failurePoint == FailurePoint.RecordTarget) {
            coEvery { queue.recordTargetDurably(any(), any()) } throws IOException("target")
        } else coJustRun { queue.recordTargetDurably(any(), any()) }
        if (failurePoint == FailurePoint.RecordCreated) {
            coEvery { queue.recordCreatedDurably(any(), any()) } throws IOException("created")
        } else coJustRun { queue.recordCreatedDurably(any(), any()) }
        if (failurePoint == FailurePoint.AckThrows) {
            coEvery { queue.ackDurably("lease") } throws IOException("ack")
        } else {
            coEvery { queue.ackDurably("lease") } returns (failurePoint != FailurePoint.AckReturnsFalse)
        }
        coEvery { queue.nackDurably(any()) } returns true

        val importer = mockk<SharedFileImporter>()
        val pending = ShareImportItem(uri, "shared.txt", "text/plain", 4)
        val created = pending.copy(
            status = ShareImportStatus.Created,
            indexStatus = ShareIndexStatus.Pending,
            fileUuid = FILE,
            indexTaskId = TASK,
        )
        coEvery { importer.inspect(any()) } returns listOf(created)
        if (failurePoint == FailurePoint.Import) {
            coEvery { importer.import(any(), any(), null, any()) } throws IOException("import")
        } else {
            coEvery { importer.import(any(), any(), null, any()) } returns ShareImportBatchResult(listOf(created))
        }
        val indexState = MutableStateFlow(VectorizationQueue.QueueState(emptyList(), null, false, false))
        val viewModel = ShareImportViewModel(
            queue,
            importer,
            targetProvider = { listOf(ShareImportTarget(ROOT, "知识库", ShareTargetKind.KnowledgeBase)) },
            indexQueueState = indexState,
        )
        return Fixture(viewModel, queue, indexState)
    }

    private data class Fixture(
        val viewModel: ShareImportViewModel,
        val queue: ShareIntentQueue,
        val indexState: MutableStateFlow<VectorizationQueue.QueueState>,
    )

    private enum class FailurePoint { RecordTarget, Import, RecordCreated, AckReturnsFalse, AckThrows }

    private companion object {
        const val ROOT = "knowledge-root"
        const val FILE = "11111111-1111-1111-1111-111111111111"
        const val TASK = "doc-ref-fixture"
    }
}
