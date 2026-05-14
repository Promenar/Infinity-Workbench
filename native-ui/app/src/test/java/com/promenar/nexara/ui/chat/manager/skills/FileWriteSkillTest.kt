package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FileWriteSkillTest {

    private val skill = FileWriteSkill()

    @TempDir
    lateinit var tempDir: File

    private fun ctx(workspace: String? = tempDir.absolutePath) = object : SkillExecutionContext {
        override val sessionId = "test-session"
        override val agentId = "test-agent"
        override val workspacePath = workspace
    }

    @Test
    fun `writes content to file`() = runTest {
        val result = skill.execute(
            mapOf("path" to "output.txt", "content" to "Hello, Write!"),
            ctx()
        )

        assertThat(result.status).isEqualTo("success")
        val written = File(tempDir, "output.txt")
        assertThat(written.exists()).isTrue()
        assertThat(written.readText()).isEqualTo("Hello, Write!")
    }

    @Test
    fun `creates parent directories`() = runTest {
        val result = skill.execute(
            mapOf("path" to "subdir/nested/test.txt", "content" to "deep"),
            ctx()
        )

        assertThat(result.status).isEqualTo("success")
        val written = File(tempDir, "subdir/nested/test.txt")
        assertThat(written.exists()).isTrue()
        assertThat(written.readText()).isEqualTo("deep")
    }

    @Test
    fun `returns error for path escaping workspace`() = runTest {
        val result = skill.execute(
            mapOf("path" to "../escape.txt", "content" to "bad"),
            ctx()
        )

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Security")
    }

    @Test
    fun `returns error for missing content`() = runTest {
        val result = skill.execute(mapOf("path" to "test.txt"), ctx())

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Missing required parameter")
    }
}
