package com.promenar.nexara.data.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitInspectorTest {
    private val work: File = Files.createTempDirectory("git-inspector").toFile()
    private val root = File(work, "repo").apply { mkdirs() }
    private val outputs = File(work, "cache/git-inspect")
    private val target = GitWorkspaceTarget(root, "shared")
    private val runtime = FakePythonRuntime()
    private val runner = FakeInspectRunner()
    private val inspector = GitInspector(runtime, runner, { outputs }, timeoutSeconds = 30, maxOutputBytes = 64 * 1024)

    @After fun tearDown() { work.deleteRecursively() }

    @Test fun `在工作区根以打包 Python 运行检查子命令，结果文件读后删除`() = runTest {
        runner.respond = { _, _ -> InspectFixtures.status }
        val result = inspector.status(target)
        assertThat(result).isInstanceOf(GitInspectResult.Success::class.java)
        val command = runner.commands.single()
        assertThat(command.argv.take(5)).containsExactly(runtime.launcher.path, "-m", "nexara_git", "--nexara-inspect", "status").inOrder()
        val output = File(runner.values(0, "--output").single())
        assertThat(output.parentFile).isEqualTo(outputs)
        assertThat(output.name).endsWith(".json")
        assertThat(runner.values(0, "--repo")).containsExactly(root.path)
        assertThat(runner.values(0, "--limit")).containsExactly(GitInspectLimits.MAX_ENTRIES.toString())
        assertThat(command.workingDirectory).isEqualTo(root)
        assertThat(command.timeoutSeconds).isEqualTo(30)
        assertThat(command.environment["NEXARA_WORKSPACE_KIND"]).isEqualTo("shared")
        assertThat(runtime.environments.single()).isEqualTo(root to "shared")
        assertThat(output.exists()).isFalse()
        assertThat(File(output.path + ".tmp").exists()).isFalse()
        assertThat(outputs.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `并行检查同时创建结果目录时不误判失败`() = runTest {
        // 模拟另一个并行检查在本次检查判断目录不存在之后、调用 mkdirs 之前抢先建好了目录。
        val racing = object : File(outputs.path) {
            override fun mkdirs(): Boolean {
                File(path).mkdirs()
                return false
            }
        }
        val inspector = GitInspector(runtime, runner, { racing })
        runner.respond = { _, _ -> InspectFixtures.log }
        assertThat(inspector.log(target)).isInstanceOf(GitInspectResult.Success::class.java)
    }

    @Test fun `Python 不可用时不启动进程`() = runTest {
        runtime.available = false
        assertThat(inspector.status(target)).isEqualTo(GitInspectResult.Failure(GitInspectError.PythonUnavailable))
        assertThat(runner.commands).isEmpty()
    }

    @Test fun `超时、非零退出、未写出结果、超大输出与无效 JSON 分别归类`() = runTest {
        runner.timedOut = true
        runner.exitCode = null
        assertThat(inspector.log(target)).isEqualTo(GitInspectResult.Failure(GitInspectError.TimedOut))

        runner.timedOut = false
        runner.exitCode = 2
        runner.stderr = "usage error"
        assertThat(inspector.log(target)).isEqualTo(GitInspectResult.Failure(GitInspectError.CommandFailed(2, "usage error")))

        runner.exitCode = 0
        runner.respond = { _, _ -> null }
        assertThat((inspector.log(target) as GitInspectResult.Failure).error).isInstanceOf(GitInspectError.CommandFailed::class.java)

        runner.respond = { _, _ -> " ".repeat(64 * 1024 + 1) }
        assertThat((inspector.log(target) as GitInspectResult.Failure).error).isInstanceOf(GitInspectError.ParseFailed::class.java)

        runner.respond = { _, _ -> "{broken" }
        assertThat((inspector.log(target) as GitInspectResult.Failure).error).isInstanceOf(GitInspectError.ParseFailed::class.java)

        runner.respond = { _, _ -> """{"version":1,"repository":false}""" }
        assertThat(inspector.log(target)).isEqualTo(GitInspectResult.Failure(GitInspectError.NotRepository))
        assertThat(outputs.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `读取请求逐个传入版本与路径，不安全路径在启动前拒绝`() = runTest {
        runner.respond = { _, args -> InspectFixtures.echoRead(args) }
        val refs = listOf(GitBlobRef(GitRevisions.HEAD, "src/中文 文件.kt"), GitBlobRef(GitRevisions.INDEX, "src/中文 文件.kt"))
        val blobs = (inspector.read(target, refs) as GitInspectResult.Success).value
        assertThat(runner.values(0, "--blob")).containsExactly("HEAD:src/中文 文件.kt", "INDEX:src/中文 文件.kt").inOrder()
        assertThat(blobs.map { it.text }).containsExactly("HEAD:src/中文 文件.kt\n", "INDEX:src/中文 文件.kt\n").inOrder()

        for (path in listOf("../escape", "/etc/passwd", "a//b", ".git/config", "a\\b")) {
            assertThat(inspector.read(target, listOf(GitBlobRef("HEAD", path)))).isEqualTo(GitInspectResult.Failure(GitInspectError.UnsafePath(path)))
        }
        assertThat(runner.commands).hasSize(1)
        assertThat(inspector.read(target, emptyList())).isEqualTo(GitInspectResult.Success(emptyList<GitFileContent>()))
    }

    @Test fun `提交详情只接受完整 sha`() = runTest {
        assertThat((inspector.commit(target, "abc") as GitInspectResult.Failure).error).isInstanceOf(GitInspectError.InspectFailed::class.java)
        assertThat(runner.commands).isEmpty()
        runner.respond = { _, _ -> InspectFixtures.commit }
        val detail = (inspector.commit(target, InspectFixtures.SHA_B) as GitInspectResult.Success).value
        assertThat(detail.summary.sha).isEqualTo(InspectFixtures.SHA_B)
        assertThat(runner.values(0, "--commit")).containsExactly(InspectFixtures.SHA_B)
    }
}
