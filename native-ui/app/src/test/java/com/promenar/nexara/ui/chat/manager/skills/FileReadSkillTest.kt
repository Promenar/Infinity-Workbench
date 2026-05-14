package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FileReadSkillTest {

    private val skill = FileReadSkill()

    @TempDir
    lateinit var tempDir: File

    private fun ctx(workspace: String? = tempDir.absolutePath) = object : SkillExecutionContext {
        override val sessionId = "test-session"
        override val agentId = "test-agent"
        override val workspacePath = workspace
    }

    @Test
    fun `reads existing file`() = runTest {
        val file = File(tempDir, "hello.txt")
        file.writeText("Hello, World!")

        val result = skill.execute(mapOf("path" to "hello.txt"), ctx())

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).isEqualTo("Hello, World!")
    }

    @Test
    fun `returns error for missing file`() = runTest {
        val result = skill.execute(mapOf("path" to "nonexistent.txt"), ctx())

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("File not found")
    }

    @Test
    fun `returns error for path escaping workspace`() = runTest {
        val result = skill.execute(mapOf("path" to "../etc/passwd"), ctx())

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Security")
    }

    @Test
    fun `returns error for missing workspacePath`() = runTest {
        val result = skill.execute(mapOf("path" to "test.txt"), ctx(workspace = null))

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("No workspace path")
    }

    @Test
    fun `returns error for missing path parameter`() = runTest {
        val result = skill.execute(emptyMap(), ctx())

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Missing required parameter")
    }
}
