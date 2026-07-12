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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
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
        coEvery { repo.ensureSessionRoot("broken") } throws IllegalStateException("磁盘不可用") andThen root("root-ok")
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
        coEvery { importer.import(any(), "root", null) } coAnswers {
            importGate.await()
            val uri = firstArg<com.promenar.nexara.ShareRequest>().uris.single()
            ShareImportBatchResult(
                listOf(
                    if (uri == first) item(uri, ShareImportStatus.Created)
                    else item(uri, ShareImportStatus.Rejected, ShareRejectReason.ReadFailed)
                )
            )
        }
        coEvery { importer.import(any(), "root", setOf(second)) } returns
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
        coEvery { importer.import(any(), "root", null) } throws IllegalStateException("unexpected")
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

        coEvery { importer.import(any(), "root", setOf(uri)) } throws IllegalArgumentException("retry failed")
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
}
