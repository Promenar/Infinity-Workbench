package com.promenar.nexara.data.ssh

import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchProxyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/** 只跨边界发布稳定错误码，底层异常的消息、地址及路径均不进入工具结果。 */
internal fun classifySshFailure(
    failure: Exception,
    hostKeyRejection: String? = null,
    fallbackCode: String = "SSH_IO_FAILED",
): SshFailure {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = generateSequence<Throwable>(failure) { it.cause }.takeWhile(seen::add).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    causes.filterIsInstance<SshFailure>().firstOrNull()?.let { return it }
    if (hostKeyRejection != null) return SshFailure(hostKeyRejection)
    val code = when {
        causes.any { it is JSchChangedHostKeyException } -> "HOST_KEY_CHANGED"
        causes.any { it is JSchUnknownHostKeyException } -> "HOST_KEY_UNTRUSTED"
        causes.any { it is SocketTimeoutException } -> "TIMEOUT"
        causes.any { it is UnknownHostException } -> "DNS_FAILED"
        causes.any { it is NoRouteToHostException } -> "HOST_UNREACHABLE"
        causes.any { it is ConnectException } -> "CONNECTION_REFUSED"
        // SOCKS5 CONNECT 回复没有 socket cause；仅识别当前 JSch 固定格式的三类返回码。
        causes.any { it is JSchProxyException && it.message == "ProxySOCKS5: server returns 5" } -> "CONNECTION_REFUSED"
        causes.any { it is JSchProxyException && it.message in SOCKS_UNREACHABLE_MESSAGES } -> "HOST_UNREACHABLE"
        // JSch 2.28.8 的 Session.connect 没有公开的认证拒绝异常，仅抛出此固定格式的 JSchException。
        causes.any { it is JSchException && AUTH_REJECTED_MESSAGE.matches(it.message.orEmpty()) } -> "AUTH_REJECTED"
        else -> fallbackCode
    }
    return SshFailure(code)
}

private val AUTH_REJECTED_MESSAGE = Regex("Auth fail for methods '[a-zA-Z0-9@._,+-]*'")
private val SOCKS_UNREACHABLE_MESSAGES = setOf("ProxySOCKS5: server returns 3", "ProxySOCKS5: server returns 4")
