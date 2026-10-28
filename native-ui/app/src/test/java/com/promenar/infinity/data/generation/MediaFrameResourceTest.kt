package com.promenar.infinity.data.generation

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.media.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaFrameResourceTest {
    @Test fun `本地与ContentUri抽帧有尺寸时间戳且retriever在成功取消时释放`() = runBlocking {
        val root = Files.createTempDirectory("media-frame-resources").toFile()
        try {
            val app = RuntimeEnvironment.getApplication()
            val ref = MediaRef("/v", "video/mp4", MediaKind.VIDEO, 3, durationMs = 10000)
            var source: MediaSource = MediaSource.LocalFile(File(root, "v.mp4"))
            var opens = 0
            var closes = 0
            val resolver = object : WorkspaceMediaResolver {
                override suspend fun resolve(sessionId: String, path: String) = MediaResolution.Available(ref, source)
                override suspend fun open(resolution: MediaResolution.Available): ByteArrayInputStream {
                    opens++
                    return object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) { override fun close() { closes++; super.close() } }
                }
            }
            val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
            every { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) } returns "3840"
            every { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) } returns "2160"
            every { retriever.getScaledFrameAtTime(any(), MediaMetadataRetriever.OPTION_CLOSEST, 1024, 576) } answers {
                Bitmap.createBitmap(1024, 576, Bitmap.Config.ARGB_8888)
            }
            val processor = AndroidMediaViewPreprocessor(app, resolver, File(root, "cache"), retrieverFactory = { retriever })
            val success = processor.prepare(resolver.resolve("s", "/v"), MediaViewMode.KEYFRAMES, listOf(2.5, 7.5), null)
            assertThat(success.parts.map { it.timestampSeconds }).containsExactly(2.5, 7.5).inOrder()
            verify(exactly = 1) { retriever.setDataSource((source as MediaSource.LocalFile).file.path) }
            verify(exactly = 1) { retriever.release() }
            source = MediaSource.ContentUri("content://media/test")
            every { retriever.getScaledFrameAtTime(any(), any(), any(), any()) } throws CancellationException("取消抽帧")
            assertThrows(CancellationException::class.java) { runBlocking {
                processor.prepare(resolver.resolve("s", "/v"), MediaViewMode.KEYFRAMES, listOf(1.0), null)
            } }
            verify(exactly = 1) { retriever.setDataSource(app, Uri.parse("content://media/test")) }
            verify(exactly = 2) { retriever.release() }
            assertThat(closes).isEqualTo(opens)
        } finally { root.deleteRecursively() }
    }
}
