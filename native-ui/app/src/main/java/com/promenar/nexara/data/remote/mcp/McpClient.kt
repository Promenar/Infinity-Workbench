package com.promenar.nexara.data.remote.mcp

import com.promenar.nexara.BuildConfig
import com.promenar.nexara.domain.tool.ToolSchemaValidation
import com.promenar.nexara.domain.tool.ToolSchemaValidator
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement,
    val id: Long,
)

class McpProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
class McpRemoteToolException(message: String) : Exception(message)
class McpInputRequiredUnsupportedException : Exception("MCP_INPUT_REQUIRED_UNSUPPORTED")

/** 仅实现 MCP 2026-07-28 无状态 Streamable HTTP。 */
class McpClient(
    private val httpClient: HttpClient,
    private val serverUrl: String,
) {
    private val requestId = AtomicLong(1)
    private val discoveredHeaderParameters = mutableMapOf<String, Set<String>>()

    init {
        requireModernHttpsUrl(serverUrl)
    }

    suspend fun listTools(): List<McpTool> {
        val tools = mutableListOf<McpTool>()
        val names = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = call("tools/list", params)
            val root = result as? JsonObject
                ?: throw McpProtocolException("MCP_TOOLS_LIST_RESULT_NOT_OBJECT")
            val page = root["tools"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: throw McpProtocolException("MCP_TOOLS_LIST_TOOLS_NOT_ARRAY")
            page.forEach { element ->
                val obj = element as? JsonObject
                    ?: throw McpProtocolException("MCP_TOOL_DEFINITION_NOT_OBJECT")
                val name = obj["name"]?.let(::requiredString)
                    ?: throw McpProtocolException("MCP_TOOL_NAME_MISSING")
                if (!TOOL_NAME.matches(name)) throw McpProtocolException("MCP_TOOL_NAME_INVALID")
                if (!names.add(name)) throw McpProtocolException("MCP_TOOL_NAME_DUPLICATE")
                val rawSchema = obj["inputSchema"] as? JsonObject
                    ?: throw McpProtocolException("MCP_TOOL_SCHEMA_NOT_OBJECT")
                val normalized = normalizeInputSchema(rawSchema)
                val tool = McpTool(
                    name = name,
                    description = obj["description"]?.let(::requiredString).orEmpty(),
                    inputSchema = rawSchema.toString(),
                    advertisedInputSchema = normalized.schema.toString(),
                    headerParameters = normalized.headerParameters,
                )
                discoveredHeaderParameters[name] = tool.headerParameters
                tools += tool
            }
            cursor = root["nextCursor"]?.let { next ->
                if (next is JsonNull) null else requiredString(next)
                    .takeIf(String::isNotEmpty)
                    ?: throw McpProtocolException("MCP_NEXT_CURSOR_INVALID")
            }
            if (cursor != null && !cursors.add(cursor!!)) throw McpProtocolException("MCP_CURSOR_LOOP")
            if (cursors.size > MAX_PAGES) throw McpProtocolException("MCP_PAGE_LIMIT_EXCEEDED")
        } while (cursor != null)
        return tools
    }

    suspend fun callTool(
        name: String,
        arguments: JsonElement,
        headerParameters: Set<String> = discoveredHeaderParameters[name].orEmpty(),
    ): JsonElement {
        if (!TOOL_NAME.matches(name)) throw McpProtocolException("MCP_TOOL_NAME_INVALID")
        val argumentObject = arguments as? JsonObject
            ?: throw McpProtocolException("MCP_TOOL_ARGUMENTS_NOT_OBJECT")
        val headers = encodeHeaderParameters(argumentObject, headerParameters)
        val params = buildJsonObject {
            put("name", name)
            put("arguments", argumentObject)
        }
        val result = call("tools/call", params, name, headers)
        val resultObject = result as? JsonObject
            ?: throw McpProtocolException("MCP_TOOL_RESULT_NOT_OBJECT")
        val inputRequired = containsInputRequired(resultObject)
        if (inputRequired) throw McpInputRequiredUnsupportedException()
        if (resultObject["isError"]?.jsonPrimitive?.booleanOrNull == true) {
            throw McpRemoteToolException("MCP_REMOTE_TOOL_ERROR")
        }
        return resultObject
    }

    private suspend fun call(
        method: String,
        rawParams: JsonObject,
        toolName: String? = null,
        parameterHeaders: Map<String, String> = emptyMap(),
    ): JsonElement {
        val id = requestId.getAndIncrement()
        val params = JsonObject(rawParams + ("_meta" to requestMetadata()))
        val request = JsonRpcRequest(method = method, params = params, id = id)
        val response: HttpResponse = try {
            httpClient.post(serverUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ACCEPT)
                header(HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION)
                header(HEADER_METHOD, method)
                toolName?.let { header(HEADER_NAME, it) }
                parameterHeaders.forEach { (name, value) -> header(name, value) }
                setBody(Json.encodeToString(JsonRpcRequest.serializer(), request))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        if (!response.status.isSuccess()) throw McpProtocolException("MCP_HTTP_${response.status.value}")
        val root = try {
            Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        } catch (error: SerializationException) {
            throw McpProtocolException("MCP_RPC_MALFORMED", error)
        } catch (error: IllegalArgumentException) {
            throw McpProtocolException("MCP_RPC_MALFORMED", error)
        } ?: throw McpProtocolException("MCP_RPC_NOT_OBJECT")
        if (root["jsonrpc"]?.jsonPrimitive?.contentOrNull != "2.0") {
            throw McpProtocolException("MCP_RPC_VERSION_MISMATCH")
        }
        if (root["id"] != JsonPrimitive(id)) throw McpProtocolException("MCP_RPC_ID_MISMATCH")
        if (root["error"] != null && root["error"] !is JsonNull) throw McpProtocolException("MCP_RPC_ERROR")
        return root["result"] ?: throw McpProtocolException("MCP_RPC_RESULT_MISSING")
    }

    private fun normalizeInputSchema(schema: JsonObject): McpNormalizedSchema = McpInputSchemaPolicy.parse(schema)

    private fun encodeHeaderParameters(args: JsonObject, declared: Set<String>): Map<String, String> =
        declared.mapNotNull { name ->
            if (!HEADER_PARAMETER_NAME.matches(name)) throw McpProtocolException("MCP_HEADER_NAME_INVALID")
            val rawValue = args[name] ?: return@mapNotNull null
            val value = rawValue as? JsonPrimitive
                ?: throw McpProtocolException("MCP_HEADER_VALUE_NOT_PRIMITIVE")
            val encoded = when {
                value.isString -> value.content
                value.booleanOrNull != null -> value.booleanOrNull.toString()
                value.longOrNull != null -> value.longOrNull.toString()
                else -> throw McpProtocolException("MCP_HEADER_VALUE_NOT_SUPPORTED")
            }
            if (encoded.any { it.code < 0x20 || it.code == 0x7f }) {
                throw McpProtocolException("MCP_HEADER_VALUE_INVALID")
            }
            "$HEADER_PARAMETER_PREFIX$name" to encoded
        }.toMap()

    private fun containsInputRequired(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element["input_required"]?.jsonPrimitive?.booleanOrNull == true ||
            element["status"]?.jsonPrimitive?.contentOrNull == "input_required" ||
            element["type"]?.jsonPrimitive?.contentOrNull == "input_required" ||
            element.values.any(::containsInputRequired)
        is kotlinx.serialization.json.JsonArray -> element.any(::containsInputRequired)
        else -> false
    }

    private fun requestMetadata(): JsonObject = buildJsonObject {
        put(META_PROTOCOL_VERSION, PROTOCOL_VERSION)
        put(META_CLIENT_INFO, buildJsonObject {
            put("name", CLIENT_NAME)
            put("version", BuildConfig.VERSION_NAME)
        })
        put(META_CLIENT_CAPABILITIES, JsonObject(emptyMap()))
    }

    private fun requiredString(element: JsonElement): String {
        val primitive = element as? JsonPrimitive ?: throw McpProtocolException("MCP_STRING_EXPECTED")
        if (!primitive.isString) throw McpProtocolException("MCP_STRING_EXPECTED")
        return primitive.content
    }

    private fun requireModernHttpsUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw IllegalArgumentException("MCP_URL_INVALID")
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "MCP_HTTPS_REQUIRED"
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = "2026-07-28"
        const val CLIENT_NAME = "Nexara"
        const val ACCEPT = "application/json, text/event-stream"
        const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
        const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
        const val META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
        const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
        const val HEADER_METHOD = "Mcp-Method"
        const val HEADER_NAME = "Mcp-Name"
        const val HEADER_PARAMETER_PREFIX = "Mcp-Param-"
        const val MAX_PAGES = 1_000
        val TOOL_NAME = Regex("[A-Za-z0-9_.:/-]{1,128}")
        val HEADER_PARAMETER_NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    }
}

