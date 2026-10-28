package com.promenar.infinity.domain.script

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.script.QuickJsScriptEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class QuickJsHeadersTest {
    private val rawHeaders = """{"Content-Type":"application/json","X-Trace":"first","x-trace":"second","empty":""}"""

    private fun execute(code: String): ScriptResult = runBlocking {
        QuickJsScriptEngine().execute(ScriptRequest(code), ScriptCapabilities { operation, _ ->
            assertThat(operation).isEqualTo("fetch")
            buildJsonObject {
                put("url", "https://example.test")
                put("status", 200)
                put("headers", Json.parseToJsonElement(rawHeaders))
                put("body", "{}")
            }
        })
    }

    @Test
    fun `headers按大小写无关名称读取并合并重复值且保留小写属性`() {
        val result = execute("""
            const response = await fetch('https://example.test');
            var result = {
              contentType: response.headers.get('CONTENT-TYPE'),
              trace: response.headers.get('X-TRACE'),
              direct: response.headers['content-type'],
              directTrace: response.headers['x-trace'],
              hasEmpty: response.headers.has('EMPTY'),
              empty: response.headers.get('empty'),
              missing: response.headers.get('missing'),
              hasMissing: response.headers.has('missing')
            };
        """.trimIndent())

        assertThat(result.errorCode).isNull()
        assertThat(result.value).isEqualTo(Json.parseToJsonElement("""
            {"contentType":"application/json","trace":"first, second","direct":"application/json","directTrace":"first, second","hasEmpty":true,"empty":"","missing":null,"hasMissing":false}
        """.trimIndent()))
    }

    @Test
    fun `headers查询拒绝非法名称和缺失参数且合法小写名称仍可读取`() {
        val result = execute("""
            const headers = (await fetch('https://example.test')).headers;
            const failure = action => {
              try { action(); return null; } catch (error) { return error.name; }
            };
            var result = {
              invalidGet: failure(() => headers.get('bad name')),
              invalidHas: failure(() => headers.has('bad name')),
              emptyGet: failure(() => headers.get('')),
              emptyHas: failure(() => headers.has('')),
              missingGet: failure(() => headers.get()),
              missingHas: failure(() => headers.has()),
              valid: headers.get('content-type'),
              present: headers.has('x-trace')
            };
        """.trimIndent())

        assertThat(result.errorCode).isNull()
        assertThat(result.value).isEqualTo(Json.parseToJsonElement("""
            {"invalidGet":"TypeError","invalidHas":"TypeError","emptyGet":"TypeError","emptyHas":"TypeError", "missingGet":"TypeError","missingHas":"TypeError","valid":"application/json","present":true}
        """.trimIndent()))
    }

    @Test
    fun `headers迭代与forEach按标准顺序返回合并值与原对象`() {
        val result = execute("""
            const headers = (await fetch('https://example.test')).headers;
            const context = {seen:[]};
            headers.forEach(function(value, name, owner) {
              this.seen.push([name, value, owner === headers]);
            }, context);
            var result = {
              keys: [...headers.keys()], values: [...headers.values()],
              entries: [...headers.entries()], iterable: [...headers], seen: context.seen
            };
        """.trimIndent())

        assertThat(result.errorCode).isNull()
        assertThat(result.value).isEqualTo(Json.parseToJsonElement("""
            {"keys":["content-type","empty","x-trace"],"values":["application/json","","first, second"],
             "entries":[["content-type","application/json"],["empty",""],["x-trace","first, second"]],
             "iterable":[["content-type","application/json"],["empty",""],["x-trace","first, second"]],
             "seen":[["content-type","application/json",true],["empty","",true],["x-trace","first, second",true]]}
        """.trimIndent()))
    }

    @Test
    fun `headers只读且新增方法不参与枚举或改变原有JSON输出`() {
        val result = execute("""
            const headers = (await fetch('https://example.test')).headers;
            const methods = ['get','has','keys','values','entries','forEach'];
            var result = {
              frozen: Object.isFrozen(headers),
              hidden: methods.every(name => !Object.prototype.propertyIsEnumerable.call(headers, name)),
              immutable: ['append','set','delete'].every(name => typeof headers[name] === 'undefined'),
              json: JSON.stringify(headers)
            };
        """.trimIndent())

        assertThat(result.errorCode).isNull()
        assertThat(result.value.jsonObject["frozen"]).isEqualTo(JsonPrimitive(true))
        assertThat(result.value.jsonObject["hidden"]).isEqualTo(JsonPrimitive(true))
        assertThat(result.value.jsonObject["immutable"]).isEqualTo(JsonPrimitive(true))
        assertThat(result.value.jsonObject["json"]).isEqualTo(JsonPrimitive(rawHeaders))
    }
}
