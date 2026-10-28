package com.promenar.infinity.domain.media

import com.promenar.infinity.data.tailnet.TailnetHosts
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import com.promenar.infinity.domain.network.NetworkTargetClass
import java.net.URI
import java.util.Locale

/** 不做 DNS 或网络访问；节点名由当前 tailnet 路由提供，固定地址段在离线时也能识别。 */
class MediaFetchUrlPolicy(private val isTailnetHost: (String) -> Boolean) {
    fun isTailnet(host: String): Boolean = AgentNetworkPolicy.isTailnetName(AgentNetworkPolicy.normalizeHost(host), isTailnetHost)

    fun validate(raw: String, previousUrl: String? = null, target: NetworkTargetClass? = null): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return "媒体 URL 格式无效"
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("https", "http")) return "媒体 URL 只支持 https 或受控的 http"
        if (uri.rawUserInfo != null) return "媒体 URL 不允许包含用户信息"
        val host = uri.host?.takeIf(String::isNotBlank) ?: return "媒体 URL 缺少有效主机"
        if (uri.port < -1 || uri.port == 0 || uri.port > 65535) return "媒体 URL 端口无效"
        val tailnet = if (target == null) isTailnet(host) else target == NetworkTargetClass.TAILNET
        val loopback = if (target == null) isLoopback(host) else target == NetworkTargetClass.LOOPBACK
        if (scheme == "http" && !tailnet && !loopback) return "明文 http 仅允许 tailnet 或本机回环主机"
        val previousScheme = previousUrl?.let { runCatching { URI(it).scheme }.getOrNull() }
        if (previousScheme.equals("https", true) && scheme == "http" && !tailnet) {
            return "https 媒体重定向不允许降级到非 tailnet 明文 http"
        }
        return null
    }

    private fun isLoopback(host: String): Boolean {
        if (TailnetHosts.normalize(host) == "localhost") return true
        val bytes = TailnetHosts.parseAddressLiteral(host) ?: return false
        return bytes.size == 4 && bytes[0].toInt() and 0xff == 127 ||
            bytes.size == 16 && bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()
    }
}
