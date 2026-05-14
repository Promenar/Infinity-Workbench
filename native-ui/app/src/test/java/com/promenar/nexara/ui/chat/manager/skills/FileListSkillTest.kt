package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FileListSkillTest {

    private val skill = FileListSkill()

    @TempDir
    lateinit var tempDir: File

    private fun ctx(workspace: String? = tempDir.absolutePath) = object : SkillExecutionContext {
        override val sessionId = "test-session"
        override val agentId = "test-agent"
        override val workspacePath = workspace
    }

    @Test
    fun `lists workspace root`() = runTest {
        File(tempDir, "alpha.txt").writeText("a")
        File(tempDir, "beta.txt").writeText("b")

        val result = skill.execute(emptyMap(), ctx())

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("[FILE]")
        assertThat(result.content).contains("alpha.txt")
        assertThat(result.content).contains("beta.txt")
    }

    @Test
    fun `lists subdirectory`() = runTest {
        val sub = File(tempDir, "sub")
        sub.mkdirs()
        File(sub, "inner.txt").writeText("x")

        val result = skill.execute(mapOf("path" to "sub"), ctx())

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("inner.txt")
    }

    @Test
    fun `returns error for non-existent directory`() = runTest {
        val result = skill.execute(mapOf("path" to "no-such-dir"), ctx())

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Directory not found")
    }

    @Test
    fun `lists empty directory`() = runTest {
        val empty = File(tempDir, "empty")
        empty.mkdirs()

        val result = skill.execute(mapOf("path" to "empty"), ctx())

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("Contents of")
    }
}
