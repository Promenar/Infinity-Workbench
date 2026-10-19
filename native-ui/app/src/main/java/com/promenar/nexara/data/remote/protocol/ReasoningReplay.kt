package com.promenar.nexara.data.remote.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 协议原生的思考状态：随助手消息保存，只向产生它的同一模型原样回传。
 * 思考正文本身保存在消息的 reasoning 字段；这里只记录回传所需的协议细节。
 */
@Serializable
sealed interface ReasoningReplay {
    /** 是否只在当前用户回合（工具循环）内回传。 */
    val currentTurnOnly: Boolean

    /** OpenAI Chat Completions 兼容服务：思考正文从哪个字段返回，就按同一字段回传。 */
    @Serializable
    @SerialName("chat_field")
    data class ChatField(val field: String) : ReasoningReplay {
        override val currentTurnOnly: Boolean get() = false
    }

    /**
     * Anthropic Messages：按原顺序保存的 thinking / redacted_thinking 块（含签名）。
     * 官方只要求工具循环内回传，跨回合回传会受系统提示与工具列表变化影响，因此只在当前回合内回传。
     */
    @Serializable
    @SerialName("anthropic_blocks")
    data class AnthropicBlocks(val blocks: List<AnthropicThinkingBlock>) : ReasoningReplay {
        override val currentTurnOnly: Boolean get() = true
    }

    /**
     * Gemini generateContent：[text] 为非函数调用部分（正文或思考摘要）末尾携带的签名，
     * [calls] 为工具调用 ID 到其 functionCall part 签名的映射。官方建议整段历史原样回传，
     * Gemini 3 对当前回合的函数调用签名强制校验。
     */
    @Serializable
    @SerialName("gemini_signatures")
    data class GeminiSignatures(
        val text: String? = null,
        val calls: Map<String, String> = emptyMap(),
    ) : ReasoningReplay {
        override val currentTurnOnly: Boolean get() = false

        override fun toString(): String = "GeminiSignatures(text=${text != null}, calls=${calls.size})"
    }
}

/** [type] 为 thinking 时使用 [thinking] 与 [signature]；为 redacted_thinking 时使用 [data]。 */
@Serializable
data class AnthropicThinkingBlock(
    val type: String,
    val thinking: String? = null,
    val signature: String? = null,
    val data: String? = null,
) {
    override fun toString(): String = "AnthropicThinkingBlock(type=$type)"

    companion object {
        const val THINKING = "thinking"
        const val REDACTED = "redacted_thinking"
    }
}

/** 持久化信封：[source] 是产生该状态的应用模型 ID（含 Provider），回传前必须与当前路由一致。 */
@Serializable
data class ReasoningReplayEnvelope(
    val v: Int = VERSION,
    val source: String,
    val replay: ReasoningReplay,
) {
    companion object {
        const val VERSION = 1
    }
}

object ReasoningReplayCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }

    /** 兼容服务可回传的字段名；解码时拒绝其它值，避免把任意字段写进请求。 */
    val CHAT_FIELDS = setOf("reasoning_content", "reasoning")

    fun encode(source: String, replay: ReasoningReplay): String =
        json.encodeToString(ReasoningReplayEnvelope.serializer(), ReasoningReplayEnvelope(source = source, replay = replay))

    /** 旧版本写入的原始 thought signature 或损坏数据一律视为无可回传状态。 */
    fun decode(raw: String?): ReasoningReplayEnvelope? {
        if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
        val envelope = runCatching { json.decodeFromString(ReasoningReplayEnvelope.serializer(), raw) }.getOrNull()
            ?: return null
        if (envelope.v != ReasoningReplayEnvelope.VERSION || envelope.source.isBlank()) return null
        return when (val replay = envelope.replay) {
            is ReasoningReplay.ChatField -> envelope.takeIf { replay.field in CHAT_FIELDS }
            is ReasoningReplay.AnthropicBlocks -> envelope.takeIf { replay.blocks.isNotEmpty() && replay.blocks.all(::isValid) }
            is ReasoningReplay.GeminiSignatures -> envelope.takeIf {
                (!replay.text.isNullOrEmpty() || replay.calls.isNotEmpty()) &&
                    replay.calls.all { (id, signature) -> id.isNotBlank() && signature.isNotEmpty() }
            }
        }
    }

    private fun isValid(block: AnthropicThinkingBlock): Boolean = when (block.type) {
        AnthropicThinkingBlock.THINKING -> !block.signature.isNullOrEmpty()
        AnthropicThinkingBlock.REDACTED -> !block.data.isNullOrEmpty()
        else -> false
    }
}

/** 记录兼容服务在流中用哪个字段返回思考正文；标准字段 `reasoning_content` 优先。 */
internal class ReasoningFieldTracker {
    var field: String? = null
        private set

    fun observe(delta: JsonObject) {
        if (field == ReasoningReplayCodec.CHAT_FIELDS.first()) return
        ReasoningReplayCodec.CHAT_FIELDS.firstOrNull { name ->
            (delta[name] as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true
        }?.let { field = it }
    }

    fun replay(): ReasoningReplay? = field?.let(ReasoningReplay::ChatField)
}

/**
 * 兼容协议回传思考正文：按该消息保存的来源字段回传；没有保存状态时只对 [legacyField] 指定的服务
 * 沿用固定字段（DeepSeek、Kimi 带工具时要求回传 `reasoning_content`）。
 */
internal fun JsonObjectBuilder.putChatReasoningReplay(msg: ProtocolMessage, legacyField: String? = null) {
    if (msg.role != "assistant") return
    val reasoning = msg.reasoning?.takeIf(String::isNotBlank) ?: return
    val field = (msg.reasoningReplay as? ReasoningReplay.ChatField)?.field ?: legacyField ?: return
    put(field, reasoning)
}
