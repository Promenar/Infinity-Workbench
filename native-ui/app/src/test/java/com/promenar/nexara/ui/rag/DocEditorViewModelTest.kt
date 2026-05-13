package com.promenar.nexara.ui.rag

import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.repository.IDocumentRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocEditorViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repo: IDocumentRepository

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = mockk(relaxed = true)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createDocument(
        id: String = "doc-1",
        title: String = "Test Doc",
        content: String = "content"
    ) = Document(
        id = id,
        folderId = "f1",
        title = title,
        content = content,
        createdAt = 1000L,
        updatedAt = 2000L
    )

    @Test
    fun `loadDocument sets document state`() = runTest {
        val doc = createDocument()
        coEvery { repo.getById("doc-1") } returns doc

        val vm = DocEditorViewModel(repo)
        vm.loadDocument("doc-1")
        advanceUntilIdle()

        assertThat(vm.document.value).isNotNull()
        assertThat(vm.document.value?.id).isEqualTo("doc-1")
    }

    @Test
    fun `loadDocument sets content via generateMockContent`() = runTest {
        val doc = createDocument(title = "My Doc")
        coEvery { repo.getById("doc-1") } returns doc

        val vm = DocEditorViewModel(repo)
        vm.loadDocument("doc-1")
        advanceUntilIdle()

        assertThat(vm.content.value).contains("# My Doc")
    }

    @Test
    fun `loadDocument detects large content as large file`() = runTest {
        val largeContent = "x".repeat(11 * 1024 * 1024) // > 10MB
        val doc = createDocument(content = largeContent)
        coEvery { repo.getById("doc-1") } returns doc

        val vm = DocEditorViewModel(repo)
        vm.loadDocument("doc-1")
        advanceUntilIdle()

        assertThat(vm.isLargeFile.value).isTrue()
    }

    @Test
    fun `loadDocument does not flag small content as large`() = runTest {
        val doc = createDocument(content = "small")
        coEvery { repo.getById("doc-1") } returns doc

        val vm = DocEditorViewModel(repo)
        vm.loadDocument("doc-1")
        advanceUntilIdle()

        assertThat(vm.isLargeFile.value).isFalse()
    }

    @Test
    fun `loadDocument handles null result gracefully`() = runTest {
        coEvery { repo.getById("missing") } returns null

        val vm = DocEditorViewModel(repo)
        vm.loadDocument("missing")
        advanceUntilIdle()

        assertThat(vm.document.value).isNull()
        assertThat(vm.content.value).isEmpty()
    }

    @Test
    fun `saveDocument calls repository update`() = runTest {
        val doc = createDocument(id = "doc-save")
        coEvery { repo.getById("doc-save") } returns doc

        val vm = DocEditorViewModel(repo)
        vm.loadDocument("doc-save")
        advanceUntilIdle()

        vm.onContentChanged("new content")
        vm.saveDocument()
        advanceUntilIdle()

        coVerify { repo.update("doc-save", "new content") }
    }

    @Test
    fun `saveDocument clears dirty flag`() = runTest {
        val doc = createDocument()
        coEvery { repo.getById("doc-1") } returns doc

        val vm = DocEditorViewModel(repo)
        vm.loadDocument("doc-1")
        advanceUntilIdle()

        vm.onContentChanged("changed")
        assertThat(vm.isDirty.value).isTrue()

        vm.saveDocument()
        advanceUntilIdle()

        assertThat(vm.isDirty.value).isFalse()
    }

    @Test
    fun `saveDocument does nothing when no document loaded`() = runTest {
        coEvery { repo.getById(any()) } returns null

        val vm = DocEditorViewModel(repo)
        vm.saveDocument()
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.update(any(), any()) }
    }

    @Test
    fun `onContentChanged marks dirty when content differs`() = runTest {
        val doc = createDocument()
        coEvery { repo.getById("doc-1") } returns doc

        val vm = DocEditorViewModel(repo)
        vm.loadDocument("doc-1")
        advanceUntilIdle()

        vm.onContentChanged("different content")
        assertThat(vm.isDirty.value).isTrue()
    }

    @Test
    fun `onContentChanged does not mark dirty when content same`() = runTest {
        val doc = createDocument(title = "Test")
        coEvery { repo.getById("doc-1") } returns doc

        val vm = DocEditorViewModel(repo)
        vm.loadDocument("doc-1")
        advanceUntilIdle()

        val currentContent = vm.content.value
        vm.onContentChanged(currentContent)
        assertThat(vm.isDirty.value).isFalse()
    }

    @Test
    fun `toggleEditMode toggles state`() {
        val vm = DocEditorViewModel(repo)

        assertThat(vm.isEditing.value).isFalse()
        vm.toggleEditMode()
        assertThat(vm.isEditing.value).isTrue()
        vm.toggleEditMode()
        assertThat(vm.isEditing.value).isFalse()
    }

    @Test
    fun `dismissWarning sets flag`() {
        val vm = DocEditorViewModel(repo)

        assertThat(vm.warningDismissed.value).isFalse()
        vm.dismissWarning()
        assertThat(vm.warningDismissed.value).isTrue()
    }

    @Test
    fun `updateTitle updates document and marks dirty`() = runTest {
        val doc = createDocument(title = "Old Title")
        coEvery { repo.getById("doc-1") } returns doc

        val vm = DocEditorViewModel(repo)
        vm.loadDocument("doc-1")
        advanceUntilIdle()

        vm.updateTitle("New Title")

        assertThat(vm.document.value?.title).isEqualTo("New Title")
        assertThat(vm.isDirty.value).isTrue()
    }
}
