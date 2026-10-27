package com.promenar.nexara.data.branch

import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.domain.branch.BranchPlan
import com.promenar.nexara.domain.branch.BranchWorkMode
import com.promenar.nexara.domain.branch.BranchWorktree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BranchWorktreeServiceTest {
    private val head = "a".repeat(40)
    private val parent = session("parent")
    private val branch = session("branch").copy(parentSessionId = parent.id)
    private val plan = BranchPlan("测试", "验收", workMode = BranchWorkMode.WORKTREE,
        worktree = BranchWorktree("feat/test", "main", "../branch", "隔离"), approvalMode = "workspace")
    private val calls = mutableListOf<Pair<String, String>>()
    private val executingSessions = mutableListOf<String>()
    private var added = false
    private var addResult = BranchWorktreeCommandOutput(0)
    private var listings: List<String>? = null
    private var listIndex = 0
    private val service = BranchWorktreeService(
        resolveDirectory = { if (it.id == "parent") "/root/repo" else it.worktreeDirectory ?: "/root/repo" },
        execute = { session, cwd, command ->
            executingSessions += session.id
            calls += cwd to command
            when {
                "rev-parse" in command -> BranchWorktreeCommandOutput(0, "$head\n")
                "worktree list" in command -> BranchWorktreeCommandOutput(0,
                    listings?.get(listIndex++.coerceAtMost(listings!!.lastIndex)) ?: listing(added))
                "worktree add" in command -> addResult.also { if (it.exitCode == 0) added = true }
                "worktree remove" in command -> BranchWorktreeCommandOutput(0)
                else -> error("未预期的命令：$command")
            }
        },
    )

    private fun session(id: String) = SessionEntity(id = id, agentId = "a", createdAt = 1, updatedAt = 1)
    private fun listing(withBranch: Boolean, branchHead: String = head, branchName: String = "feat/test"): String =
        "worktree /root/repo\u0000HEAD $head\u0000branch refs/heads/main\u0000\u0000" +
            if (withBranch) "worktree /root/branch\u0000HEAD $branchHead\u0000branch refs/heads/$branchName\u0000\u0000" else ""

    @Test fun `批准全文和工作目录完全相同时才匹配`() {
        val command = BranchWorktreeCommands.plan("/root/repo", plan)
        assertTrue(BranchWorktreeCommands.matchesApprovedCommand(command, "/root/repo", command.command))
        assertFalse(BranchWorktreeCommands.matchesApprovedCommand(command, "/root/other", command.command))
        assertFalse(BranchWorktreeCommands.matchesApprovedCommand(command, "/root/repo", command.command + " "))
        assertFalse(BranchWorktreeCommands.matchesApprovedCommand(command, "/root/repo", command.command.replace("feat/test", "feat/other")))
        assertFalse(BranchWorktreeCommands.matchesApprovedCommand(command, "/root/repo", command.command.replace("'main'", "'HEAD'")))
        assertFalse(BranchWorktreeCommands.matchesApprovedCommand(command, "/root/repo", command.command.replace("/root/branch", "/root/other")))
    }

    @Test fun `目录按Linux语义解析且完整命令安全引用单引号和shell字符`() {
        val directory = "../it's;\$(touch nope)`id`"
        val command = BranchWorktreeCommands.plan("/root/my repo", plan.copy(worktree = plan.worktree!!.copy(directory = directory)))
        assertEquals("/root/it's;\$(touch nope)`id`", command.directory)
        assertEquals("git -C '/root/my repo' worktree add -b 'feat/test' -- '/root/it'\\''s;\$(touch nope)`id`' 'main'", command.command)
    }

    @Test fun `拒绝option引用与非法分支而不执行命令`() = runTest {
        for (value in listOf("--force", "-b", "HEAD", "bad branch", "x\nmain", "a..b", "refs/heads/x", "a.lock", "a@{b")) {
            val result = service.create(parent, branch, plan.copy(worktree = plan.worktree!!.copy(branch = value)))
            assertTrue(value, result.isFailure)
        }
        for (value in listOf("--detach", "-main", "main\n", "main:secret", "")) {
            assertTrue(service.create(parent, branch, plan.copy(worktree = plan.worktree!!.copy(base = value))).isFailure)
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun `支持Unicode和下划线基线引用`() {
        for (base in listOf("功能/测试", "_main", "HEAD~1", "a".repeat(40))) {
            assertEquals(base, BranchWorktreeCommands.plan("/root/repo", plan.copy(worktree = plan.worktree!!.copy(base = base))).base)
        }
    }

    @Test fun `目录拒绝控制字符和根目录且缺少worktree方案时失败`() = runTest {
        for (value in listOf("", "/", "../../../..", "x\u0000y", "x\ny", "-other")) {
            assertTrue(value, service.create(parent, branch, plan.copy(worktree = plan.worktree!!.copy(directory = value))).isFailure)
        }
        assertTrue(service.create(parent, branch, plan.copy(worktree = null)).isFailure)
        assertTrue(service.create(parent, branch, plan.copy(workMode = BranchWorkMode.SHARED)).isFailure)
        assertTrue(calls.isEmpty())
    }

    @Test fun `成功只执行获批add并返回已核验目录分支和完整基线提交`() = runTest {
        val binding = service.create(parent, branch, plan).getOrThrow()
        assertEquals("/root/branch", binding.directory)
        assertEquals("feat/test", binding.branch)
        assertEquals(head, binding.baseCommit)
        assertEquals(BranchWorktreeCommands.plan("/root/repo", plan).command, binding.approvedCommand)
        assertEquals(1, calls.count { "worktree add" in it.second })
        assertEquals(setOf(parent.id), executingSessions.toSet())
        assertFalse(calls.any { "--force" in it.second || " -B " in it.second })
    }

    @Test fun `add失败显式返回错误且不回退或重试`() = runTest {
        addResult = BranchWorktreeCommandOutput(128, stderr = "目录已存在")
        val failure = service.create(parent, branch, plan).exceptionOrNull()!!
        assertTrue(failure.message!!.contains("目录已存在"))
        assertEquals(1, calls.count { "worktree add" in it.second })
        assertEquals(3, calls.size)
    }

    @Test fun `重启已有同目录分支和基线时采用核验结果不重复add`() = runTest {
        listings = listOf(listing(true))
        val binding = service.create(parent, branch, plan).getOrThrow()
        assertEquals(head, binding.baseCommit)
        assertEquals("/root/branch", binding.directory)
        assertFalse(calls.any { "worktree add" in it.second })
    }

    @Test fun `已存在目录的分支或HEAD不同拒绝采用`() = runTest {
        for (output in listOf(listing(true, branchHead = "b".repeat(40)), listing(true, branchName = "foreign"))) {
            listings = listOf(output)
            listIndex = 0
            assertTrue(service.create(parent, branch, plan).isFailure)
        }
        assertFalse(calls.any { "worktree add" in it.second })
    }

    @Test fun `同名分支被其它目录占用时拒绝创建`() = runTest {
        listings = listOf(listing(true).replace("worktree /root/branch", "worktree /root/foreign"))
        assertTrue(service.create(parent, branch, plan).isFailure)
        assertFalse(calls.any { "worktree add" in it.second })
    }

    @Test fun `add后目录核验失败保留明确失败不虚报绑定`() = runTest {
        listings = listOf(listing(false), listing(false))
        assertTrue(service.create(parent, branch, plan).isFailure)
        assertEquals(1, calls.count { "worktree add" in it.second })
    }

    @Test fun `已有持久绑定与方案不同不执行命令`() = runTest {
        assertTrue(service.create(parent, branch.copy(worktreeDirectory = "/root/foreign", worktreeBranch = "other", worktreeBase = head), plan).isFailure)
        assertTrue(calls.isEmpty())
    }

    @Test fun `移除只使用非force命令且没有绑定时不执行`() = runTest {
        assertTrue(service.remove(branch).isFailure)
        assertTrue(calls.isEmpty())
        listings = listOf(listing(true))
        service.remove(branch.copy(worktreeDirectory = "/root/branch", worktreeBranch = "feat/test", worktreeBase = head)).getOrThrow()
        assertEquals("/root/branch" to "git -C '/root/branch' worktree remove -- '/root/branch'", calls.last())
        assertEquals(1, calls.count { "worktree remove" in it.second })
        assertEquals(setOf(branch.id), executingSessions.toSet())
    }

    @Test fun `移除前拒绝绑定目录被其它分支占用`() = runTest {
        listings = listOf(listing(true, branchName = "foreign"))
        assertTrue(service.remove(branch.copy(worktreeDirectory = "/root/branch", worktreeBranch = "feat/test", worktreeBase = head)).isFailure)
        assertFalse(calls.any { "worktree remove" in it.second })
    }

    @Test fun `list解析NUL路径并按每个会话实际工作区标注使用者`() = runTest {
        listings = listOf(listing(true))
        val bound = branch.copy(title = "分支", worktreeDirectory = "/root/branch", worktreeBranch = "feat/test", worktreeBase = head)
        val entries = service.list(parent, listOf(parent.copy(title = "主会话"), bound)).getOrThrow()
        assertEquals(2, entries.size)
        assertTrue(entries.first().isMain)
        assertEquals("parent", entries.first().sessionId)
        assertEquals("branch", entries.last().sessionId)
        assertEquals("分支", entries.last().sessionTitle)
        assertEquals("feat/test", entries.last().branch)
    }
    @Test fun `相同Linux路径不能关联不同助手或其它仓库的会话`() = runTest {
        listings = listOf(listing(true))
        val root = parent.copy(workspaceRootUuid = "approved-root")
        val bound = branch.copy(workspaceId = "branch-shared:approved-root", worktreeDirectory = "/root/branch",
            worktreeBranch = "feat/test", worktreeBase = head)
        val otherAgent = bound.copy(id = "private-other-agent", agentId = "private-agent", title = "不可见标题")
        val otherRepository = bound.copy(id = "other-repository", workspaceId = "different-project", title = "别的仓库")
        val entries = service.list(root, listOf(otherAgent, otherRepository, root, bound)).getOrThrow()
        assertEquals(root.id, entries.first().sessionId)
        assertEquals(bound.id, entries.last().sessionId)
        assertTrue(entries.none { it.sessionTitle in setOf("不可见标题", "别的仓库") })
    }

    @Test fun `porcelain解析保留空格换行和中文且支持detached与bare`() {
        val output = "worktree /root/主 目录\n引号\"\u0000HEAD $head\u0000detached\u0000locked reason\u0000\u0000" +
            "worktree /root/bare.git\u0000bare\u0000\u0000"
        val entries = BranchWorktreeCommands.parsePorcelain(output)
        assertEquals("/root/主 目录\n引号\"", entries.first().path)
        assertEquals(null, entries.first().branch)
        assertEquals("", entries.last().head)
    }

    @Test fun `porcelain解析普通行格式且拒绝截断和重复字段`() {
        assertEquals("main", BranchWorktreeCommands.parsePorcelain("worktree /root/repo\nHEAD $head\nbranch refs/heads/main\n\n").single().branch)
        for (output in listOf("HEAD $head\n\n", "worktree /root/repo\n\n", "worktree /root/repo\nHEAD $head\nHEAD $head\n\n")) {
            assertThrows(IllegalArgumentException::class.java) { BranchWorktreeCommands.parsePorcelain(output) }
        }
    }

    @Test fun `超时和被截断输出不能当作成功`() = runTest {
        val failed = BranchWorktreeService({ "/root/repo" }) { _, _, _ -> BranchWorktreeCommandOutput(0, listing(false), timedOut = true) }
        assertTrue(failed.list(parent, emptyList()).isFailure)
        val truncated = BranchWorktreeService({ "/root/repo" }) { _, _, _ -> BranchWorktreeCommandOutput(0, listing(false), truncated = true) }
        assertTrue(truncated.list(parent, emptyList()).isFailure)
    }

    @Test fun `协程取消必须向上传递`() = runTest {
        val cancelled = BranchWorktreeService({ "/root/repo" }) { _, _, _ -> throw CancellationException("取消") }
        var propagated = false
        try { cancelled.create(parent, branch, plan) } catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
    }
}
