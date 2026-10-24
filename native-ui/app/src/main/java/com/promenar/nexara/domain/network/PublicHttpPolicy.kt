package com.promenar.nexara.domain.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Agent 网络目标分类使用的公网判定，不在各个工具内复制网段规则。 */
object PublicHttpPolicy {
    fun isPrivateOrLocalAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        if (address is Inet4Address) return bytes[0] == 10 || bytes[0] == 127 || bytes[0] == 0 ||
            bytes[0] == 172 && bytes[1] in 16..31 || bytes[0] == 192 && bytes[1] == 168 ||
            bytes[0] == 169 && bytes[1] == 254 || bytes[0] == 100 && bytes[1] in 64..127 || bytes[0] >= 224
        if (address is Inet6Address) return bytes[0] == 0xfc || bytes[0] == 0xfd || bytes[0] == 0xfe
        return false
    }
}
