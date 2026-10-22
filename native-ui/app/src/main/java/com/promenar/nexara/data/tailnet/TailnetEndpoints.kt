package com.promenar.nexara.data.tailnet

import java.net.URI

/**
 * 界面上判定接口地址是否在 tailnet 内（提供商表单的自动识别、模型行的内网标记、指示灯是否出现）。
 * 与请求分流不同，这里不看内置 Tailscale 是否开启；不含点的短名只认已知设备，避免把局域网短名误标为内网。
 */
object TailnetEndpoints {
    fun hostOf(baseUrl: String): String? =
        runCatching { URI(baseUrl.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }

    fun isTailnetEndpoint(baseUrl: String, state: TailnetState): Boolean {
        val host = hostOf(baseUrl) ?: return false
        return TailnetHosts.isTailnetHost(host, state.summary?.magicDnsSuffix, knownPeerNames(state))
    }

    fun usesCleartext(baseUrl: String): Boolean =
        runCatching { URI(baseUrl.trim()).scheme }.getOrNull().equals("http", ignoreCase = true)

    /** 点按 tailnet 设备时填入的地址：换成该设备的 https 全名，保留已填写的路径；仍是同一设备时也保留端口。 */
    fun addressForPeer(peer: TailnetPeer, currentBaseUrl: String): String? {
        val host = peerHost(peer) ?: return null
        val current = runCatching { URI(currentBaseUrl.trim()) }.getOrNull()
        val path = current?.rawPath
            ?.takeIf { it.isNotBlank() && it != "/" }
            ?: DEFAULT_PATH
        val port = current?.port?.takeIf { it > 0 && matchesPeer(currentBaseUrl, peer) }?.let { ":$it" }.orEmpty()
        return "https://$host$port$path"
    }

    /** 设备的 MagicDNS 全名（去掉末尾的点）；缺少全名时为 null，此时无法签发 HTTPS 证书。 */
    fun peerHost(peer: TailnetPeer): String? =
        peer.dnsName?.let(TailnetHosts::normalize)?.takeIf { it.isNotEmpty() }

    fun matchesPeer(baseUrl: String, peer: TailnetPeer): Boolean {
        val host = hostOf(baseUrl)?.let(TailnetHosts::normalize) ?: return false
        return host == peerHost(peer) || host == TailnetHosts.normalize(peer.name)
    }

    private fun knownPeerNames(state: TailnetState): Set<String> =
        state.peers.flatMap { listOfNotNull(it.name, it.dnsName) }.map(TailnetHosts::normalize).toSet()

    private const val DEFAULT_PATH = "/v1"
}
