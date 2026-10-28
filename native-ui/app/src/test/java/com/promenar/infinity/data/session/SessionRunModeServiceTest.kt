package com.promenar.infinity.data.session

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.repository.SessionRepository
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.model.SessionRunMode
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class SessionRunModeServiceTest {
    private lateinit var db: InfinityDatabase
    private lateinit var runtime: ModeTestRuntime
    private val changed = mutableListOf<Pair<String, SessionRunMode>>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        runtime = ModeTestRuntime()
    }

    @After fun tearDown() = db.close()

    private fun service(supported: Boolean = true) = SessionRunModeService(
        db.sessionDao(), db.workspaceDao(), runtime, supported,
        onModeChanged = { id, mode -> changed += id to mode },
    )

    private suspend fun seed(id: String = "s", mode: String? = null, workspaceId: String? = null) {
        db.sessionDao().insert(SessionEntity(id, "agent", runMode = mode, workspaceId = workspaceId,
            createdAt = 1, updatedAt = 1))
    }

    @Test fun `未知会话与旧空字段不会默认为内置`() = runBlocking<Unit> {
        seed()
        val service = service()
        assertThat(service.modeFor("missing")).isNull()
        assertThat(service.modeFor("s")).isNull()
        assertThat(service.check("missing")).isEqualTo(SessionRunModeCheck.SessionMissing)
        assertThat(service.check("s")).isInstanceOf(SessionRunModeCheck.SelectionRequired::class.java)
        assertThat(db.sessionDao().getById("s")!!.runMode).isNull()
    }

    @Test fun `旧会话在侧载就绪时固定Linux且忽略新会话默认偏好`() = runBlocking<Unit> {
        seed()
        runtime.state.value = READY
        runtime.agentEnabled.value = false
        val result = service().check("s")
        assertThat(result).isEqualTo(SessionRunModeCheck.Available(SessionRunMode.LINUX))
        assertThat(db.sessionDao().getById("s")!!.runMode).isEqualTo("LINUX")
        assertThat(changed).containsExactly("s" to SessionRunMode.LINUX)
    }

    @Test fun `已固定Linux在环境失效后保留模式并暴露有序处理动作`() = runBlocking<Unit> {
        seed(mode = "LINUX")
        val service = service()
        val result = service.check("s") as SessionRunModeCheck.EnvironmentUnavailable
        assertThat(result.actions).containsExactly(SessionRunModeAction.REPAIR, SessionRunModeAction.RESET,
            SessionRunModeAction.EXPORT, SessionRunModeAction.SWITCH_BUILTIN).inOrder()
        assertThat(service.modeFor("s")).isEqualTo(SessionRunMode.LINUX)
        assertThat(db.sessionDao().getById("s")!!.runMode).isEqualTo("LINUX")
        assertThat(changed).isEmpty()
    }

    @Test fun `内置模式不受Linux环境状态变化影响`() = runBlocking<Unit> {
        seed(mode = "BUILTIN")
        val service = service()
        assertThat(service.check("s")).isEqualTo(SessionRunModeCheck.Available(SessionRunMode.BUILTIN))
        runtime.state.value = LinuxRuntimeState.Broken(LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED))
        assertThat(service.check("s")).isEqualTo(SessionRunModeCheck.Available(SessionRunMode.BUILTIN))
    }

    @Test fun `Play旧会话要求明确选择且拒绝Linux切换`() = runBlocking<Unit> {
        seed()
        runtime.state.value = READY
        val service = service(supported = false)
        val check = service.check("s") as SessionRunModeCheck.SelectionRequired
        assertThat(check.canChooseLinux).isFalse()
        assertThat(service.switchMode("s", SessionRunMode.LINUX))
            .isEqualTo(SessionRunModeSwitchResult.LinuxUnsupported)
        assertThat(db.sessionDao().getById("s")!!.runMode).isNull()
    }

    @Test fun `未知持久化值要求选择且不会被Ready静默覆盖`() = runBlocking<Unit> {
        seed(mode = "FUTURE")
        runtime.state.value = READY
        val service = service()
        assertThat(service.modeFor("s")).isNull()
        assertThat(service.check("s")).isInstanceOf(SessionRunModeCheck.SelectionRequired::class.java)
        assertThat(db.sessionDao().getById("s")!!.runMode).isEqualTo("FUTURE")
    }

    @Test fun `切换到内置提示导出Linux工作区但保持绑定和文件位置`() = runBlocking<Unit> {
        db.workspaceDao().insert(WorkspaceEntity("home", "linux_home", "Linux主目录", "/root#" + "a".repeat(64), 1))
        seed(mode = "LINUX", workspaceId = "home")
        val result = service().switchMode("s", SessionRunMode.BUILTIN) as SessionRunModeSwitchResult.Success
        assertThat(result.previousMode).isEqualTo(SessionRunMode.LINUX)
        assertThat(result.exportWorkspaceId).isEqualTo("home")
        assertThat(db.sessionDao().getById("s")!!.workspaceId).isEqualTo("home")
        assertThat(db.sessionDao().getById("s")!!.runMode).isEqualTo("BUILTIN")
        assertThat(changed).containsExactly("s" to SessionRunMode.BUILTIN)
    }

    @Test fun `环境未就绪也可明确选择Linux随后处理环境`() = runBlocking<Unit> {
        seed(mode = "BUILTIN")
        assertThat(service().switchMode("s", SessionRunMode.LINUX))
            .isInstanceOf(SessionRunModeSwitchResult.Success::class.java)
        assertThat(service().check("s")).isInstanceOf(SessionRunModeCheck.EnvironmentUnavailable::class.java)
        assertThat(service().switchMode("missing", SessionRunMode.BUILTIN))
            .isEqualTo(SessionRunModeSwitchResult.SessionMissing)
    }

    @Test fun `模式与环境观察流同时反映切换和失效且不初始化旧会话`() = runBlocking<Unit> {
        seed(mode = "LINUX")
        val service = service()
        runtime.state.value = READY
        val updates = Channel<SessionRunModeStatus>(Channel.UNLIMITED)
        val collector = launch { service.observe("s").collect { updates.send(it) } }
        suspend fun next() = withTimeout(5_000) { updates.receive() }
        assertThat(next().check).isEqualTo(SessionRunModeCheck.Available(SessionRunMode.LINUX))
        runtime.state.value = LinuxRuntimeState.NotInstalled(0)
        val failed = next()
        assertThat(failed.mode).isEqualTo(SessionRunMode.LINUX)
        assertThat(failed.check).isInstanceOf(SessionRunModeCheck.EnvironmentUnavailable::class.java)
        service.switchMode("s", SessionRunMode.BUILTIN)
        assertThat(next().mode).isEqualTo(SessionRunMode.BUILTIN)
        collector.cancel()
        seed(id = "legacy")
        runtime.state.value = READY
        assertThat(service.observe("legacy").first().mode).isNull()
        assertThat(db.sessionDao().getById("legacy")!!.runMode).isNull()
    }

    @Test fun `启动检查只统计显式Linux会话且不写入旧会话`() = runBlocking<Unit> {
        seed(id = "linux1", mode = "LINUX")
        seed(id = "linux2", mode = "LINUX")
        seed(id = "builtin", mode = "BUILTIN")
        seed(id = "legacy")
        val service = service()
        assertThat(service.startupCheck().affectedSessionCount).isEqualTo(2)
        runtime.state.value = READY
        val ready = service.startupCheck()
        assertThat(ready.linuxAvailable).isTrue()
        assertThat(ready.linuxSessionCount).isEqualTo(2)
        assertThat(ready.affectedSessionCount).isEqualTo(0)
        assertThat(db.sessionDao().getById("legacy")!!.runMode).isNull()
        assertThat(changed).isEmpty()
    }

    @Test fun `新会话仅在侧载Ready时使用兼容默认设置`() = runBlocking<Unit> {
        val service = service()
        assertThat(service.newSessionMode()).isEqualTo(SessionRunMode.BUILTIN)
        runtime.state.value = READY
        assertThat(service.newSessionMode()).isEqualTo(SessionRunMode.LINUX)
        service.setDefaultMode(SessionRunMode.BUILTIN)
        assertThat(runtime.agentEnabled.value).isFalse()
        assertThat(service.defaultMode.first()).isEqualTo(SessionRunMode.BUILTIN)
        assertThat(service.newSessionMode()).isEqualTo(SessionRunMode.BUILTIN)
        service.setDefaultMode(SessionRunMode.LINUX)
        assertThat(runtime.agentEnabled.value).isTrue()
        assertThat(service(supported = false).newSessionMode()).isEqualTo(SessionRunMode.BUILTIN)
        assertThat(service(supported = false).setDefaultMode(SessionRunMode.LINUX)).isFalse()
    }

    @Test fun `两条仓库创建入口都固定模式且后续默认更改不影响既有会话`() = runBlocking<Unit> {
        runtime.state.value = READY
        val service = service()
        val repository = SessionRepository(db.sessionDao(), db.messageDao(), newSessionRunMode = service::newSessionMode)
        repository.create(Session("data", "agent"))
        val domain = repository.create("agent", "model")
        assertThat(repository.getById("data")!!.runMode).isEqualTo(SessionRunMode.LINUX)
        assertThat(domain.runMode).isEqualTo(SessionRunMode.LINUX)
        service.setDefaultMode(SessionRunMode.BUILTIN)
        repository.create(Session("new", "agent"))
        assertThat(db.sessionDao().getById("new")!!.runMode).isEqualTo("BUILTIN")
        assertThat(db.sessionDao().getById("data")!!.runMode).isEqualTo("LINUX")
    }

    @Test fun `普通会话保存不能撤销检查确定或用户切换的模式`() = runBlocking<Unit> {
        seed(mode = "LINUX")
        val repository = SessionRepository(db.sessionDao(), db.messageDao())
        repository.update(Session("s", "agent", title = "新标题"))
        assertThat(db.sessionDao().getById("s")!!.runMode).isEqualTo("LINUX")
        service().switchMode("s", SessionRunMode.BUILTIN)
        repository.update(Session("s", "agent", runMode = SessionRunMode.LINUX))
        assertThat(db.sessionDao().getById("s")!!.runMode).isEqualTo("BUILTIN")
        val stale = db.sessionDao().getById("s")!!.copy(runMode = null, title = "直接保存")
        db.sessionDao().update(stale)
        assertThat(db.sessionDao().getById("s")!!.title).isEqualTo("直接保存")
        assertThat(db.sessionDao().getById("s")!!.runMode).isEqualTo("BUILTIN")
    }

    private class ModeTestRuntime : LinuxRuntime {
        override val state = MutableStateFlow<LinuxRuntimeState>(LinuxRuntimeState.NotInstalled(0))
        override val agentEnabled = MutableStateFlow(true)
        override suspend fun setAgentEnabled(enabled: Boolean) { agentEnabled.value = enabled }
        override fun startInstall(options: LinuxInstallOptions) = error("检查不能安装环境")
        override fun cancelInstall() = error("检查不能取消安装")
        override suspend fun storageUsage() = LinuxStorageUsage(0, 0, 0, 0)
        override suspend fun clearDownloadCache() = error("检查不能清理缓存")
        override suspend fun runDiagnostics(): LinuxDiagnostics = error("检查不能启动诊断进程")
        override suspend fun reset() = error("检查不能重置环境")
        override suspend fun uninstall() = error("检查不能卸载环境")
        override suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch = error("检查不能启动进程")
        override val paths = LinuxPaths(File("rootfs"), File("rootfs/root"))
    }

    companion object {
        private val READY = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "3.22", "aarch64", "5", 1, null))
    }
}
