package com.promenar.nexara.domain.script

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.script.QuickJsScriptEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class QuickJsScriptEngineTest {
    private fun execute(code: String, host: ScriptCapabilities = ScriptCapabilities { _, _ -> error("未授权能力") }) = runBlocking {
        withContext(Dispatchers.Default) { QuickJsScriptEngine().execute(ScriptRequest(code), host) }
    }

    @Test fun `真实引擎支持最后表达式与result变量优先`() {
        assertThat(execute("1 + 2").error).isNull()
        assertThat(execute("1 + 2").value).isEqualTo(JsonPrimitive(3))
        assertThat(execute("var result = {name:'中',path:'C:\\\\tmp'}; 42").value.jsonObject["name"]).isEqualTo(JsonPrimitive("中"))
        assertThat(execute("let result = 7; 42").value).isEqualTo(JsonPrimitive(7))
        assertThat(execute("var result = await Promise.resolve(42)").value).isEqualTo(JsonPrimitive(42))
    }

    @Test fun `Promise结果和能力调用均被等待且每次上下文全新`() {
        val host = ScriptCapabilities { op, args ->
            assertThat(op).isEqualTo("fetch")
            assertThat(args["url"]?.jsonPrimitive?.content).isEqualTo("https://example.test")
            buildJsonObject { put("status", 200); put("body", "{\"a\":42}"); put("url", "https://example.test"); put("headers", buildJsonObject {}) }
        }
        val result = execute("var result = fetch('https://example.test').then(r=>r.json()).then(x=>x.a)", host)
        assertThat(result.errorCode).isNull()
        assertThat(result.value).isEqualTo(JsonPrimitive(42))
        assertThat(execute("globalThis.secret = 3; 1").errorCode).isNull()
        assertThat(execute("typeof secret").value).isEqualTo(JsonPrimitive("undefined"))
    }

    @Test fun `日志顺序语法错误和结果大小均有明确结果`() {
        val result = execute("console.log('一'); console.warn('二'); console.error('三'); 3")
        assertThat(result.logs.map { it.text }).containsExactly("一", "二", "三").inOrder()
        assertThat(execute("throw new Error('bad')").errorCode).isEqualTo("JS_ERROR")
        assertThat(execute("'x'.repeat(100000)").errorCode).isEqualTo("OUTPUT_LIMIT")
        assertThat(execute("console.log('x'.repeat(40000)); 1").logsTruncated).isTrue()
    }

    @Test fun `console 输出逐条转给实时展示，log 为 stdout，warn 与 error 为 stderr`() {
        val live = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        val host = object : ScriptCapabilities {
            override suspend fun invoke(operation: String, arguments: JsonObject): JsonElement = error("未授权能力")
            override fun output(fd: Int, text: String) { live.add(fd to text) }
        }
        val result = execute("console.log('一', {a:1}); console.warn('二'); console.error('三'); 3", host)
        assertThat(result.errorCode).isNull()
        assertThat(live).containsExactly(1 to "一 {\"a\":1}\n", 2 to "二\n", 2 to "三\n").inOrder()
        assertThat(result.logs.map { it.text }).containsExactly("一 {\"a\":1}", "二", "三").inOrder()
    }

    @Test fun `内存上限和引擎中断不依赖主进程执行线程`() = runBlocking {
        val engine = QuickJsScriptEngine()
        val result = withContext(Dispatchers.Default) { engine.execute(ScriptRequest("while(true){}", timeoutSeconds = 1), ScriptCapabilities { _, _ -> JsonNull }) }
        assertThat(result.errorCode).isEqualTo("TIMEOUT")
        assertThat(execute("new Uint8Array(128 * 1024 * 1024)").errorCode).isEqualTo("MEMORY_LIMIT")
    }

    @Test fun `模块按实际解析器导入且相对路径在代理之前受限`() = runBlocking {
        val request = ScriptRequest("import {v} from './lib/value.mjs'; var result = v + 1;", entryPath = "/project/main.mjs")
        val host = ScriptCapabilities { op, args ->
            assertThat(op).isEqualTo("module")
            assertThat(args["path"]?.jsonPrimitive?.content).isEqualTo("/project/lib/value.mjs")
            JsonPrimitive("export const v = 41;")
        }
        val engine = QuickJsScriptEngine()
        assertThat(withContext(Dispatchers.Default) { engine.execute(request, host) }.value).isEqualTo(JsonPrimitive(42))
        val denied = withContext(Dispatchers.Default) { engine.execute(request.copy(code = "import '../out.js'"), host) }
        assertThat(denied.errorCode).isEqualTo("IMPORT_DENIED")
    }
}
