package com.promenar.nexara.data.git

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.workspace.SessionWorkspace
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitChangesServiceTest {
    private val work: File = Files.createTempDirectory("git-service").toFile().canonicalFile
    private val root = File(work, "repo").apply { mkdirs() }
    private val runtime = FakePythonRuntime()
    private val runner = FakeInspectRunner()
    private val inspector = GitInspector(runtime, runner, { File(work, "out") })
    private var resolution: GitWorkspaceResolution = GitWorkspaceResolution.Ready(GitWorkspaceTarget(root, "project"))
    private val service = GitChangesService({ resolution }, inspector, NioGitWorkTreeReader(pathFallback = true))

    @After fun tearDown() { work.deleteRecursively() }

    private fun makeRepository() {
        File(root, ".git").mkdirs()
    }

    @Test fun `工作区不可用时不运行命令`() = runTest {
        resolution = GitWorkspaceResolution.Unavailable("目录已删除")
        assertThat(service.status("s")).isEqualTo(GitInspectResult.Failure(GitInspectError.WorkspaceUnavailable("目录已删除")))
        assertThat(service.log("s")).isEqualTo(GitInspectResult.Failure(GitInspectError.WorkspaceUnavailable("目录已删除")))
        assertThat(runner.commands).isEmpty()
    }

    @Test fun `没有 git 目录时直接判定不是仓库，不启动解释器`() = runTest {
        assertThat(service.status("s")).isEqualTo(GitInspectResult.Failure(GitInspectError.NotRepository))
        assertThat(runner.commands).isEmpty()
        makeRepository()
        runtime.available = false
        assertThat(service.status("s")).isEqualTo(GitInspectResult.Failure(GitInspectError.PythonUnavailable))
        runtime.available = true
        runner.respond = { _, _ -> InspectFixtures.status }
        assertThat((service.status("s") as GitInspectResult.Success).value.branch).isEqualTo("main")
    }

    @Test fun `已暂存差异读取 HEAD 与索引`() = runTest {
        makeRepository()
        runner.respond = { _, args -> InspectFixtures.echoRead(args) }
        val sides = (service.changeDiff("s", GitChangeGroup.STAGED, GitFileChange("a.txt", GitChangeKind.MODIFIED)) as GitInspectResult.Success).value
        assertThat(runner.values(0, "--blob")).containsExactly("HEAD:a.txt", "INDEX:a.txt").inOrder()
        assertThat(sides.old.text).isEqualTo("HEAD:a.txt\n")
        assertThat(sides.new.text).isEqualTo("INDEX:a.txt\n")
    }

    @Test fun `未暂存差异的工作区一侧按目录读取，删除的文件为空`() = runTest {
        makeRepository()
        File(root, "src").mkdirs()
        File(root, "src/a.txt").writeText("工作区内容\n")
        runner.respond = { _, args -> InspectFixtures.echoRead(args) }
        val modified = (service.changeDiff("s", GitChangeGroup.UNSTAGED, GitFileChange("src/a.txt", GitChangeKind.MODIFIED)) as GitInspectResult.Success).value
        assertThat(runner.values(0, "--blob")).containsExactly("INDEX:src/a.txt")
        assertThat(modified.old.text).isEqualTo("INDEX:src/a.txt\n")
        assertThat(modified.new.text).isEqualTo("工作区内容\n")
        val deleted = (service.changeDiff("s", GitChangeGroup.UNSTAGED, GitFileChange("gone.txt", GitChangeKind.DELETED)) as GitInspectResult.Success).value
        assertThat(deleted.new).isEqualTo(GitFileContent.Absent)
    }

    @Test fun `未跟踪差异只读工作区，旧侧为空`() = runTest {
        makeRepository()
        File(root, "new.txt").writeText("fresh\n")
        val sides = (service.changeDiff("s", GitChangeGroup.UNTRACKED, GitFileChange("new.txt", GitChangeKind.ADDED)) as GitInspectResult.Success).value
        assertThat(sides.old).isEqualTo(GitFileContent.Absent)
        assertThat(sides.new.text).isEqualTo("fresh\n")
        assertThat(runner.commands).isEmpty()
    }

    @Test fun `工作区一侧路径越界或经过符号链接时拒绝`() = runTest {
        makeRepository()
        val outside = File(work, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("secret")
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        val viaLink = service.changeDiff("s", GitChangeGroup.UNTRACKED, GitFileChange("escape/secret.txt", GitChangeKind.ADDED))
        assertThat(viaLink).isEqualTo(GitInspectResult.Failure(GitInspectError.UnsafePath("escape/secret.txt")))
        val dotted = service.changeDiff("s", GitChangeGroup.UNTRACKED, GitFileChange("../outside/secret.txt", GitChangeKind.ADDED))
        assertThat(dotted).isEqualTo(GitInspectResult.Failure(GitInspectError.UnsafePath("../outside/secret.txt")))
        val unstaged = service.changeDiff("s", GitChangeGroup.UNSTAGED, GitFileChange("../x", GitChangeKind.MODIFIED))
        assertThat(unstaged).isEqualTo(GitInspectResult.Failure(GitInspectError.UnsafePath("../x")))
        assertThat(runner.commands).isEmpty()
    }

    @Test fun `提交文件差异比较父提交与本提交，重命名读取原路径`() = runTest {
        makeRepository()
        runner.respond = { _, args -> InspectFixtures.echoRead(args) }
        val change = GitFileChange("src/New.kt", GitChangeKind.RENAMED, oldPath = "src/Old.kt")
        val sides = (service.commitFileDiff("s", InspectFixtures.SHA_B, change) as GitInspectResult.Success).value
        assertThat(runner.values(0, "--blob")).containsExactly(
            "${InspectFixtures.SHA_B}^:src/Old.kt",
            "${InspectFixtures.SHA_B}:src/New.kt",
        ).inOrder()
        assertThat(sides.oldPath).isEqualTo("src/Old.kt")
        assertThat(sides.path).isEqualTo("src/New.kt")
        assertThat((service.commitFileDiff("s", "HEAD", change) as GitInspectResult.Failure).error)
            .isInstanceOf(GitInspectError.InspectFailed::class.java)
    }
}

class SessionGitWorkspaceLocatorTest {
    private val work: File = Files.createTempDirectory("git-locator").toFile()

    @After fun tearDown() { work.deleteRecursively() }

    @Test fun `私有工作区取规范化的会话根，自选目录取其根与类型`() = runTest {
        val real = File(work, "real").apply { mkdirs() }
        val alias = File(work, "alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val private = SessionGitWorkspaceLocator({ SessionWorkspace.Private }, { alias.path }).locate("s")
        assertThat(private).isEqualTo(GitWorkspaceResolution.Ready(GitWorkspaceTarget(real.canonicalFile, "private")))

        val entity = WorkspaceEntity("ws", WorkspaceEntity.KIND_SHARED, "共享", "Documents/repo", 0L)
        val directory = SessionGitWorkspaceLocator({ SessionWorkspace.Directory(entity, real) }, { error("不应读取私有根") }).locate("s")
        assertThat(directory).isEqualTo(GitWorkspaceResolution.Ready(GitWorkspaceTarget(real, WorkspaceEntity.KIND_SHARED)))
    }

    @Test fun `不可用与解析失败都给出原因`() = runTest {
        val unavailable = SessionGitWorkspaceLocator({ SessionWorkspace.Unavailable("ws", null, "工作区登记已删除") }, { "" }).locate("s")
        assertThat(unavailable).isEqualTo(GitWorkspaceResolution.Unavailable("工作区登记已删除"))
        val failing = SessionGitWorkspaceLocator({ SessionWorkspace.Private }, { throw NoSuchElementException("Session not found") }).locate("s")
        assertThat(failing).isEqualTo(GitWorkspaceResolution.Unavailable("Session not found"))
    }
}
