package com.promenar.nexara.ui.renderer

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

internal class RichContentDocumentSession {
    private data class Document(val url: String, val source: String, val html: String)
    @Volatile private var document: Document? = null

    val currentUrl: String? get() = document?.url

    fun register(html: String): String {
        document?.takeIf { it.source == html }?.let { return it.url }
        val next = Document(
            RichContentRequestPolicy.BASE_URL + "preview-${UUID.randomUUID()}.html",
            html,
            secureRichContentDocument(html),
        )
        document = next
        return next.url
    }

    fun contentFor(url: String, isMainFrame: Boolean): String? =
        document?.takeIf { isMainFrame && it.url == url }?.html
}

internal data class RichContentInlineResource(val mimeType: String, val bytes: ByteArray)

private const val MAX_INLINE_RESOURCE_BYTES = 4 * 1024 * 1024
private val inlineHeader = Regex(
    "data:(image/(?:png|gif|webp|jpeg|svg\\+xml|bmp|avif|x-icon)|font/(?:woff2?|ttf|otf)|application/(?:font-woff|font-sfnt|vnd.ms-fontobject))(?:;charset=[a-z0-9_-]+)?(;base64)?,",
    RegexOption.IGNORE_CASE,
)

internal fun richContentInlineMimeType(url: String): String? {
    if (url.length > MAX_INLINE_RESOURCE_BYTES * 3 + 256) return null
    return inlineHeader.find(url)?.takeIf { it.range.first == 0 }?.groupValues?.get(1)?.lowercase()
}

internal fun richContentInlineResource(url: String, isMainFrame: Boolean): RichContentInlineResource? {
    if (isMainFrame) return null
    val type = richContentInlineMimeType(url) ?: return null
    val header = inlineHeader.find(url) ?: return null
    val payload = url.substring(header.range.last + 1)
    val bytes = try {
        if (header.groupValues[2].isNotEmpty()) Base64.getDecoder().decode(payload) else {
            val output = ByteArrayOutputStream()
            var index = 0
            var start = 0
            while (index < payload.length) {
                if (payload[index] == '%') {
                    output.write(payload.substring(start, index).toByteArray(Charsets.UTF_8))
                    if (index + 2 >= payload.length) return null
                    val hex = payload.substring(index + 1, index + 3)
                    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
                    val byte = hex.toInt(16)
                    output.write(byte)
                    index += 3
                    start = index
                } else index++
                if (output.size() > MAX_INLINE_RESOURCE_BYTES) return null
            }
            output.write(payload.substring(start).toByteArray(Charsets.UTF_8))
            output.toByteArray()
        }
    } catch (_: IllegalArgumentException) {
        return null
    }
    return bytes.takeIf { it.size <= MAX_INLINE_RESOURCE_BYTES }?.let { RichContentInlineResource(type, it) }
}
