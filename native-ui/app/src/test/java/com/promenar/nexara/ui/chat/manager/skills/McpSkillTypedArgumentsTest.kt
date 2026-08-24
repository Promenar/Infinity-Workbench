package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.mcp.McpClient
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteReadPacket
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class McpSkillTypedArgumentsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "session-1"
        override val agentId = "agent-1"
        override val workspacePath = null
        override val workspaceRootUuid = "root-1"
    }

    @Test
    fun `MCP skill 原样发送嵌套 JsonObject`() = runTest {
        val requestBody = AtomicReference<String>()
        val client = HttpClient(MockEngine { request ->
            requestBody.set(request.body.toByteReadPacket().readText())
            respond(
                content = """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"ok"}]}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val skill = McpSkill(
            name = "nested",
            description = "nested",
            parametersSchema = """{"type":"object","properties":{}}""",
            mcpClient = McpClient(client, "https://mcp.example.test"),
        )
        val args = Json.parseToJsonElement(
            """{"object":{"n":1,"flag":true,"nil":null},"array":["x",2]}""",
        ).jsonObject

        val result = skill.execute(args, context)

        assertThat(result.status).isEqualTo("success")
        assertThat(requestBody.get()).contains(""""object":{"n":1,"flag":true,"nil":null}""")
        assertThat(requestBody.get()).contains(""""array":["x",2]""")
    }

    @Test
    fun `MCP skill 不吞掉取消`() = runTest {
        val client = HttpClient(MockEngine { throw CancellationException("cancelled") })
        val skill = McpSkill(
            name = "cancel",
            description = "cancel",
            parametersSchema = """{"type":"object","properties":{}}""",
            mcpClient = McpClient(client, "https://mcp.example.test"),
        )

        var thrown: Throwable? = null
        try {
            skill.execute(Json.parseToJsonElement("{}").jsonObject, context)
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
    }
}
