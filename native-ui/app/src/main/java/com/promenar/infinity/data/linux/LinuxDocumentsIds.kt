package com.promenar.infinity.data.linux

import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** 文档标识只描述主目录内的规范容器路径，不承载任何宿主路径。 */
object LinuxDocumentsIds {
    fun encode(path: String): String {
        val normalized = try { LinuxFilePaths.normalize(path) } catch (_: Exception) { invalid() }
        if (normalized != "/root" && !normalized.startsWith("/root/")) invalid()
        return "home:" + Base64.getUrlEncoder().withoutPadding().encodeToString(normalized.toByteArray(Charsets.UTF_8))
    }

    fun decode(id: String): String = try {
        if (!id.startsWith("home:") || id.length > 8192) invalid()
        val bytes = Base64.getUrlDecoder().decode(id.removePrefix("home:"))
        val path = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        if (encode(path) != id) invalid()
        path
    } catch (_: Exception) { invalid() }

    private fun invalid(): Nothing = throw FileNotFoundException("Linux 文档标识无效或不在主目录内")
}
