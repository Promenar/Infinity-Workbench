package com.promenar.nexara.data.generation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.remote.protocol.*
import com.promenar.nexara.domain.media.*
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewedMediaContextTest {
    private fun imageBytes(width: Int, height: Int, alpha: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if (alpha) Color.argb(80, 25, 30, 60) else Color.RED)
        bitmap.setHasAlpha(alpha)
        return try { ByteArrayOutputStream().also { bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray() } finally { bitmap.recycle() }
    }
    @Test fun `大图采样缩放长边到2048且透明保留PNG`() = runBlocking {
        val transparent = imageBytes(5000, 2500, true)
        val encoded = preprocessImage { ByteArrayInputStream(transparent) }
        assertThat(encoded.mimeType).isEqualTo("image/png")
        val bitmap = BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size)
        try { assertThat(bitmap.width).isEqualTo(2048); assertThat(bitmap.height).isEqualTo(1024); assertThat(bitmap.getPixel(0, 0).ushr(24)).isLessThan(255) } finally { bitmap.recycle() }
        val opaque = imageBytes(200, 100, false)
        assertThat(preprocessImage { ByteArrayInputStream(opaque) }.mimeType).isEqualTo("image/jpeg")
        assertThat(encoded.bytes.size).isAtMost(VIEW_MEDIA_MAX_IMAGE_BYTES)
        assertThat(scaledMediaDimensions(3840, 2160, 1024)).isEqualTo(1024 to 576)
    }
    @Test fun `高熵透明图超过单图上限时拒绝`() {
        val bitmap = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(3)
        val pixels = IntArray(2048 * 2048) { random.nextInt() }
        bitmap.setPixels(pixels, 0, 2048, 0, 0, 2048, 2048)
        try {
            val failure = assertThrows(IllegalArgumentException::class.java) { encodeBitmap(bitmap, true, 2048) }
            assertThat(failure.message).contains("4 MiB")
        } finally { bitmap.recycle() }
    }
    @Test fun `当前轮重复请求加载历史轮降级且修改删除不发送过期媒体`() = runBlocking {
        val root = Files.createTempDirectory("view-media-test").toFile()
        try {
            val file = File(root, "source.png").apply { writeBytes(imageBytes(100, 50, true)) }
            val resolver = object : WorkspaceMediaResolver {
                override suspend fun resolve(sessionId: String, path: String): MediaResolution = if (file.exists()) MediaResolution.Available(MediaRef(path, "image/png", MediaKind.IMAGE, file.length()), MediaSource.LocalFile(file)) else MediaResolution.Unavailable(path, MediaUnavailableReason.NOT_FOUND)
                override suspend fun open(resolution: MediaResolution.Available) = file.inputStream()
            }
            val cache = File(root, "cache")
            val processor = AndroidMediaViewPreprocessor(RuntimeEnvironment.getApplication(), resolver, cache, 2048)
            val viewer = WorkspaceMediaViewer(resolver, processor)
            val target = MediaViewTarget(ModelCapabilities(vision = true))
            val result = viewer.view("s", MediaViewRequest(listOf("/a.png")), target)
            assertThat(result.viewedMedia).hasSize(1)
            val session = Session("s", "a", messages = listOf(
                Message("u", MessageRole.USER, "查看"),
                Message("a", MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("call", "view_media", "{}"))),
                Message("t", MessageRole.TOOL, result.content, toolCallId = "call", name = "view_media", viewedMedia = result.viewedMedia),
            ))
            val strategy = DefaultChatGenerationContentStrategy(mockk(relaxed = true), null)
            val callingThread = Thread.currentThread()
            val loadingThreads = mutableListOf<Thread>()
            val loader = ViewedMediaContextLoader(viewer) { _, _ -> loadingThreads += Thread.currentThread(); target }
            val messages = strategy.buildProtocolMessages(session, "")
            repeat(2) { assertThat(loader.attach(session, messages, "r").last().imageUrls).hasSize(1) }
            assertThat(loadingThreads.all { it !== callingThread }).isTrue()
            assertThat(cache.listFiles().orEmpty().sumOf { it.length() }).isAtMost(2048L)
            val historicalSession = session.copy(messages = session.messages + Message("next", MessageRole.USER, "第二轮"))
            val historical = loader.attach(historicalSession, strategy.buildProtocolMessages(historicalSession, ""), "r").first { it.role == "tool" }
            assertThat(historical.imageUrls).isNull()
            assertThat(historical.content).contains("已查看 /a.png（原文件）")
            val sent = mutableListOf<PromptRequest>()
            val protocol = object : LlmProtocol {
                override val protocolType = ProtocolType.Local
                override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
                    sent += request
                    return flowOf(StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN))
                }
                override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
                override fun cancel() = Unit
            }
            val provider = com.promenar.nexara.data.remote.provider.LlmProvider(protocol)
            val store = com.promenar.nexara.ui.chat.ChatStore().apply { upsertSession(session) }
            val runtime = DefaultChatGenerationRuntime(
                mockk(relaxed = true), this, store, mockk(), mockk(), mockk(), mockk(), mockk(),
                { provider }, provider, mockk(), mockk(), mockk(), null, mockk(), mockk(), strategy, mockk(),
                mediaContextLoader = loader,
            )
            DefaultChatGenerationRuntime::class.java.getDeclaredField("preparedRoute").apply {
                isAccessible = true; set(runtime, ChatProviderRoute(null, "m", null, useLocalProvider = true, modelId = "p::m"))
            }
            DefaultChatGenerationRuntime::class.java.getDeclaredField("preparedPrompt").apply {
                isAccessible = true; set(runtime, PromptRequest(loader.attach(session, messages, "p::m"), "m"))
            }
            val request = com.promenar.nexara.domain.generation.GenerationRequest("s", "a", "u", "查看", emptyList(), com.promenar.nexara.domain.generation.GenerationRuntimePolicy.BACKGROUND_ALLOWED)
            runtime.stream(request, 0).toList()
            assertThat(sent.last().messages.last().imageUrls).hasSize(1)
            file.writeBytes(imageBytes(100, 50, false))
            val changed = loader.attach(session, messages, "r").last()
            assertThat(changed.imageUrls).isNull()
            assertThat(changed.content).contains("文件已被修改")
            runtime.stream(request, 1).toList()
            assertThat(sent.last().messages.last().imageUrls).isNull()
            assertThat(sent.last().messages.last().content).contains("文件已被修改")
            file.delete()
            runtime.stream(request, 2).toList()
            assertThat(sent.last().messages.last().imageUrls).isNull()
            val removed = loader.attach(session, messages, "r").last()
            assertThat(removed.imageUrls).isNull()
            assertThat(removed.content).contains("文件已删除或不可读")
        } finally { root.deleteRecursively() }
    }
    @Test fun `媒体按固定保守值估算不按base64长度计费`() {
        val tool = ProtocolMessage("tool", "说明", imageUrls = listOf(ImageInput(base64 = "x".repeat(5000))), audioData = listOf(AudioInput("aaa")), videoData = listOf(VideoInput("bbb")))
        assertThat(ApproximateTokenEstimator.estimate(tool) - ApproximateTokenEstimator.estimate(tool.copy(imageUrls = null, audioData = null, videoData = null)))
            .isEqualTo(2048 + 32768 + 65536)
    }
}
