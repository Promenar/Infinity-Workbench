package com.promenar.nexara.data.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import com.promenar.nexara.domain.media.MediaKind
import com.promenar.nexara.domain.media.MediaSource
import java.io.InputStream
import kotlinx.coroutines.CancellationException

internal data class MediaMetadata(val width: Int? = null, val height: Int? = null, val durationMs: Long? = null)

/** 仅取图片边界与音视频元数据；不解码整张图片或提取视频帧。 */
internal class MediaMetadataReader(private val context: Context) {
    fun read(kind: MediaKind, source: MediaSource, open: () -> InputStream): MediaMetadata = try {
        when (kind) {
            MediaKind.IMAGE -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open().use { BitmapFactory.decodeStream(it, null, options) }
                MediaMetadata(options.outWidth.takeIf { it > 0 }, options.outHeight.takeIf { it > 0 })
            }
            MediaKind.AUDIO, MediaKind.VIDEO -> MediaMetadataRetriever().use { retriever ->
                when (source) {
                    is MediaSource.ContentUri -> context.contentResolver.openAssetFileDescriptor(source.uri.toUri(), "r")?.use { descriptor ->
                        if (descriptor.declaredLength >= 0) retriever.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
                        else retriever.setDataSource(descriptor.fileDescriptor)
                        values(retriever, kind)
                    } ?: MediaMetadata()
                    is MediaSource.LocalFile -> open().use { stream ->
                        val descriptor = (stream as? MediaFileStream)?.descriptor
                        if (descriptor == null) MediaMetadata() else {
                            retriever.setDataSource(descriptor)
                            values(retriever, kind)
                        }
                    }
                }
            }
            MediaKind.FILE -> MediaMetadata()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        MediaMetadata()
    }

    internal fun values(retriever: MediaMetadataRetriever, kind: MediaKind): MediaMetadata {
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it >= 0 }
        if (kind != MediaKind.VIDEO) return MediaMetadata(durationMs = duration)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 }
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 }
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        return if ((rotation % 360 + 360) % 360 in setOf(90, 270)) MediaMetadata(height, width, duration) else MediaMetadata(width, height, duration)
    }
}
