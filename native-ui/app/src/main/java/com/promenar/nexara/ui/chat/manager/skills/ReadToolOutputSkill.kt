package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.repository.ToolOutputStore
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.tool.ToolSchemaValidation
import com.promenar.nexara.domain.tool.ToolSchemaValidator
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class ReadToolOutputSkill(private val store: ToolOutputStore) : SkillDefinition {
    override val id = "read_tool_output"
    override val name = "read_tool_output"
    override val description = "Read a stored tool output from the current session. Offsets and limits count UTF-16 code units, not bytes or lines. Start at offset 0, then advance by the returned character count. An empty result means the end of the output."
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{
        "type":"object",
        "properties":{
            "id":{"type":"string","description":"Output ID supplied by an offloaded tool result"},
            "offset":{"type":"integer","description":"Nonnegative UTF-16 offset; at most the full output length","default":0},
            "limit":{"type":"integer","description":"UTF-16 code units to return, from 1 to 8000","default":4000}
        },
        "required":["id"],
        "additionalProperties":false
    }""".trimIndent()

    private val validator = ToolSchemaValidator()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        if (validator.validate(parametersSchema, args) is ToolSchemaValidation.Invalid) {
            return failure("Invalid read_tool_output arguments; supply id and optional integer offset/limit.")
        }
        val outputId = (args["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return failure("A stored output ID is required.")
        val offset = integer(args, "offset", 0)
            ?: return failure("offset must be a nonnegative UTF-16 integer offset.")
        val limit = integer(args, "limit", 4_000)
            ?: return failure("limit must be an integer between 1 and 8000.")
        return try {
            val content = store.read(context.sessionId, outputId, offset, limit)
            ToolResult(
                id = "read_tool_output_${System.currentTimeMillis()}",
                content = content,
                data = buildJsonObject {
                    put("outputId", outputId)
                    put("offset", offset)
                    put("returnedChars", content.length)
                    put("nextOffset", offset.toLong() + content.length)
                    put("offsetUnit", "UTF-16")
                }.toString(),
            )
        } catch (_: IllegalArgumentException) {
            failure("Invalid output ID or UTF-16 range; use offset >= 0 and limit from 1 to 8000.")
        } catch (_: IOException) {
            failure("Tool output is unavailable in this session; it may have been deleted or evicted.")
        } catch (_: SecurityException) {
            failure("Tool output storage is unavailable.")
        }
    }

    private fun integer(args: JsonObject, key: String, default: Int): Int? =
        if (key !in args) default else (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun failure(message: String): ToolResult = ToolResult(
        id = "read_tool_output_error_${System.currentTimeMillis()}",
        content = message,
        status = "error",
    )
}
