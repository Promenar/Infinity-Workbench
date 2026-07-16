package com.promenar.nexara.ui.rag

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.DiffResult
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.PatchOperation
import com.promenar.nexara.domain.repository.PatchResult
import com.promenar.nexara.domain.repository.ReadResult
import com.promenar.nexara.domain.repository.WriteResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fileRepository: IFileOperationRepository
    private lateinit var workspaceRepository: IWorkspaceRepository

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fileRepository = mockk()
        workspaceRepository = mockk()
        coEvery { workspaceRepository.getByUuid(ROOT, any()) } answers {
            metadataEntry(uuid = secondArg())
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `读取成功进入 Ready 并保存真实文件信息`() = runTest(dispatcher) {
        coEvery { fileRepository.readFileRange(ROOT, DOC) } returns readResult()
        val vm = viewModel()

        vm.loadDocument(ROOT, DOC)
        runCurrent()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.title).isEqualTo("guide.md")
        assertThat(vm.uiState.value.content).isEqualTo("hello\nworld")
        assertThat(vm.uiState.value.currentHash).isEqualTo("hash-1")
        assertThat(vm.uiState.value.totalLines).isEqualTo(2)
        assertThat(vm.uiState.value.lastModified).isEqualTo(123L)
        assertThat(vm.uiState.value.sizeBytes).isEqualTo(11L)
        assertThat(vm.uiState.value.isDirty).isFalse()
    }

    @Test
    fun `超过一 MiB 时预检元数据后进入安全只读且不读取全文`() = runTest(dispatcher) {
        coEvery { workspaceRepository.getByUuid(ROOT, DOC) } returns metadataEntry(
            sizeBytes = ONE_MIB + 1L,
        )
        coEvery { fileRepository.readFileRange(ROOT, DOC) } returns readResult(
            content = "不应读取的全文",
        )
        val vm = viewModel()

        vm.loadDocument(ROOT, DOC)
        runCurrent()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.hasLoadedDocument).isTrue()
        assertThat(vm.uiState.value.isLargeFile).isTrue()
        assertThat(vm.uiState.value.sizeBytes).isEqualTo(ONE_MIB + 1L)
        assertThat(vm.uiState.value.content).isEmpty()
        coVerify(exactly = 0) { fileRepository.readFileRange(ROOT, DOC) }
    }

    @Test
    fun `大文件安全只读状态不能变脏或保存`() = runTest(dispatcher) {
        coEvery { workspaceRepository.getByUuid(ROOT, DOC) } returns metadataEntry(
            sizeBytes = ONE_MIB + 1L,
        )
        coEvery { fileRepository.readFileRange(ROOT, DOC) } returns readResult()
        val vm = viewModel()
        vm.loadDocument(ROOT, DOC)
        runCurrent()

        vm.updateTitle("renamed.md")
        vm.onContentChanged("local draft")
        vm.saveDocument()
        advanceUntilIdle()

        assertThat(vm.uiState.value.title).isEqualTo("guide.md")
        assertThat(vm.uiState.value.content).isEmpty()
        assertThat(vm.uiState.value.isDirty).isFalse()
        coVerify(exactly = 0) { workspaceRepository.rename(any(), any(), any()) }
        coVerify(exactly = 0) {
            fileRepository.writeFileAtomic(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `普通文件大小始终使用 FileEntry 元数据而非正文字符或字节数`() = runTest(dispatcher) {
        coEvery { workspaceRepository.getByUuid(ROOT, DOC) } returns metadataEntry(
            sizeBytes = 4_096L,
        )
        coEvery { fileRepository.readFileRange(ROOT, DOC) } returns readResult(
            content = "短正文",
        )
        val vm = viewModel()

        vm.loadDocument(ROOT, DOC)
        runCurrent()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.sizeBytes).isEqualTo(4_096L)
        assertThat(vm.uiState.value.isLargeFile).isFalse()
    }

    @Test
    fun `读取异常进入稳定 LoadError 而不是伪空 Ready`() = runTest(dispatcher) {
        coEvery { fileRepository.readFileRange(ROOT, DOC) } throws IllegalStateException("secret backend detail")
        val vm = viewModel()

        vm.loadDocument(ROOT, DOC)
        runCurrent()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.LoadError)
        assertThat(vm.uiState.value.failureCode).isEqualTo(DocEditorFailureCode.LoadFailed)
        assertThat(vm.uiState.value.failureDetail).isNull()
    }

    @Test
    fun `读取缺失文档进入 NotFound`() = runTest(dispatcher) {
        coEvery { fileRepository.readFileRange(ROOT, DOC) } throws NoSuchElementException("missing path")
        val vm = viewModel()

        vm.loadDocument(ROOT, DOC)
        runCurrent()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.NotFound)
        assertThat(vm.uiState.value.failureCode).isEqualTo(DocEditorFailureCode.LoadNotFound)
    }

    @Test
    fun `标题输入只更新内存且不会持久化`() = runTest(dispatcher) {
        val vm = loadedViewModel()

        vm.updateTitle("renamed.md")
        runCurrent()

        assertThat(vm.uiState.value.title).isEqualTo("renamed.md")
        assertThat(vm.uiState.value.titleDirty).isTrue()
        coVerify(exactly = 0) { workspaceRepository.rename(any(), any(), any()) }
        coVerify(exactly = 0) { fileRepository.writeFileAtomic(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `显式保存先安全重命名再保存正文并清理全部 dirty`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        vm.updateTitle("renamed.md")
        vm.onContentChanged("updated")
        coEvery { workspaceRepository.rename(ROOT, DOC, "renamed.md") } returns Unit
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        } returns WriteResult.Success("hash-2")

        vm.saveDocument()
        advanceUntilIdle()

        coVerifyOrder {
            workspaceRepository.rename(ROOT, DOC, "renamed.md")
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        }
        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.titleDirty).isFalse()
        assertThat(vm.uiState.value.contentDirty).isFalse()
        assertThat(vm.uiState.value.currentHash).isEqualTo("hash-2")
    }

    @Test
    fun `重命名失败不会继续写正文且保留 dirty`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        vm.updateTitle("renamed.md")
        vm.onContentChanged("updated")
        coEvery { workspaceRepository.rename(ROOT, DOC, "renamed.md") } throws IllegalArgumentException("duplicate")

        vm.saveDocument()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.SaveError)
        assertThat(vm.uiState.value.failureCode).isEqualTo(DocEditorFailureCode.TitleRenameFailed)
        assertThat(vm.uiState.value.titleDirty).isTrue()
        assertThat(vm.uiState.value.contentDirty).isTrue()
        coVerify(exactly = 0) { fileRepository.writeFileAtomic(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `正文冲突保留原 expectedHash 与 dirty 且普通保存不会隐式覆盖`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        vm.updateTitle("renamed.md")
        vm.onContentChanged("updated")
        coEvery { workspaceRepository.rename(ROOT, DOC, "renamed.md") } returns Unit
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        } returns WriteResult.Conflict("remote-hash", "hash-1", "remote detail")

        vm.saveDocument()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.SaveConflict)
        assertThat(vm.uiState.value.titleDirty).isFalse()
        assertThat(vm.uiState.value.contentDirty).isTrue()
        assertThat(vm.uiState.value.conflictCurrentHash).isEqualTo("remote-hash")
        assertThat(vm.uiState.value.currentHash).isEqualTo("hash-1")
        assertThat(vm.uiState.value.failureDetail).isNull()

        vm.onContentChanged("another local draft")
        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.SaveConflict)
        assertThat(vm.uiState.value.failureCode).isEqualTo(DocEditorFailureCode.ContentConflict)
        vm.saveDocument()
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.rename(ROOT, DOC, "renamed.md") }
        coVerify(exactly = 1) {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        }
        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.SaveConflict)
        assertThat(vm.uiState.value.currentHash).isEqualTo("hash-1")
        assertThat(vm.uiState.value.contentDirty).isTrue()

        vm.reload()
        advanceUntilIdle()
        coVerify(exactly = 2) { fileRepository.readFileRange(ROOT, DOC) }
    }

    @Test
    fun `标题已保存而正文异常时重试只重写正文`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        vm.updateTitle("renamed.md")
        vm.onContentChanged("updated")
        coEvery { workspaceRepository.rename(ROOT, DOC, "renamed.md") } returns Unit
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        } throws IllegalStateException("temporary") andThen WriteResult.Success("hash-2")

        vm.saveDocument()
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.SaveError)
        assertThat(vm.uiState.value.titleDirty).isFalse()
        assertThat(vm.uiState.value.contentDirty).isTrue()

        vm.saveDocument()
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.rename(ROOT, DOC, "renamed.md") }
        coVerify(exactly = 2) {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        }
        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.isDirty).isFalse()
    }

    @Test
    fun `正文 NotFound 保留 dirty 并进入 NotFound`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        vm.onContentChanged("updated")
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        } returns WriteResult.NotFound

        vm.saveDocument()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.NotFound)
        assertThat(vm.uiState.value.failureCode).isEqualTo(DocEditorFailureCode.SaveNotFound)
        assertThat(vm.uiState.value.contentDirty).isTrue()

        vm.reload()
        advanceUntilIdle()
        coVerify(exactly = 2) { fileRepository.readFileRange(ROOT, DOC) }
    }

    @Test
    fun `正文异常进入 SaveError 且不暴露异常原文`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        vm.onContentChanged("updated")
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        } throws IllegalStateException("sensitive provider path")

        vm.saveDocument()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.SaveError)
        assertThat(vm.uiState.value.failureCode).isEqualTo(DocEditorFailureCode.ContentSaveFailed)
        assertThat(vm.uiState.value.failureDetail).isNull()
        assertThat(vm.uiState.value.contentDirty).isTrue()

        vm.reload()
        advanceUntilIdle()
        coVerify(exactly = 2) { fileRepository.readFileRange(ROOT, DOC) }
    }

    @Test
    fun `保存取消后释放同文件加载门禁`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        vm.onContentChanged("updated")
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        } throws CancellationException("cancelled write")

        vm.saveDocument()
        advanceUntilIdle()
        vm.reload()
        advanceUntilIdle()

        coVerify(exactly = 2) { fileRepository.readFileRange(ROOT, DOC) }
        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.content).isEqualTo("hello\nworld")
    }

    @Test
    fun `正文提交前取消会恢复为可重试错误并保留 dirty`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        vm.onContentChanged("updated")
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        } throws CancellationException("cancelled before commit")

        vm.saveDocument()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.SaveError)
        assertThat(vm.uiState.value.failureCode).isEqualTo(DocEditorFailureCode.SaveCancelled)
        assertThat(vm.uiState.value.content).isEqualTo("updated")
        assertThat(vm.uiState.value.contentDirty).isTrue()
        assertThat(vm.uiState.value.persistedContent).isEqualTo("hello\nworld")
    }

    @Test
    fun `ViewModel 销毁取消保存时不回写错误状态`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        val writeStarted = CompletableDeferred<Unit>()
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        } coAnswers {
            writeStarted.complete(Unit)
            awaitCancellation()
        }
        vm.onContentChanged("updated")
        val store = ViewModelStore().also { it.put("doc-editor", vm) }

        vm.saveDocument()
        runCurrent()
        writeStarted.await()
        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Saving)

        store.clear()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Saving)
        assertThat(vm.uiState.value.failureCode).isNull()
    }

    @Test
    fun `旧文档取消迟到不会污染新文档`() = runTest(dispatcher) {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        coEvery { fileRepository.readFileRange(ROOT, DOC_A) } returns
            readResult(uuid = DOC_A, name = "a.md", content = "A")
        coEvery { fileRepository.readFileRange(ROOT, DOC_B) } returns
            readResult(uuid = DOC_B, name = "b.md", content = "B", hash = "hash-b")
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC_A, "A updated", "editor", "hash-1")
        } coAnswers {
            writeStarted.complete(Unit)
            withContext(NonCancellable) { releaseWrite.await() }
            throw CancellationException("late cancellation")
        }
        val vm = viewModel()
        vm.loadDocument(ROOT, DOC_A)
        advanceUntilIdle()
        vm.onContentChanged("A updated")
        vm.saveDocument()
        runCurrent()
        writeStarted.await()

        vm.loadDocument(ROOT, DOC_B)
        runCurrent()
        releaseWrite.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_B)
        assertThat(vm.uiState.value.content).isEqualTo("B")
        assertThat(vm.uiState.value.failureCode).isNull()
    }

    @Test
    fun `连续保存调用在首个写入完成前只执行一次`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        vm.onContentChanged("updated")
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        } coAnswers {
            writeStarted.complete(Unit)
            releaseWrite.await()
            WriteResult.Success("hash-2")
        }

        vm.saveDocument()
        runCurrent()
        writeStarted.await()
        vm.saveDocument()
        runCurrent()
        coVerify(exactly = 1) {
            fileRepository.writeFileAtomic(ROOT, DOC, "updated", "editor", "hash-1")
        }

        releaseWrite.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
    }

    @Test
    fun `未加载文档时保存不会访问任何仓库`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.saveDocument()
        advanceUntilIdle()

        coVerify(exactly = 0) { workspaceRepository.rename(any(), any(), any()) }
        coVerify(exactly = 0) { fileRepository.writeFileAtomic(any(), any(), any(), any(), any()) }
        confirmVerified(workspaceRepository, fileRepository)
    }

    @Test
    fun `重试加载会重新读取并重置标题正文哈希与 dirty`() = runTest(dispatcher) {
        coEvery { fileRepository.readFileRange(ROOT, DOC) } returnsMany listOf(
            readResult(),
            readResult(name = "fresh.md", content = "fresh", hash = "hash-fresh"),
        )
        val vm = viewModel()
        vm.loadDocument(ROOT, DOC)
        advanceUntilIdle()
        vm.updateTitle("draft.md")
        vm.onContentChanged("draft")

        vm.reload()
        advanceUntilIdle()

        coVerify(exactly = 2) { fileRepository.readFileRange(ROOT, DOC) }
        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.title).isEqualTo("fresh.md")
        assertThat(vm.uiState.value.content).isEqualTo("fresh")
        assertThat(vm.uiState.value.currentHash).isEqualTo("hash-fresh")
        assertThat(vm.uiState.value.isDirty).isFalse()
    }

    @Test
    fun `旧文档标题保存晚返回不会污染新文档标题基线`() = runTest(dispatcher) {
        val renameStarted = CompletableDeferred<Unit>()
        val releaseRename = CompletableDeferred<Unit>()
        coEvery { fileRepository.readFileRange(ROOT, DOC_A) } returns
            readResult(uuid = DOC_A, name = "a.md", content = "A")
        coEvery { fileRepository.readFileRange(ROOT, DOC_B) } returns
            readResult(uuid = DOC_B, name = "b.md", content = "B")
        coEvery { workspaceRepository.rename(ROOT, DOC_A, "a-renamed.md") } coAnswers {
            renameStarted.complete(Unit)
            withContext(NonCancellable) { releaseRename.await() }
        }
        val vm = viewModel()
        vm.loadDocument(ROOT, DOC_A)
        advanceUntilIdle()
        vm.updateTitle("a-renamed.md")
        vm.saveDocument()
        runCurrent()
        renameStarted.await()

        vm.loadDocument(ROOT, DOC_B)
        runCurrent()
        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_B)
        releaseRename.complete(Unit)
        advanceUntilIdle()

        vm.updateTitle("temporary.md")
        vm.updateTitle("b.md")
        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_B)
        assertThat(vm.uiState.value.titleDirty).isFalse()
    }

    @Test
    fun `旧文档正文保存晚返回不会污染新文档正文基线`() = runTest(dispatcher) {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        coEvery { fileRepository.readFileRange(ROOT, DOC_A) } returns
            readResult(uuid = DOC_A, name = "a.md", content = "A")
        coEvery { fileRepository.readFileRange(ROOT, DOC_B) } returns
            readResult(uuid = DOC_B, name = "b.md", content = "B")
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC_A, "A updated", "editor", "hash-1")
        } coAnswers {
            writeStarted.complete(Unit)
            withContext(NonCancellable) { releaseWrite.await() }
            WriteResult.Success("hash-a2")
        }
        val vm = viewModel()
        vm.loadDocument(ROOT, DOC_A)
        advanceUntilIdle()
        vm.onContentChanged("A updated")
        vm.saveDocument()
        runCurrent()
        writeStarted.await()

        vm.loadDocument(ROOT, DOC_B)
        runCurrent()
        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_B)
        releaseWrite.complete(Unit)
        advanceUntilIdle()

        vm.onContentChanged("temporary B")
        vm.onContentChanged("B")
        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_B)
        assertThat(vm.uiState.value.contentDirty).isFalse()
    }

    @Test
    fun `旧加载成功迟到不会覆盖新文档 Ready`() = runTest(dispatcher) {
        val oldLoadStarted = CompletableDeferred<Unit>()
        val releaseOldLoad = CompletableDeferred<Unit>()
        coEvery { fileRepository.readFileRange(ROOT, DOC_A) } coAnswers {
            oldLoadStarted.complete(Unit)
            withContext(NonCancellable) { releaseOldLoad.await() }
            readResult(uuid = DOC_A, name = "a.md", content = "A")
        }
        coEvery { fileRepository.readFileRange(ROOT, DOC_B) } returns
            readResult(uuid = DOC_B, name = "b.md", content = "B", hash = "hash-b")
        val vm = viewModel()

        vm.loadDocument(ROOT, DOC_A)
        runCurrent()
        oldLoadStarted.await()
        vm.loadDocument(ROOT, DOC_B)
        runCurrent()
        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_B)
        releaseOldLoad.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_B)
        assertThat(vm.uiState.value.title).isEqualTo("b.md")
        assertThat(vm.uiState.value.currentHash).isEqualTo("hash-b")
    }

    @Test
    fun `旧加载错误迟到不会覆盖新文档 Ready`() = runTest(dispatcher) {
        val oldLoadStarted = CompletableDeferred<Unit>()
        val releaseOldLoad = CompletableDeferred<Unit>()
        coEvery { fileRepository.readFileRange(ROOT, DOC_A) } coAnswers {
            oldLoadStarted.complete(Unit)
            withContext(NonCancellable) {
                releaseOldLoad.await()
                throw IllegalStateException("late A failure")
            }
        }
        coEvery { fileRepository.readFileRange(ROOT, DOC_B) } returns
            readResult(uuid = DOC_B, name = "b.md", content = "B", hash = "hash-b")
        val vm = viewModel()

        vm.loadDocument(ROOT, DOC_A)
        runCurrent()
        oldLoadStarted.await()
        vm.loadDocument(ROOT, DOC_B)
        runCurrent()
        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_B)
        releaseOldLoad.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_B)
        assertThat(vm.uiState.value.title).isEqualTo("b.md")
        assertThat(vm.uiState.value.failureCode).isNull()
    }

    @Test
    fun `同一文档保存挂起时重载被拒绝且保存完成后状态与物理结果一致`() = runTest(dispatcher) {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        coEvery { fileRepository.readFileRange(ROOT, DOC) } returnsMany listOf(
            readResult(content = "base", hash = "hash-1"),
            readResult(content = "stale reload", hash = "hash-1"),
        )
        coEvery {
            fileRepository.writeFileAtomic(ROOT, DOC, "saved", "editor", "hash-1")
        } coAnswers {
            writeStarted.complete(Unit)
            withContext(NonCancellable) { releaseWrite.await() }
            WriteResult.Success("hash-2")
        }
        val vm = viewModel()
        vm.loadDocument(ROOT, DOC)
        advanceUntilIdle()
        val loadedEpoch = vm.uiState.value.documentEpoch
        vm.onContentChanged("saved")
        vm.saveDocument()
        runCurrent()
        writeStarted.await()

        vm.reload()
        runCurrent()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Saving)
        assertThat(vm.uiState.value.content).isEqualTo("saved")
        assertThat(vm.uiState.value.documentEpoch).isEqualTo(loadedEpoch)
        coVerify(exactly = 1) { fileRepository.readFileRange(ROOT, DOC) }

        releaseWrite.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.content).isEqualTo("saved")
        assertThat(vm.uiState.value.persistedContent).isEqualTo("saved")
        assertThat(vm.uiState.value.currentHash).isEqualTo("hash-2")
        assertThat(vm.uiState.value.contentDirty).isFalse()
    }

    @Test
    fun `第一代文档保存中经其他文档再导航回来不被加载门禁吞掉`() = runTest(dispatcher) {
        val casRepository = ControlledCasFileRepository(
            workspaceRootUuid = ROOT,
            gatedDocumentId = DOC_A,
            files = mutableMapOf(
                DOC_A to StoredFile(name = "a.md", content = "A first", hash = "hash-a1"),
                DOC_B to StoredFile(name = "b.md", content = "B", hash = "hash-b"),
            ),
        )
        fileRepository = casRepository
        val vm = viewModel()
        vm.loadDocument(ROOT, DOC_A)
        advanceUntilIdle()
        vm.onContentChanged("A saved late")
        vm.saveDocument()
        runCurrent()
        casRepository.writeStarted.await()

        vm.loadDocument(ROOT, DOC_B)
        runCurrent()
        vm.loadDocument(ROOT, DOC_A)
        runCurrent()

        assertThat(casRepository.readCount(DOC_A)).isEqualTo(2)
        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_A)
        assertThat(vm.uiState.value.content).isEqualTo("A first")
        val navigationEpoch = vm.uiState.value.documentEpoch

        casRepository.releaseWrite.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_A)
        assertThat(vm.uiState.value.documentEpoch).isEqualTo(navigationEpoch)
        assertThat(vm.uiState.value.content).isEqualTo("A first")
        assertThat(vm.uiState.value.currentHash).isEqualTo("hash-a1")

        vm.reload()
        runCurrent()

        assertThat(vm.uiState.value.phase).isEqualTo(DocEditorPhase.Ready)
        assertThat(vm.uiState.value.documentId).isEqualTo(DOC_A)
        assertThat(vm.uiState.value.content).isEqualTo("A saved late")
        assertThat(vm.uiState.value.persistedContent).isEqualTo("A saved late")
        assertThat(vm.uiState.value.currentHash).isEqualTo("hash-a-save")
        assertThat(vm.uiState.value.contentDirty).isFalse()
        assertThat(casRepository.readCount(DOC_A)).isEqualTo(3)
    }

    private fun viewModel() = DocEditorViewModel(
        fileOperationRepository = fileRepository,
        workspaceRepository = workspaceRepository,
    )

    private suspend fun loadedViewModel(): DocEditorViewModel {
        coEvery { fileRepository.readFileRange(ROOT, DOC) } returns readResult()
        return viewModel().also {
            it.loadDocument(ROOT, DOC)
            dispatcher.scheduler.advanceUntilIdle()
        }
    }

    private fun readResult(
        uuid: String = DOC,
        name: String = "guide.md",
        content: String = "hello\nworld",
        hash: String = "hash-1",
    ) = ReadResult(
        uuid = uuid,
        name = name,
        totalLines = content.lines().size,
        startLine = 1,
        endLine = content.lines().size,
        content = content,
        hash = hash,
        lastModified = 123L,
    )

    private fun metadataEntry(
        uuid: String = DOC,
        name: String = "guide.md",
        sizeBytes: Long = 11L,
    ) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = ROOT,
        parentUuid = null,
        name = name,
        hash = "hash-1",
        mimeType = "text/markdown",
        sizeBytes = sizeBytes,
        physicalRootPath = "/fixture",
        materializedPath = "/$name",
        createdAt = 123L,
        updatedAt = 123L,
    )

    private data class StoredFile(
        val name: String,
        var content: String,
        var hash: String,
    )

    private class ControlledCasFileRepository(
        private val workspaceRootUuid: String,
        private val gatedDocumentId: String,
        private val files: MutableMap<String, StoredFile>,
    ) : IFileOperationRepository {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        private val readCounts = mutableMapOf<String, Int>()

        fun readCount(documentId: String): Int = readCounts[documentId] ?: 0

        override suspend fun readFileRange(
            workspaceRootUuid: String,
            uuid: String,
            startLine: Int?,
            endLine: Int?,
        ): ReadResult {
            check(workspaceRootUuid == this.workspaceRootUuid)
            val file = files[uuid] ?: throw NoSuchElementException(uuid)
            readCounts[uuid] = readCount(uuid) + 1
            return ReadResult(
                uuid = uuid,
                name = file.name,
                totalLines = file.content.lines().size,
                startLine = 1,
                endLine = file.content.lines().size,
                content = file.content,
                hash = file.hash,
                lastModified = 123L,
            )
        }

        override suspend fun writeFileAtomic(
            workspaceRootUuid: String,
            uuid: String,
            newContent: String,
            sessionId: String,
            expectedHash: String,
        ): WriteResult {
            check(workspaceRootUuid == this.workspaceRootUuid)
            val file = files[uuid] ?: return WriteResult.NotFound
            if (file.hash != expectedHash) {
                return WriteResult.Conflict(file.hash, expectedHash, "hash mismatch")
            }
            if (uuid == gatedDocumentId) {
                writeStarted.complete(Unit)
                withContext(NonCancellable) { releaseWrite.await() }
            }
            if (file.hash != expectedHash) {
                return WriteResult.Conflict(file.hash, expectedHash, "hash mismatch")
            }
            file.content = newContent
            file.hash = "hash-a-save"
            return WriteResult.Success(file.hash)
        }

        override suspend fun diffFile(
            workspaceRootUuid: String,
            uuid: String,
            basisHash: String?,
        ): DiffResult = error("本测试不使用 diffFile")

        override suspend fun patchFile(
            workspaceRootUuid: String,
            uuid: String,
            operations: List<PatchOperation>,
            expectedHash: String,
        ): PatchResult = error("本测试不使用 patchFile")
    }

    companion object {
        private const val ROOT = "root"
        private const val DOC = "doc"
        private const val DOC_A = "doc-a"
        private const val DOC_B = "doc-b"
        private const val ONE_MIB = 1_048_576L
    }
}
