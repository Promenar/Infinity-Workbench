package com.promenar.nexara.data.media

import com.promenar.nexara.domain.media.MediaKind
import java.util.Locale

internal data class SniffedMediaType(val mimeType: String, val kind: MediaKind)

internal object MediaTypeSniffer {
    private val extensions = linkedMapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "svg" to "image/svg+xml",
        "mp4" to "video/mp4", "mov" to "video/quicktime", "m4v" to "video/x-m4v",
        "webm" to "video/webm", "mkv" to "video/x-matroska", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "opus" to "audio/ogg",
        "flac" to "audio/flac", "aac" to "audio/aac", "m4a" to "audio/mp4",
        "pdf" to "application/pdf", "txt" to "text/plain", "json" to "application/json", "zip" to "application/zip",
    )
    private val unknown = type("application/octet-stream")

    fun sniff(header: ByteArray, name: String?): SniffedMediaType =
        magic(header, name) ?: byName(name) ?: unknown

    fun forStorage(header: ByteArray, hint: String?, name: String?): SniffedMediaType =
        magic(header, name) ?: hint?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            ?.let { if (it in extensions.values) type(it) else null } ?: byName(name) ?: unknown

    fun extension(mimeType: String, suggestedName: String?): String =
        extensions.entries.firstOrNull { it.value == mimeType }?.key
            ?: suggestedName?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
                ?.takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "bin"

    private fun byName(name: String?) = name?.substringAfterLast('/')?.substringAfterLast('.', "")
        ?.lowercase(Locale.ROOT)?.let(extensions::get)?.let(::type)

    private fun type(mime: String) = SniffedMediaType(mime, when (mime.substringBefore('/')) {
        "image" -> MediaKind.IMAGE
        "video" -> MediaKind.VIDEO
        "audio" -> MediaKind.AUDIO
        else -> MediaKind.FILE
    })

    private fun magic(bytes: ByteArray, name: String?): SniffedMediaType? {
        fun at(offset: Int, signature: String) = bytes.size >= offset + signature.length &&
            signature.indices.all { bytes[offset + it].toInt() and 0xff == signature[it].code }
        val mime = when {
            bytes.size >= 8 && at(0, "\u0089PNG\r\n\u001a\n") -> "image/png"
            at(0, "\u00ff\u00d8\u00ff") -> "image/jpeg"
            at(0, "GIF87a") || at(0, "GIF89a") -> "image/gif"
            at(0, "RIFF") && at(8, "WEBP") -> "image/webp"
            at(0, "BM") -> "image/bmp"
            at(4, "ftyp") -> return bmff(bytes, name)
            at(0, "\u001a\u0045\u00df\u00a3") -> {
                val text = bytes.toString(Charsets.ISO_8859_1)
                if ("webm" in text) "video/webm" else if ("matroska" in text) "video/x-matroska"
                else byName(name)?.mimeType?.takeIf { it == "video/webm" } ?: "video/x-matroska"
            }
            at(0, "RIFF") && at(8, "WAVE") -> "audio/wav"
            at(0, "ID3") -> "audio/mpeg"
            at(0, "OggS") -> "audio/ogg"
            at(0, "fLaC") -> "audio/flac"
            bytes.size >= 2 && bytes[0].toInt() and 0xff == 0xff && bytes[1].toInt() and 0xf6 == 0xf0 -> "audio/aac"
            bytes.size >= 2 && bytes[0].toInt() and 0xff == 0xff && bytes[1].toInt() and 0xe0 == 0xe0 &&
                bytes[1].toInt() and 0x06 != 0 && bytes[1].toInt() and 0x18 != 0x08 -> "audio/mpeg"
            at(0, "%PDF-") -> "application/pdf"
            else -> {
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
                if (Regex("^(?:<\\?xml[\\s\\S]*?\\?>\\s*)?<svg(?:\\s|/|>)").containsMatchIn(text)) "image/svg+xml" else null
            }
        }
        return mime?.let(::type)
    }

    private fun bmff(bytes: ByteArray, name: String?): SniffedMediaType? {
        if (bytes.size < 16) return null
        val shortSize = (0..3).fold(0L) { size, index -> (size shl 8) or (bytes[index].toLong() and 0xff) }
        val brandOffset = if (shortSize == 1L) 16 else 8
        if (bytes.size < brandOffset + 8) return null
        val boxSize = when (shortSize) {
            1L -> java.nio.ByteBuffer.wrap(bytes, 8, 8).long
            0L -> bytes.size.toLong()
            else -> shortSize
        }
        if (boxSize < brandOffset + 8) return null
        // 只读取 ftyp 自己的品牌，不把后续媒体数据里的文字当作品牌。
        val limit = minOf(boxSize, bytes.size.toLong()).toInt()
        fun brand(offset: Int) = String(bytes, offset, 4, Charsets.ISO_8859_1)
        val brands = listOf(brand(brandOffset)) + (brandOffset + 8..limit - 4 step 4).map(::brand)
        val mime = when {
            brands.any { it in setOf("avif", "avis") } -> "image/avif"
            brands.any { it in setOf("heic", "heix", "hevc", "hevx") } -> "image/heic"
            brands.any { it in setOf("mif1", "msf1") } -> "image/heif"
            brands.any { it in setOf("M4A ", "M4B ", "M4P ", "F4A ", "F4B ") } -> "audio/mp4"
            "qt  " in brands -> "video/quicktime"
            brands.any { it in setOf("M4V ", "M4VH", "M4VP") } -> "video/x-m4v"
            brands.any { it.startsWith("3gp") || it.startsWith("3g2") } -> "video/3gpp"
            brands.any { it in setOf("mp41", "mp42", "avc1") } -> "video/mp4"
            brands.any { it == "isom" || it.matches(Regex("iso[2-9]")) } ->
                byName(name)?.mimeType?.takeIf { it in setOf("audio/mp4", "video/mp4", "video/quicktime", "video/x-m4v", "video/3gpp") } ?: "video/mp4"
            else -> return null
        }
        return type(mime)
    }
}
