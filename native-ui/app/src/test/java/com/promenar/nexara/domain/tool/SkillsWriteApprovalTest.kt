package com.promenar.nexara.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.data.repository.ToolAllowlistScopes
import com.promenar.nexara.data.repository.ToolAllowlistStore
import com.promenar.nexara.data.workspace.SessionWorkspace
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test

class SkillsWriteApprovalTest {
    private fun tool(name: String) = ProtocolTool(function = ProtocolToolFunction(name, "", "{}"))

    @Test fun `全部变更工具的技能路径目标逐次审批且不能记忆`() {
        for (root in listOf("/skills", "/.agents/skills")) {
            val calls = listOf(
                "write_file" to """{"path":"$root/demo/SKILL.md"}""",
                "patch_file" to """{"path":"$root/demo/SKILL.md"}""",
                "create_file" to """{"parentPath":"$root/demo","name":"SKILL.md"}""",
                "create_directory" to """{"parentPath":"$root","name":"demo"}""",
                "move_file" to """{"path":"$root/demo","newParentPath":"/elsewhere"}""",
                "move_file" to """{"path":"/demo","newParentPath":"$root"}""",
                "delete_file" to """{"path":"$root/demo"}""",
            )
            for ((name, args) in calls) {
                val target = requireNotNull(ToolExecutionTargets.extract(tool(name), args))
                assertThat(ToolArgumentApprovalRules.requiresExplicitApproval(name, args)).isTrue()
                assertThat(Json.parseToJsonElement(target).jsonArray.last().jsonObject["oneShot"]?.jsonPrimitive?.booleanOrNull).isTrue()
                assertThat(ToolExecutionTargets.canRemember(target)).isFalse()
                assertThat(ToolAllowlistScopes.resolve(target, "session", SessionWorkspace.Private, mapOf("skills" to "builtin:skills"))).isNull()
            }
        }
    }

    @Test fun `已解析UUID的技能路径生成不可记忆目标且路径参数不能覆盖UUID`() {
        for (name in listOf("write_file", "patch_file", "delete_file")) {
            val target = ToolExecutionTargets.extract(tool(name), """{"uuid":"file","path":"/safe.txt"}""", mapOf("file" to "/skills/demo/SKILL.md"))!!
            assertThat(ToolExecutionTargets.display(target)).isEqualTo("/skills/demo/SKILL.md")
            assertThat(ToolExecutionTargets.canRemember(target)).isFalse()
        }
        val target = ToolExecutionTargets.extract(tool("move_file"), """{"uuid":"file","newParentUuid":"directory"}""",
            mapOf("file" to "/a.md", "directory" to "/.agents/skills/demo"))!!
        assertThat(ToolExecutionTargets.canRemember(target)).isFalse()
    }

    @Test fun `只读和相似前缀以及普通挂载不新增审批`() {
        for (name in listOf("read_file", "list_files", "search_files", "diff_file")) {
            assertThat(ToolArgumentApprovalRules.requiresExplicitApproval(name, """{"path":"/skills/demo/SKILL.md"}""")).isFalse()
        }
        for (path in listOf("/skills-other/a.md", "/.agents/skills-other/a.md", "/mnt/docs/skills/a.md")) {
            assertThat(ToolArgumentApprovalRules.requiresExplicitApproval("write_file", """{"path":"$path"}""")).isFalse()
        }
    }

    @Test fun `历史白名单文件中的同名技能路径不可继续使用`() = runBlocking {
        val root = Files.createTempDirectory("skills-old-grants").toFile()
        try {
            val target = """["path","/skills/demo/SKILL.md"]"""
            val document = buildJsonObject {
                put("version", 1)
                val oldEntry = buildJsonObject {
                    put("id", "old"); put("toolName", "write_file"); put("target", target)
                    put("scopeKey", "session:session"); put("createdAt", 1); put("sourceSessionId", "session")
                }
                put("entries", JsonArray(listOf(oldEntry, JsonObject(oldEntry + mapOf(
                    "id" to JsonPrimitive("ordinary"), "target" to JsonPrimitive("""["path","/docs/a.md"]"""),
                )))))
            }
            root.resolve("allowlist.json").writeText(document.toString())
            val store = ToolAllowlistStore(root)
            assertThat(ToolAllowlistScopes.resolve(target, "session", SessionWorkspace.Private)).isNull()
            assertThat(store.contains("write_file", target, "session:session")).isFalse()
            assertThat(store.contains("write_file", """["path","/docs/a.md"]""", "session:session")).isTrue()
        } finally { root.deleteRecursively() }
    }
}
