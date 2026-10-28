package com.promenar.infinity.data.session

import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.dao.WorkspaceDao
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.distribution.DistributionCapabilities
import com.promenar.infinity.domain.linux.LinuxRuntime
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.model.SessionRunMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionRunModeAction { REPAIR, RESET, EXPORT, SWITCH_BUILTIN }

sealed interface SessionRunModeCheck {
    data class Available(val mode: SessionRunMode) : SessionRunModeCheck
    data class SelectionRequired(
        val linuxState: LinuxRuntimeState,
        val canChooseLinux: Boolean,
    ) : SessionRunModeCheck
    data class EnvironmentUnavailable(
        val linuxState: LinuxRuntimeState,
        val actions: List<SessionRunModeAction> = listOf(SessionRunModeAction.REPAIR, SessionRunModeAction.RESET,
            SessionRunModeAction.EXPORT, SessionRunModeAction.SWITCH_BUILTIN),
    ) : SessionRunModeCheck
    data object SessionMissing : SessionRunModeCheck
}

data class SessionRunModeStatus(val sessionId: String, val mode: SessionRunMode?, val check: SessionRunModeCheck)

sealed interface SessionRunModeSwitchResult {
    data class Success(
        val previousMode: SessionRunMode?,
        val mode: SessionRunMode,
        /** Linux 主目录绑定保留；界面可提示用户先导出可读文件。 */
        val exportWorkspaceId: String? = null,
    ) : SessionRunModeSwitchResult
    data object LinuxUnsupported : SessionRunModeSwitchResult
    data object SessionMissing : SessionRunModeSwitchResult
}

data class SessionRunModeStartupCheck(
    val linuxAvailable: Boolean,
    val linuxState: LinuxRuntimeState,
    val linuxSessionCount: Int,
    val affectedSessionCount: Int,
)

/** 只在显式检查旧会话或用户切换时写入模式；运行时失效只影响可用性。 */
class SessionRunModeService(
    private val sessionDao: SessionDao,
    private val workspaceDao: WorkspaceDao,
    private val runtime: LinuxRuntime,
    private val linuxSupported: Boolean = DistributionCapabilities.linuxRuntimeAvailable,
    private val now: () -> Long = System::currentTimeMillis,
    private val onModeChanged: suspend (String, SessionRunMode) -> Unit = { _, _ -> },
) {
    private val modeMutation = Mutex()

    /** 沿用 agent_enabled 偏好作为新会话默认值，已存在会话不跟随该偏好变化。 */
    val defaultMode: Flow<SessionRunMode> = runtime.agentEnabled.map { enabled ->
        if (linuxSupported && enabled) SessionRunMode.LINUX else SessionRunMode.BUILTIN
    }.distinctUntilChanged()

    fun currentDefaultMode(): SessionRunMode =
        if (linuxSupported && runtime.agentEnabled.value) SessionRunMode.LINUX else SessionRunMode.BUILTIN

    suspend fun setDefaultMode(mode: SessionRunMode): Boolean {
        if (mode == SessionRunMode.LINUX && !linuxSupported) return false
        runtime.setAgentEnabled(mode == SessionRunMode.LINUX)
        return true
    }

    fun newSessionMode(): SessionRunMode =
        if (linuxAvailable(runtime.state.value)) currentDefaultMode() else SessionRunMode.BUILTIN

    suspend fun modeFor(sessionId: String): SessionRunMode? =
        SessionRunMode.fromStored(sessionDao.getById(sessionId)?.runMode)

    suspend fun check(sessionId: String): SessionRunModeCheck = modeMutation.withLock {
        var session = sessionDao.getById(sessionId) ?: return@withLock SessionRunModeCheck.SessionMissing
        if (session.runMode == null && linuxAvailable(runtime.state.value)) {
            // 只初始化仍为空的行，避免覆盖其它服务实例或显式切换写入的模式。
            if (sessionDao.initializeRunMode(sessionId, SessionRunMode.LINUX.name) == 1) {
                onModeChanged(sessionId, SessionRunMode.LINUX)
            }
            session = sessionDao.getById(sessionId) ?: return@withLock SessionRunModeCheck.SessionMissing
        }
        checkSession(session, runtime.state.value)
    }

    /** 观察不写库；进入会话与发送消息前通过 [check] 完成旧会话的首次选择。 */
    fun observe(sessionId: String): Flow<SessionRunModeStatus> =
        combine(sessionDao.observeById(sessionId), runtime.state) { session, state ->
            SessionRunModeStatus(sessionId, SessionRunMode.fromStored(session?.runMode), checkSession(session, state))
        }.distinctUntilChanged()

    suspend fun switchMode(sessionId: String, mode: SessionRunMode): SessionRunModeSwitchResult = modeMutation.withLock {
        val session = sessionDao.getById(sessionId) ?: return@withLock SessionRunModeSwitchResult.SessionMissing
        if (mode == SessionRunMode.LINUX && !linuxSupported) return@withLock SessionRunModeSwitchResult.LinuxUnsupported
        val previousMode = SessionRunMode.fromStored(session.runMode)
        val exportWorkspaceId = if (previousMode == SessionRunMode.LINUX && mode == SessionRunMode.BUILTIN) {
            session.workspaceId?.takeIf { workspaceDao.getById(it)?.kind == WorkspaceEntity.KIND_LINUX_HOME }
        } else null
        if (sessionDao.updateRunMode(sessionId, mode.name, now()) != 1) {
            return@withLock SessionRunModeSwitchResult.SessionMissing
        }
        onModeChanged(sessionId, mode)
        SessionRunModeSwitchResult.Success(previousMode, mode, exportWorkspaceId)
    }

    /** 启动提示不启动进程、不初始化旧会话，也不修复或更改环境。 */
    suspend fun startupCheck(): SessionRunModeStartupCheck {
        val state = runtime.state.value
        val available = linuxAvailable(state)
        val count = sessionDao.countLinuxSessions()
        return SessionRunModeStartupCheck(available, state, count, if (available) 0 else count)
    }

    private fun linuxAvailable(state: LinuxRuntimeState): Boolean = linuxSupported && state is LinuxRuntimeState.Ready

    private fun checkSession(session: SessionEntity?, state: LinuxRuntimeState): SessionRunModeCheck {
        if (session == null) return SessionRunModeCheck.SessionMissing
        return when (val mode = SessionRunMode.fromStored(session.runMode)) {
            SessionRunMode.BUILTIN -> SessionRunModeCheck.Available(mode)
            SessionRunMode.LINUX -> if (linuxAvailable(state)) SessionRunModeCheck.Available(mode)
                else SessionRunModeCheck.EnvironmentUnavailable(state)
            null -> SessionRunModeCheck.SelectionRequired(state, canChooseLinux = linuxSupported)
        }
    }
}
