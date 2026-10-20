package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.remote.protocol.*
import com.promenar.nexara.domain.media.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class WorkspaceMediaViewerTest {
    private val vision = MediaViewTarget(ModelCapabilities(vision = true))
    private fun image(path: String) = MediaRef(path, "image/png", MediaKind.IMAGE, 10, width = 20, height = 10)
    private class Resolver(val refs: MutableMap<String, MediaRef>) : WorkspaceMediaResolver {
        override suspend fun resolve(sessionId: String, path: String): MediaResolution = refs[path]?.let { MediaResolution.Available(it, MediaSource.LocalFile(File("unused"))) }
            ?: MediaResolution.Unavailable(path, MediaUnavailableReason.NOT_FOUND)
        override suspend fun open(resolution: MediaResolution.Available) = ByteArrayInputStream(byteArrayOf(1))
    }
    private fun processor(size: Int = 3) = MediaViewPreprocessor { source, mode, times, _ ->
        PreparedViewedMedia(ViewedMedia(source.ref.copy(sha256 = "identity"), mode, times),
            if (mode == MediaViewMode.KEYFRAMES) times.map { EncodedViewedMedia(MediaKind.IMAGE, "image/jpeg", ByteArray(size), it) }
            else listOf(EncodedViewedMedia(source.ref.kind, source.ref.mimeType, ByteArray(size))))
    }
    @Test fun `部分不可用与普通文件不影响其它路径且结果没有媒体字节`() = runTest {
        val viewer = WorkspaceMediaViewer(Resolver(mutableMapOf("/ok" to image("/ok"), "/file" to MediaRef("/file", "text/plain", MediaKind.FILE, 5))), processor())
        val result = viewer.view("s", MediaViewRequest(listOf("/missing", "/file", "/ok")), vision)
        assertThat(result.media?.map { it.path }).containsExactly("/ok")
        assertThat(result.viewedMedia?.single()?.ref?.sha256).isEqualTo("identity")
        assertThat(result.content).contains("文件不存在")
        assertThat(result.content).contains("不是可查看")
        assertThat(result.content).doesNotContain("base64")
        assertThat(result.data).isNull()
    }
    @Test fun `单次媒体字节超限按顺序截断后续路径`() = runTest {
        val refs = (1..3).associate { "/$it" to MediaRef("/$it", "audio/wav", MediaKind.AUDIO, 11L * 1024 * 1024) }.toMutableMap()
        val result = WorkspaceMediaViewer(Resolver(refs), processor(11 * 1024 * 1024)).view("s", MediaViewRequest(refs.keys.toList()), MediaViewTarget(ModelCapabilities(audioInput = true)))
        assertThat(result.media?.map { it.path }).containsExactly("/1")
        assertThat(result.content).contains("20 MiB")
        assertThat(result.content).contains("/3")
    }
    @Test fun `视频回退帧带时间戳且可以强制降级`() = runTest {
        val video = MediaRef("/v", "video/mp4", MediaKind.VIDEO, 20, durationMs = 80000)
        val viewer = WorkspaceMediaViewer(Resolver(mutableMapOf("/v" to video)), processor())
        val fallback = viewer.view("s", MediaViewRequest(listOf("/v")), vision)
        assertThat(fallback.viewedMedia?.single()?.frameTimestampsSeconds).hasSize(8)
        assertThat(fallback.content).contains("以下为关键帧，不含声音")
        assertThat(fallback.content).contains("t=5s")
        val native = MediaViewTarget(ModelCapabilities(vision = true, videoUnderstanding = true), ProtocolType.Google_Gemini)
        val original = viewer.view("s", MediaViewRequest(listOf("/v")), native)
        assertThat(original.viewedMedia?.single()?.mode).isEqualTo(MediaViewMode.ORIGINAL)
        val forced = viewer.view("s", MediaViewRequest(listOf("/v"), asFrames = true, frames = 2), native)
        assertThat(forced.viewedMedia?.single()?.frameTimestampsSeconds).containsExactly(20.0, 60.0)
    }
    @Test fun `多视频共享单调用三十二帧上限`() = runTest {
        val refs = (1..5).associate { "/$it" to MediaRef("/$it", "video/mp4", MediaKind.VIDEO, 20, durationMs = 80000) }.toMutableMap()
        val result = WorkspaceMediaViewer(Resolver(refs), processor()).view("s", MediaViewRequest(refs.keys.toList()), vision)
        assertThat(result.viewedMedia.orEmpty().sumOf { it.frameTimestampsSeconds.size }).isEqualTo(32)
        assertThat(result.media).hasSize(4)
        assertThat(result.content).contains("单次调用 32 帧")
    }

    @Test fun `模态与协议拒绝带元数据且不调用预处理`() = runTest {
        val audio = MediaRef("/a", "audio/ogg", MediaKind.AUDIO, 123, durationMs = 2300)
        val video = MediaRef("/v", "video/mp4", MediaKind.VIDEO, 456, width = 10, height = 20, durationMs = 3000)
        var calls = 0
        val viewer = WorkspaceMediaViewer(Resolver(mutableMapOf("/a" to audio, "/v" to video, "/i" to image("/i"))), MediaViewPreprocessor { _, _, _, _ -> calls++; error("不应读取") })
        val result = viewer.view("s", MediaViewRequest(listOf("/a")), vision)
        assertThat(result.content).contains("当前模型不支持音频输入")
        assertThat(result.content).contains("MIME=audio/ogg")
        assertThat(result.content).contains("123 字节")
        val textOnly = viewer.view("s", MediaViewRequest(listOf("/a")), MediaViewTarget())
        assertThat(textOnly.status).isEqualTo("error")
        val audioOnly = MediaViewTarget(ModelCapabilities(audioInput = true))
        val unsupported = viewer.view("s", MediaViewRequest(listOf("/v", "/i", "/a")), audioOnly)
        assertThat(unsupported.content).contains("当前模型不支持视频输入")
        assertThat(unsupported.content).contains("不支持图片输入")
        assertThat(unsupported.content).contains("不执行转码")
        assertThat(calls).isEqualTo(0)
    }
    @Test fun `所有能力组合统一决定广告与执行重验`() = runTest {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getStringSet(any(), null) } returns null
        val registry = mockk<com.promenar.nexara.ui.chat.manager.registry.SkillRegistry>()
        val tool = ProtocolTool(function = ProtocolToolFunction("view_media", "查看", "{}"), runtimeToolId = "view_media")
        every { registry.getAllTools(null) } returns listOf(tool)
        for (mask in 0..7) {
            var caps = ModelCapabilities(vision = mask and 1 != 0, audioInput = mask and 2 != 0, videoUnderstanding = mask and 4 != 0)
            val resolver = DefaultSessionToolResolver(prefs, registry) { caps }
            val session = Session("s", "a")
            assertThat(resolver.resolve(session).isNotEmpty()).isEqualTo(mask != 0)
            assertThat(resolver.resolveForExecution(session)).isEqualTo(resolver.resolve(session))
            caps = ModelCapabilities()
            assertThat(resolver.resolveForExecution(session)).isEmpty()
        }
    }
    @Test fun `不合法路径数与取消保持明确错误`() = runTest {
        val viewer = WorkspaceMediaViewer(Resolver(mutableMapOf("/ok" to image("/ok"))), MediaViewPreprocessor { _, _, _, _ -> throw CancellationException("取消") })
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { viewer.view("s", MediaViewRequest(emptyList()), vision) } }
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { viewer.view("s", MediaViewRequest(listOf("/ok")), vision) } }
    }
}
