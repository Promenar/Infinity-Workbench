package com.promenar.nexara.data.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitTrackingTest {
    @Test fun `解析状态跟踪信息和各组行数`() {
        val text = """{"version":1,"repository":true,"upstream":"origin/main","ahead":3,"behind":2,
            "upstream_missing":false,"tracking_truncated":true,"last_fetch_seconds":1700000123,
            "staged":[{"path":"a","change":"modified","added_lines":4,"removed_lines":1}],
            "unstaged":[{"path":"b","change":"deleted","added_lines":0,"removed_lines":2}],
            "untracked":[{"path":"c","change":"added","added_lines":null,"removed_lines":null,"lines_skipped":"binary"}]}"""
        val status = (GitInspectJson.parseStatus(text) as GitInspectResult.Success).value
        assertThat(status.tracking).isEqualTo(GitTracking("origin/main", 3, 2, false, true))
        assertThat(status.lastFetchSeconds).isEqualTo(1_700_000_123L)
        assertThat(status.staged.entries.single()).isEqualTo(GitFileChange("a", GitChangeKind.MODIFIED, addedLines = 4, removedLines = 1))
        assertThat(status.unstaged.entries.single().removedLines).isEqualTo(2)
        assertThat(status.untracked.entries.single().addedLines).isNull()
        assertThat(status.untracked.entries.single().linesSkipped).isEqualTo("binary")
    }

    @Test fun `旧状态缺字段兼容并保留未知统计与缺失上游`() {
        val old = (GitInspectJson.parseStatus(InspectFixtures.status) as GitInspectResult.Success).value
        assertThat(old.tracking).isEqualTo(GitTracking())
        assertThat(old.lastFetchSeconds).isNull()
        assertThat(old.staged.entries.first().addedLines).isNull()
        assertThat(old.staged.entries.first().linesSkipped).isNull()
        val missing = (GitInspectJson.parseStatus("""{"version":1,"repository":true,"upstream":"origin/gone","upstream_missing":true,"ahead":null,"behind":null}""") as GitInspectResult.Success).value
        assertThat(missing.tracking).isEqualTo(GitTracking(upstream = "origin/gone", upstreamMissing = true))
    }

    @Test fun `解析本地分支列表与旧条目缺省字段`() {
        val list = (GitInspectJson.parseBranches(branchesJson) as GitInspectResult.Success).value
        assertThat(list.truncated).isTrue()
        assertThat(list.branches).containsExactly(
            GitBranch("main", true, "1234567", 1_700_000_000L, "提交", GitTracking("origin/main", 1, 0, false, false)),
            GitBranch(name = "topic"),
        ).inOrder()
        val empty = (GitInspectJson.parseBranches("""{"version":1,"repository":true}""") as GitInspectResult.Success).value
        assertThat(empty).isEqualTo(GitBranchList())
        assertThat(GitInspectJson.parseBranches("""{"version":1,"repository":false}"""))
            .isEqualTo(GitInspectResult.Failure(GitInspectError.NotRepository))
        assertThat(GitInspectJson.parseBranches("""{"version":2,"repository":true}"""))
            .isInstanceOf(GitInspectResult.Failure::class.java)
    }

    @Test fun `会话分支请求使用工作区与默认限制并清理结果文件`() = runTest {
        val root = Files.createTempDirectory("git-tracking").toFile()
        try {
            val outputs = File(root, "outputs")
            val runner = FakeInspectRunner(respond = { operation, _ -> if (operation == "branches") branchesJson else null })
            val inspector = GitInspector(FakePythonRuntime(), runner, { outputs })
            val target = GitWorkspaceTarget(root, "project")
            val service = GitChangesService({ id ->
                assertThat(id).isEqualTo("session")
                GitWorkspaceResolution.Ready(target)
            }, inspector)
            val result = (service.branches("session") as GitInspectResult.Success).value
            assertThat(result.branches.first().tracking.ahead).isEqualTo(1)
            assertThat(runner.operations()).containsExactly("branches")
            assertThat(runner.values(0, "--repo")).containsExactly(root.path)
            assertThat(runner.values(0, "--limit")).containsExactly(GitInspectLimits.MAX_ENTRIES.toString())
            assertThat(outputs.listFiles().orEmpty()).isEmpty()
            inspector.branches(target, 7)
            assertThat(runner.values(1, "--limit")).containsExactly("7")
            val unavailable = GitChangesService({ GitWorkspaceResolution.Unavailable("失效") }, inspector)
            assertThat(unavailable.branches("session"))
                .isEqualTo(GitInspectResult.Failure(GitInspectError.WorkspaceUnavailable("失效")))
            assertThat(runner.commands).hasSize(2)
        } finally {
            root.deleteRecursively()
        }
    }

    private val branchesJson = """{"version":1,"repository":true,"truncated":true,"branches":[
        {"name":"main","current":true,"short_sha":"1234567","author_time":1700000000,"title":"提交",
         "upstream":"origin/main","ahead":1,"behind":0,"upstream_missing":false,"tracking_truncated":false},
        {"name":"topic"}]}"""
}
