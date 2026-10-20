package com.promenar.nexara.data.media

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.media.MediaKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class MediaTypeSnifferTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("formats")
    fun `文件头优先于冲突扩展名`(sample: Sample) {
        val type = MediaTypeSniffer.sniff(sample.header, sample.name)
        assertThat(type.mimeType).isEqualTo(sample.mime)
        assertThat(type.kind).isEqualTo(sample.kind)
    }

    @Test fun `未知和截断文件头保持普通文件`() {
        for (header in listOf(byteArrayOf(), byteArrayOf(0xff.toByte()), "RIFF".toByteArray(), "ftyp".toByteArray())) {
            assertThat(MediaTypeSniffer.sniff(header, "blob")).isEqualTo(SniffedMediaType("application/octet-stream", MediaKind.FILE))
        }
        assertThat(MediaTypeSniffer.sniff("not <svg>".toByteArray(), "blob").kind).isEqualTo(MediaKind.FILE)
    }

    @Test fun `扩展名兜底忽略大小写且不把路径当扩展名`() {
        val names = mapOf(
            "a.PNG" to "image/png", "a.jpeg" to "image/jpeg", "a.gif" to "image/gif",
            "a.webp" to "image/webp", "a.bmp" to "image/bmp", "a.heic" to "image/heic",
            "a.heif" to "image/heif", "a.avif" to "image/avif", "a.svg" to "image/svg+xml",
            "a.mp4" to "video/mp4", "a.mov" to "video/quicktime", "a.m4v" to "video/x-m4v",
            "a.webm" to "video/webm", "a.mkv" to "video/x-matroska", "a.3gp" to "video/3gpp",
            "a.mp3" to "audio/mpeg", "a.wav" to "audio/wav", "a.ogg" to "audio/ogg",
            "a.opus" to "audio/ogg", "a.flac" to "audio/flac", "a.aac" to "audio/aac",
            "a.m4a" to "audio/mp4", "a.pdf" to "application/pdf",
        )
        names.forEach { (name, mime) -> assertThat(MediaTypeSniffer.sniff(byteArrayOf(), name).mimeType).isEqualTo(mime) }
        assertThat(MediaTypeSniffer.sniff(byteArrayOf(), "folder.png/blob").kind).isEqualTo(MediaKind.FILE)
    }

    data class Sample(val label: String, val header: ByteArray, val mime: String, val kind: MediaKind, val name: String = "wrong.bin") {
        override fun toString() = label
    }

    companion object {
        private fun hex(raw: String) = raw.split(' ').map { it.toInt(16).toByte() }.toByteArray()
        private fun bmff(vararg brands: String) = byteArrayOf(0, 0, 0, (16 + (brands.size - 1) * 4).toByte()) +
            "ftyp${brands.first()}".toByteArray() + ByteArray(4) + brands.drop(1).joinToString("").toByteArray()

        @JvmStatic fun formats(): Stream<Sample> = listOf(
            Sample("PNG", hex("89 50 4e 47 0d 0a 1a 0a"), "image/png", MediaKind.IMAGE, "wrong.mp3"),
            Sample("JPEG", hex("ff d8 ff e0"), "image/jpeg", MediaKind.IMAGE),
            Sample("GIF87", "GIF87a".toByteArray(), "image/gif", MediaKind.IMAGE),
            Sample("GIF89", "GIF89a".toByteArray(), "image/gif", MediaKind.IMAGE),
            Sample("WebP", "RIFF0000WEBP".toByteArray(), "image/webp", MediaKind.IMAGE),
            Sample("BMP", "BM0000".toByteArray(), "image/bmp", MediaKind.IMAGE),
            Sample("HEIC", bmff("heic"), "image/heic", MediaKind.IMAGE),
            Sample("HEIF", bmff("mif1"), "image/heif", MediaKind.IMAGE),
            Sample("HEIC兼容品牌", bmff("mif1", "heic"), "image/heic", MediaKind.IMAGE),
            Sample("AVIF", bmff("avif"), "image/avif", MediaKind.IMAGE),
            Sample("AVIF兼容品牌", bmff("mif1", "avis"), "image/avif", MediaKind.IMAGE),
            Sample("SVG", " \n<svg xmlns='x'>".toByteArray(), "image/svg+xml", MediaKind.IMAGE),
            Sample("XML SVG", "\uFEFF<?xml version='1.0'?>\n<svg>".toByteArray(), "image/svg+xml", MediaKind.IMAGE),
            Sample("MP4", bmff("mp42"), "video/mp4", MediaKind.VIDEO, "wrong.m4a"),
            Sample("MOV", bmff("qt  "), "video/quicktime", MediaKind.VIDEO),
            Sample("M4V", bmff("M4V "), "video/x-m4v", MediaKind.VIDEO),
            Sample("3GP", bmff("3gp6"), "video/3gpp", MediaKind.VIDEO),
            Sample("M4A", bmff("M4A ", "isom"), "audio/mp4", MediaKind.AUDIO, "wrong.mp4"),
            Sample("BMFF音频扩展名", bmff("isom"), "audio/mp4", MediaKind.AUDIO, "voice.m4a"),
            Sample("BMFF不能按伪装MP3改变容器", bmff("isom"), "video/mp4", MediaKind.VIDEO, "voice.mp3"),
            Sample("64位ftyp不扫描后续mdat", byteArrayOf(0, 0, 0, 1) + "ftyp".toByteArray() +
                byteArrayOf(0, 0, 0, 0, 0, 0, 0, 24) + "isom".toByteArray() + ByteArray(4) +
                byteArrayOf(0, 0, 0, 12) + "mdatM4A ".toByteArray(), "video/mp4", MediaKind.VIDEO),
            Sample("BMFF未知品牌", bmff("zzzz"), "application/octet-stream", MediaKind.FILE),
            Sample("WebM", hex("1a 45 df a3") + "xxxxwebm".toByteArray(), "video/webm", MediaKind.VIDEO),
            Sample("Matroska", hex("1a 45 df a3") + "xxxxmatroska".toByteArray(), "video/x-matroska", MediaKind.VIDEO),
            Sample("MP3 ID3", "ID3".toByteArray(), "audio/mpeg", MediaKind.AUDIO),
            Sample("MP3同步帧", hex("ff fb 90 64"), "audio/mpeg", MediaKind.AUDIO),
            Sample("WAV", "RIFF0000WAVE".toByteArray(), "audio/wav", MediaKind.AUDIO),
            Sample("OGG", "OggS0000".toByteArray(), "audio/ogg", MediaKind.AUDIO),
            Sample("Opus", "OggS0000OpusHead".toByteArray(), "audio/ogg", MediaKind.AUDIO),
            Sample("FLAC", "fLaC".toByteArray(), "audio/flac", MediaKind.AUDIO),
            Sample("AAC ADTS", hex("ff f1 50 80"), "audio/aac", MediaKind.AUDIO),
            Sample("PDF", "%PDF-1.7".toByteArray(), "application/pdf", MediaKind.FILE, "wrong.png"),
        ).stream()
    }
}
