package com.promenar.infinity.data.git

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitInspectJsonTest {
    private fun <T> success(result: GitInspectResult<T>): T {
        assertThat(result).isInstanceOf(GitInspectResult.Success::class.java)
        return (result as GitInspectResult.Success).value
    }

    private fun failure(result: GitInspectResult<*>): GitInspectError {
        assertThat(result).isInstanceOf(GitInspectResult.Failure::class.java)
        return (result as GitInspectResult.Failure).error
    }

    @Test fun `解析状态的三组条目与截断标志并忽略未知字段`() {
        val status = success(GitInspectJson.parseStatus(InspectFixtures.status))
        assertThat(status.branch).isEqualTo("main")
        assertThat(status.head).isEqualTo(InspectFixtures.SHA_B)
        assertThat(status.unborn).isFalse()
        assertThat(status.staged.entries).containsExactly(
            GitFileChange("src/App.kt", GitChangeKind.MODIFIED),
            GitFileChange("docs/new.md", GitChangeKind.ADDED),
        ).inOrder()
        assertThat(status.unstaged.entries.map { it.kind }).containsExactly(GitChangeKind.MODIFIED, GitChangeKind.DELETED).inOrder()
        assertThat(status.unstaged.truncated).isTrue()
        assertThat(status.untracked.entries.single().path).isEqualTo("notes.txt")
        assertThat(status.changeCount).isEqualTo(5)
        assertThat(status.truncated).isTrue()
        assertThat(status.isClean).isFalse()
    }

    @Test fun `空仓库与游离 HEAD`() {
        val empty = success(GitInspectJson.parseStatus("""{"version":1,"repository":true,"branch":"master","detached":false,"head":null,"unborn":true,"staged":[],"unstaged":[],"untracked":[]}"""))
        assertThat(empty.unborn).isTrue()
        assertThat(empty.head).isNull()
        assertThat(empty.isClean).isTrue()
        val detached = success(GitInspectJson.parseStatus("""{"version":1,"repository":true,"branch":null,"detached":true,"head":"${InspectFixtures.SHA_A}","unborn":false}"""))
        assertThat(detached.branch).isNull()
        assertThat(detached.detached).isTrue()
        assertThat(detached.staged).isEqualTo(GitChangeList.Empty)
    }

    @Test fun `状态保留已脱敏仓库名且兼容未提供名称的检查端`() {
        for (name in listOf("owner/repo", "team/ssh-repo", "owner/private-repo", "local-project")) {
            val text = """{"version":1,"repository":true,"repository_name":"$name"}"""
            assertThat(success(GitInspectJson.parseStatus(text)).repositoryName).isEqualTo(name)
        }
        assertThat(success(GitInspectJson.parseStatus("""{"version":1,"repository":true}""")).repositoryName).isNull()
    }

    @Test fun `不是仓库、子命令错误、版本不符与无效 JSON 分别归类`() {
        assertThat(failure(GitInspectJson.parseStatus("""{"version":1,"repository":false}"""))).isEqualTo(GitInspectError.NotRepository)
        assertThat(failure(GitInspectJson.parseLog("""{"version":1,"repository":true,"error":{"code":"NOT_FOUND","message":"提交不存在"}}""")))
            .isEqualTo(GitInspectError.InspectFailed("NOT_FOUND", "提交不存在"))
        assertThat(failure(GitInspectJson.parseStatus("""{"version":2,"repository":true}"""))).isInstanceOf(GitInspectError.ParseFailed::class.java)
        assertThat(failure(GitInspectJson.parseStatus("""{"repository":true}"""))).isInstanceOf(GitInspectError.ParseFailed::class.java)
        assertThat(failure(GitInspectJson.parseStatus("not json"))).isInstanceOf(GitInspectError.ParseFailed::class.java)
        assertThat(failure(GitInspectJson.parseStatus(""))).isInstanceOf(GitInspectError.ParseFailed::class.java)
        assertThat(failure(GitInspectJson.parseStatus("""{"version":1,"repository":true,"staged":[{"change":"added"}]}""")))
            .isInstanceOf(GitInspectError.ParseFailed::class.java)
        assertThat(failure(GitInspectJson.parseCommit("""{"version":1,"repository":true,"files":[]}"""))).isInstanceOf(GitInspectError.ParseFailed::class.java)
    }

    @Test fun `读取结果按请求顺序映射，二进制与超限不带文本`() {
        val refs = listOf(GitBlobRef("HEAD", "a.txt"), GitBlobRef("INDEX", "a.txt"), GitBlobRef("HEAD", "img.png"), GitBlobRef("HEAD", "big.log"))
        val text = InspectFixtures.read(
            InspectFixtures.blob("HEAD", "a.txt", "one\n"),
            InspectFixtures.blob("INDEX", "a.txt", null, exists = false),
            InspectFixtures.blob("HEAD", "img.png", "ignored", binary = true, size = 15),
            InspectFixtures.blob("HEAD", "big.log", "ignored", truncated = true, size = 700_000),
        )
        val blobs = success(GitInspectJson.parseRead(text, refs))
        assertThat(blobs[0]).isEqualTo(GitFileContent(exists = true, size = 4, text = "one\n"))
        assertThat(blobs[1]).isEqualTo(GitFileContent.Absent)
        assertThat(blobs[2].binary).isTrue()
        assertThat(blobs[2].text).isNull()
        assertThat(blobs[3].truncated).isTrue()
        assertThat(blobs[3].size).isEqualTo(700_000)
        assertThat(blobs[3].text).isNull()
    }

    @Test fun `读取结果与请求不一致时视为解析失败`() {
        val text = InspectFixtures.read(InspectFixtures.blob("HEAD", "b.txt", "x"))
        assertThat(failure(GitInspectJson.parseRead(text, listOf(GitBlobRef("HEAD", "a.txt"))))).isInstanceOf(GitInspectError.ParseFailed::class.java)
        assertThat(failure(GitInspectJson.parseRead(text, emptyList()))).isInstanceOf(GitInspectError.ParseFailed::class.java)
    }

    @Test fun `解析提交列表与提交详情，未知变更类型按修改处理`() {
        val log = success(GitInspectJson.parseLog(InspectFixtures.log))
        assertThat(log.truncated).isTrue()
        assertThat(log.commits.map { it.shortSha }).containsExactly("2222222", "1111111").inOrder()
        val first = log.commits.first()
        assertThat(first.authorTimeSeconds).isEqualTo(1_700_000_000L)
        assertThat(first.authorTimezoneSeconds).isEqualTo(28_800)
        assertThat(first.parents).containsExactly(InspectFixtures.SHA_A)

        val detail = success(GitInspectJson.parseCommit(InspectFixtures.commit))
        assertThat(detail.message).isEqualTo("第二次提交\n\n正文")
        assertThat(detail.files.entries).containsExactly(
            GitFileChange("src/App.kt", GitChangeKind.MODIFIED),
            GitFileChange("src/New.kt", GitChangeKind.RENAMED, oldPath = "src/Old.kt"),
            GitFileChange("bin/data", GitChangeKind.MODIFIED),
        ).inOrder()
    }
}
