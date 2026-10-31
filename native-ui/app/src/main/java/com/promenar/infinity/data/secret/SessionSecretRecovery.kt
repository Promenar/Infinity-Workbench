package com.promenar.infinity.data.secret

import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.domain.secret.*
import com.promenar.infinity.ui.chat.manager.skills.SecretRequestProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 只从已有工具参数恢复槽位标识与输出卡外壳；值不会写盘，也不会在重启时重新执行命令。 */
class SessionSecretRecovery(private val database: InfinityDatabase, private val vault: SessionSecretVault) {
    suspend fun restoreUnavailableSlots() = withContext(Dispatchers.IO) {
        database.sessionDao().getAll().forEach { session ->
            val messages = database.messageDao().getBySession(session.id)
            val results = messages.filter { it.role == "tool" }.groupBy { it.parentMessageId }
            val assistants = messages.filter { it.role == "assistant" }.map { message ->
                message to results[message.id].orEmpty().mapNotNull { result -> result.toolCallId?.let { it to result.content } }.toMap()
            }
            // 先登记全部槽位，输出卡才能挂到后续回复里声明的同名槽位上。
            assistants.forEach { (message, completed) ->
                declaredSlots(message.toolCalls, completed).forEach { slot ->
                    vault.rememberUnavailable(session.id, slot.name, slot.label, slot.source)
                }
            }
            assistants.forEach { (message, completed) ->
                shownCards(message.toolCalls, completed).forEach { card ->
                    vault.restoreCard(session.id, message.id, card.toolCallId, card.name, card.purpose)
                }
            }
        }
    }

    internal data class Declaration(val name: String, val label: String, val source: SecretSource)
    internal data class ShownCard(val toolCallId: String, val name: String, val purpose: String)

    internal companion object {
        /** 成功执行过的 `show_secret` 调用；失败结果与未执行的调用不恢复卡片。 */
        fun shownCards(toolCalls: String?, results: Map<String, String>): List<ShownCard> {
            val calls = toolCalls?.let { runCatching { Json.decodeFromString<List<ToolCall>>(it) }.getOrNull() }.orEmpty()
            return calls.filter { it.name == SecretIoLimits.SHOW_TOOL_NAME }.mapNotNull { call ->
                runCatching {
                    val result = results[call.id]?.let { Json.parseToJsonElement(it).jsonObject } ?: return@runCatching null
                    val arguments = Json.parseToJsonElement(call.arguments).jsonObject
                    val name = arguments["name"]?.jsonPrimitive?.content ?: return@runCatching null
                    val purpose = arguments["purpose"]?.jsonPrimitive?.content ?: return@runCatching null
                    if (result["status"]?.jsonPrimitive?.content != "shown" || result["name"]?.jsonPrimitive?.content != name ||
                        !SecretIoLimits.SLOT_NAME.matches(name) || !SecretRequestProtocol.validPurpose(purpose)) return@runCatching null
                    ShownCard(call.id, name, purpose)
                }.getOrNull()
            }.distinctBy { it.toolCallId }
        }

        fun declaredSlots(toolCalls: String?, results: Map<String, String> = emptyMap()): List<Declaration> {
            val calls = toolCalls?.let { runCatching { Json.decodeFromString<List<ToolCall>>(it) }.getOrNull() }.orEmpty()
            return calls.mapNotNull { call ->
                runCatching {
                    val result = results[call.id]?.let { Json.parseToJsonElement(it).jsonObject } ?: return@runCatching null
                    when (call.name) {
                        SecretIoLimits.REQUEST_TOOL_NAME -> {
                            if (result["type"]?.jsonPrimitive?.content != "saved" || result["destination"]?.jsonPrimitive?.content != "session_env") return@runCatching null
                            val destination = SecretRequestProtocol.decode(call.arguments).destination as? SecretDestination.SessionEnv
                            destination?.let { Declaration(it.name, it.name.take(SecretIoLimits.MAX_LABEL_CHARS), SecretSource.UserInput) }
                        }
                        "shell_exec", "python_exec", "ssh_exec" -> {
                            SecretCapture.parse(Json.parseToJsonElement(call.arguments).jsonObject)?.let { capture ->
                                if (result["exit_code"]?.jsonPrimitive?.intOrNull != 0 || result["error_code"] != JsonNull ||
                                    result["timed_out"]?.jsonPrimitive?.booleanOrNull != false ||
                                    result["stdout"]?.jsonPrimitive?.content?.startsWith("‹已捕获为 ${capture.name} · ") != true) return@runCatching null
                                Declaration(capture.name, capture.label, if (capture.from == "file") SecretSource.File(capture.path!!) else SecretSource.CommandStdout("命令标准输出"))
                            }
                        }
                        else -> null
                    }
                }.getOrNull()
            }.distinctBy { it.name }
        }
    }
}
