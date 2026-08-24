package com.promenar.nexara.ui.chat.manager.skills

import android.content.Context
import android.webkit.WebView
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import com.promenar.nexara.ui.chat.manager.registry.stringArgument

class ExecJsSkill(
    private val appContext: Context,
    private val evaluator: suspend (String) -> String = { wrappedCode ->
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val wv = WebView(appContext).also {
                    it.settings.javaScriptEnabled = true
                    it.settings.allowFileAccess = false
                    it.settings.allowContentAccess = false
                    it.settings.blockNetworkLoads = true
                    it.settings.loadsImagesAutomatically = false
                    it.settings.domStorageEnabled = false
                    it.removeJavascriptInterface("searchBoxJavaBridge_")
                    it.removeJavascriptInterface("accessibility")
                    it.removeJavascriptInterface("accessibilityTraversal")
                }
                wv.evaluateJavascript(wrappedCode) { result ->
                    cont.resume(result ?: "null")
                    wv.destroy()
                }
                cont.invokeOnCancellation { wv.destroy() }
            }
        }
    },
) : SkillDefinition {
    override val id = "exec_js"
    override val name = "exec_js"
    override val description =
        "Execute JavaScript code in a sandbox and return the result. " +
        "Use for calculations, string transformations, JSON processing, " +
        "or logic that requires precise execution. " +
        "No file system or network access. " +
        "Assign the final value to a variable named 'result'."
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SCRIPT

    override val parametersSchema = """{
        "type":"object",
        "properties":{
            "code":{"type":"string","description":"JavaScript code. Return value via 'result = ...;'"}
        },
        "required":["code"]
    }""".trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val code = args.stringArgument("code")
            ?: return ToolResult("err", "Missing required parameter: code", "error")

        if (code.length > 50000) {
            return ToolResult("err", "Code too long (max 50000 chars)", "error")
        }

        return try {
            val wrappedCode = buildString {
                append("(function(){try{var result;")
                append(code)
                append(";return JSON.stringify({ok:true,value:result});")
                append("}catch(e){return JSON.stringify({ok:false,error:e.toString()});}})()")
            }

            val output = withTimeoutOrNull(5000L) {
                evaluator(wrappedCode)
            }

            if (output == null) {
                return ToolResult(
                    "exec_js_${System.currentTimeMillis()}",
                    "Execution timed out (>5s)", "error"
                )
            }

            val clean = output
                .trim('"')
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
            val parsed = json.decodeFromString<JsResult>(clean)
            if (parsed.ok) {
                ToolResult(
                    "exec_js_${System.currentTimeMillis()}",
                    "Result: ${parsed.value}", "success"
                )
            } else {
                ToolResult(
                    "exec_js_${System.currentTimeMillis()}",
                    "JS Error: ${parsed.error}", "error"
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ToolResult(
                "exec_js_${System.currentTimeMillis()}",
                "Execution failed: ${e.message}", "error"
            )
        }
    }

    @Serializable
    private data class JsResult(
        val ok: Boolean,
        val value: JsonElement? = null,
        val error: String? = null
    )
}