internal data class McpNormalizedSchema(val schema: JsonObject, val headerParameters: Set<String>)

internal object McpInputSchemaPolicy {
    fun parse(schemaJson: String): McpNormalizedSchema {
        val schema = runCatching { Json.parseToJsonElement(schemaJson) as? JsonObject }.getOrNull()
            ?: throw McpProtocolException("MCP_TOOL_SCHEMA_NOT_OBJECT")
        return parse(schema)
    }

    fun parse(schema: JsonObject): McpNormalizedSchema {
        val headerParameters = mutableSetOf<String>()
        fun strip(
            current: JsonObject,
            propertyName: String? = null,
            headerAllowed: Boolean = false,
            allowHeaderProperties: Boolean = false,
        ): JsonObject {
            val header = current["x-mcp-header"]
            if (header != null) {
                if (!headerAllowed || propertyName == null || header !is JsonPrimitive || header.booleanOrNull != true ||
                    !HEADER_NAME.matches(propertyName)
                ) throw McpProtocolException("MCP_HEADER_SCHEMA_INVALID")
                val type = (current["type"] as? JsonPrimitive)?.contentOrNull
                if (type !in PRIMITIVE_TYPES) throw McpProtocolException("MCP_HEADER_SCHEMA_NOT_PRIMITIVE")
                headerParameters += propertyName
            }
            return JsonObject(current.filterKeys { it != "x-mcp-header" }.mapValues { (key, value) ->
                when {
                    key == "properties" && value is JsonObject -> JsonObject(
                        value.mapValues { (nestedName, nested) ->
                            strip(
                                nested as? JsonObject
                                    ?: throw McpProtocolException("MCP_TOOL_SCHEMA_PROPERTY_INVALID"),
                                nestedName,
                                headerAllowed = allowHeaderProperties,
                            )
                        },
                    )
                    key == "items" && value is JsonObject -> strip(value)
                    key == "additionalProperties" && value is JsonObject -> strip(value)
                    else -> value
                }
            })
        }
        val sanitized = strip(schema, allowHeaderProperties = true)
        val validation = ToolSchemaValidator().validateDefinition(sanitized.toString())
        if (validation !is ToolSchemaValidation.Valid) throw McpProtocolException("MCP_TOOL_SCHEMA_UNSUPPORTED")
        return McpNormalizedSchema(
            schema = Json.parseToJsonElement(validation.canonicalSchema).jsonObject,
            headerParameters = headerParameters,
        )
    }

    private val HEADER_NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    private val PRIMITIVE_TYPES = setOf("string", "integer", "boolean")
}

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: String,
    val advertisedInputSchema: String = inputSchema,
    val headerParameters: Set<String> = emptySet(),
)
