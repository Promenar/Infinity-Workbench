package com.promenar.nexara.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.dao.WorkspaceDao
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.linux.UnavailableLinuxRuntime
import com.promenar.nexara.domain.linux.*
import com.promenar.nexara.domain.storage.ExternalFolderException
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
    private val access = LinuxHomeWorkspaceSource(runtime, com.promenar.nexara.data.linux.LinuxFileIo())
    private val records = mutableMapOf<String, WorkspaceEntity>()
    private val dao = mockk<WorkspaceDao> {
        coEvery { register(any()) } answers {
            val candidate = firstArg<WorkspaceEntity>()
            records.values.firstOrNull { it.kind == candidate.kind && it.location == candidate.location }
                ?: candidate.also { records[it.id] = it }
        }
        coEvery { getById(any()) } answers { records[firstArg()] }
    }
    @After fun cleanup() { com.promenar.nexara.data.linux.deleteLinuxFixture(base) }

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
