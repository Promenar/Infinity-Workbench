package com.promenar.nexara.domain.script

import com.promenar.nexara.domain.network.PublicHttpPolicy
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Locale

data class ScriptEndpoint(val url: String, val host: String, val addresses: List<InetAddress>)

object ScriptNetworkPolicy {
    fun parseAllowlist(text: String): Set<String> = text.split(Regex("[\\s,]+"))
        .filter(String::isNotBlank).map { raw ->
            val host = raw.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
            val valid = runCatching { URI("https://" + if (':' in host) "[$host]" else host).host }.getOrNull()
            if (host.length > 253 || valid == null || '/' in host || '*' in host || '@' in host || '?' in host || '#' in host || host.endsWith('.')) denied("白名单只能填写精确主机名或 IP，不含协议、端口与路径")
            host
        }.toSet().also { if (it.size > 64) denied("白名单最多 64 个主机") }

    suspend fun validate(url: String, allowedLanHosts: Set<String>, resolver: suspend (String) -> Array<InetAddress>): ScriptEndpoint {
        val uri = runCatching { URI(url) }.getOrNull() ?: denied("URL 无效")
        if (url.length > 4096 || uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.userInfo != null || uri.host == null) denied("仅支持不含凭据的 HTTP/HTTPS URL")
        val host = uri.host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        val addresses = try { resolver(host).toList() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { denied("主机解析失败") }
        if (addresses.isEmpty()) denied("主机没有可用地址")
        addresses.forEach { address ->
            if (address.isAnyLocalAddress || address.isMulticastAddress || address.isLinkLocalAddress ||
                PublicHttpPolicy.isPrivateOrLocalAddress(address) && host !in allowedLanHosts
            ) denied("目标属于非公网地址，且未获得精确局域网主机授权")
        }
        return ScriptEndpoint(url, host, addresses)
    }

    private fun denied(message: String): Nothing = throw ScriptFailure("NETWORK_DENIED", message)
}

class ScriptNetworkBudget(
    private val maxRequests: Int = ScriptLimits.NETWORK_REQUESTS,
    private val bodyBytes: Int = ScriptLimits.NETWORK_BODY_BYTES,
    private val totalBytes: Int = ScriptLimits.NETWORK_TOTAL_BYTES,
) {
    private var requests = 0
    private var received = 0
    @Synchronized fun request(bytes: Int) {
        if (++requests > maxRequests || bytes > bodyBytes || bytes < 0) throw ScriptFailure("NETWORK_LIMIT", "请求体或请求次数超过限制")
    }
    @Synchronized fun response(bytes: Int) {
        if (bytes < 0 || bytes > bodyBytes || received.toLong() + bytes > totalBytes) throw ScriptFailure("NETWORK_LIMIT", "响应体或累计响应超过限制")
        received += bytes
    }
}
