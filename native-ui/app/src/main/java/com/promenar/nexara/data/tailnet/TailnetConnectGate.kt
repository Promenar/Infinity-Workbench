package com.promenar.nexara.data.tailnet

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** 发送前确认 tailnet 已接入所需的最小能力；由 [TailnetManager] 实现，测试使用假实现。 */
interface TailnetConnector {
    val state: StateFlow<TailnetState>
    fun isTailnetHost(host: String): Boolean
    /**
     * 内置 Tailscale 已关闭、[host] 明确属于 tailnet（tailnet IP 或 MagicDNS 全名），且系统网络也没有通往 tailnet 的路由。
     * 此时交给系统网络只会空等，应立即报告未开启；系统已有路由（例如官方 Tailscale App 在运行）时返回 false。
     */
    fun isUnreachableWhileDisabled(host: String): Boolean = false
    /**
     * 按需拉起子进程并等待本机代理就绪，最多 [timeoutMillis]；就绪返回 true，启动失败、已停用或退避期内返回 false。
     * [force] 为 true 时忽略失败退避，用于用户主动发送或重试。
     */
    suspend fun ensureStarted(force: Boolean, timeoutMillis: Long): Boolean
}

/** 等待中所处的步骤：拉起内置 Tailscale（本机代理就绪前）→ 接入 tailnet（等待节点上线）。 */
enum class TailnetConnectStep { STARTING, JOINING }

/** 等待进度；[host] 是请求的目标主机，供界面说明“发送到哪里”。 */
data class TailnetConnectProgress(
    val host: String,
    val step: TailnetConnectStep,
    val timeoutMillis: Long,
)

/** tailnet 不可用的原因；界面按原因给出说明与入口。 */
enum class TailnetUnavailableReason { DISABLED, NEEDS_LOGIN, FAILED, TIMEOUT }

sealed interface TailnetConnectResult {
    /** 目标不在 tailnet 内，或未启用内置 Tailscale 但系统有 tailnet 路由（交给系统网络，例如已运行的官方 Tailscale App）。 */
    data object NotRequired : TailnetConnectResult
    /** 已接入；[waited] 表示本次确实等待过连接。 */
    data class Ready(val waited: Boolean) : TailnetConnectResult
    data class Unavailable(val reason: TailnetUnavailableReason) : TailnetConnectResult
}

/** 带凭据的 tailnet 请求在内置 Tailscale 未接入时直接失败，不回退直连。 */
class TailnetUnavailableException(val reason: TailnetUnavailableReason) :
    IOException("内置 Tailscale 未接入 tailnet：$reason")

/**
 * 模型请求发出前的 tailnet 关口：目标属于 tailnet 时，等内置 Tailscale 接入后再放行；
 * 需要登录或内置 Tailscale 已关闭（且系统无 tailnet 路由）立即返回，启动失败或超过 [timeoutMillis] 返回不可用。非 tailnet 目标不等待。
 */
class TailnetConnectGate(
    private val connector: TailnetConnector,
    private val timeoutMillis: Long = SEND_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 内置 Tailscale 关闭时的快速判断：[host] 会被 [await] 判为未开启。不阻塞，可在网络线程调用。 */
    fun mayReject(host: String): Boolean = !connector.isTailnetHost(host) && connector.isUnreachableWhileDisabled(host)

    suspend fun await(
        host: String?,
        force: Boolean = false,
        timeoutMillis: Long = this.timeoutMillis,
        onProgress: (TailnetConnectProgress) -> Unit = {},
    ): TailnetConnectResult {
        if (host.isNullOrBlank()) return TailnetConnectResult.NotRequired
        if (!connector.isTailnetHost(host)) {
            return if (connector.isUnreachableWhileDisabled(host)) {
                TailnetConnectResult.Unavailable(TailnetUnavailableReason.DISABLED)
            } else {
                TailnetConnectResult.NotRequired
            }
        }
        when (connector.state.value.phase) {
            TailnetPhase.RUNNING -> return TailnetConnectResult.Ready(waited = false)
            TailnetPhase.NEEDS_LOGIN -> return TailnetConnectResult.Unavailable(TailnetUnavailableReason.NEEDS_LOGIN)
            else -> Unit
        }
        val deadline = clock() + timeoutMillis
        fun remaining() = (deadline - clock()).coerceAtLeast(0)
        onProgress(TailnetConnectProgress(host, TailnetConnectStep.STARTING, timeoutMillis))
        val started = withTimeoutOrNull(remaining()) { connector.ensureStarted(force, remaining()) }
            ?: return TailnetConnectResult.Unavailable(TailnetUnavailableReason.TIMEOUT)
        if (!started) return TailnetConnectResult.Unavailable(TailnetUnavailableReason.FAILED)
        onProgress(TailnetConnectProgress(host, TailnetConnectStep.JOINING, timeoutMillis))
        // 代理就绪后会话已是新的一轮，此时的 FAILED 来自本次启动，不是上一次的残留状态。
        val settled = withTimeoutOrNull(remaining()) {
            connector.state.first { it.phase in SETTLED_PHASES }
        } ?: return TailnetConnectResult.Unavailable(TailnetUnavailableReason.TIMEOUT)
        return when (settled.phase) {
            TailnetPhase.RUNNING -> TailnetConnectResult.Ready(waited = true)
            TailnetPhase.NEEDS_LOGIN -> TailnetConnectResult.Unavailable(TailnetUnavailableReason.NEEDS_LOGIN)
            else -> TailnetConnectResult.Unavailable(TailnetUnavailableReason.FAILED)
        }
    }

    companion object {
        /** 用户发送时最多等待的时长。 */
        const val SEND_TIMEOUT_MS = 30_000L
        /** 后台模型请求（连接测试、向量化、重排、生图）在网络层最多等待的时长。 */
        const val REQUEST_TIMEOUT_MS = 20_000L
        private val SETTLED_PHASES = setOf(
            TailnetPhase.RUNNING,
            TailnetPhase.NEEDS_LOGIN,
            TailnetPhase.FAILED,
            TailnetPhase.DISABLED,
            TailnetPhase.UNAVAILABLE,
        )
    }
}
