package com.promenar.nexara.ui.chat.manager.skills

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.media.download.WorkspaceMediaDownloader
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.rag.ImageGenClient
import com.promenar.nexara.domain.media.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class ImageGenerationWorkspaceTest {
    private val store = RecordingMediaStore()
    private val providers = mockk<ProviderManager> {
        every { getMainProviderConfig() } returns ProviderConfig(baseUrl = "https://images.test", apiKey = "test-key")
        every { imageModelId } returns MutableStateFlow("image-test")
    }
    private val downloader = WorkspaceMediaDownloader(OkHttpClient.Builder().addInterceptor {
        Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .header("Content-Type", "image/png").body(mediaTestPng.toResponseBody()).build()
    }.build(), MediaFetchUrlPolicy { false }, { false }, { false })

    private suspend fun generate(vararg images: JsonObject): com.promenar.nexara.data.model.ToolResult {
        val client = HttpClient(MockEngine { respond(buildJsonObject { put("data", JsonArray(images.toList())) }.toString()) })
        return try {
            val skill = ImageGenerationSkill(mockk<Context>(), providers, store, downloader) { url, key, model -> ImageGenClient(url, key, model, client) }
            skill.execute(buildJsonObject { put("prompt", "一棵树") }, mediaTestContext)
        } finally { client.close() }
    }
    private fun base64Image(raw: String = mediaBase64()) = buildJsonObject { put("b64_json", raw); put("revised_prompt", "模型修订的提示词") }

    @Test fun `URL和base64结果落工作区且不再返回旧data`() = runTest {
        val result = generate(buildJsonObject { put("url", "https://images.test/a.png") }, base64Image())
        assertThat(result.media).hasSize(2)
        assertThat(result.data).isNull()
        assertThat(result.content).contains("/media/2026-10-20/gen-1.png")
        assertThat(result.content).contains("/media/2026-10-20/gen-2.png")
        assertThat(result.content).contains("3×2")
        assertThat(result.content).contains("![说明]")
        assertThat(result.content).contains("模型修订的提示词")
        assertThat(result.content).doesNotContain(mediaBase64())
        assertThat(store.saved.map { it.origin }).containsExactly(MediaOrigin.GENERATED, MediaOrigin.GENERATED)
    }
    @Test fun `部分失败保留成功引用并说明数量`() = runTest {
        val result = generate(base64Image(), base64Image("%invalid%"), base64Image())
        assertThat(result.status).isEqualTo("success")
        assertThat(result.media).hasSize(2)
        assertThat(result.content).contains("失败 1")
    }
    @Test fun `服务端错误返回状态码错误码与完整说明且不重复前缀`() = runTest {
        val message = "No available channel for model default::sensenova-u1.5-lite under group default (distributor) (request id: 20261020145110140000000000000000)"
        val body = buildJsonObject { put("error", buildJsonObject { put("code", "model_not_found"); put("message", message); put("type", "new_api_error") }) }.toString()
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.ServiceUnavailable) })
        val result = try {
            ImageGenerationSkill(mockk<Context>(), providers, store, downloader) { url, key, model -> ImageGenClient(url, key, model, client) }
                .execute(buildJsonObject { put("prompt", "一棵树") }, mediaTestContext)
        } finally { client.close() }
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).isEqualTo("Image generation failed (HTTP 503 · model_not_found): $message")
    }
    @Test fun `工作区不可用返回明确错误不写旧目录`() = runTest {
        store.failure = WorkspaceMediaException("WORKSPACE_UNAVAILABLE", "当前会话工作区不可用")
        val result = generate(base64Image())
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("工作区不可用")
        assertThat(result.media).isNull()
        assertThat(result.data).isNull()
    }
}
