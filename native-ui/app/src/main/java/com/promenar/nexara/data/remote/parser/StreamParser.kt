package com.promenar.nexara.data.remote.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any>
)

data class PlanStep(
    val id: String,
    val title: String,
    val status: String,
    val description: String? = null
)

data class ParseResult(
    val content: String,
    val reasoning: String = "",
    val toolCalls: List<ToolCall>? = null,
    val plan: List<PlanStep>? = null
)

enum class ParserState {
    IDLE,
    IN_TOOL_XML,
    IN_PLAN
}

class StreamParser {

    private var buffer: String = ""
    private var state: ParserState = ParserState.IDLE
    private var startTag: String = ""

    private var inFence: Boolean = false
    private var inInlineCode: Boolean = false
    private var fenceMarker: String = ""

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun reset() {
        buffer = ""
        state = ParserState.IDLE
        startTag = ""
        inFence = false
        inInlineCode = false
        fenceMarker = ""
    }

    fun process(chunk: String): ParseResult {
        val outputContent = StringBuilder()
        val outputToolCalls = mutableListOf<ToolCall>()
        var outputPlan: List<PlanStep>? = null

        buffer += chunk

        var loopGuard = 0
        while (loopGuard++ < 1000) {
            if (buffer.isEmpty()) break

            when (state) {
                ParserState.IDLE -> {
                    if (inFence) {
                        val closeIdx = buffer.indexOf(fenceMarker)
                        if (closeIdx != -1) {
                            val segment = buffer.substring(0, closeIdx + fenceMarker.length)
                            outputContent.append(segment)
                            buffer = buffer.substring(closeIdx + fenceMarker.length)
                            inFence = false
                            fenceMarker = ""
                            continue
                        } else {
                            if (buffer.length > 20) {
                                val safeLen = buffer.length - 10
                                outputContent.append(buffer.substring(0, safeLen))
                                buffer = buffer.substring(safeLen)
                            }
                            break
                        }
                    }

                    if (inInlineCode) {
                        val closeIdx = buffer.indexOf(fenceMarker)
                        if (closeIdx != -1) {
                            outputContent.append(buffer.substring(0, closeIdx + fenceMarker.length))
                            buffer = buffer.substring(closeIdx + fenceMarker.length)
                            inInlineCode = false
                            fenceMarker = ""
                            continue
                        } else {
                            if (buffer.length > 20) {
                                val safeLen = buffer.length - 10
                                outputContent.append(buffer.substring(0, safeLen))
                                buffer = buffer.substring(safeLen)
                            }
                            break
                        }
                    }

                    val tagResult = findTag(buffer)
                    val codeResult = findCodeStart(buffer)

                    val tagIdx = tagResult?.first ?: -1
                    val codeIdx = codeResult?.first ?: -1

                    val winner = when {
                        tagIdx != -1 && codeIdx != -1 -> if (tagIdx < codeIdx) "tag" else "code"
                        tagIdx != -1 -> "tag"
                        codeIdx != -1 -> "code"
                        else -> "none"
                    }

                    if (winner == "none") {
                        val lastOpen = buffer.lastIndexOf('<')
                        val lastBacktick = buffer.lastIndexOf('`')
                        val lastTilde = buffer.lastIndexOf('~')
                        val dangerZone = maxOf(lastOpen, lastBacktick, lastTilde)

                        if (dangerZone != -1 && dangerZone > buffer.length - 10) {
                            outputContent.append(buffer.substring(0, dangerZone))
                            buffer = buffer.substring(dangerZone)
                        } else {
                            outputContent.append(buffer)
                            buffer = ""
                        }
                        break
                    }

                    if (winner == "code" && codeResult != null) {
                        outputContent.append(buffer.substring(0, codeIdx))
                        buffer = buffer.substring(codeIdx)

                        val marker = codeResult.second
                        if (marker.length >= 3) {
                            inFence = true
                        } else {
                            inInlineCode = true
                        }
                        fenceMarker = marker

                        outputContent.append(marker)
                        buffer = buffer.substring(marker.length)
                        continue
                    }

                    if (winner == "tag" && tagResult != null) {
                        outputContent.append(buffer.substring(0, tagIdx))
                        buffer = buffer.substring(tagIdx)

                        val tagName = tagResult.second.lowercase()
                        startTag = tagName

                        if (tagName == "plan") {
                            state = ParserState.IN_PLAN
                            val closeBracket = buffer.indexOf('>')
                            if (closeBracket != -1) {
                                buffer = buffer.substring(closeBracket + 1)
                            } else {
                                break
                            }
                        } else {
                            state = ParserState.IN_TOOL_XML
                            continue
                        }
                    }
                }

                ParserState.IN_PLAN -> {
                    val endTag = "</plan>"
                    val endIdx = buffer.indexOf(endTag, ignoreCase = true)
                    if (endIdx != -1) {
                        val planContent = buffer.substring(0, endIdx)
                        outputPlan = parsePlan(planContent)
                        buffer = buffer.substring(endIdx + endTag.length)
                        state = ParserState.IDLE
                    } else {
                        break
                    }
                }

                ParserState.IN_TOOL_XML -> {
                    val closeTag = "</$startTag>"
                    val endIdx = buffer.indexOf(closeTag, ignoreCase = true)
                    if (endIdx != -1) {
                        val fullBlock = buffer.substring(0, endIdx + closeTag.length)
                        val tools = parseTools(fullBlock)
                        outputToolCalls.addAll(tools)
                        buffer = buffer.substring(endIdx + closeTag.length)
                        state = ParserState.IDLE
                    } else {
                        break
                    }
                }
            }
        }

        return ParseResult(
            content = outputContent.toString(),
            toolCalls = if (outputToolCalls.isNotEmpty()) outputToolCalls else null,
            plan = outputPlan
        )
    }

