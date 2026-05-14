package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FileSearchSkillTest {

    private val skill = FileSearchSkill()

    @TempDir
    lateinit var tempDir: File

    private fun ctx(workspace: String? = tempDir.absolutePath) = object : SkillExecutionContext {
        override val sessionId = "test-session"
        override val agentId = "test-agent"
        override val workspacePath = workspace
    }

    @Test
    fun `finds files by glob pattern`() = runTest {
        File(tempDir, "test.kt").writeText("kt")
        File(tempDir, "test.txt").writeText("txt")
        File(tempDir, "other.kt").writeText("kt2")

        val result = skill.execute(mapOf("pattern" to "*.kt"), ctx())

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("Found 2 file(s)")
        assertThat(result.content).contains("test.kt")
        assertThat(result.content).contains("other.kt")
        assertThat(result.content).doesNotContain("test.txt")
    }

    @Test
    fun `finds files in subdirectories`() = runTest {
        val sub = File(tempDir, "src")
        sub.mkdirs()
        File(sub, "main.kt").writeText("code")

        val result = skill.execute(mapOf("pattern" to "*.kt"), ctx())

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("main.kt")
    }

    @Test
    fun `returns no match message`() = runTest {
        File(tempDir, "readme.md").writeText("doc")

        val result = skill.execute(mapOf("pattern" to "*.kt"), ctx())

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("No files matching")
    }

    @Test
    fun `returns error for missing pattern`() = runTest {
        val result = skill.execute(emptyMap(), ctx())

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Missing required parameter")
    }
}
