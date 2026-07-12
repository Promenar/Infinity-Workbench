package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.domain.repository.DiffResult
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.PatchResult
import com.promenar.nexara.domain.repository.ReadResult
import com.promenar.nexara.domain.repository.WriteResult
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FileSkillsScopeTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspacePath = "/workspace"
        override val workspaceRootUuid = "root-a"
    }

    @Test
    fun `every file skill forwards execution context workspace root`() = runTest {
        val operations = mockk<IFileOperationRepository>()
        coEvery { operations.readFileRange("root-a", "file", any(), any()) } returns
            ReadResult("file", "a.txt", 1, 1, 1, "old", "hash", 1)
        coEvery { operations.writeFileAtomic("root-a", "file", "new", "session", "hash") } returns
            WriteResult.Success("new-hash")
        coEvery { operations.diffFile("root-a", "file", null) } returns
            DiffResult("file", "hash", "hash", emptyList())
        coEvery { operations.patchFile("root-a", "file", any(), "hash") } returns
            PatchResult.Success("new-hash", 1)

        FileReadSkill(operations).execute(mapOf("uuid" to "file"), context)
        FileWriteSkill(operations).execute(
            mapOf("uuid" to "file", "content" to "new", "expectedHash" to "hash"),
            context,
        )
        FileDiffSkill(operations).execute(mapOf("uuid" to "file"), context)
        FilePatchSkill(operations).execute(
            mapOf(
                "uuid" to "file",
                "expectedHash" to "hash",
                "operations" to listOf(
                    mapOf("action" to "replace_lines", "startLine" to 1, "endLine" to 1, "newContent" to "new"),
                ),
            ),
            context,
        )

        coVerify { operations.readFileRange("root-a", "file", any(), any()) }
        coVerify { operations.writeFileAtomic("root-a", "file", "new", "session", "hash") }
        coVerify { operations.diffFile("root-a", "file", null) }
        coVerify { operations.patchFile("root-a", "file", any(), "hash") }
    }

    @Test
    fun `list and search skills never use another root`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        every { workspace.observeChildren("root-a", "root-a") } returns flowOf(emptyList())

        FileListSkill(workspace).execute(emptyMap(), context)
        FileSearchSkill(workspace).execute(mapOf("query" to "x"), context)

        verify(exactly = 2) { workspace.observeChildren("root-a", "root-a") }
    }
}
