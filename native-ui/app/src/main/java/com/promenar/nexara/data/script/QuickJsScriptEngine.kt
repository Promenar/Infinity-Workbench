package com.promenar.nexara.data.script

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.moduleLoader
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.promenar.nexara.domain.script.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class QuickJsScriptEngine : ScriptEngine {
    override val language = "javascript"
    @Volatile private var active: QuickJs? = null

    override fun cancel() { active?.let { runCatching { it.interruptEvaluation() } } }

    override suspend fun execute(request: ScriptRequest, capabilities: ScriptCapabilities): ScriptResult {
        val output = ScriptOutput()
        var capabilityFailure: ScriptFailure? = null
        val root = request.entryPath?.let(ScriptModules::root)
        val loader = moduleLoader {
            normalize { base, name ->
                try { ScriptModules.resolve(root ?: throw ScriptFailure("IMPORT_DENIED", "内联脚本不允许导入模块"), base, name) }
                catch (e: ScriptFailure) { capabilityFailure = e; throw e }
            }
            load { name ->
                val content = runBlocking {
                    capabilities.invoke("module", buildJsonObject { put("path", name) })
                }.jsonPrimitive.content
                ModuleContent.Source(content)
            }
        }
        val engine = QuickJs.create(Dispatchers.Default, loader)
        active = engine
        return try {
            engine.memoryLimit = ScriptLimits.MEMORY_BYTES
            engine.maxStackSize = ScriptLimits.STACK_BYTES
            engine.evaluationTimeoutMillis = request.timeoutSeconds * 1000L
            engine.asyncFunction("__nexaraHost") { args ->
                try {
                    val operation = args.getOrNull(0) as? String ?: throw ScriptFailure("INVALID_ARGUMENT", "缺少能力名称")
                    val json = args.getOrNull(1) as? String ?: throw ScriptFailure("INVALID_ARGUMENT", "能力参数必须为 JSON")
                    capabilities.invoke(operation, Json.parseToJsonElement(json).jsonObject).toString()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: ScriptFailure) { capabilityFailure = e; throw e }
            }
            engine.function("__nexaraLog") { args ->
                output.log(args.getOrNull(0) as? String ?: "log", args.getOrNull(1) as? String ?: "")
            }
            engine.evaluate<Any?>(BOOTSTRAP)
            engine.evaluate<Any?>("globalThis.args = Object.freeze(${request.args});")
            val value = if (request.entryPath != null) {
                engine.evaluate<Any?>(request.code + "\n;globalThis.__nexaraModuleResult = typeof result === 'undefined' ? null : result;", filename = request.entryPath, asModule = true)
                parseOutput(engine.evaluate<String>("await __nexaraEncode(globalThis.__nexaraModuleResult)"))
            } else {
                val completion = engine.evaluate<Any?>(request.code, filename = "exec_js.js")
                val explicit = engine.evaluate<String>("await __nexaraEncode(typeof result === 'undefined' ? {found:false} : {found:true,value:result})")
                val parsed = parseOutput(explicit).jsonObject
                if (parsed["found"]?.jsonPrimitive?.booleanOrNull == true) {
                    // Promise 在 result 变量中时必须等待解析。
                    parseOutput(engine.evaluate<String>("await __nexaraEncode(result)"))
                } else boundedJson(completion)
            }
            capabilityFailure?.takeIf { it.code.endsWith("LIMIT") }?.let { throw it }
            output.finish(value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            val code = capabilityFailure?.code ?: when {
                e is ScriptFailure -> e.code
                e is QuickJsInterruptedException -> "TIMEOUT"
                e is OutOfMemoryError || e.message?.contains("out of memory", true) == true -> "MEMORY_LIMIT"
                e.message?.contains("NEXARA_OUTPUT_LIMIT") == true -> "OUTPUT_LIMIT"
                else -> "JS_ERROR"
            }
            val logs = runCatching { output.finish(JsonNull) }.getOrDefault(ScriptResult())
            logs.copy(errorCode = code, error = (capabilityFailure?.message ?: e.message ?: "脚本执行失败").take(1024))
        } finally {
            active = null
            engine.close()
        }
    }

    private fun parseOutput(text: String): JsonElement {
        if (text.toByteArray(Charsets.UTF_8).size > ScriptLimits.OUTPUT_BYTES) throw ScriptFailure("OUTPUT_LIMIT", "脚本输出超过总量上限")
        return Json.parseToJsonElement(text)
    }

    private fun boundedJson(value: Any?): JsonElement {
        var count = 0
        fun convert(item: Any?, depth: Int): JsonElement {
            if (depth > 32 || ++count > ScriptLimits.OUTPUT_BYTES / 2) throw ScriptFailure("OUTPUT_LIMIT", "结果深度或大小超过上限")
            return when (item) {
                null, Unit -> JsonNull
                is Boolean -> JsonPrimitive(item)
                is Number -> JsonPrimitive(item)
                is String -> {
                    if (item.length > ScriptLimits.OUTPUT_BYTES) throw ScriptFailure("OUTPUT_LIMIT", "结果超过上限")
                    JsonPrimitive(item)
                }
                is Map<*, *> -> JsonObject(item.entries.associate { it.key.toString() to convert(it.value, depth + 1) })
                is Iterable<*> -> JsonArray(item.map { convert(it, depth + 1) })
                is Array<*> -> JsonArray(item.map { convert(it, depth + 1) })
                else -> throw ScriptFailure("JS_ERROR", "返回值必须可序列化为 JSON")
            }
        }
        return convert(value, 0)
    }

    private companion object {
        val BOOTSTRAP = """
            (() => {
              const encode = JSON.stringify, decode = JSON.parse;
              const host = globalThis.__nexaraHost;
              const call = async (op, args) => decode(await host(op, encode(args)));
              globalThis.__nexaraEncode = async value => {
                const encoded = encode(await value) ?? 'null';
                if (encoded.length > ${ScriptLimits.OUTPUT_BYTES}) throw Error('NEXARA_OUTPUT_LIMIT');
                return encoded;
              };
              globalThis.console = Object.freeze(Object.fromEntries(['log','warn','error'].map(level => [level, (...args) => {
                const text = args.map(x => typeof x === 'string' ? x : encode(x) ?? String(x)).join(' ');
                __nexaraLog(level, text);
              }])));
              globalThis.fetch = async (url, options = {}) => {
                const r = await call('fetch', {url:String(url), ...options});
                return Object.freeze({url:r.url,status:r.status,ok:r.status>=200&&r.status<300,headers:r.headers,
                  text:async()=>r.body,json:async()=>decode(r.body)});
              };
              globalThis.fs = Object.freeze({
                read: path => call('read', {path:String(path)}),
                readFile: async path => (await call('read', {path:String(path)})).content,
                stat: path => call('stat', {path:String(path)}),
                list: (path='/') => call('list', {path:String(path)}),
                writeFile: (path, content, options={}) => call('write', {...options,path:String(path),content:String(content)}),
                createFile: (path, content='') => call('create', {path:String(path),content:String(content)}),
                mkdir: path => call('mkdir', {path:String(path)}),
                deleteFile: (path, options={}) => call('delete', {...options,path:String(path)})
              });
            })();
        """.trimIndent()
    }
}
