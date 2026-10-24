package com.promenar.nexara.data.network

import com.promenar.nexara.data.tailnet.TailnetRouting
import com.promenar.nexara.domain.network.AgentNetworkPolicy
import com.promenar.nexara.domain.network.NetworkAccessDenied
import java.net.InetSocketAddress
import java.net.Proxy

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
}
