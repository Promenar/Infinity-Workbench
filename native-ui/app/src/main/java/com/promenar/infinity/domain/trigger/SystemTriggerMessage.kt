package com.promenar.infinity.domain.trigger

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 系统触发消息的正文格式。消息以 `role = USER`、`name = [MESSAGE_NAME]` 保存，正文第一行是与界面语言无关的
 * 标记行，模型据此知道这一轮不是用户亲自发起；界面解析标记行后按当前语言渲染紧凑的触发行。
 *
 * ```
 * <system_trigger kind="schedule" source="sch_1" title="检查构建" seq="3" fired_at="2026-10-23T10:30:00+08:00"/>
 * 正文……
 * ```
 */
object SystemTriggerMessage {
    const val MESSAGE_NAME = "system_trigger"

    data class Parsed(
        val kind: SystemTurnKind,
        val sourceId: String,
        val title: String,
        val sequence: Int?,
        val catchUp: Boolean,
        val firedAt: OffsetDateTime,
        val body: String,
        val deliveryIds: List<String> = emptyList(),
        val consumerAssistantId: String? = null,
    )

    fun compose(request: SystemTurnRequest, firedAt: OffsetDateTime): String {
        val attributes = buildList {
            add("kind" to request.kind.wireName)
            add("source" to request.sourceId)
            add("title" to request.title)
            request.sequence?.let { add("seq" to it.toString()) }
            if (request.catchUp) add("catch_up" to "true")
            if (request.deliveryIds.isNotEmpty()) add("delivery_ids" to request.deliveryIds.joinToString(","))
            request.consumerAssistantId?.let { add("consumer_assistant" to it) }
            add("fired_at" to firedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        }
        val header = attributes.joinToString(" ", prefix = "<$TAG ", postfix = "/>") { (key, value) ->
            "$key=\"${escape(value)}\""
        }
        return if (request.body.isEmpty()) header else "$header\n${request.body}"
    }

    /** 不是合法标记行时返回 null，调用方按普通消息展示。 */
    fun parse(content: String): Parsed? {
        val header = content.substringBefore('\n')
        val match = HEADER.matchEntire(header) ?: return null
        val attributes = ATTRIBUTE.findAll(match.groupValues[1]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        val kind = attributes["kind"]?.let(SystemTurnKind::fromWire) ?: return null
        val source = attributes["source"]?.takeIf(String::isNotEmpty) ?: return null
        val firedAt = attributes["fired_at"]?.let {
            try {
                OffsetDateTime.parse(it, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            } catch (_: DateTimeParseException) {
                null
            }
        } ?: return null
        return Parsed(
            kind = kind,
            sourceId = source,
            title = attributes["title"].orEmpty(),
            sequence = attributes["seq"]?.toIntOrNull(),
            catchUp = attributes["catch_up"] == "true",
            firedAt = firedAt,
            body = if ('\n' in content) content.substringAfter('\n') else "",
            deliveryIds = attributes["delivery_ids"]?.split(',')?.filter(String::isNotEmpty).orEmpty(),
            consumerAssistantId = attributes["consumer_assistant"]?.takeIf(String::isNotBlank),
        )
    }

    private const val TAG = "system_trigger"
    private val HEADER = Regex("""<$TAG((?:\s+[a-z_]+="[^"]*")*)\s*/>""")
    private val ATTRIBUTE = Regex("""([a-z_]+)="([^"]*)"""")

    // 标题来自模型或命令，换行也转义，保证标记行始终只占一行。
    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                else -> append(char)
            }
        }
    }

    private fun unescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#10;", "\n")
        .replace("&#13;", "\r")
        .replace("&amp;", "&")
}
