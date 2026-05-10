package com.promenar.nexara.data.remote.mcp

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement,
    val id: Int
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val result: JsonElement? = null,
    val error: JsonElement? = null,
    val id: Int? = null
)

class McpClient(
    private val httpClient: HttpClient,
    private val serverUrl: String
) {
    private var requestId = 1

    suspend fun listTools(): List<McpTool> {
        val response = call("tools/list", buildJsonObject {})
        val tools = response.result?.jsonObject?.get("tools")?.jsonArray
        return tools?.map { it.jsonObject.let { obj ->
            McpTool(
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                inputSchema = obj["inputSchema"]?.toString() ?: "{}"
            )
        }} ?: emptyList()
    }

    suspend fun callTool(name: String, arguments: JsonElement): JsonElement {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val response = call("tools/call", params)
        return response.result ?: throw Exception("Tool call failed: ${response.error}")
    }

    private suspend fun call(method: String, params: JsonElement): JsonRpcResponse {
        val request = JsonRpcRequest(method = method, params = params, id = requestId++)
        val response: HttpResponse = httpClient.post(serverUrl) {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(JsonRpcRequest.serializer(), request))
        }
        return Json.decodeFromString(JsonRpcResponse.serializer(), response.bodyAsText())
    }
}

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: String
)
