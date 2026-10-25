package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.canvas.FileCanvasStore
import com.promenar.nexara.data.canvas.CanvasWorkspaceFiles
import com.promenar.nexara.data.canvas.canvasSha256
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.tool.ToolSchemaValidation
import com.promenar.nexara.domain.tool.ToolSchemaValidator
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanvasSkillsTest {
    @get:Rule val folder = TemporaryFolder()
    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspacePath = null
        override val workspaceRootUuid = "workspace"
        override val assistantMessageId = "assistant-message"
    }

    @Test fun `创建与更新的模型可见描述要求窄屏可读和宽表格容器`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        for (skill in listOf(CanvasCreateSkill(store), CanvasUpdateSkill(store))) {
            assertThat(skill.description).contains("320–420 CSS px")
            assertThat(skill.description).contains("overflow-x: auto")
            assertThat(skill.description).contains("换行")
            assertThat(skill.description).contains("不要使用超过容器的固定宽度")
        }
    }

    @Test fun `工作区失败工具仍成功并返回警告路径恢复后读取导入修改`() = runTest {
        var fail = true
        val content = mutableMapOf<String, ByteArray>()
        val files = object : CanvasWorkspaceFiles {
            override suspend fun exists(sessionId: String, path: String) = content.containsKey(path)
            override suspend fun read(sessionId: String, path: String, maxBytes: Int) = content[path]
            override suspend fun write(sessionId: String, path: String, html: String, expectedHash: String?) {
                if (fail) throw java.io.IOException("测试拒绝写入")
                check(content[path]?.let(::canvasSha256) == expectedHash)
                content[path] = html.toByteArray()
            }
        }
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler), workspaceFiles = files)
        val created = CanvasCreateSkill(store).execute(args("""{"title":"图表","html":"原文"}"""), context)
        assertThat(created.status).isEqualTo("success")
        assertThat(created.body()["workspace_path"]!!.jsonPrimitive.content).isEqualTo("canvas/图表.html")
        assertThat(created.body()["workspace_warning"]!!.jsonPrimitive.content).isNotEmpty()
        val id = created.body()["canvasId"]!!.jsonPrimitive.content
        val updated = CanvasUpdateSkill(store).execute(args("""{"canvas_id":"$id","html":"更新"}"""), context)
        assertThat(updated.status).isEqualTo("success")
        assertThat(updated.body()["workspace_warning"]).isNotNull()
        fail = false
        CanvasReadSkill(store).execute(args("""{"canvas_id":"$id"}"""), context)
        content["canvas/图表.html"] = "外部".toByteArray()
        val read = CanvasReadSkill(store).execute(args("""{"canvas_id":"$id"}"""), context)
        assertThat(read.body()["html"]!!.jsonPrimitive.content).isEqualTo("外部")
        assertThat(read.body()["version"]!!.jsonPrimitive.content).isEqualTo("3")
        assertThat(read.body()["workspace_warning"]).isNull()
        assertThat(read.body()["metadata"]!!.jsonObject.keys).doesNotContain("workspaceHash")
    }

    @Test fun `创建返回固定顶层字段并绑定真实消息且不泄露源码`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val result = CanvasCreateSkill(store).execute(args("""{"title":"图表 \"一\"","html":"<p>正文</p>"}"""), context)
        assertThat(result.status).isEqualTo("success")
        val body = result.body()
        assertThat(body.keys).containsAtLeast("canvasId", "title", "version", "metadata")
        assertThat(body["title"]!!.jsonPrimitive.content).isEqualTo("图表 \"一\"")
        assertThat(body["html"]).isNull()
        assertThat(body["version"]!!.jsonPrimitive.content).isEqualTo("1")
        val saved = store.read("session", body["canvasId"]!!.jsonPrimitive.content)!!
        assertThat(saved.metadata.createdByMessageId).isEqualTo("assistant-message")
    }

    @Test fun `更新整体替换与补丁参数结构直接消费嵌套JSON`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("session", "标题", "one", "m")
        val skill = CanvasUpdateSkill(store)
        val replaced = skill.execute(args("""{"canvas_id":"${created.metadata.id}","html":"two","expected_version":1}"""), context)
        assertThat(replaced.status).isEqualTo("success")
        assertThat(replaced.body()["version"]!!.jsonPrimitive.content).isEqualTo("2")
        val patched = skill.execute(args("""{"canvas_id":"${created.metadata.id}","patches":[{"find":"two","replace":"three"}],"expected_version":2}"""), context)
        assertThat(patched.status).isEqualTo("success")
        assertThat(store.read("session", created.metadata.id)?.html).isEqualTo("three")
        assertThat(patched.body()["html"]).isNull()
    }

    @Test fun `读取含源码和元数据且支持显式历史版本`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("session", "标题", "one", "m")
        store.update("session", created.metadata.id, html = "two")
        val result = CanvasReadSkill(store).execute(args("""{"canvas_id":"${created.metadata.id}","version":1}"""), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(result.body()["html"]!!.jsonPrimitive.content).isEqualTo("one")
        assertThat(result.body()["version"]!!.jsonPrimitive.content).isEqualTo("1")
        assertThat(result.body()["metadata"]!!.jsonObject["currentVersion"]!!.jsonPrimitive.content).isEqualTo("2")
    }

    @Test fun `列表只返回当前会话元数据且无源码`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("session", "标题", "<secret-source>", "m")
        store.create("other", "其它会话", "other", "m")
        val result = CanvasListSkill(store).execute(args("{}"), context)
        val canvases = result.body()["canvases"]!!.jsonArray
        assertThat(canvases).hasSize(1)
        assertThat(canvases.single().jsonObject["id"]!!.jsonPrimitive.content).isEqualTo(created.metadata.id)
        assertThat(result.content).doesNotContain("secret-source")
    }

    @Test fun `创建拒绝缺失和错误类型参数及缺少消息上下文`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val skill = CanvasCreateSkill(store)
        for (raw in listOf("{}", """{"title":"标题"}""", """{"title":3,"html":"x"}""", """{"title":"标题","html":{}}""")) {
            assertError(skill.execute(args(raw), context), "INVALID_ARGUMENTS")
        }
        val missingMessage = object : SkillExecutionContext {
            override val sessionId = "session"
            override val agentId = "agent"
            override val workspacePath = null
            override val workspaceRootUuid = "workspace"
        }
        assertError(skill.execute(args("""{"title":"标题","html":"x"}"""), missingMessage), "MISSING_MESSAGE_CONTEXT")
        assertThat(store.list("session")).isEmpty()
    }

    @Test fun `更新拒绝错误类型空数组以及不互斥的两种方式`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("session", "标题", "one", "m")
        val skill = CanvasUpdateSkill(store)
        for (extra in listOf(
            "", ",\"html\":null", ",\"html\":4", ",\"patches\":{}", ",\"patches\":[]",
            ",\"patches\":[{\"find\":\"one\",\"replace\":1}]", ",\"patches\":[{\"find\":\"\",\"replace\":\"x\"}]",
            ",\"html\":\"x\",\"patches\":[{\"find\":\"one\",\"replace\":\"x\"}]",
            ",\"html\":\"x\",\"expected_version\":\"1\"", ",\"html\":\"x\",\"expected_version\":0",
        )) {
            assertError(skill.execute(args("{\"canvas_id\":\"${created.metadata.id}\"$extra}"), context), "INVALID_ARGUMENTS")
        }
        assertThat(store.read("session", created.metadata.id)).isEqualTo(created)
    }

    @Test fun `工具错误保留冲突码且提供可操作建议`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("session", "标题", "one", "m")
        assertError(CanvasUpdateSkill(store).execute(args("""{"canvas_id":"${created.metadata.id}","html":"two","expected_version":2}"""), context), "VERSION_CONFLICT")
        assertError(CanvasUpdateSkill(store).execute(args("""{"canvas_id":"${created.metadata.id}","patches":[{"find":"missing","replace":"x"}]}"""), context), "PATCH_NOT_UNIQUE")
        assertError(CanvasReadSkill(store).execute(args("""{"canvas_id":"${created.metadata.id}","version":9}"""), context), "NOT_FOUND")
    }

    @Test fun `读取和更新不接受其它会话的画布`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("other", "标题", "one", "m")
        assertError(CanvasReadSkill(store).execute(args("""{"canvas_id":"${created.metadata.id}"}"""), context), "NOT_FOUND")
        assertError(CanvasUpdateSkill(store).execute(args("""{"canvas_id":"${created.metadata.id}","html":"x"}"""), context), "NOT_FOUND")
    }

    @Test fun `风险等级与模型可见schema符合工具职责`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        assertThat(CanvasCreateSkill(store).risk).isEqualTo(ToolRisk.SESSION_STATE)
        assertThat(CanvasUpdateSkill(store).risk).isEqualTo(ToolRisk.SESSION_STATE)
        assertThat(CanvasReadSkill(store).risk).isEqualTo(ToolRisk.SAFE_READ)
        assertThat(CanvasListSkill(store).risk).isEqualTo(ToolRisk.SAFE_READ)
        val update = args(CanvasUpdateSkill(store).parametersSchema)
        assertThat(update["properties"]!!.jsonObject.keys).containsAtLeast("canvas_id", "html", "patches", "expected_version")
        assertThat(update["required"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("canvas_id")
    }

    @Test fun `创建schema及真实创建参数通过系统工具门禁`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        assertSchemaAccepted(CanvasCreateSkill(store), """{"title":"交互图表","html":"<p>数据</p>"}""")
    }

    @Test fun `更新schema及整体替换和补丁参数通过系统工具门禁`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("session", "标题", "one", "m")
        assertSchemaAccepted(
            CanvasUpdateSkill(store),
            """{"canvas_id":"${created.metadata.id}","html":"two","expected_version":1}""",
            """{"canvas_id":"${created.metadata.id}","patches":[{"find":"one","replace":"two"}],"expected_version":1}""",
        )
    }

    @Test fun `读取schema及当前和历史版本参数通过系统工具门禁`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("session", "标题", "one", "m")
        assertSchemaAccepted(
            CanvasReadSkill(store),
            """{"canvas_id":"${created.metadata.id}"}""",
            """{"canvas_id":"${created.metadata.id}","version":1}""",
        )
    }

    @Test fun `列表schema及空参数通过系统工具门禁`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        assertSchemaAccepted(CanvasListSkill(store), "{}")
    }

    private fun assertSchemaAccepted(skill: SkillDefinition, vararg rawArguments: String) {
        val validator = ToolSchemaValidator()
        assertThat(validator.validateDefinition(skill.parametersSchema)).isInstanceOf(ToolSchemaValidation.Valid::class.java)
        rawArguments.forEach { raw ->
            assertThat(validator.validate(skill.parametersSchema, args(raw))).isInstanceOf(ToolSchemaValidation.Valid::class.java)
        }
    }

    private fun args(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    private fun ToolResult.body(): JsonObject = args(content)
    private fun assertError(result: ToolResult, code: String) {
        assertThat(result.status).isEqualTo("error")
        val body = result.body()
        assertThat(body["status"]!!.jsonPrimitive.content).isEqualTo("error")
        assertThat(body["error"]!!.jsonPrimitive.content).isEqualTo(code)
        assertThat(body["message"]!!.jsonPrimitive.content).isNotEmpty()
        assertThat(body["suggestion"]!!.jsonPrimitive.content).isNotEmpty()
    }
}
