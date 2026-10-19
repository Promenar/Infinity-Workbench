package com.promenar.nexara.data.workspace

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.domain.storage.ExternalFolderException
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 目录浏览数据层：项目名校验、新建项目、共享卷只列目录并跳过符号链接与卷顶层 Android、登记幂等。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceDirectoryBrowserTest {
    private lateinit var db: NexaraDatabase
    private val base = Files.createTempDirectory("workspace-browser").toFile()
    private val projects = File(base, "files/projects")
    private val volume = File(base, "storage/emulated/0").apply { mkdirs() }
    private val shared = FakeVolumes(listOf(volume))
    private var nextId = 0
    private lateinit var browser: WorkspaceDirectoryBrowser

    private class FakeVolumes(val volumes: List<File>) : SharedStorageVolumes {
        override var supported = true
        var permission = true
        val normalized = mutableListOf<String>()
        override fun hasPermission() = permission
        override fun roots() = volumes
        override fun normalize(path: String): String {
            normalized += path
            if (path.split('/').any { it.equals("Android", ignoreCase = true) }) {
                throw ExternalFolderException("INVALID_PATH", "只允许共享存储卷内的普通目录")
            }
            return File(path).absoluteFile.toPath().normalize().toString()
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        browser = WorkspaceDirectoryBrowser(projects, shared, db.workspaceDao(), idFactory = { "ws-${++nextId}" }, clock = { 42 }, io = Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    @Test
    fun `项目名只接受一级目录名格式并拒绝保留名`() {
        assertThat(WorkspaceDirectoryBrowser.validateProjectName("my-app_1.0")).isNull()
        assertThat(WorkspaceDirectoryBrowser.validateProjectName("")).isEqualTo(FolderNameError.EMPTY)
        listOf("中文", "a b", "a/b", "..", ".", "x".repeat(129), "a\\b").forEach {
            assertThat(WorkspaceDirectoryBrowser.validateProjectName(it)).isEqualTo(FolderNameError.INVALID)
        }
        assertThat(WorkspaceDirectoryBrowser.validateProjectName(".nexara-cache")).isEqualTo(FolderNameError.RESERVED)
    }

    @Test
    fun `新建项目在项目根下创建空目录并拒绝非法与重名`() = runBlocking<Unit> {
        assertThat(browser.listProjects()).isEmpty()
        val created = browser.createProject("  demo  ") as CreateFolderResult.Created
        assertThat(created.folder.name).isEqualTo("demo")
        assertThat(File(projects, "demo").isDirectory).isTrue()
        assertThat(File(projects, "demo").list()).isEmpty()
        assertThat(browser.createProject("demo")).isEqualTo(CreateFolderResult.Rejected(FolderNameError.EXISTS))
        assertThat(browser.createProject("坏 名")).isEqualTo(CreateFolderResult.Rejected(FolderNameError.INVALID))
        assertThat(browser.createProject("   ")).isEqualTo(CreateFolderResult.Rejected(FolderNameError.EMPTY))
        assertThat(File(projects, "坏 名").exists()).isFalse()
    }

    @Test
    fun `项目列表只列一级目录，跳过文件、符号链接与非法名`() = runBlocking<Unit> {
        File(projects, "beta").mkdirs()
        File(projects, "Alpha").mkdirs()
        File(projects, "中文项目").mkdirs()
        File(projects, "notes.txt").writeText("x")
        Files.createSymbolicLink(File(projects, "linked").toPath(), File(projects, "beta").toPath())
        assertThat(browser.listProjects().map { it.name }).containsExactly("Alpha", "beta").inOrder()
    }

    @Test
    fun `项目登记幂等且显示名默认取目录名`() = runBlocking<Unit> {
        File(projects, "demo").mkdirs()
        val first = browser.registerProject("demo")
        val second = browser.registerProject("demo")
        assertThat(second).isEqualTo(first)
        assertThat(first).isEqualTo(WorkspaceEntity("ws-1", WorkspaceEntity.KIND_PROJECT, "demo", "demo", 42))
        assertThat(db.workspaceDao().getById("ws-2")).isNull()
        assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking { browser.registerProject("missing") } }.code)
            .isEqualTo("NOT_FOUND")
    }

    @Test
    fun `共享卷只列目录，跳过符号链接、保留名与卷顶层 Android`() = runBlocking<Unit> {
        File(volume, "Android/data").mkdirs()
        File(volume, "android").mkdirs()
        File(volume, "Code/repo/Android").mkdirs()
        File(volume, "Documents").mkdirs()
        File(volume, ".nexara-meta").mkdirs()
        File(volume, "readme.md").writeText("x")
        Files.createSymbolicLink(File(volume, "Shortcut").toPath(), File(volume, "Documents").toPath())

        assertThat(browser.listShared(volume).map { it.name }).containsExactly("Code", "Documents").inOrder()
        // 只有卷顶层的 Android 被跳过；更深层的同名目录照常列出，登记时由真实路径规则决定能否使用。
        assertThat(browser.listShared(File(volume, "Code/repo")).map { it.name }).containsExactly("Android")
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { browser.listShared(File(volume, "Shortcut")) }
        }.code).isEqualTo("INVALID_PATH")
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { browser.listShared(File(volume, "Android")) }
        }.code).isEqualTo("INVALID_PATH")
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { browser.listShared(base) }
        }.code).isEqualTo("INVALID_PATH")
    }

    @Test
    fun `共享目录可新建子文件夹，卷顶层拒绝 Android 与保留名`() = runBlocking<Unit> {
        val created = browser.createSharedFolder(volume, "Projects") as CreateFolderResult.Created
        assertThat(created.folder.path).isEqualTo(File(volume, "Projects"))
        assertThat(File(volume, "Projects").isDirectory).isTrue()
        assertThat(browser.createSharedFolder(volume, "Projects")).isEqualTo(CreateFolderResult.Rejected(FolderNameError.EXISTS))
        assertThat(browser.createSharedFolder(volume, "ANDROID")).isEqualTo(CreateFolderResult.Rejected(FolderNameError.RESERVED))
        assertThat(browser.createSharedFolder(volume, ".nexara")).isEqualTo(CreateFolderResult.Rejected(FolderNameError.INVALID))
        assertThat(browser.createSharedFolder(volume, "a/b")).isEqualTo(CreateFolderResult.Rejected(FolderNameError.INVALID))
        assertThat(browser.createSharedFolder(File(volume, "Projects"), "app")).isInstanceOf(CreateFolderResult.Created::class.java)
    }

    @Test
    fun `共享目录登记经规范化且幂等，未授权时拒绝浏览与登记`() = runBlocking<Unit> {
        val repo = File(volume, "Code/repo").apply { mkdirs() }
        val first = browser.registerShared(repo)
        assertThat(first.kind).isEqualTo(WorkspaceEntity.KIND_SHARED)
        assertThat(first.displayName).isEqualTo("repo")
        assertThat(first.location).isEqualTo(repo.toPath().normalize().toString())
        assertThat(browser.registerShared(repo)).isEqualTo(first)
        assertThat(shared.normalized).hasSize(2)

        shared.permission = false
        assertThat(browser.hasSharedPermission()).isFalse()
        assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking { browser.listShared(volume) } }.code)
            .isEqualTo("UNAVAILABLE")
        assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking { browser.registerShared(repo) } }.code)
            .isEqualTo("UNAVAILABLE")
    }

    @Test
    fun `渠道不支持共享存储时没有共享根且拒绝共享操作`() = runBlocking<Unit> {
        shared.supported = false
        assertThat(browser.sharedSupported).isFalse()
        assertThat(browser.sharedRoots()).isEmpty()
        assertThat(browser.hasSharedPermission()).isFalse()
        assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking { browser.listShared(volume) } }.code)
            .isEqualTo("UNSUPPORTED")
        assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking { browser.registerShared(volume) } }.code)
            .isEqualTo("UNSUPPORTED")
        // 项目文件夹不受渠道限制。
        assertThat(browser.createProject("demo")).isInstanceOf(CreateFolderResult.Created::class.java)
    }
}
