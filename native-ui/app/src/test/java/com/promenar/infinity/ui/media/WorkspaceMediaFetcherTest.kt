package com.promenar.infinity.ui.media

import coil3.fetch.SourceFetchResult
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaSource
import com.promenar.infinity.domain.media.MediaUnavailableReason
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class WorkspaceMediaFetcherTest {
    @TempDir
    lateinit var temp: File

    /** 与真实解析器同语义：open 只接受 resolve 返回的那个对象（按对象身份）。 */
    private class FakeResolver(private val result: MediaResolution) : WorkspaceMediaResolver {
        val resolved = mutableListOf<Pair<String, String>>()
        private val issued = mutableListOf<MediaResolution.Available>()
        var opened = 0
        override suspend fun resolve(sessionId: String, path: String): MediaResolution {
            resolved += sessionId to path
            // 每次解析返回新对象，模拟按对象身份绑定。
            return when (result) {
                is MediaResolution.Available -> result.copy().also { issued += it }
                is MediaResolution.Unavailable -> result
            }
        }

        override suspend fun open(resolution: MediaResolution.Available): InputStream {
            require(issued.any { it === resolution }) { "open 只接受 resolve 返回的对象" }
            opened++
            return ByteArrayInputStream("png-bytes".toByteArray())
        }
    }

    private fun ref(path: String, kind: MediaKind, mime: String) = MediaRef(path, mime, kind, sizeBytes = 9)

    private fun fetcher(resolver: WorkspaceMediaResolver, path: String = "/media/a.png") =
        WorkspaceMediaFetcher(WorkspaceMediaModel("s1", path, "sha256:x"), resolver) { null }

    @Test
    fun `真实文件以文件路径交给解码器并带上 MIME`() = runTest {
        val file = File(temp, "a.png").apply { writeText("png") }
        val resolver = FakeResolver(MediaResolution.Available(ref("/media/a.png", MediaKind.IMAGE, "image/png"), MediaSource.LocalFile(file)))

        val result = fetcher(resolver).fetch() as SourceFetchResult

        assertThat(resolver.resolved).containsExactly("s1" to "/media/a.png")
        assertThat(result.mimeType).isEqualTo("image/png")
        assertThat(result.source.fileOrNull()?.toString()).isEqualTo(file.path)
        assertThat(resolver.opened).isEqualTo(0)
    }

    @Test
    fun `视频真实文件同样给出文件路径供首帧解码`() = runTest {
        val file = File(temp, "v.mp4").apply { writeText("mp4") }
        val resolver = FakeResolver(MediaResolution.Available(ref("/media/v.mp4", MediaKind.VIDEO, "video/mp4"), MediaSource.LocalFile(file)))

        val result = fetcher(resolver, "/media/v.mp4").fetch() as SourceFetchResult

        assertThat(result.mimeType).isEqualTo("video/mp4")
        assertThat(result.source.fileOrNull()).isNotNull()
    }

    @Test
    fun `SAF 图片经解析器字节流读取`() = runTest {
        val resolver = FakeResolver(
            MediaResolution.Available(ref("/mnt/相册/a.png", MediaKind.IMAGE, "image/png"), MediaSource.ContentUri("content://x/1")),
        )

        val result = fetcher(resolver, "/mnt/相册/a.png").fetch() as SourceFetchResult

        assertThat(resolver.opened).isEqualTo(1)
        assertThat(result.source.source().readUtf8()).isEqualTo("png-bytes")
    }

    @Test
    fun `每次取数都先解析再以同一对象读取，模型不保存解析结果`() = runTest {
        val resolver = FakeResolver(
            MediaResolution.Available(ref("/mnt/相册/a.png", MediaKind.IMAGE, "image/png"), MediaSource.ContentUri("content://x/1")),
        )

        fetcher(resolver, "/mnt/相册/a.png").fetch()
        fetcher(resolver, "/mnt/相册/a.png").fetch()

        assertThat(resolver.resolved).hasSize(2)
        assertThat(resolver.opened).isEqualTo(2)
    }

    @Test
    fun `不可用解析结果以原因失败`() = runTest {
        val resolver = FakeResolver(MediaResolution.Unavailable("/media/gone.png", MediaUnavailableReason.NOT_FOUND))

        val error = runCatching { fetcher(resolver, "/media/gone.png").fetch() }.exceptionOrNull()

        assertThat(error).isInstanceOf(WorkspaceMediaUnavailableException::class.java)
        error as WorkspaceMediaUnavailableException
        assertThat(error.reason).isEqualTo(MediaUnavailableReason.NOT_FOUND)
        assertThat(error.path).isEqualTo("/media/gone.png")
    }

    @Test
    fun `音频与普通文件不作为图片解码`() = runTest {
        val file = File(temp, "a.mp3").apply { writeText("mp3") }
        val resolver = FakeResolver(MediaResolution.Available(ref("/a.mp3", MediaKind.AUDIO, "audio/mpeg"), MediaSource.LocalFile(file)))

        val error = runCatching { fetcher(resolver, "/a.mp3").fetch() }.exceptionOrNull()

        assertThat(error).isInstanceOf(WorkspaceMediaUnavailableException::class.java)
    }

    @Test
    fun `SAF 视频取不到文件描述符时失败`() = runTest {
        val resolver = FakeResolver(MediaResolution.Available(ref("/mnt/a/v.mp4", MediaKind.VIDEO, "video/mp4"), MediaSource.ContentUri("content://x/2")))

        val error = runCatching { fetcher(resolver, "/mnt/a/v.mp4").fetch() }.exceptionOrNull()

        assertThat((error as WorkspaceMediaUnavailableException).reason).isEqualTo(MediaUnavailableReason.UNREADABLE)
    }

    @Test
    fun `字符串地址映射为工作区模型`() {
        val mapper = WorkspaceMediaUriMapper()
        val model = WorkspaceMediaModel("s1", "/媒体/a b.png", "size:1")
        val options = io.mockk.mockk<coil3.request.Options>(relaxed = true)
        assertThat(mapper.map(model.toUri(), options)).isEqualTo(model)
        assertThat(mapper.map("https://example.com/a.png", options)).isNull()
    }
}
