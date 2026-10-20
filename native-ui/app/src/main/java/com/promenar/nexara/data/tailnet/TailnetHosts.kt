package com.promenar.nexara.data.tailnet

import java.util.Locale

/**
 * tailnet 目标判定，与子进程中的分流规则一致：
 * tailnet 地址段内的 IP、MagicDNS 全名（`*.ts.net` 或当前 tailnet 后缀）属于 tailnet；
 * 不含点的短名只在匹配已知节点（或节点列表尚未取得）时属于 tailnet，局域网短名保持原有路径。
 * 判定不做 DNS 解析。
 */
object TailnetHosts {
    const val TAILSCALE_MAGIC_DNS_DOMAIN = "ts.net"

    /** 规范化：小写、去掉 IPv6 方括号与末尾的点。 */
    fun normalize(host: String): String =
        host.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT).removeSuffix(".")

    /**
     * [host] 是 IP 字面量时返回其字节（IPv4 映射的 IPv6 地址返回 4 字节），否则 null。
     * 自行解析而不用 InetAddress：Android 上非法字面量会退化为 DNS 查询。
     */
    fun parseAddressLiteral(host: String): ByteArray? {
        val value = normalize(host)
        if (value.isEmpty()) return null
        if (':' in value) return parseIpv6(value)?.let { ipv4Mapped(it) ?: it }
        return parseIpv4(value)
    }

    private fun parseIpv4(value: String): ByteArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val number = part.toInt()
            if (number > 255) return null
            bytes[index] = number.toByte()
        }
        return bytes
    }

    private fun parseIpv6(value: String): ByteArray? {
        if ('%' in value) return null
        val halves = value.split("::")
        if (halves.size > 2) return null
        fun groups(text: String): List<String>? = if (text.isEmpty()) emptyList() else text.split(':').takeIf { parts -> parts.none(String::isEmpty) }
        val head = groups(halves[0]) ?: return null
        val tail = (if (halves.size == 2) groups(halves[1]) else emptyList()) ?: return null
        val words = mutableListOf<Int>()
        fun append(parts: List<String>, allowIpv4Tail: Boolean): Boolean {
            parts.forEachIndexed { index, part ->
                if (allowIpv4Tail && index == parts.lastIndex && '.' in part) {
                    val ipv4 = parseIpv4(part) ?: return false
                    words += ((ipv4[0].toInt() and 0xff) shl 8) or (ipv4[1].toInt() and 0xff)
                    words += ((ipv4[2].toInt() and 0xff) shl 8) or (ipv4[3].toInt() and 0xff)
                } else {
                    if (part.length > 4 || !part.all { it in '0'..'9' || it in 'a'..'f' }) return false
                    words += part.toInt(16)
                }
            }
            return true
        }
        if (!append(head, allowIpv4Tail = halves.size == 1)) return null
        val headCount = words.size
        if (!append(tail, allowIpv4Tail = true)) return null
        val tailWords = words.subList(headCount, words.size).toList()
        val headWords = words.subList(0, headCount).toList()
        val missing = 8 - headWords.size - tailWords.size
        if (halves.size == 1 && missing != 0 || halves.size == 2 && missing < 1) return null
        val all = headWords + List(if (halves.size == 2) missing else 0) { 0 } + tailWords
        return ByteArray(16) { index -> (all[index / 2] shr (if (index % 2 == 0) 8 else 0)).toByte() }
    }

    private fun ipv4Mapped(bytes: ByteArray): ByteArray? {
        for (index in 0 until 10) if (bytes[index] != 0.toByte()) return null
        if (bytes[10] != 0xff.toByte() || bytes[11] != 0xff.toByte()) return null
        return bytes.copyOfRange(12, 16)
    }

    /** 100.64.0.0/10 或 fd7a:115c:a1e0::/48。 */
    fun isTailnetAddress(bytes: ByteArray): Boolean = when (bytes.size) {
        4 -> (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
        16 -> (bytes[0].toInt() and 0xff) == 0xfd && (bytes[1].toInt() and 0xff) == 0x7a &&
            (bytes[2].toInt() and 0xff) == 0x11 && (bytes[3].toInt() and 0xff) == 0x5c &&
            (bytes[4].toInt() and 0xff) == 0xa1 && (bytes[5].toInt() and 0xff) == 0xe0
        else -> false
    }

    /**
     * [magicDnsSuffix] 为当前 tailnet 的 MagicDNS 后缀（未知时为 null）；
     * [peerNames] 为已知节点的短名与全名（规范化后），null 表示节点列表尚未取得。
     */
    fun isTailnetHost(host: String, magicDnsSuffix: String?, peerNames: Set<String>?): Boolean {
        val name = normalize(host)
        if (name.isEmpty()) return false
        parseAddressLiteral(name)?.let { return isTailnetAddress(it) }
        if (name == "localhost" || name.endsWith(".localhost")) return false
        if (name.endsWith(".$TAILSCALE_MAGIC_DNS_DOMAIN")) return true
        val suffix = magicDnsSuffix?.let(::normalize)?.takeIf { it.isNotEmpty() }
        if (suffix != null && (name == suffix || name.endsWith(".$suffix"))) return true
        if ('.' in name) return false
        return peerNames?.contains(name) ?: true
    }
}
