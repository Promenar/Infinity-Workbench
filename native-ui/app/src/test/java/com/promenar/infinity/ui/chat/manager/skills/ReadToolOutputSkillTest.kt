package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.repository.ToolOutputStore
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReadToolOutputSkillTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val root get() = temporaryDirectory.resolve("private-output").toFile()

    @Test
    fun `工具为安全读取且schema遵守现有严格校验器`() {
        val skill = ReadToolOutputSkill(ToolOutputStore(root))
        val validator = ToolSchemaValidator()

        assertThat(skill.id).isEqualTo("read_tool_output")
        assertThat(skill.name).isEqualTo("read_tool_output")
        assertThat(skill.risk).isEqualTo(ToolRisk.SAFE_READ)
        assertThat(skill.mcpServerId).isNull()
        assertThat(validator.validateDefinition(skill.parametersSchema))
            .isInstanceOf(ToolSchemaValidation.Valid::class.java)
        assertThat(validator.validate(skill.parametersSchema, arguments("""{"id":"output"}""")))
            .isInstanceOf(ToolSchemaValidation.Valid::class.java)
        assertThat(validator.validate(skill.parametersSchema, arguments("{}")))
            .isInstanceOf(ToolSchemaValidation.Invalid::class.java)
        assertThat(validator.validate(skill.parametersSchema, arguments("""{"id":"output","offset":"1"}""")))
            .isInstanceOf(ToolSchemaValidation.Invalid::class.java)
        assertThat(validator.validate(skill.parametersSchema, arguments("""{"id":"output","sessionId":"other"}""")))
            .isInstanceOf(ToolSchemaValidation.Invalid::class.java)
    }

    @Test
    fun `读取使用当前会话并默认返回四千字符`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("session", "0123456789".repeat(700)))
        val skill = ReadToolOutputSkill(store)

        val result = skill.execute(arguments("""{"id":"$id"}"""), context("session"))

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).isEqualTo("0123456789".repeat(400))
    }

    @Test
    fun `显式分页按UTF16字符返回精确内容`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("session", "A😀B界\nEND"))
        val skill = ReadToolOutputSkill(store)

        val result = skill.execute(arguments("""{"id":"$id","offset":1,"limit":2}"""), context("session"))

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).isEqualTo("😀")
    }

    @Test
    fun `工具不能借助输出ID读取另一会话`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("owner", "private owner result"))

        val result = ReadToolOutputSkill(store).execute(arguments("""{"id":"$id"}"""), context("other"))

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).doesNotContain("private owner result")
        assertThat(result.content).doesNotContain(root.absolutePath)
    }

    @Test
    fun `无效类型负数越界及缺失ID均返回受控错误`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("session", "short output"))
        val skill = ReadToolOutputSkill(store)
        val invalidArguments = listOf(
            "{}",
            """{"id":17}""",
            """{"id":"../outside"}""",
            """{"id":"$id","offset":-1}""",
            """{"id":"$id","offset":99}""",
            """{"id":"$id","offset":"1"}""",
            """{"id":"$id","offset":1.5}""",
            """{"id":"$id","offset":2147483648}""",
            """{"id":"$id","limit":0}""",
            """{"id":"$id","limit":8001}""",
            """{"id":"$id","limit":null}""",
            """{"id":"$id","sessionId":"other"}""",
        )

        invalidArguments.forEach { raw ->
            assertThat(skill.execute(arguments(raw), context("session")).status).isEqualTo("error")
        }
    }

    @Test
    fun `已删除与未知输出不会崩溃或回显私有路径`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("session", "deleted output"))
        store.deleteSession("session")

        val result = ReadToolOutputSkill(store).execute(arguments("""{"id":"$id"}"""), context("session"))

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).doesNotContain("deleted output")
        assertThat(result.content).doesNotContain(root.absolutePath)
    }

    @Test
    fun `读取不创建或改写用户工作区`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("session", "stored output"))
        val workspace = temporaryDirectory.resolve("workspace").toFile().apply { mkdirs() }
        val sentinel = workspace.resolve("user.txt").apply { writeText("user content") }

        val result = ReadToolOutputSkill(store).execute(
            arguments("""{"id":"$id","offset":0,"limit":8000}"""),
            context("session", workspace.absolutePath),
        )

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).isEqualTo("stored output")
        assertThat(workspace.listFiles().orEmpty().map { it.name }).containsExactly("user.txt")
        assertThat(sentinel.readText()).isEqualTo("user content")
    }

    private fun arguments(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun outputId(preview: String): String =
        checkNotNull(Regex("output_id=(out_[a-f0-9]{64})").find(preview)?.groupValues?.get(1))

    private fun context(session: String, workspace: String? = null) = object : SkillExecutionContext {
        override val sessionId = session
        override val agentId = "agent"
        override val workspacePath = workspace
        override val workspaceRootUuid = "workspace-root"
    }
}
