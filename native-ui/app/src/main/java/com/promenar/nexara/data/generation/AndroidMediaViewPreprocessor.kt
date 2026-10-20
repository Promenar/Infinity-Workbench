package com.promenar.nexara.data.generation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.promenar.nexara.domain.media.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** 缓存只存处理后的媒体；读取缓存前必须重新计算工作区原文件的摘要。 */
class AndroidMediaViewPreprocessor(
    private val context: Context,
    private val resolver: WorkspaceMediaResolver,
    private val cacheDirectory: File = File(context.cacheDir, "view_media_v1"),
    private val maxCacheBytes: Long = 80L * 1024 * 1024,
    private val retrieverFactory: () -> MediaMetadataRetriever = { MediaMetadataRetriever() },
) : MediaViewPreprocessor {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun prepare(source: MediaResolution.Available, mode: MediaViewMode, timestamps: List<Double>, expectedSha256: String?): PreparedViewedMedia = withContext(Dispatchers.IO) {
        mutex.withLock {
            cacheDirectory.mkdirs()
            val identity = fingerprint(source)
            require(expectedSha256 == null || identity.first == expectedSha256) { "文件已被修改，请重新调用 view_media 查看；未发送过期媒体。" }
            val key = sha256((source.ref.path + "\u0000" + source.ref.kind + "\u0000" + source.ref.mimeType + "\u0000" + identity.first + "\u0000" + mode + "\u0000" + timestamps.joinToString(",")).toByteArray())
            val manifest = File(cacheDirectory, "$key.json")
            val cached = readCache(manifest)
            if (cached != null) return@withLock PreparedViewedMedia(
                ViewedMedia(source.ref.copy(sha256 = identity.first, sizeBytes = identity.second), mode, cached.mapNotNull { it.timestampSeconds }), cached,
                cacheNote(mode, timestamps, cached),
            )
            coroutineContext.ensureActive()
            val parts = when {
                mode == MediaViewMode.KEYFRAMES -> extractFrames(source, timestamps)
                source.ref.kind == MediaKind.IMAGE -> listOf(preprocessImage { resolver.open(source) })
                else -> {
                    require(identity.second <= VIEW_MEDIA_MAX_BYTES) { "媒体超过单个 20 MiB 上限。" }
                    val bytes = resolver.open(source).use { readBounded(it, VIEW_MEDIA_MAX_BYTES) }
                    listOf(EncodedViewedMedia(source.ref.kind, source.ref.mimeType, bytes))
                }
            }
            require(parts.isNotEmpty()) { "没有可读取的媒体帧。" }
            require(fingerprint(source) == identity) { "文件在读取期间被修改，请重新调用 view_media；未发送过期媒体。" }
            val entries = parts.mapIndexed { index, part ->
                val file = File(cacheDirectory, "$key-$index.bin")
                file.writeBytes(part.bytes)
                CachedMediaPart(file.name, part.kind, part.mimeType, part.timestampSeconds, part.bytes.size)
            }
            manifest.writeText(json.encodeToString(entries))
            trimCache()
            PreparedViewedMedia(ViewedMedia(source.ref.copy(sha256 = identity.first, sizeBytes = identity.second), mode, parts.mapNotNull { it.timestampSeconds }), parts, cacheNote(mode, timestamps, parts))
        }
    }

    private fun cacheNote(mode: MediaViewMode, timestamps: List<Double>, parts: List<EncodedViewedMedia>): String =
        if (mode == MediaViewMode.KEYFRAMES && parts.size < timestamps.size) {
            val next = timestamps.getOrNull(parts.size)
            "媒体总量达到 20 MiB，关键帧已截断；请从 t=${formatMediaSeconds(next ?: 0.0)}s 继续查看。"
        } else ""

    private suspend fun fingerprint(source: MediaResolution.Available): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        resolver.open(source).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                length += count
            }
        }
        return digest.digest().toHex() to length
    }

    private fun readCache(manifest: File): List<EncodedViewedMedia>? = try {
        if (!manifest.isFile || manifest.length() > 64 * 1024) null else {
            val entries = json.decodeFromString<List<CachedMediaPart>>(manifest.readText())
            if (entries.isEmpty() || entries.size > 32 || entries.sumOf { it.size.toLong() } > VIEW_MEDIA_MAX_BYTES) null else {
                val parts = entries.map { entry ->
                    require(entry.file.matches(Regex("[a-f0-9]{64}-[0-9]+\\.bin")))
                    val file = File(cacheDirectory, entry.file)
                    require(entry.size in 1..VIEW_MEDIA_MAX_BYTES && file.isFile && file.length() == entry.size.toLong())
                    file.setLastModified(System.currentTimeMillis())
                    EncodedViewedMedia(entry.kind, entry.mimeType, file.readBytes(), entry.timestampSeconds)
                }
                manifest.setLastModified(System.currentTimeMillis())
                parts
            }
        }
    } catch (_: Exception) { null }

    private fun trimCache() {
        val files = cacheDirectory.listFiles().orEmpty().filter(File::isFile).sortedBy(File::lastModified)
        var total = files.sumOf(File::length)
        files.forEach { file ->
            if (total > maxCacheBytes) { val size = file.length(); if (file.delete()) total -= size }
        }
    }

    private suspend fun extractFrames(source: MediaResolution.Available, timestamps: List<Double>): List<EncodedViewedMedia> {
        require(timestamps.isNotEmpty() && timestamps.size <= 32 && timestamps.all { it.isFinite() && it >= 0 }) { "关键帧时间戳无效。" }
        val retriever = retrieverFactory()
        try {
            when (val mediaSource = source.source) {
                is MediaSource.LocalFile -> retriever.setDataSource(mediaSource.file.path)
                is MediaSource.ContentUri -> retriever.setDataSource(context, Uri.parse(mediaSource.uri))
            }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val size = scaledMediaDimensions(width ?: 1024, height ?: 1024, 1024)
            val parts = mutableListOf<EncodedViewedMedia>()
            var bytes = 0
            for (timestamp in timestamps) {
                coroutineContext.ensureActive()
                val bitmap = retriever.getScaledFrameAtTime((timestamp * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, size.first, size.second)
                    ?: throw IllegalArgumentException("无法读取 t=${formatMediaSeconds(timestamp)}s 的关键帧。")
                val encoded = try { encodeBitmap(bitmap, false, 1024) } finally { bitmap.recycle() }
                if (bytes + encoded.bytes.size > VIEW_MEDIA_MAX_BYTES) break
                parts += encoded.copy(timestampSeconds = timestamp)
                bytes += encoded.bytes.size
            }
            return parts
        } finally { runCatching { retriever.release() } }
    }
}

