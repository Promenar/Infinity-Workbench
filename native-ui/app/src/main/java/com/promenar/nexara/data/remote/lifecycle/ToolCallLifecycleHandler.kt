package com.promenar.nexara.data.remote.lifecycle

import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.protocol.ToolCallLifecycleEvent
import com.promenar.nexara.data.remote.protocol.ToolChunkType
import com.promenar.nexara.data.remote.protocol.ToolType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class ToolCallLifecycleHandler(
    private val onChunk: (StreamChunk) -> Unit,
    private val knownTools: Map<String, ProtocolTool> = emptyMap()
) {
    private data class PendingCall(
        val id: String,
        val name: String,
        val arguments: StringBuilder = StringBuilder()
    )

    private val pending = mutableMapOf<String, PendingCall>()

    private fun toolTypeFor(name: String): ToolType {
        return if (knownTools.containsKey(name)) {
            val tool = knownTools[name]!!
            if (tool.function.name.startsWith("mcp_")) ToolType.MCP
            else ToolType.BUILTIN
        } else ToolType.PROVIDER
    }

    fun handleToolInputDelta(id: String, argumentsDelta: String) {
        val call = pending.getOrPut(id) {
            PendingCall(id = id, name = "")
        }
        call.arguments.append(argumentsDelta)

        onChunk(StreamChunk.ToolCallLifecycle(
            type = ToolChunkType.MCP_TOOL_STREAMING,
            events = listOf(
                ToolCallLifecycleEvent.Streaming(
                    toolCallId = id,
                    toolName = call.name,
                    toolType = toolTypeFor(call.name),
                    partialArguments = argumentsDelta
                )
            )
        ))
    }

    fun handleToolNameDelta(id: String, nameDelta: String) {
        val existing = pending[id]
        if (existing != null) {
            pending[id] = existing.copy(name = existing.name + nameDelta)
        } else {
            pending[id] = PendingCall(id = id, name = nameDelta)
        }
    }

    fun handleToolComplete(id: String, name: String, arguments: String) {
        pending.remove(id)

        val parsedArgs = try {
            Json.decodeFromString<JsonObject>(arguments)
        } catch (_: Exception) {
            null
        }

        onChunk(StreamChunk.ToolCallLifecycle(
            type = ToolChunkType.MCP_TOOL_COMPLETE,
            events = listOf(
                ToolCallLifecycleEvent.Complete(
                    toolCallId = id,
                    toolName = name,
                    toolType = toolTypeFor(name),
                    arguments = parsedArgs,
                    response = null
                )
            )
        ))
    }

    fun handleToolPending(id: String, name: String, arguments: String) {
        val parsedArgs = try {
            Json.decodeFromString<JsonObject>(arguments)
        } catch (_: Exception) {
            null
        }

        onChunk(StreamChunk.ToolCallLifecycle(
            type = ToolChunkType.MCP_TOOL_PENDING,
            events = listOf(
                ToolCallLifecycleEvent.Pending(
                    toolCallId = id,
                    toolName = name,
                    toolType = toolTypeFor(name),
                    arguments = parsedArgs
                )
            )
        ))
    }
}
