package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.artifact.FileSessionArtifacts
import com.promenar.nexara.data.canvas.FileCanvasStore
import com.promenar.nexara.domain.media.*
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtifactSkillsTest {
    @get:Rule val folder = TemporaryFolder()
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath = null
        override val workspaceRootUuid = "w"
        override val assistantMessageId = "m"
    }
    private val resolver = object : WorkspaceMediaResolver {
        override suspend fun resolve(sessionId: String, path: String): MediaResolution = if (path == "/doc.txt")
            MediaResolution.Available(MediaRef(path, "text/plain", MediaKind.FILE, 4), MediaSource.LocalFile(File(folder.root, "source")))
        else MediaResolution.Unavailable(path, MediaUnavailableReason.NOT_FOUND)
        override suspend fun open(resolution: MediaResolution.Available) = "text".byteInputStream()
    }
    @Test fun `发布列表和错误结果`() = runTest {
        val repo = FileSessionArtifacts(File(folder.root, "artifacts"), FileCanvasStore(File(folder.root, "canvas")), resolver)
        val publish = ArtifactPublishSkill(repo)
        val result = publish.execute(Json.parseToJsonElement("""{"path":"/doc.txt"}""").jsonObject, context)
        val body = Json.parseToJsonElement(result.content).jsonObject
        assertThat(result.status).isEqualTo("success")
        assertThat(body["kind"]!!.jsonPrimitive.content).isEqualTo("document")
        assertThat(body["source_path"]!!.jsonPrimitive.content).isEqualTo("/doc.txt")
        assertThat(body["artifactId"]).isNotNull()
        val list = ArtifactListSkill(repo).execute(Json.parseToJsonElement("{}").jsonObject, context)
        assertThat(Json.parseToJsonElement(list.content).jsonObject["artifacts"]!!.jsonArray).hasSize(1)
        for ((raw, code) in listOf("""{"path":"/missing"}""" to "NOT_FOUND", """{"path":"/doc.txt","kind":"prototype"}""" to "INVALID_ARGUMENTS", "{}" to "INVALID_ARGUMENTS")) {
            val error = publish.execute(Json.parseToJsonElement(raw).jsonObject, context)
            val json = Json.parseToJsonElement(error.content).jsonObject
            assertThat(error.status).isEqualTo("error")
            assertThat(json["error"]!!.jsonPrimitive.content).isEqualTo(code)
            assertThat(json["suggestion"]!!.jsonPrimitive.content).isNotEmpty()
        }
    }
    @Test fun `工具schema通过系统门禁`() {
        val repo = FileSessionArtifacts(File(folder.root, "artifacts"), FileCanvasStore(File(folder.root, "canvas")), resolver)
        val validator = com.promenar.nexara.domain.tool.ToolSchemaValidator()
        for (skill in listOf(ArtifactPublishSkill(repo), ArtifactListSkill(repo))) {
            assertThat(validator.validateDefinition(skill.parametersSchema)).isInstanceOf(com.promenar.nexara.domain.tool.ToolSchemaValidation.Valid::class.java)
        }
    }

    @Test fun `列表来源异常返回结构化错误`() = runTest {
        val base = FileSessionArtifacts(File(folder.root, "artifacts"), FileCanvasStore(File(folder.root, "canvas")), resolver)
        val failing = object : com.promenar.nexara.data.artifact.SessionArtifactRepository by base {
            override fun observe(sessionId: String): kotlinx.coroutines.flow.Flow<List<com.promenar.nexara.data.artifact.ArtifactSummary>> = kotlinx.coroutines.flow.flow { throw IllegalStateException("failed") }
        }
        val result = ArtifactListSkill(failing).execute(Json.parseToJsonElement("{}").jsonObject, context)
        assertThat(result.status).isEqualTo("error")
        assertThat(Json.parseToJsonElement(result.content).jsonObject["error"]!!.jsonPrimitive.content).isEqualTo("STORAGE_ERROR")
    }

}