    private fun findTag(buf: String): Pair<Int, String>? {
        val tagNames = listOf("plan", "tool_call_xml", "tool_code", "tool_calls", "tools", "tool_call", "call")
        var bestIdx = -1
        var bestTag = ""

        for (tag in tagNames) {
            val idx = buf.indexOf("<$tag", ignoreCase = true)
            if (idx != -1) {
                val afterPrefix = idx + 1 + tag.length
                if (afterPrefix < buf.length) {
                    val nextChar = buf[afterPrefix]
                    if (nextChar == '>' || nextChar.isWhitespace()) {
                        if (bestIdx == -1 || idx < bestIdx) {
                            bestIdx = idx
                            bestTag = tag
                        }
                    }
                } else if (afterPrefix == buf.length) {
                    continue
                }
            }
        }
        return if (bestIdx != -1) Pair(bestIdx, bestTag) else null
    }

    private fun findCodeStart(buf: String): Pair<Int, String>? {
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            if (c == '`' || c == '~') {
                val start = i
                val marker = StringBuilder()
                while (i < buf.length && buf[i] == c) {
                    marker.append(buf[i])
                    i++
                }
                val markerStr = marker.toString()
                if (markerStr.length >= 3) {
                    return Pair(start, markerStr)
                }
                if (markerStr.length in 1..2) {
                    return Pair(start, markerStr)
                }
            } else {
                i++
            }
        }
        return null
    }

    private fun parsePlan(text: String): List<PlanStep>? {
        val sanitized = text.trim()
            .replace(Regex("^```json\\s*"), "")
            .replace(Regex("\\s*```$"), "")

        try {
            val parsed = json.parseToJsonElement(sanitized)
            val steps = mutableListOf<PlanStep>()

            when (parsed) {
                is JsonArray -> {
                    for ((idx, element) in parsed.withIndex()) {
                        val obj = element.jsonObject
                        steps.add(
                            PlanStep(
                                id = obj["id"]?.jsonPrimitive?.content ?: "plan_step_${System.currentTimeMillis()}_$idx",
                                title = obj["title"]?.jsonPrimitive?.content
                                    ?: obj["content"]?.jsonPrimitive?.content
                                    ?: "Step ${idx + 1}",
                                status = obj["status"]?.jsonPrimitive?.content ?: "pending",
                                description = obj["description"]?.jsonPrimitive?.content
                            )
                        )
                    }
                }
                is JsonObject -> {
                    val stepsArr = obj(parsed)["steps"]?.jsonArray
                    if (stepsArr != null) {
                        for ((idx, element) in stepsArr.withIndex()) {
                            val s = element.jsonObject
                            steps.add(
                                PlanStep(
                                    id = s["id"]?.jsonPrimitive?.content ?: "plan_step_${System.currentTimeMillis()}_$idx",
                                    title = s["title"]?.jsonPrimitive?.content
                                        ?: s["content"]?.jsonPrimitive?.content
                                        ?: "Step ${idx + 1}",
                                    status = s["status"]?.jsonPrimitive?.content ?: "pending",
                                    description = s["description"]?.jsonPrimitive?.content
                                )
                            )
                        }
                    }
                }
                else -> {}
            }
            if (steps.isNotEmpty()) return steps
        } catch (_: Exception) {
        }

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isNotEmpty()) {
            return lines.mapIndexed { idx, line ->
                PlanStep(
                    id = "legacy_step_${System.currentTimeMillis()}_$idx",
                    title = line.replace(Regex("^\\d+[.)\\s]+"), ""),
                    status = "pending"
                )
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTools(block: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        val tag = startTag.lowercase()

        if (tag == "tool_code" || tag == "tool_calls" || tag == "tools") {
            val inner = block
                .replace(Regex("</?(tool_code|tool_calls|tools)>", RegexOption.IGNORE_CASE), "")
                .trim()
            try {
                val parsed = json.parseToJsonElement(inner)
                extractToolCallsFromJson(parsed, calls)
            } catch (_: Exception) {
            }
        } else if (tag == "call") {
            val match = Regex("""<call\s+tool=["']([^"']+)["']>([\s\S]*?)</call>""", RegexOption.IGNORE_CASE)
                .find(block)
            if (match != null) {
                val name = match.groupValues[1]
                val inner = match.groupValues[2].trim()

                val inputMatch = Regex("""<tool_input>([\s\S]*?)</tool_input>""", RegexOption.IGNORE_CASE)
                    .find(inner)
                val jsonStr = if (inputMatch != null) {
                    inputMatch.groupValues[1]
                } else {
                    val firstBrace = inner.indexOf('{')
                    val lastBrace = inner.lastIndexOf('}')
                    if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                        inner.substring(firstBrace, lastBrace + 1)
                    } else {
                        inner
                    }
                }

                try {
                    val parsed = json.parseToJsonElement(jsonStr)
                    calls.add(
                        ToolCall(
                            id = generateCallId(),
                            name = name,
                            arguments = jsonElementToMap(parsed)
                        )
                    )
                } catch (_: Exception) {
                }
            }
        } else if (tag == "tool_call" || tag == "tool_call_xml") {
            val nameMatch = Regex("""<function_name>([\s\S]*?)</function_name>""", RegexOption.IGNORE_CASE)
                .find(block)
            val paramsMatch = Regex("""<parameters>([\s\S]*?)</parameters>""", RegexOption.IGNORE_CASE)
                .find(block)

            if (nameMatch != null && paramsMatch != null) {
                val name = nameMatch.groupValues[1].trim()
                val paramsInner = paramsMatch.groupValues[1].trim()

                val args = mutableMapOf<String, Any>()
                val argRegex = Regex("""<([^>]+)>([\s\S]*?)</\1>""")
                var hasXmlArgs = false
                for (argMatch in argRegex.findAll(paramsInner)) {
                    args[argMatch.groupValues[1]] = argMatch.groupValues[2].trim()
                    hasXmlArgs = true
                }

                if (!hasXmlArgs && paramsInner.startsWith('{')) {
                    try {
                        val parsed = json.parseToJsonElement(paramsInner)
                        args.putAll(jsonElementToMap(parsed))
                    } catch (_: Exception) {
                    }
                }

                calls.add(ToolCall(id = generateCallId(), name = name, arguments = args))
            }
        }

        return calls
    }

    private fun extractToolCallsFromJson(parsed: JsonElement, calls: MutableList<ToolCall>) {
        when (parsed) {
            is JsonArray -> {
                for (element in parsed) {
                    val obj = obj(element)
                    val name = obj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                        ?: obj["name"]?.jsonPrimitive?.content
                        ?: obj["id"]?.jsonPrimitive?.content
                    val argsElement = obj["function"]?.jsonObject?.get("arguments")
                        ?: obj["arguments"]
                        ?: obj["parameters"]
                    if (name != null && argsElement != null) {
                        calls.add(
                            ToolCall(
                                id = generateCallId(),
                                name = name,
                                arguments = jsonElementToMap(argsElement)
                            )
                        )
                    }
                }
            }
            is JsonObject -> {
                val name = obj(parsed)["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    ?: obj(parsed)["name"]?.jsonPrimitive?.content
                val argsElement = obj(parsed)["function"]?.jsonObject?.get("arguments")
                    ?: obj(parsed)["arguments"]
                if (name != null && argsElement != null) {
                    calls.add(
                        ToolCall(
                            id = generateCallId(),
                            name = name,
                            arguments = jsonElementToMap(argsElement)
                        )
                    )
                }
            }
            else -> {}
        }
    }

    private fun jsonElementToMap(element: JsonElement): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        if (element is JsonObject) {
            for ((key, value) in element) {
                result[key] = jsonElementToValue(value)
            }
        }
        return result
    }

    private fun jsonElementToValue(element: JsonElement): Any {
        return when (element) {
            is JsonObject -> jsonElementToMap(element)
            is JsonArray -> element.map { jsonElementToValue(it) }
            else -> {
                val prim = element.jsonPrimitive
                when {
                    prim.isString -> prim.content
                    prim.booleanOrNull != null -> prim.boolean
                    prim.intOrNull != null -> prim.int
                    prim.doubleOrNull != null -> prim.double
                    else -> prim.content
                }
            }
        }
    }

    private fun obj(element: JsonElement): JsonObject =
        if (element is JsonObject) element else JsonObject(emptyMap())

    private fun generateCallId(): String =
        "call_${System.currentTimeMillis()}_${(0..999999999).random().toString(36)}"

    fun getCleanContent(rawContent: String): String {
        return rawContent
            .replace(
                Regex(
                    """(?:<!--\s*THINKING_START\s*-->[\s\S]*?<!--\s*THINKING_END\s*-->)|""" +
                    """(?:<(?:think|thought|plan|tool_code|tool_calls|tools|tool_call|call)[^>]*>[\s\S]*?</(?:think|thought|plan|tool_code|tool_calls|tools|tool_call|call)>)""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
    }
}

private fun String.indexOf(str: String, ignoreCase: Boolean): Int {
    return if (ignoreCase) this.lowercase().indexOf(str.lowercase()) else this.indexOf(str)
}
