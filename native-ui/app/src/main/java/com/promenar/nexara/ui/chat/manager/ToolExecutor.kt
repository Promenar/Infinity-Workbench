package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.model.UpdateMessageOptions
import com.promenar.nexara.ui.chat.ChatStore

interface SkillRegistry {
    fun getSkill(name: String): SkillDefinition?
}

interface SkillDefinition {
    val id: String
    val name: String
    val description: String
    val mcpServerId: String?
    suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult
}

interface SkillExecutionContext {
    val sessionId: String
    val agentId: String
    val workspacePath: String?
}

class ToolExecutor(
    private val store: ChatStore,
    private val messageManager: MessageManager,
    private val skillRegistry: SkillRegistry?
) {
    suspend fun executeTools(
        sessionId: String,
        toolCalls: List<ToolCall>,
        targetMessageId: String? = null
    ) {
        val session = store.getSession(sessionId) ?: return

        var targetMsgId = targetMessageId
        if (targetMsgId == null) {
            targetMsgId = session.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
        }
        if (targetMsgId == null) return

        val targetMsg = session.messages.find { it.id == targetMsgId } ?: return

        if (session.options?.toolsEnabled == false) {
            for (tc in toolCalls) {
                val syntheticContent = "[SYSTEM WARNING]: Tool usage is currently DISABLED by the user configuration.\nYou CANNOT use tools in this turn.\nPlease STOP trying to use tools and answer the user's request directly using your internal knowledge."

                val toolMessage = Message(
                    id = "tool_shield_${System.currentTimeMillis()}_${tc.id}",
                    role = MessageRole.TOOL,
                    toolCallId = tc.id,
                    content = syntheticContent,
                    name = tc.name,
                    thoughtSignature = targetMsg.thoughtSignature,
                    createdAt = System.currentTimeMillis()
                )
                messageManager.addMessage(sessionId, toolMessage)
            }
            return
        }

        for (tc in toolCalls) {
            if (tc.name.isEmpty()) continue

            val stepId = "step_${System.currentTimeMillis()}_${tc.id}"

            appendStep(sessionId, targetMsgId, ExecutionStep(
                id = stepId,
                type = "tool_call",
                toolName = tc.name,
                toolArgs = tc.arguments,
                toolCallId = tc.id,
                timestamp = System.currentTimeMillis()
            ))

            val result: ToolResult = executeSkill(tc, session)

            val finalContent = if (result.status == "error") {
                result.content + "\n\n[SYSTEM NOTE]: The tool execution failed. Please analyze the error message above. Do NOT give up or apologize. Instead:\n1. Check if arguments were correct.\n2. If a file/directory is missing, use 'list_dir' or 'search_by_name' to locate it.\n3. If a parameter was invalid, check the docs or schema and retry.\n4. Propose a specific alternative approach immediately."
            } else {
                result.content
            }

            appendStep(sessionId, targetMsgId, ExecutionStep(
                id = "res_$stepId",
                type = if (result.status == "success") "tool_result" else "error",
                toolName = tc.name,
                toolCallId = tc.id,
                content = finalContent,
                data = result.data,
                timestamp = System.currentTimeMillis()
            ))

            val toolMessage = Message(
                id = "tool_${System.currentTimeMillis()}_${tc.id}",
                role = MessageRole.TOOL,
                toolCallId = tc.id,
                content = finalContent,
                name = tc.name,
                thoughtSignature = targetMsg.thoughtSignature,
                createdAt = System.currentTimeMillis()
            )
            messageManager.addMessage(sessionId, toolMessage)
        }
    }

    private suspend fun executeSkill(tc: ToolCall, session: com.promenar.nexara.data.model.Session): ToolResult {
        if (skillRegistry == null) {
            return ToolResult(id = tc.id, content = "Error: SkillRegistry not configured", status = "error")
        }

        val skill = skillRegistry.getSkill(tc.name)
        if (skill == null) {
            return ToolResult(id = tc.id, content = "Error: Skill ${tc.name} not found", status = "error")
        }

        return try {
            skill.execute(
                parseArgs(tc.arguments),
                object : SkillExecutionContext {
                    override val sessionId = session.id
                    override val agentId = session.agentId
                    override val workspacePath = session.workspacePath
                }
            )
        } catch (e: Exception) {
            ToolResult(id = tc.id, content = "Error: ${e.message}", status = "error")
        }
    }

    private suspend fun appendStep(sessionId: String, targetMsgId: String, newStep: ExecutionStep) {
        val currentSession = store.getSession(sessionId) ?: return
        val currentMsg = currentSession.messages.find { it.id == targetMsgId } ?: return
        val currentSteps = currentMsg.executionSteps ?: emptyList()

        val index = currentSteps.indexOfFirst { s ->
            s.id == newStep.id || (newStep.toolCallId != null && s.toolCallId == newStep.toolCallId && s.type == newStep.type)
        }

        val updatedSteps = if (index > -1) {
            currentSteps.toMutableList().apply { set(index, newStep) }
        } else {
            currentSteps + newStep
        }

        messageManager.updateMessageContent(
            sessionId, targetMsgId, currentMsg.content,
            UpdateMessageOptions(executionSteps = updatedSteps)
        )
    }

    private fun parseArgs(argsJson: String): Map<String, Any> {
        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(argsJson)
            if (element is kotlinx.serialization.json.JsonObject) {
                val result = mutableMapOf<String, Any>()
                for ((key, v) in element) {
                    when (v) {
                        is kotlinx.serialization.json.JsonPrimitive -> {
                            when {
                                v.isString -> result[key] = v.content
                                else -> {
                                    val content = v.content
                                    when {
                                        content == "true" -> result[key] = true
                                        content == "false" -> result[key] = false
                                        else -> {
                                            val long = content.toLongOrNull()
                                            if (long != null) result[key] = long
                                            else result[key] = content
                                        }
                                    }
                                }
                            }
                        }
                        else -> result[key] = v.toString()
                    }
                }
                result
            } else {
                emptyMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
