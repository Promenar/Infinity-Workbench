package com.promenar.nexara.ui.chat.components

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.storage.JvmBoundRealPathFileSystem
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderEntry
import com.promenar.nexara.domain.storage.ExternalFolderException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 资源管理器文件区按会话绑定的工作区（私有 / 目录 / 不可用）加载。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ResourceExplorerWorkspaceModeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `private workspace keeps room observers and session recycle bin`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val files = listOf(file("a.txt"))
        coEvery { repo.ensureSessionRoot("s") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(files)
        every { repo.observeRecycleBin("root") } returns flowOf(listOf(file("old.txt")))
        val access = FakeAccess(SessionWorkspace.Private)
        val viewModel = viewModel(repo, access)

        viewModel.loadSession("s")
        advanceUntilIdle()

        assertThat(viewModel.workspaceMode.value).isEqualTo(ResourceExplorerWorkspaceMode.Private)
        assertThat(viewModel.workspaceRootUuid.value).isEqualTo("root")
        assertThat(viewModel.rootFiles.value).isEqualTo(files)
        assertThat(viewModel.recycleBinCount.value).isEqualTo(1)
        assertThat(viewModel.directory.value).isEqualTo(DirectoryBrowserState())
        assertThat(access.backendRequests).isEqualTo(0)
    }

    @Test
    fun `directory workspace lists bound folder through validated backend without private root`() = runTest(dispatcher) {
        val folder = temporaryFolder.newFolder("p4-accept").canonicalFile
        File(folder, "notes.md").writeText("# 笔记\n内容")
        File(folder, "src").mkdir()
        File(folder, "src/main.kt").writeText("fun main() {}")
        File(folder, ".nexara-write-tmp").writeText("internal")
        val outside = temporaryFolder.newFolder("outside").canonicalFile
        File(outside, "secret.txt").writeText("outside")
        Files.createSymbolicLink(File(folder, "link").toPath(), outside.toPath())
        val repo = mockk<IWorkspaceRepository>(relaxed = true)
        val access = FakeAccess(directory("ws1", "p4-accept", folder)) { JvmBoundRealPathFileSystem().backend(it.root) }
        val viewModel = viewModel(repo, access)

        viewModel.loadSession("s")
        settle { !viewModel.directory.value.isLoading && viewModel.directory.value.entries.isNotEmpty() }

        assertThat(viewModel.workspaceMode.value).isEqualTo(
            ResourceExplorerWorkspaceMode.Directory("ws1", "p4-accept", WorkspaceEntity.KIND_PROJECT, location = "p4-accept"),
        )
        // 符号链接与内部临时文件不出现在列表中，与 Agent 文件工具使用同一目录后端规则。
        assertThat(viewModel.directory.value.entries.map(ExternalFolderEntry::name)).containsExactly("src", "notes.md").inOrder()
        assertThat(viewModel.workspaceRootUuid.value).isNull()
        assertThat(viewModel.rootFiles.value).isEmpty()
        coVerify(exactly = 0) { repo.ensureSessionRoot(any()) }

        viewModel.openDirectory("/src")
        settle { viewModel.directory.value.path == "/src" && !viewModel.directory.value.isLoading }
        assertThat(viewModel.directory.value.entries.map(ExternalFolderEntry::path)).containsExactly("/src/main.kt")

        viewModel.openDirectory("/")
        settle { viewModel.directory.value.path == "/" && !viewModel.directory.value.isLoading }
        val notes = viewModel.directory.value.entries.single { it.name == "notes.md" }
        viewModel.openDirectoryFile(notes)
        settle { viewModel.directory.value.preview !is ExternalTextPreview.Loading }
        assertThat(viewModel.directory.value.selectedFile).isEqualTo(notes)
        assertThat((viewModel.directory.value.preview as ExternalTextPreview.Text).content).isEqualTo("# 笔记\n内容")

        val escaped = runCatching { viewModel.readDirectoryFile("/link/secret.txt", 1024) }.exceptionOrNull()
        assertThat((escaped as ExternalFolderException).code).isEqualTo("INVALID_PATH")
        viewModel.openDirectory("/link")
        settle { viewModel.directory.value.path == "/link" && !viewModel.directory.value.isLoading }
        assertThat(viewModel.directory.value.entries).isEmpty()
        assertThat(viewModel.directory.value.error?.reason).isNotNull()
    }

    @Test
    fun `unavailable workspace exposes reason and never falls back to private workspace`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>(relaxed = true)
        val workspace = WorkspaceEntity("ws1", WorkspaceEntity.KIND_SHARED, "Docs", "/storage/emulated/0/Docs", 1)
        val access = FakeAccess(SessionWorkspace.Unavailable("ws1", workspace, "需要“所有文件访问”授权"))
        val viewModel = viewModel(repo, access)

        viewModel.loadSession("s")
        advanceUntilIdle()

        assertThat(viewModel.workspaceMode.value).isEqualTo(
            ResourceExplorerWorkspaceMode.Unavailable(
                "ws1", "Docs", "需要“所有文件访问”授权",
                kind = WorkspaceEntity.KIND_SHARED, location = "/storage/emulated/0/Docs",
            ),
        )
        assertThat(viewModel.isLoading.value).isFalse()
        assertThat(viewModel.loadError.value).isNull()
        assertThat(viewModel.workspaceRootUuid.value).isNull()
        assertThat(viewModel.directory.value.entries).isEmpty()
        assertThat(access.backendRequests).isEqualTo(0)
        coVerify(exactly = 0) { repo.ensureSessionRoot(any()) }
        coVerify(exactly = 0) { repo.observeChildren(any(), any()) }
    }

    @Test
    fun `rebinding private session to directory stops private observers on reopen`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val files = MutableSharedFlow<List<FileEntry>>()
        val recycle = MutableSharedFlow<List<FileEntry>>()
        coEvery { repo.ensureSessionRoot("s") } returns root("root")
        every { repo.observeChildren("root", "root") } returns files
        every { repo.observeRecycleBin("root") } returns recycle
        val backend = FakeBackend(mapOf("/" to listOf(entry("/notes.md"))))
        val access = FakeAccess(SessionWorkspace.Private) { backend }
        val viewModel = viewModel(repo, access)

        viewModel.loadSession("s")
        advanceUntilIdle()
        assertThat(files.subscriptionCount.value).isEqualTo(1)

        access.workspace = directory("ws1", "p4-accept", temporaryFolder.newFolder())
        viewModel.deactivateSession()
        viewModel.loadSession("s")
        advanceUntilIdle()

        assertThat(files.subscriptionCount.value).isEqualTo(0)
        assertThat(recycle.subscriptionCount.value).isEqualTo(0)
        assertThat(viewModel.workspaceRootUuid.value).isNull()
        assertThat(viewModel.recycleBinCount.value).isEqualTo(0)
        assertThat(viewModel.workspaceMode.value).isInstanceOf(ResourceExplorerWorkspaceMode.Directory::class.java)
        assertThat(viewModel.directory.value.entries.map(ExternalFolderEntry::name)).containsExactly("notes.md")
    }

    @Test
    fun `directory that becomes unavailable while browsing switches to unavailable state`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>(relaxed = true)
        val backend = FakeBackend(mapOf("/" to listOf(entry("/src", isDirectory = true)), "/src" to emptyList()))
        val access = FakeAccess(directory("ws1", "p4-accept", temporaryFolder.newFolder())) { backend }
        val viewModel = viewModel(repo, access)
        viewModel.loadSession("s")
        advanceUntilIdle()
        assertThat(viewModel.directory.value.entries).hasSize(1)

        access.workspace = SessionWorkspace.Unavailable("ws1", null, "项目文件夹不存在")
        viewModel.openDirectory("/src")
        advanceUntilIdle()

        assertThat(viewModel.workspaceMode.value).isEqualTo(
            ResourceExplorerWorkspaceMode.Unavailable("ws1", null, "项目文件夹不存在"),
        )
        assertThat(viewModel.directory.value).isEqualTo(DirectoryBrowserState())
        assertThat(backend.listed).containsExactly("/")
        coVerify(exactly = 0) { repo.ensureSessionRoot(any()) }
    }

    @Test
    fun `resolution failure reports load error without showing any workspace`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>(relaxed = true)
        val access = FakeAccess(SessionWorkspace.Private).apply { failure = IllegalStateException("db closed") }
        val viewModel = viewModel(repo, access)

        viewModel.loadSession("s")
        advanceUntilIdle()

        assertThat(viewModel.workspaceMode.value).isEqualTo(ResourceExplorerWorkspaceMode.Resolving)
        assertThat(viewModel.loadError.value?.code).isEqualTo(ResourceExplorerNotice.CODE_WORKSPACE_LOAD_FAILED)
        assertThat(viewModel.isLoading.value).isFalse()
        coVerify(exactly = 0) { repo.ensureSessionRoot(any()) }

        access.failure = null
        viewModel.retryLoadSession()
        advanceUntilIdle()
        assertThat(viewModel.workspaceMode.value).isEqualTo(ResourceExplorerWorkspaceMode.Private)
        coVerify(exactly = 1) { repo.ensureSessionRoot("s") }
    }

    @Test
    fun `parent directory path stops at workspace root`() {
        assertThat(parentDirectoryPath("/")).isNull()
        assertThat(parentDirectoryPath("/src")).isEqualTo("/")
        assertThat(parentDirectoryPath("/src/main")).isEqualTo("/src")
    }

    private fun TestScope.viewModel(repo: IWorkspaceRepository, access: ResourceExplorerWorkspaceAccess) =
        ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            injectedWorkspaceRepository = repo,
            injectedImporter = mockk(relaxed = true),
            injectedWorkspaceAccess = access,
            io = dispatcher,
        )

    /** 真实目录后端在 Dispatchers.IO 上执行，需等待其回到测试调度器。 */
    private fun TestScope.settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            advanceUntilIdle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "等待目录状态超时" }
            Thread.sleep(10)
        }
    }

    private class FakeAccess(
        var workspace: SessionWorkspace,
        private val backendFor: (SessionWorkspace.Directory) -> ExternalFolderBackend = { error("未提供目录后端") },
    ) : ResourceExplorerWorkspaceAccess {
        var failure: Exception? = null
        var backendRequests = 0
        override suspend fun resolve(sessionId: String): SessionWorkspace {
            failure?.let { throw it }
            return workspace
        }
        override fun backend(directory: SessionWorkspace.Directory): ExternalFolderBackend {
            backendRequests++
            return backendFor(directory)
        }
    }

    private class FakeBackend(private val children: Map<String, List<ExternalFolderEntry>>) : ExternalFolderBackend {
        val listed = mutableListOf<String>()
        override suspend fun stat(path: String): ExternalFolderEntry? = null
        override suspend fun list(path: String): List<ExternalFolderEntry> {
            listed += path
            return children[path] ?: throw ExternalFolderException("NOT_FOUND", "目录不存在")
        }
        override suspend fun read(path: String, maxBytes: Long): ByteArray = ByteArray(0)
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) =
            error("只读测试")
        override suspend fun createDirectory(path: String) = error("只读测试")
        override suspend fun delete(path: String, expectedSha256: String?) = error("只读测试")
    }

    private fun directory(id: String, name: String, root: File) = SessionWorkspace.Directory(
        WorkspaceEntity(id, WorkspaceEntity.KIND_PROJECT, name, name, 1),
        root,
    )

    private fun entry(path: String, isDirectory: Boolean = false) =
        ExternalFolderEntry(path, path.substringAfterLast('/'), isDirectory, 0, 0)

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

    private fun file(name: String) = FileEntry(
        uuid = name,
        workspaceRootUuid = "root",
        parentUuid = "root",
        name = name,
        hash = "",
        isDirectory = false,
        physicalRootPath = "/tmp/root",
        materializedPath = "/$name",
        createdAt = 1,
        updatedAt = 1,
    )
}
