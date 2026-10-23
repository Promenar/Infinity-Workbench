package com.promenar.nexara.ui.chat

import androidx.annotation.StringRes
import com.promenar.nexara.R
import com.promenar.nexara.data.state.StateChange
import com.promenar.nexara.data.state.StateField
import kotlinx.serialization.json.*

internal fun stateValueText(value: JsonElement, unit: String? = null): String = when (value) {
    is JsonArray -> value.joinToString("、") { (it as? JsonPrimitive)?.content.orEmpty() }
    is JsonObject -> "${value["current"]?.jsonPrimitive?.content}/${value["max"]?.jsonPrimitive?.content}"
    is JsonPrimitive -> value.content
}.let { if (!unit.isNullOrBlank()) "$it $unit" else it }

internal fun StateField.progress(): Float = if (type == "meter") {
    val current = (value as? JsonObject)?.get("current")?.jsonPrimitive?.doubleOrNull ?: 0.0
    val max = value.jsonObject["max"]?.jsonPrimitive?.doubleOrNull ?: 1.0
    (current / max).toFloat().coerceIn(0f, 1f)
} else 0f

internal data class StateToolSummary(@param:StringRes val resource: Int, val detail: String? = null)

internal fun stateToolSummary(invocation: ToolInvocationPresentation): StateToolSummary? {
    if (invocation.name !in setOf("state_define", "state_update", "state_get", "state_remove") || invocation.failed || !invocation.complete) return null
    val result = invocation.results.lastOrNull { it.type == "tool_result" } ?: return null
    val data = runCatching { Json.parseToJsonElement(result.content.orEmpty()).jsonObject }.getOrNull() ?: return null
    return when (invocation.name) {
        "state_define" -> StateToolSummary(
            if (data["created"]?.jsonPrimitive?.booleanOrNull == true) R.string.state_created else R.string.state_defined,
            (data["board"] as? JsonObject)?.get("title")?.jsonPrimitive?.content.orEmpty().take(80),
        )
        "state_update" -> {
            val changes = runCatching { Json.decodeFromJsonElement<List<StateChange>>(data.getValue("changes")) }.getOrNull() ?: return null
            StateToolSummary(R.string.state_updated, changes.joinToString("，") { change ->
                val before = change.before
                val after = change.after
                val values = if (before is JsonArray && after is JsonArray) {
                    val added = after.filter { it !in before }.map { "+${stateValueText(it)}" }
                    val removed = before.filter { it !in after }.map { "−${stateValueText(it)}" }
                    (added + removed).joinToString(" ").ifEmpty { "${stateValueText(before)}→${stateValueText(after)}" }
                } else "${stateValueText(before)}→${stateValueText(after)}"
                "${change.label} ${values.take(80)}"
            }.take(240).takeIf { it.isNotEmpty() })
        }
        "state_remove" -> StateToolSummary(R.string.state_removed)
        else -> StateToolSummary(R.string.state_read)
    }
}

internal fun stateFieldEdit(field: StateField, text: String, max: String, items: List<String>): com.promenar.nexara.data.state.StateOperation {
    fun number(raw: String): JsonElement = Json.parseToJsonElement(raw.trim())
    val value = when (field.type) {
        "text" -> JsonPrimitive(text)
        "number" -> number(text)
        "meter" -> buildJsonObject { put("current", number(text)); put("max", number(max)) }
        else -> JsonArray(items.map(::JsonPrimitive))
    }
    com.promenar.nexara.data.state.StateBoardEngine.validateValue(field.type, value)
    return com.promenar.nexara.data.state.StateOperation(field.key, "set", value)
}

@androidx.compose.runtime.Composable
internal fun stateToolSummaryText(summary: StateToolSummary): String = when (summary.resource) {
    R.string.state_created, R.string.state_defined -> androidx.compose.ui.res.stringResource(summary.resource, summary.detail.orEmpty())
    R.string.state_updated -> androidx.compose.ui.res.stringResource(summary.resource, summary.detail ?: androidx.compose.ui.res.stringResource(R.string.state_no_changes))
    else -> androidx.compose.ui.res.stringResource(summary.resource)
}
