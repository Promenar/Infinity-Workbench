package com.promenar.nexara.domain.network

import com.promenar.nexara.data.tailnet.TailnetHosts
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NetworkAccessDenied(val code: String, message: String) : IllegalArgumentException(message)

data class AgentNetworkTarget(val host: String, val target: NetworkTargetClass, val addresses: List<InetAddress>)

data class AgentNetworkEndpoint(val uri: URI, val destination: AgentNetworkTarget) {
    val host: String get() = destination.host
    val target: NetworkTargetClass get() = destination.target
    val addresses: List<InetAddress> get() = destination.addresses
}

/** 三类 App 内联网工具共用的分类与授权；路由名称先于系统 DNS 判定。 */
class AgentNetworkPolicy(
    private val isTailnetHost: (String) -> Boolean = { false },
    private val allowedLanHosts: () -> Set<String> = { emptySet() },
    private val tailnetRunning: () -> Boolean = { false },
    private val resolver: suspend (String) -> Array<InetAddress> = { host ->
        withContext(Dispatchers.IO) { InetAddress.getAllByName(host) }
    },
    /** Android 网络安全配置对该主机是否允许明文；发行配置只放行 127.0.0.1。 */
    private val cleartextPermitted: (String) -> Boolean = { true },
) {
    suspend fun classify(rawHost: String): AgentNetworkTarget {
        val host = normalizeHost(rawHost)
        if (isTailnetName(host, isTailnetHost)) return AgentNetworkTarget(host, NetworkTargetClass.TAILNET, emptyList())
        val addresses = try { resolver(host).toList() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { denied("DNS_FAILED", "主机解析失败，请检查地址与网络连接") }
        if (addresses.isEmpty()) denied("DNS_FAILED", "主机没有可用地址，请检查地址与网络连接")
        if (addresses.any { it.isAnyLocalAddress || it.isMulticastAddress }) denied("ADDRESS_DENIED", "目标包含未指定地址或组播地址，禁止访问")
        val target = when {
            addresses.any { it.isLoopbackAddress } -> NetworkTargetClass.LOOPBACK
            addresses.any { TailnetHosts.isTailnetAddress(it.address) } -> NetworkTargetClass.TAILNET
            addresses.any(PublicHttpPolicy::isPrivateOrLocalAddress) -> NetworkTargetClass.LAN
            else -> NetworkTargetClass.PUBLIC
        }
        return AgentNetworkTarget(host, target, addresses)
    }

    suspend fun validate(raw: String, tool: AgentNetworkTool = AgentNetworkTool.WEB_FETCH): AgentNetworkEndpoint {
        val uri = runCatching { URI(raw) }.getOrNull() ?: denied("URL_POLICY", "URL 格式无效")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (raw.length > 4096 || scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.host.isNullOrBlank() ||
            uri.port < -1 || uri.port == 0 || uri.port > 65535) denied("URL_POLICY", "仅支持不含凭据、主机与端口有效的 HTTP/HTTPS URL")
        val destination = classify(uri.host)
        // 先于白名单判定：系统会拒绝的明文请求，加入白名单也无法访问。
        if (scheme == "http" && destination.target != NetworkTargetClass.TAILNET && !cleartextPermitted(destination.host)) {
            denied("CLEARTEXT_BLOCKED", "Android 系统策略只允许明文 http 访问本机 127.0.0.1；请改用 https，或用内置命令 shell_exec / python_exec 访问")
        }
        when (stateFor(tool, destination.target)) {
            NetworkAccessState.ALLOWLIST_ONLY -> if (destination.host !in allowedLanHosts()) {
                denied("LAN_HOST_NOT_ALLOWED", "局域网与本机目标需要精确主机授权，请在“设置 → 网络访问”中添加主机白名单")
            }
            NetworkAccessState.TAILNET_REQUIRED -> {
                if (!tailnetRunning()) denied("TAILNET_OFFLINE", TAILNET_GUIDANCE)
                if (scheme == "http") denied("TAILNET_CLEARTEXT", "不支持 tailnet 明文 http，请改用 https，或用内置命令 python_exec / shell_exec 经内置代理访问")
            }
            else -> Unit
        }
        return AgentNetworkEndpoint(uri, destination)
    }

    companion object {
        const val TAILNET_GUIDANCE = "内置 Tailscale 未运行或代理不可用，请在 Tailscale 设置中启用并连接；已拒绝回退直连"

        fun normalizeHost(host: String): String = host.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")

        fun isTailnetName(host: String, routing: (String) -> Boolean): Boolean =
            TailnetHosts.parseAddressLiteral(host)?.let(TailnetHosts::isTailnetAddress) == true || routing(host) ||
                TailnetHosts.isTailnetHost(host, null, emptySet())

        fun parseAllowlist(text: String): Set<String> = text.split(Regex("[\\s,]+"))
            .filter(String::isNotBlank).map { raw ->
                val host = normalizeHost(raw)
                val valid = runCatching { URI("https://" + if (':' in host) "[$host]" else host).host }.getOrNull()
                require(host.length <= 253 && valid != null && '/' !in host && '*' !in host && '@' !in host && '?' !in host && '#' !in host && !host.endsWith('.')) {
                    "白名单只能填写精确主机名或 IP，不含协议、端口与路径"
                }
                host
            }.toSet().also { require(it.size <= 64) { "白名单最多 64 个主机" } }

        fun stateFor(tool: AgentNetworkTool, target: NetworkTargetClass): NetworkAccessState = when (tool) {
            AgentNetworkTool.WEB_FETCH, AgentNetworkTool.MEDIA_FETCH, AgentNetworkTool.SCRIPT -> when (target) {
                NetworkTargetClass.PUBLIC -> NetworkAccessState.ALLOWED
                NetworkTargetClass.LAN, NetworkTargetClass.LOOPBACK -> NetworkAccessState.ALLOWLIST_ONLY
                NetworkTargetClass.TAILNET -> NetworkAccessState.TAILNET_REQUIRED
            }
            AgentNetworkTool.MCP, AgentNetworkTool.SSH -> NetworkAccessState.CONFIGURED_ONLY
            AgentNetworkTool.BUILTIN_PROCESS -> if (target == NetworkTargetClass.TAILNET) NetworkAccessState.TAILNET_REQUIRED else NetworkAccessState.UNRESTRICTED
            AgentNetworkTool.TERMUX -> NetworkAccessState.UNRESTRICTED
        }

        val rows: List<NetworkAccessRow> = AgentNetworkTool.entries.map { tool ->
            NetworkAccessRow(tool, NetworkTargetClass.entries.map { NetworkAccessCell(it, stateFor(tool, it)) })
        }

        private fun denied(code: String, message: String): Nothing = throw NetworkAccessDenied(code, message)
    }
}
