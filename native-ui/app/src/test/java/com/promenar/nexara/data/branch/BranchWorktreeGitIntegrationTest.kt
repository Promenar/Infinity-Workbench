package com.promenar.nexara.data.branch

import com.promenar.nexara.data.git.FakeInspectRunner
import com.promenar.nexara.data.git.FakePythonRuntime
import com.promenar.nexara.data.git.GitChangesService
import com.promenar.nexara.data.git.GitInspectResult
import com.promenar.nexara.data.git.GitInspector
import com.promenar.nexara.data.git.GitWorkspaceResolution
import com.promenar.nexara.data.git.GitWorkspaceTarget
import com.promenar.nexara.data.git.InspectFixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BranchWorktreeGitIntegrationTest {
    private val temporary = Files.createTempDirectory("branch-git-reader").toFile().canonicalFile
    private val root = File(temporary, "branch").apply { mkdirs() }
    private val runner = FakeInspectRunner(respond = { _, _ -> InspectFixtures.status })
    private val inspector = GitInspector(FakePythonRuntime(), runner, { File(temporary, "output") })

    @After fun tearDown() { temporary.deleteRecursively() }

    @Test fun `git指针文件允许进入只读检查并携带显式Linux映射`() = runTest {
        File(root, ".git").writeText("gitdir: /root/repo/.git/worktrees/branch\n")
        val target = GitWorkspaceTarget(root, BranchWorktreeWorkspaceResolver.KIND, mapOf("/" to temporary.path))
        val service = GitChangesService({ GitWorkspaceResolution.Ready(target) }, inspector)
        assertTrue(service.status("branch") is GitInspectResult.Success)
        val mapping = Json.parseToJsonElement(runner.commands.single().environment.getValue("NEXARA_GIT_LINUX_PATH_MAP")).jsonObject
        assertEquals(temporary.path, mapping.getValue("/").jsonPrimitive.content)
    }

    @Test fun `普通仓库不继承Linux映射且空映射原样交给安全检查`() = runTest {
        inspector.status(GitWorkspaceTarget(root, "project"))
        assertNull(runner.commands.last().environment["NEXARA_GIT_LINUX_PATH_MAP"])
        inspector.status(GitWorkspaceTarget(root, BranchWorktreeWorkspaceResolver.KIND, emptyMap()))
        assertEquals("{}", runner.commands.last().environment["NEXARA_GIT_LINUX_PATH_MAP"])
    }
}
