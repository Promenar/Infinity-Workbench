package com.promenar.nexara.data.ssh

import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Agent 管理 SSH 主机的设备级授权；默认关闭，不随备份迁移。 */
object SshAgentPreferences {
    const val HOST_MANAGEMENT_KEY = "ssh_agent_host_management"
    val HOST_MANAGEMENT_TOOLS = setOf("ssh_host_list", "ssh_host_save", "ssh_host_delete", "ssh_host_install_key")

    fun isHostManagementEnabled(settings: SharedPreferences): Boolean = settings.getBoolean(HOST_MANAGEMENT_KEY, false)
}

/** 需要用户在应用内亲自完成的 SSH 步骤；内容只含主机配置，不含任何凭据。 */
sealed interface SshUserPrompt {
    val alias: String

    class Password internal constructor(
        override val alias: String,
        val username: String,
        val hostname: String,
        val port: Int,
        internal val result: CompletableDeferred<ByteArray?>,
    ) : SshUserPrompt {
        /** 用户提交的密码字节由调用方负责清零；传 null 表示取消。 */
        fun submit(password: ByteArray?) {
            if (!result.complete(password)) password?.fill(0)
        }
    }

    class HostKey internal constructor(
        override val alias: String,
        val hostname: String,
        val port: Int,
        val previous: SshHostKey?,
        val presented: SshHostKey,
        internal val result: CompletableDeferred<Boolean>,
    ) : SshUserPrompt {
        fun answer(trusted: Boolean) { result.complete(trusted) }
    }
}

/**
 * 工具执行中向用户发起的安全确认：一次只显示一个，等待超时或协程取消时按“取消”处理。
 * 密码只在用户与本机安全存储之间传递，结果中不会返回给模型。
 */
class SshUserPromptBroker(private val timeoutMillis: Long = 5 * 60_000L) {
    private val _current = MutableStateFlow<SshUserPrompt?>(null)
    val current: StateFlow<SshUserPrompt?> = _current.asStateFlow()
    private val serial = Mutex()

    suspend fun requestPassword(host: SshHost): ByteArray? = serial.withLock {
        val prompt = SshUserPrompt.Password(host.alias, host.username, host.hostname, host.port, CompletableDeferred())
        show(prompt) { withTimeoutOrNull(timeoutMillis) { prompt.result.await() } }.also { if (it == null) prompt.result.cancel() }
    }

    suspend fun confirmHostKey(host: SshHost, presented: SshHostKey): Boolean = serial.withLock {
        val previous = host.trustedFingerprint?.let { SshHostKey(host.trustedKeyType.orEmpty().ifBlank { presented.type }, it) }
        val prompt = SshUserPrompt.HostKey(host.alias, host.hostname, host.port, previous, presented, CompletableDeferred())
        show(prompt) { withTimeoutOrNull(timeoutMillis) { prompt.result.await() } } == true
    }

    private suspend fun <T> show(prompt: SshUserPrompt, wait: suspend () -> T): T {
        _current.value = prompt
        return try { wait() } finally { _current.compareAndSet(prompt, null) }
    }
}
