package com.promenar.infinity.data.media

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.storage.BoundRealPathRead
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaSource
import io.mockk.*
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class MediaMetadataReaderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val reader get() = MediaMetadataReader(context)

    @After fun cleanup() = unmockkAll()

    @Test fun `视频旋转九十或二百七十度时交换宽高并保留时长`() {
        for (rotation in listOf("90", "270", "-90", "450")) {
            val retriever = mockk<MediaMetadataRetriever> {
                every { extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) } returns "1234"
                every { extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) } returns "1920"
                every { extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) } returns "1080"
                every { extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) } returns rotation
            }
            assertThat(reader.values(retriever, MediaKind.VIDEO)).isEqualTo(MediaMetadata(1080, 1920, 1234))
        }
    }

    @Test fun `无效元数据不泄漏负数或非数字且音频只返回时长`() {
        val retriever = mockk<MediaMetadataRetriever> { every { extractMetadata(any()) } returns null }
        every { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) } returns "2000"
        assertThat(reader.values(retriever, MediaKind.AUDIO)).isEqualTo(MediaMetadata(durationMs = 2000))
        every { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) } returns "-1"
        every { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) } returns "0"
        every { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) } returns "bad"
        assertThat(reader.values(retriever, MediaKind.VIDEO)).isEqualTo(MediaMetadata())
    }

    @Test fun `本地音视频通过已核验文件描述符读取并关闭流`() {
        mockkConstructor(MediaMetadataRetriever::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<FileDescriptor>()) } just Runs
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) } returns null
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) } returns "5678"
        every { anyConstructed<MediaMetadataRetriever>().release() } just Runs
        every { anyConstructed<MediaMetadataRetriever>().close() } just Runs
        val file = File.createTempFile("media-meta-", ".m4a", context.cacheDir)
        var closed = false
        try {
            val value = reader.read(MediaKind.AUDIO, MediaSource.LocalFile(file)) {
                val stream = FileInputStream(file)
                MediaFileStream(object : BoundRealPathRead {
                    override val input = stream
                    override fun state() = error("元数据读取不需要重新按路径定位")
                    override fun close() { closed = true; stream.close() }
                })
            }
            assertThat(value.durationMs).isEqualTo(5678)
            assertThat(closed).isTrue()
        } finally { file.delete() }
    }
}
