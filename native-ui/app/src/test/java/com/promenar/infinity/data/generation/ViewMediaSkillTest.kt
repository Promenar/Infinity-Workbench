package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ModelCapabilities
import com.promenar.infinity.domain.media.*
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.ViewMediaSkill
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ViewMediaSkillTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }
    @Test fun `只读工具参数传入视频规划且采用当前会话目标`() = runBlocking {
        var receivedSession = ""
        val ref = MediaRef("/v", "video/mp4", MediaKind.VIDEO, 10, durationMs = 10000)
        val resolver = object : WorkspaceMediaResolver {
            override suspend fun resolve(sessionId: String, path: String) = MediaResolution.Available(ref, MediaSource.LocalFile(File("unused")))
            override suspend fun open(resolution: MediaResolution.Available) = ByteArrayInputStream(byteArrayOf(1))
        }
        val viewer = WorkspaceMediaViewer(resolver, MediaViewPreprocessor { source, mode, times, _ ->
            PreparedViewedMedia(ViewedMedia(source.ref.copy(sha256 = "hash"), mode, times), times.map { EncodedViewedMedia(MediaKind.IMAGE, "image/jpeg", byteArrayOf(1), it) })
        })
        val skill = ViewMediaSkill(viewer) { receivedSession = it; MediaViewTarget(ModelCapabilities(vision = true)) }
        val result = skill.execute(Json.parseToJsonElement("""{"paths":["/v"],"as_frames":true,"frames":2,"start_seconds":2,"end_seconds":6,"question":"观察动作"}""").jsonObject, context)
        assertThat(receivedSession).isEqualTo("s")
        assertThat(result.viewedMedia?.single()?.frameTimestampsSeconds).containsExactly(3.0, 5.0)
        assertThat(skill.risk).isEqualTo(ToolRisk.SAFE_READ)
        assertThat(com.promenar.infinity.domain.tool.ToolSchemaValidator().validateDefinition(skill.parametersSchema))
            .isInstanceOf(com.promenar.infinity.domain.tool.ToolSchemaValidation.Valid::class.java)
        assertThat(Json.parseToJsonElement(skill.parametersSchema).jsonObject.keys).contains("required")
        listOf("""{"paths":[]}""", """{"paths":[1]}""", """{"paths":["/v"],"as_frames":"true"}""", """{"paths":["/v"],"frames":2.5}""", """{"paths":["/v"],"interval_seconds":0}""").forEach { arguments ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { skill.execute(Json.parseToJsonElement(arguments).jsonObject, context) } }
        }
    }
}
