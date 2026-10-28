package com.promenar.infinity.data.network

import com.promenar.infinity.data.tailnet.TailnetRouting
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import com.promenar.infinity.domain.network.NetworkAccessDenied
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.ssl.SSLException

/** 只接受路由实例当前持有的本机代理，不读取可能回退系统代理的选择器。 */
object AgentTailnetProxy {
    fun requireProxy(routing: TailnetRouting): Proxy {
        val proxy = routing.agentProxy()
        val address = proxy?.address() as? InetSocketAddress
        if (proxy?.type() != Proxy.Type.HTTP || address?.port !in 1..65535 ||
            address?.address?.address?.contentEquals(byteArrayOf(127, 0, 0, 1)) != true) {
            throw NetworkAccessDenied("TAILNET_OFFLINE", AgentNetworkPolicy.TAILNET_GUIDANCE)
        }
        return proxy
    }

    /** 隧道里的失败多来自目标本身：证书不受信任与连接失败分开报告，不一律归咎于内置 Tailscale。 */
    fun failure(error: IOException): NetworkAccessDenied = when (error) {
        is SSLException -> NetworkAccessDenied(
            "TAILNET_TLS_ERROR",
            "tailnet 目标的 HTTPS 证书未通过校验（例如自签名证书），已拒绝连接；请为该服务配置受信任证书，如在目标设备上使用 tailscale serve",
        )
        else -> NetworkAccessDenied(
            "TAILNET_PROXY_ERROR",
            "经内置 Tailscale 连接 tailnet 目标失败：${error.message ?: error.javaClass.simpleName}；请确认目标设备在线、服务正在监听，且内置 Tailscale 已连接",
        )
    }
}
