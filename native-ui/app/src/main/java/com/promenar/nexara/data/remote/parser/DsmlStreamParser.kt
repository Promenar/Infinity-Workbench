package com.promenar.nexara.data.remote.parser

data class DsmlToolCall(
    val toolName: String,
    val args: Map<String, Any>
)

class DsmlStreamParser {
    private val TOOL_CALLS_OPEN = "<\uff5c\uff5cDSML\uff5c\uff5ctool_calls>"
    private val TOOL_CALLS_CLOSE = "</\uff5c\uff5cDSML\uff5c\uff5ctool_calls>"

    private val INVOKE_RE = Regex(
        """<\uff5c\uff5cDSML\uff5c\uff5cinvoke\s+name="([^"]+)">([\s\S]*?)</\uff5c\uff5cDSML\uff5c\uff5cinvoke>"""
    )
    private val PARAM_RE = Regex(
        """<\uff5c\uff5cDSML\uff5c\uff5cparameter\s+name="([^"]+)"(?:\s+string="(true|false)")?>([\s\S]*?)</\uff5c\uff5cDSML\uff5c\uff5cparameter>"""
    )

    private var textBuffer = ""
    private var dsmlBuffer = ""
    private var inDsml = false

    fun process(
        input: String,
        outputText: StringBuilder
    ): List<DsmlToolCall> {
        val allCalls = mutableListOf<DsmlToolCall>()
        textBuffer += input

        var safetyCounter = 0
        while (safetyCounter++ < 100) {
            if (inDsml) {
                val closeIdx = dsmlBuffer.indexOf(TOOL_CALLS_CLOSE)
                if (closeIdx == -1) break

                val blockContent = dsmlBuffer.substring(0, closeIdx)
                val remainder = dsmlBuffer.substring(closeIdx + TOOL_CALLS_CLOSE.length)
                val calls = parseInvokeBlocks(blockContent)
                allCalls.addAll(calls)

                dsmlBuffer = ""
                inDsml = false
                textBuffer = remainder
            } else {
                val openIdx = textBuffer.indexOf(TOOL_CALLS_OPEN)
                if (openIdx == -1) {
                    outputText.append(textBuffer)
                    textBuffer = ""
                    break
                }
                if (openIdx > 0) {
                    outputText.append(textBuffer.substring(0, openIdx))
                }
                dsmlBuffer = textBuffer.substring(openIdx + TOOL_CALLS_OPEN.length)
                textBuffer = ""
                inDsml = true
            }
        }

        return allCalls
    }

    fun flush(outputText: StringBuilder) {
        if (inDsml) {
            outputText.append(TOOL_CALLS_OPEN)
            outputText.append(dsmlBuffer)
            dsmlBuffer = ""
            inDsml = false
        } else if (textBuffer.isNotEmpty()) {
            outputText.append(textBuffer)
            textBuffer = ""
        }
    }

    fun reset() {
        textBuffer = ""
        dsmlBuffer = ""
        inDsml = false
    }

    private fun parseInvokeBlocks(dsmlContent: String): List<DsmlToolCall> {
        val calls = mutableListOf<DsmlToolCall>()
        INVOKE_RE.findAll(dsmlContent).forEach { invokeMatch ->
            val toolName = invokeMatch.groupValues[1]
            val inner = invokeMatch.groupValues[2]
            val args = mutableMapOf<String, Any>()

            PARAM_RE.findAll(inner).forEach { paramMatch ->
                val paramName = paramMatch.groupValues[1]
                val isString = paramMatch.groupValues[2] != "false"
                val rawValue = paramMatch.groupValues[3]
                if (isString) {
                    args[paramName] = rawValue
                } else {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        args[paramName] = kotlinx.serialization.json.Json
                            .decodeFromString<kotlinx.serialization.json.JsonElement>(rawValue)
                    } catch (_: Exception) {
                        args[paramName] = rawValue
                    }
                }
            }
            calls.add(DsmlToolCall(toolName = toolName, args = args))
        }
        return calls
    }
}