@Serializable
private data class CachedMediaPart(val file: String, val kind: MediaKind, val mimeType: String, val timestampSeconds: Double?, val size: Int)

internal fun scaledMediaDimensions(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxEdge > 0) { "无法识别媒体尺寸。" }
    val ratio = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
    return maxOf(1, (width * ratio).roundToInt()) to maxOf(1, (height * ratio).roundToInt())
}

internal suspend fun preprocessImage(open: suspend () -> InputStream): EncodedViewedMedia {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码。" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
    coroutineContext.ensureActive()
    val bitmap = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: throw IllegalArgumentException("图片无法解码。")
    return try { encodeBitmap(bitmap, bitmap.hasAlpha(), 2048) } finally { bitmap.recycle() }
}

internal fun encodeBitmap(bitmap: Bitmap, preserveAlpha: Boolean, maxEdge: Int): EncodedViewedMedia {
    val size = scaledMediaDimensions(bitmap.width, bitmap.height, maxEdge)
    val scaled = if (size.first == bitmap.width && size.second == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, size.first, size.second, true)
    try {
        val output = ByteArrayOutputStream()
        val format = if (preserveAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        require(scaled.compress(format, 85, output)) { "图片编码失败。" }
        require(output.size() <= VIEW_MEDIA_MAX_IMAGE_BYTES) { "图片编码后超过单张 4 MiB 上限。" }
        return EncodedViewedMedia(MediaKind.IMAGE, if (preserveAlpha) "image/png" else "image/jpeg", output.toByteArray())
    } finally { if (scaled !== bitmap) scaled.recycle() }
}

private suspend fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        coroutineContext.ensureActive()
        val count = stream.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        require(output.size().toLong() + count <= maxBytes) { "媒体超过单个 20 MiB 上限。" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
