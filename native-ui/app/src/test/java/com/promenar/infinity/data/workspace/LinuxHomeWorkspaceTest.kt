package com.promenar.infinity.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.dao.WorkspaceDao
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.linux.UnavailableLinuxRuntime
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.storage.ExternalFolderException
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxHomeWorkspaceTest {
    private val base = Files.createTempDirectory("linux-workspace").toFile().canonicalFile
    private val rootfs = File(base, "alpine").apply { mkdir() }
    private val home = File(rootfs, "root").apply { mkdir() }
    private val state = MutableStateFlow<LinuxRuntimeState>(LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "test", "arm64", "test", 1, null)))
    private val runtime = object : LinuxRuntime by UnavailableLinuxRuntime(LinuxUnavailableReason.DISTRIBUTION, LinuxPaths(rootfs, home)) {
        override val state = this@LinuxHomeWorkspaceTest.state
    }
    private val access = LinuxHomeWorkspaceSource(runtime, com.promenar.infinity.data.linux.LinuxFileIo())
    private val records = mutableMapOf<String, WorkspaceEntity>()
    private val dao = mockk<WorkspaceDao> {
        coEvery { register(any()) } answers {
            val candidate = firstArg<WorkspaceEntity>()
            records.values.firstOrNull { it.kind == candidate.kind && it.location == candidate.location }
                ?: candidate.also { records[it.id] = it }
        }
        coEvery { getById(any()) } answers { records[firstArg()] }
    }
    @After fun cleanup() { com.promenar.infinity.data.linux.deleteLinuxFixture(base) }

    @Test fun `主目录登记幂等并按目录工作区解析到真实宿主路径`() = runBlocking<Unit> {
        val browser = WorkspaceDirectoryBrowser(File(base, "projects"), mockk(), dao, linuxHome = access)
        assertThat(browser.linuxHomeAvailability()).isEqualTo(LinuxHomeAvailability.READY)
        val record = browser.registerLinuxHome()
        assertThat(browser.registerLinuxHome()).isEqualTo(record)
        assertThat(record.kind).isEqualTo(WorkspaceEntity.KIND_LINUX_HOME)
        assertThat(record.location).startsWith("/root#")
        val sessions = mockk<SessionDao> {
            coEvery { getById("s") } returns SessionEntity("s", "a", workspaceId = record.id, createdAt = 1, updatedAt = 1)
        }
        val resolver = SessionWorkspaceResolver(sessions, dao, File(base, "projects"), mockk(), linuxHome = access)
        val result = resolver.resolve("s") as SessionWorkspace.Directory
        assertThat(result.root).isEqualTo(home)
        assertThat(result.kind).isEqualTo(WorkspaceEntity.KIND_LINUX_HOME)
        assertThat(SessionWorkspaceResolver.kindOf(result)).isEqualTo(WorkspaceEntity.KIND_PROJECT)
        state.value = LinuxRuntimeState.Maintaining(LinuxMaintenance.RESETTING)
        assertThat(resolver.resolve("s")).isInstanceOf(SessionWorkspace.Unavailable::class.java)
        state.value = LinuxRuntimeState.NotInstalled(100)
        assertThat(resolver.resolve("s")).isInstanceOf(SessionWorkspace.Unavailable::class.java)
        home.deleteRecursively()
        home.mkdir()
        state.value = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "test", "arm64", "test", 1, null))
        assertThat(resolver.resolve("s")).isInstanceOf(SessionWorkspace.Unavailable::class.java)
        val fresh = browser.registerLinuxHome()
        assertThat(fresh.id).isNotEqualTo(record.id)
        assertThat(resolver.resolve("s")).isInstanceOf(SessionWorkspace.Unavailable::class.java)
        assertThat(resolver.resolveWorkspace(fresh.id)).isInstanceOf(SessionWorkspace.Directory::class.java)
    }

    @Test fun `非主目录沿用环境身份且返回选中的宿主目录`() {
        val project = File(rootfs, "opt/app").apply { mkdirs() }
        val location = "/opt/app#" + access.location().substringAfter('#')
        assertThat(WorkspaceEntity.isValidLocation(WorkspaceEntity.KIND_LINUX_HOME, location)).isTrue()
        assertThat(access.root(location)).isEqualTo(project)
    }

    @Test fun `浏览显示隐藏目录跳过链接并按小写排序登记不写选中目录`() = runBlocking<Unit> {
        val browser = WorkspaceDirectoryBrowser(File(base, "projects"), mockk(), dao, linuxHome = access)
        File(rootfs, "home").mkdir()
        File(rootfs, "srv").mkdir()
        val opt = File(rootfs, "opt").apply { mkdir() }
        listOf("z", "Alpha", ".hidden").forEach { File(opt, it).mkdir() }
        File(opt, "file").writeText("普通文件")
        Files.createSymbolicLink(File(opt, "link").toPath(), home.toPath())
        assertThat(browser.linuxRoots().map { it.path.path }).containsExactly("/root", "/home", "/opt", "/srv").inOrder()
        assertThat(browser.listLinux("/opt").map { it.name }).containsExactly(".hidden", "Alpha", "z").inOrder()
        val record = browser.registerLinux("/opt/Alpha")
        assertThat(record.displayName).isEqualTo("Alpha")
        assertThat(browser.registerLinux("/opt/Alpha", "另一个显示名")).isEqualTo(record)
        assertThat(record.location.substringAfter('#')).isEqualTo(access.location().substringAfter('#'))
        assertThat(File(opt, "Alpha").list()).isEmpty()
        val created = browser.createLinuxFolder("/opt", "new") as CreateFolderResult.Created
        assertThat(created.folder.path).isEqualTo(File("/opt/new"))
        assertThat(browser.createLinuxFolder("/opt", "new")).isEqualTo(CreateFolderResult.Rejected(FolderNameError.EXISTS))
        assertThat(browser.createLinuxFolder("/opt", "../etc")).isEqualTo(CreateFolderResult.Rejected(FolderNameError.INVALID))
    }

    @Test fun `浏览登记与新建拒绝系统绑定点缺失目录及祖先链接`() = runBlocking<Unit> {
        val browser = WorkspaceDirectoryBrowser(File(base, "projects"), mockk(), dao, linuxHome = access)
        File(rootfs, "home/session").mkdirs()
        val opt = File(rootfs, "opt").apply { mkdir() }
        Files.createSymbolicLink(File(opt, "alias").toPath(), home.toPath())
        listOf("/etc", "/", "/workspace", "/home/session", "/home/session/data", "/opt/missing", "/opt/alias", "/opt/alias/child", "/opt/../root").forEach { path ->
            assertThrows(ExternalFolderException::class.java) { runBlocking { browser.listLinux(path) } }
            assertThrows(ExternalFolderException::class.java) { runBlocking { browser.registerLinux(path) } }
            assertThrows(ExternalFolderException::class.java) { runBlocking { browser.createLinuxFolder(path, "child") } }
        }
        assertThat(records).isEmpty()
        assertThat(File(home, ".infinity-linux-workspace-id").exists()).isFalse()
        val record = browser.registerLinux("/opt")
        File(home, ".infinity-linux-workspace-id").delete()
        browser.registerLinuxHome()
        assertThrows(ExternalFolderException::class.java) { access.root(record.location) }
    }

    @Test fun `未安装缺目录与伪造主目录均不可用且不会创建目录`() = runBlocking<Unit> {
        state.value = LinuxRuntimeState.NotInstalled(10)
        assertThat(access.availability()).isEqualTo(LinuxHomeAvailability.NOT_READY)
        assertThrows(ExternalFolderException::class.java) { access.root() }
        state.value = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "test", "arm64", "test", 1, null))
        home.delete()
        assertThat(access.availability()).isEqualTo(LinuxHomeAvailability.NOT_READY)
        val outside = File(base, "outside").apply { mkdir() }
        Files.createSymbolicLink(home.toPath(), outside.toPath())
        assertThat(access.availability()).isEqualTo(LinuxHomeAvailability.NOT_READY)
        assertThat(WorkspaceEntity.isValidLocation(WorkspaceEntity.KIND_LINUX_HOME, "/root/../etc")).isFalse()
        assertThat(WorkspaceEntity.isValidLocation("future_kind", "/root")).isFalse()
        state.value = LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION)
        assertThat(access.availability()).isEqualTo(LinuxHomeAvailability.UNSUPPORTED)
    }
}
