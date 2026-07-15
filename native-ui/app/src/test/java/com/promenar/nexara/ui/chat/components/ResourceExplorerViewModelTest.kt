package com.promenar.nexara.ui.chat.components

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.share.core.ShareImportBatchResult
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.share.core.ShareRejectReason
import com.promenar.nexara.share.core.SharedFileImporter
import com.promenar.nexara.ui.chat.shouldOfferImportRetry
import com.promenar.nexara.ui.common.status.NoticeSeverity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ResourceExplorerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `switching session cancels old root collectors and exposes only current root`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val aFiles = MutableSharedFlow<List<FileEntry>>()
        val bFiles = MutableSharedFlow<List<FileEntry>>()
        val aRecycle = MutableSharedFlow<List<FileEntry>>()
        val bRecycle = MutableSharedFlow<List<FileEntry>>()
        coEvery { repo.ensureSessionRoot("a") } returns root("root-a")
        coEvery { repo.ensureSessionRoot("b") } returns root("root-b")
        every { repo.observeChildren("root-a", "root-a") } returns aFiles
        every { repo.observeChildren("root-b", "root-b") } returns bFiles
        every { repo.observeRecycleBin("root-a") } returns aRecycle
        every { repo.observeRecycleBin("root-b") } returns bRecycle
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )

        viewModel.loadSession("a")
        advanceUntilIdle()
        assertThat(aFiles.subscriptionCount.value).isEqualTo(1)
        assertThat(aRecycle.subscriptionCount.value).isEqualTo(1)
        viewModel.loadSession("b")
        advanceUntilIdle()

        assertThat(aFiles.subscriptionCount.value).isEqualTo(0)
        assertThat(aRecycle.subscriptionCount.value).isEqualTo(0)
        assertThat(bFiles.subscriptionCount.value).isEqualTo(1)
        assertThat(bRecycle.subscriptionCount.value).isEqualTo(1)
        assertThat(viewModel.workspaceRootUuid.value).isEqualTo("root-b")
        verify(exactly = 0) { repo.observeRoots(any()) }
    }

    @Test
    fun `root creation failure is recoverable and retry starts scoped collectors`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val files = MutableSharedFlow<List<FileEntry>>()
        val recycle = MutableSharedFlow<List<FileEntry>>()
        coEvery { repo.ensureSessionRoot("broken") } throws IllegalStateException("disk unavailable") andThen root("root-ok")
        every { repo.observeChildren("root-ok", "root-ok") } returns files
        every { repo.observeRecycleBin("root-ok") } returns recycle
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )

        viewModel.loadSession("broken")
        advanceUntilIdle()

        assertThat(viewModel.workspaceRootUuid.value).isNull()
        assertThat(viewModel.loadError.value).isNotNull()
        assertThat(viewModel.loadError.value?.code).isEqualTo(ResourceExplorerNotice.CODE_WORKSPACE_LOAD_FAILED)
        assertThat(viewModel.loadError.value?.severity).isEqualTo(NoticeSeverity.Error)
        assertThat(viewModel.loadError.value?.technical).isEqualTo("disk unavailable")

        viewModel.retryLoadSession()
        advanceUntilIdle()

        assertThat(viewModel.loadError.value).isNull()
        assertThat(viewModel.workspaceRootUuid.value).isEqualTo("root-ok")
        assertThat(files.subscriptionCount.value).isEqualTo(1)
        assertThat(recycle.subscriptionCount.value).isEqualTo(1)
    }

    @Test
    fun `SAF import exposes inspected items then per-item results and retry`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val importer = mockk<SharedFileImporter>()
        val files = MutableSharedFlow<List<FileEntry>>()
        val recycle = MutableSharedFlow<List<FileEntry>>()
        val first = Uri.parse("content://test/first.txt")
        val second = Uri.parse("content://test/second.pdf")
        val importGate = CompletableDeferred<Unit>()
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns files
        every { repo.observeRecycleBin("root") } returns recycle
        coEvery { importer.inspect(any()) } answers {
            val uri = firstArg<com.promenar.nexara.ShareRequest>().uris.single()
            listOf(item(uri, ShareImportStatus.Pending))
        }
        coEvery { importer.import(any(), "root", null, "root") } coAnswers {
            importGate.await()
            val uri = firstArg<com.promenar.nexara.ShareRequest>().uris.single()
            ShareImportBatchResult(
                listOf(
                    if (uri == first) item(uri, ShareImportStatus.Created)
                    else item(uri, ShareImportStatus.Rejected, ShareRejectReason.ReadFailed)
                )
            )
        }
        coEvery { importer.import(any(), "root", setOf(second), "root") } returns
            ShareImportBatchResult(listOf(item(second, ShareImportStatus.Created)))
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
            importer,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.importDocuments(listOf(first, second))
        dispatcher.scheduler.runCurrent()

        assertThat(viewModel.isImporting.value).isTrue()
        assertThat(viewModel.importItems.value.map { it.uri }).containsExactly(first, second).inOrder()
        assertThat(viewModel.importItems.value.map { it.status }).containsExactly(
            ShareImportStatus.Importing,
            ShareImportStatus.Pending,
        ).inOrder()

        importGate.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.importItems.value.map { it.status }).containsExactly(
            ShareImportStatus.Created,
            ShareImportStatus.Rejected,
        ).inOrder()
        viewModel.retryImport(second)
        advanceUntilIdle()
        assertThat(viewModel.importItems.value.map { it.status }).containsExactly(
            ShareImportStatus.Created,
            ShareImportStatus.Created,
        ).inOrder()
    }

    @Test
    fun `unexpected importer failure becomes visible retryable rejection`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val importer = mockk<SharedFileImporter>()
        val files = MutableSharedFlow<List<FileEntry>>()
        val recycle = MutableSharedFlow<List<FileEntry>>()
        val uri = Uri.parse("content://test/failure.txt")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns files
        every { repo.observeRecycleBin("root") } returns recycle
        coEvery { importer.inspect(any()) } returns listOf(item(uri, ShareImportStatus.Pending))
        coEvery { importer.import(any(), "root", null, "root") } throws IllegalStateException("unexpected")
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
            importer,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.importDocuments(listOf(uri))
        advanceUntilIdle()

        assertThat(viewModel.isImporting.value).isFalse()
        assertThat(viewModel.importItems.value.single().status).isEqualTo(ShareImportStatus.Rejected)
        assertThat(viewModel.importItems.value.single().reason).isEqualTo(ShareRejectReason.WriteFailed)

        coEvery { importer.import(any(), "root", setOf(uri), "root") } throws IllegalArgumentException("retry failed")
        viewModel.retryImport(uri)
        advanceUntilIdle()

        assertThat(viewModel.isImporting.value).isFalse()
        assertThat(viewModel.importItems.value.single().status).isEqualTo(ShareImportStatus.Rejected)
        assertThat(viewModel.importItems.value.single().reason).isEqualTo(ShareRejectReason.WriteFailed)
    }

    @Test
    fun `retry action is offered only for retryable rejection reasons`() {
        val uri = Uri.parse("content://test/file.txt")

        assertThat(
            shouldOfferImportRetry(item(uri, ShareImportStatus.Rejected, ShareRejectReason.WriteFailed))
        ).isTrue()
        assertThat(
            shouldOfferImportRetry(item(uri, ShareImportStatus.Rejected, ShareRejectReason.UnsupportedMime))
        ).isFalse()
        assertThat(shouldOfferImportRetry(item(uri, ShareImportStatus.Created))).isFalse()
    }

    @Test
    fun `recycle operation is single flight across operation types`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val restoreGate = CompletableDeferred<Unit>()
        val first = recycled("first")
        val second = recycled("second")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returns flowOf(listOf(first, second))
        coEvery { repo.restoreFromRecycleBin("root", first.uuid) } coAnswers { restoreGate.await() }
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.restoreRecycledFiles(listOf(first))
        dispatcher.scheduler.runCurrent()
        assertThat(viewModel.recycleOperationState.value)
            .isEqualTo(RecycleOperationState.Running(RecycleOperation.Restore, listOf(first.uuid)))

        viewModel.permanentlyDeleteRecycledFiles(listOf(second))
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { repo.permanentDelete(any(), any()) }

        restoreGate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.recycleOperationState.value)
            .isEqualTo(RecycleOperationState.Success(RecycleOperation.Restore, listOf(first.uuid)))
    }

    @Test
    fun `batch restore continues after failures and exposes partial failure items`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val first = recycled("first")
        val second = recycled("second")
        val third = recycled("third")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returns flowOf(listOf(first, second, third))
        coEvery { repo.restoreFromRecycleBin("root", first.uuid) } throws IllegalStateException("first failed")
        coEvery { repo.restoreFromRecycleBin("root", second.uuid) } returns Unit
        coEvery { repo.restoreFromRecycleBin("root", third.uuid) } throws IllegalArgumentException("third failed")
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.restoreRecycledFiles(listOf(first, second, third))
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.restoreFromRecycleBin("root", second.uuid) }
        assertThat(viewModel.recycleOperationState.value).isEqualTo(
            RecycleOperationState.PartialFailure(
                operation = RecycleOperation.Restore,
                succeededItemUuids = listOf(second.uuid),
                failedItemUuids = listOf(first.uuid, third.uuid),
            )
        )
    }

    @Test
    fun `retry recycle operation invokes only the previous failed items`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val failed = recycled("failed")
        val succeeded = recycled("succeeded")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returns flowOf(listOf(failed, succeeded))
        coEvery { repo.permanentDelete("root", failed.uuid) } throws
            IllegalStateException("first attempt failed") andThen Unit
        coEvery { repo.permanentDelete("root", succeeded.uuid) } returns Unit
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.permanentlyDeleteRecycledFiles(listOf(failed, succeeded))
        advanceUntilIdle()
        assertThat(viewModel.recycleOperationState.value).isInstanceOf(
            RecycleOperationState.PartialFailure::class.java
        )

        viewModel.retryFailedRecycleOperation()
        advanceUntilIdle()

        coVerify(exactly = 2) { repo.permanentDelete("root", failed.uuid) }
        coVerify(exactly = 1) { repo.permanentDelete("root", succeeded.uuid) }
        assertThat(viewModel.recycleOperationState.value).isEqualTo(
            RecycleOperationState.Success(RecycleOperation.PermanentDelete, listOf(failed.uuid))
        )
    }

    @Test
    fun `successful recycle operation refreshes exposed recycle bin`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val file = recycled("file")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returnsMany listOf(
            flowOf(listOf(file)),
            flowOf(emptyList()),
        )
        coEvery { repo.restoreFromRecycleBin("root", file.uuid) } returns Unit
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()
        assertThat(viewModel.recycledFiles.value).containsExactly(file)

        viewModel.restoreRecycledFiles(listOf(file))
        advanceUntilIdle()

        assertThat(viewModel.recycledFiles.value).isEmpty()
        assertThat(viewModel.recycleBinCount.value).isEqualTo(0)
        assertThat(viewModel.recycleOperationState.value)
            .isEqualTo(RecycleOperationState.Success(RecycleOperation.Restore, listOf(file.uuid)))
        verify(exactly = 2) { repo.observeRecycleBin("root") }
    }

    @Test
    fun `empty recycle selections and empty bin keep operation idle`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returns flowOf(emptyList())
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.restoreRecycledFiles(emptyList())
        viewModel.permanentlyDeleteRecycledFiles(emptyList())
        viewModel.emptyRecycleBin()
        advanceUntilIdle()

        assertThat(viewModel.recycleOperationState.value).isEqualTo(RecycleOperationState.Idle)
        coVerify(exactly = 0) { repo.restoreFromRecycleBin(any(), any()) }
        coVerify(exactly = 0) { repo.permanentDelete(any(), any()) }
        coVerify(exactly = 0) { repo.emptyRecycleBin(any()) }
    }

    @Test
    fun `failed empty recycle bin remains retryable without synthetic item ids`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val file = recycled("file")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returns flowOf(listOf(file))
        coEvery { repo.emptyRecycleBin("root") } throws
            IllegalStateException("first attempt failed") andThen Unit
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.emptyRecycleBin()
        advanceUntilIdle()
        assertThat(viewModel.recycleOperationState.value).isEqualTo(
            RecycleOperationState.Failure(RecycleOperation.Empty, emptyList())
        )

        viewModel.retryFailedRecycleOperation()
        advanceUntilIdle()

        coVerify(exactly = 2) { repo.emptyRecycleBin("root") }
        assertThat(viewModel.recycleOperationState.value).isEqualTo(
            RecycleOperationState.Success(RecycleOperation.Empty, emptyList())
        )
    }

    @Test
    fun `switching session cancels running recycle operation without writing old result into new session`() =
        runTest(dispatcher) {
            val repo = mockk<IWorkspaceRepository>()
            val oldFile = recycled("old-file", "old-root")
            val restoreGate = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()
            coEvery { repo.ensureSessionRoot("old-session") } returns root("old-root")
            coEvery { repo.ensureSessionRoot("new-session") } returns root("new-root")
            every { repo.observeChildren("old-root", "old-root") } returns flowOf(emptyList())
            every { repo.observeChildren("new-root", "new-root") } returns flowOf(emptyList())
            every { repo.observeRecycleBin("old-root") } returns flowOf(listOf(oldFile))
            every { repo.observeRecycleBin("new-root") } returns flowOf(emptyList())
            coEvery { repo.restoreFromRecycleBin("old-root", oldFile.uuid) } coAnswers {
                try {
                    restoreGate.await()
                } catch (cancelled: CancellationException) {
                    cancellationObserved.complete(Unit)
                    throw cancelled
                }
            }
            val viewModel = ResourceExplorerViewModel(
                ApplicationProvider.getApplicationContext<Application>(),
                repo,
            )
            viewModel.loadSession("old-session")
            advanceUntilIdle()

            viewModel.restoreRecycledFiles(listOf(oldFile))
            dispatcher.scheduler.runCurrent()
            assertThat(viewModel.recycleOperationState.value).isEqualTo(
                RecycleOperationState.Running(RecycleOperation.Restore, listOf(oldFile.uuid))
            )

            viewModel.loadSession("new-session")
            advanceUntilIdle()
            restoreGate.complete(Unit)
            advanceUntilIdle()

            assertThat(cancellationObserved.isCompleted).isTrue()
            assertThat(viewModel.workspaceRootUuid.value).isEqualTo("new-root")
            assertThat(viewModel.recycledFiles.value).isEmpty()
            assertThat(viewModel.recycleOperationState.value).isEqualTo(RecycleOperationState.Idle)
            coVerify(exactly = 1) { repo.restoreFromRecycleBin("old-root", oldFile.uuid) }
            verify(exactly = 1) { repo.observeRecycleBin("old-root") }
        }

    @Test
    fun `refresh failure preserves partial result without replaying successful destructive item`() =
        runTest(dispatcher) {
            val repo = mockk<IWorkspaceRepository>()
            val deleted = recycled("deleted")
            val failed = recycled("failed")
            coEvery { repo.ensureSessionRoot("session") } returns root("root")
            every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
            every { repo.observeRecycleBin("root") } returnsMany listOf(
                flowOf(listOf(deleted, failed)),
                flow { throw IllegalStateException("refresh failed") },
            )
            coEvery { repo.permanentDelete("root", deleted.uuid) } returns Unit
            coEvery { repo.permanentDelete("root", failed.uuid) } throws IllegalStateException("delete failed")
            val viewModel = ResourceExplorerViewModel(
                ApplicationProvider.getApplicationContext<Application>(),
                repo,
            )
            viewModel.loadSession("session")
            advanceUntilIdle()

            viewModel.permanentlyDeleteRecycledFiles(listOf(deleted, failed))
            advanceUntilIdle()

            assertThat(viewModel.recycleOperationState.value).isEqualTo(
                RecycleOperationState.PartialFailure(
                    operation = RecycleOperation.PermanentDelete,
                    succeededItemUuids = listOf(deleted.uuid),
                    failedItemUuids = listOf(failed.uuid),
                )
            )
            coVerify(exactly = 1) { repo.permanentDelete("root", deleted.uuid) }
            coVerify(exactly = 1) { repo.permanentDelete("root", failed.uuid) }
            verify(exactly = 2) { repo.observeRecycleBin("root") }
        }

    private fun root(uuid: String) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = uuid,
        parentUuid = null,
        name = "root",
        hash = "",
        isDirectory = true,
        physicalRootPath = "/tmp/$uuid",
        materializedPath = "/",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun item(
        uri: Uri,
        status: ShareImportStatus,
        reason: ShareRejectReason? = null,
    ) = ShareImportItem(
        uri = uri,
        displayName = uri.lastPathSegment.orEmpty(),
        mimeType = if (uri.toString().endsWith(".pdf")) "application/pdf" else "text/plain",
        sizeBytes = 10,
        status = status,
        reason = reason,
    )

    private fun recycled(uuid: String, rootUuid: String = "root") = FileEntry(
        uuid = uuid,
        workspaceRootUuid = rootUuid,
        parentUuid = null,
        name = "$uuid.txt",
        hash = uuid,
        isDirectory = false,
        physicalRootPath = "/tmp/$rootUuid",
        materializedPath = "/$uuid.txt",
        createdAt = 1,
        updatedAt = 1,
        inRecycleBin = true,
        originalMaterializedPath = "/$uuid.txt",
        recycledAt = 2,
    )
}
