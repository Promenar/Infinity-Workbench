package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.media.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import com.promenar.nexara.data.remote.mcp.McpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf

class McpMediaResultProcessorTest {
    private val store = RecordingMediaStore()
    private val png = mediaBase64()
    private val audio = mediaBase64("RIFF0000WAVEdata".toByteArray())
    private val binary = mediaBase64("PDF binary content".toByteArray())
    private fun result(vararg items: JsonObject, structured: JsonElement? = null, error: Boolean = false) = buildJsonObject {
        put("content", JsonArray(items.toList()))
        structured?.let { put("structuredContent", it) }
        if (error) put("isError", true)
        put("_meta", buildJsonObject { put("trace", "kept") })
    }
    private fun image(data: String = png) = buildJsonObject { put("type", "image"); put("data", data); put("mimeType", "image/png") }

    @Test fun `图片音频与blob按顺序落地并剥离base64和结构化副本`() = runTest {
        val raw = result(image(), buildJsonObject { put("type", "audio"); put("data", audio); put("mimeType", "audio/wav") },
            buildJsonObject { put("type", "resource"); put("resource", buildJsonObject { put("uri", "file:///exports/report.pdf"); put("mimeType", "application/pdf"); put("blob", binary) }) },
            structured = buildJsonObject { put("nested", buildJsonArray { add(buildJsonObject { put("data", png); put("other", "kept") }); add(audio); add("data:application/pdf;base64,$binary") }) })
        val processed = McpMediaResultProcessor(store).process(raw, "session")
        assertThat(processed.media).hasSize(3)
        assertThat(store.saved.map { it.origin }).containsExactly(MediaOrigin.MCP, MediaOrigin.MCP, MediaOrigin.MCP)
        assertThat(store.saved.last().name).isEqualTo("report.pdf")
        val text = processed.content
        listOf(png, audio, binary).forEach { assertThat(text).doesNotContain(it) }
        assertThat(text).contains("/media/2026-10-20/mcp-1.png")
        assertThat(text).contains("时长 1200 毫秒")
        assertThat(text).contains("trace")
        val content = Json.parseToJsonElement(text).jsonObject.getValue("content").jsonArray
        assertThat(content.map { it.jsonObject["type"]!!.jsonPrimitive.content }).containsExactly("text", "text", "text").inOrder()
    }

    @Test fun `text和resource text原样保留且resource link不下载`() = runTest {
        val text = buildJsonObject { put("type", "text"); put("text", "标题\n正文 $png") }
        val resource = buildJsonObject { put("type", "resource"); put("resource", buildJsonObject { put("text", "原始资源\n换行"); put("uri", "file:///note.txt") }) }
        val link = buildJsonObject { put("type", "resource_link"); put("uri", "https://example.test/media.png") }
        val processed = McpMediaResultProcessor(store).process(result(text, image(), resource, link), "session")
        val content = Json.parseToJsonElement(processed.content).jsonObject.getValue("content").jsonArray
        assertThat(content[0]).isEqualTo(text)
        assertThat(content[2]).isEqualTo(resource)
        assertThat(content[3]).isEqualTo(link)
        assertThat(store.saved).hasSize(1)
    }

    @Test fun `无媒体时结果与原JSON字节表示一致`() = runTest {
        val raw = Json.parseToJsonElement("""{"content":[{"type":"text","text":"原文\n换行"}],"structuredContent":{"n":1}}""")
        val processed = McpMediaResultProcessor(store).process(raw, "session")
        assertThat(processed.content).isEqualTo(raw.toString())
        assertThat(processed.media).isNull()
        assertThat(store.saved).isEmpty()
    }

    @Test fun `isError不落地并保持原结果`() = runTest {
        val raw = result(image(), error = true)
        val processed = McpMediaResultProcessor(store).process(raw, "session")
        assertThat(processed.content).isEqualTo(raw.toString())
        assertThat(processed.status).isEqualTo("error")
        assertThat(store.saved).isEmpty()
    }

    @Test fun `解码失败超限与缺失载荷替换为说明其它项继续处理`() = runTest {
        val bad = "not%base64!!!"
        val large = mediaBase64(ByteArray(101))
        val raw = result(image(bad), image(large), buildJsonObject { put("type", "audio") }, image(), structured = buildJsonObject { put("bad", bad); put("large", large) })
        val processed = McpMediaResultProcessor(store, 100).process(raw, "session")
        assertThat(processed.media).hasSize(1)
        assertThat(processed.content).contains("解码失败")
        assertThat(processed.content).contains("上限")
        assertThat(processed.content).doesNotContain(bad)
        assertThat(processed.content).doesNotContain(large)
        assertThat(processed.content).doesNotContain(png)
    }

    @Test fun `工作区不可用时不泄露载荷且保留其它文本`() = runTest {
        store.failure = WorkspaceMediaException("WORKSPACE_UNAVAILABLE", "当前会话工作区不可用")
        val processed = McpMediaResultProcessor(store).process(result(image(), buildJsonObject { put("type", "text"); put("text", "仍保留") }), "session")
        assertThat(processed.media).isNull()
        assertThat(processed.content).contains("工作区不可用")
        assertThat(processed.content).contains("仍保留")
        assertThat(processed.content).doesNotContain(png)
    }

    @Test fun `取消直接传播`() = runTest {
        val cancelledStore = object : WorkspaceMediaStore by store {
            override suspend fun save(sessionId: String, payload: MediaPayload, origin: MediaOrigin, mimeTypeHint: String?, suggestedName: String?, targetPath: String?): MediaRef = throw CancellationException("取消")
        }
        val error = try { McpMediaResultProcessor(cancelledStore).process(result(image()), "session"); null } catch (e: CancellationException) { e }
        assertThat(error).isNotNull()
    }

    @Test fun `MCP工具执行把真实调用结果接入媒体处理器`() = runTest {
        val client = HttpClient(MockEngine {
            respond(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1)
                put("result", result(image(), structured = JsonPrimitive(png)))
            }.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val tool = McpSkill(name = "media", description = "media", parametersSchema = "{}",
                mcpClient = McpClient(client, "https://mcp.example.test"), mediaStore = store)
            val response = tool.execute(buildJsonObject {}, mediaTestContext)
            assertThat(response.status).isEqualTo("success")
            assertThat(response.media).hasSize(1)
            assertThat(response.content).doesNotContain(png)
            assertThat(store.saved.single().session).isEqualTo(mediaTestContext.sessionId)
        } finally { client.close() }
    }
}
