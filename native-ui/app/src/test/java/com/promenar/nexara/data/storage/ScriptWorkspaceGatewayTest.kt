package com.promenar.nexara.data.storage

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.ExternalMountDao
import com.promenar.nexara.data.local.db.entity.ExternalMountEntity
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.ToolAllowlistStore
import com.promenar.nexara.data.script.ScriptFileApprovalBroker
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.script.ScriptFailure
import com.promenar.nexara.domain.storage.*
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.skills.ScriptWorkspaceGateway
import io.mockk.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Test

class ScriptWorkspaceGatewayTest {
    private val directory = Files.createTempDirectory("script-gateway").toFile()
    private val context = mockk<Context> { every { noBackupFilesDir } returns directory }
    private var row = ExternalMountEntity("mount", "demo", "saf_tree", "content://test/tree/root", "session", false, 1)
    private val dao = mockk<ExternalMountDao> {
        coEvery { visible(any()) } answers { listOf(row) }
        coEvery { update(any()) } answers { row = firstArg() }
    }
    private val workspace = mockk<IWorkspaceRepository>()
    private val operations = mockk<IFileOperationRepository>()
    private val memory = TransferMemoryBackend()
    private val backend = object : ExternalFolderBackend by memory {
        override suspend fun read(path: String, maxBytes: Long): ByteArray {
            if (path == "/provider-large.txt") throw ExternalFolderException("TOO_LARGE", "文件超过读取上限")
            return memory.read(path, maxBytes)
        }
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
            if (expectedSha256 != null && Sha256Utils.hash(memory.read(path)) != expectedSha256) throw ExternalFolderException("CONFLICT", "版本不一致")
            return memory.write(path, content, expectedSha256, createOnly)
        }
    }
    private val mounts = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None) { backend }
    private val broker = ScriptFileApprovalBroker()
    private val grants = ToolAllowlistStore(directory.resolve("grants"))
    private var approveWrites = true
    private val execution = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
    }
    private suspend fun open(sessionId: String = "session") = ScriptWorkspaceGateway.open(
        object : SkillExecutionContext by execution { override val sessionId = sessionId },
        workspace, mounts, operations, FileIndexEventSink.None, broker, grants, { approveWrites }, "/mnt/demo/main.mjs",
    )
    private fun args(path: String, content: String? = null, hash: String? = null) = buildJsonObject {
        put("path", path); content?.let { put("content", it) }; hash?.let { put("expected_sha256", it) }
    }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `代理拒绝路径越界和保留名称且只读挂载不可写`() = runBlocking {
        val gateway = open()
        for (path in listOf("/mnt/demo/../outside.txt", "/mnt/demo/.nexara/x.txt", "/mnt/demo/.recycle_bin/x.txt")) {
            try { gateway.invoke("read", args(path)); error("应拒绝") } catch (_: ExternalFolderException) { }
        }
        row = row.copy(readOnly = true)
        try { gateway.invoke("create", args("/mnt/demo/a.txt", "x")); error("应拒绝只读") }
        catch (failure: ScriptFailure) { assertThat(failure.code).isEqualTo("READ_ONLY") }
        assertThat(memory.stat("/a.txt")).isNull()
    }

    @Test fun `写入先审批并按哈希拒绝冲突且审批期间挂载换源失败`() = runBlocking {
        memory.write("/a.txt", "old".toByteArray())
        val gateway = open()
        val pending = async { runCatching { gateway.invoke("write", args("/mnt/demo/a.txt", "new", Sha256Utils.hash("old"))) } }
        val approval = broker.pending.first { it.isNotEmpty() }.single()
        assertThat(String(memory.read("/a.txt"))).isEqualTo("old")
        row = row.copy(id = "replacement")
        broker.resolve(approval.id, true)
        assertThat((pending.await().exceptionOrNull() as? ScriptFailure)?.code).isEqualTo("MOUNT_CHANGED")
    }

    @Test fun `关闭写入二次审批仍执行expected哈希且删除必须独立审批`() = runBlocking {
        approveWrites = false
        memory.write("/a.txt", "old".toByteArray())
        val gateway = open()
        try { gateway.invoke("write", args("/mnt/demo/a.txt", "new", "0".repeat(64))); error("应冲突") }
        catch (failure: ScriptFailure) { assertThat(failure.code).isEqualTo("FILE_ERROR") }
        assertThat(String(memory.read("/a.txt"))).isEqualTo("old")
        gateway.invoke("write", args("/mnt/demo/a.txt", "new", Sha256Utils.hash("old")))
        assertThat(String(memory.read("/a.txt"))).isEqualTo("new")
        val deletion = async { gateway.invoke("delete", args("/mnt/demo/a.txt", hash = Sha256Utils.hash("new"))) }
        val approval = broker.pending.first { it.isNotEmpty() }.single()
        assertThat(approval.risk).isEqualTo(com.promenar.nexara.domain.tool.ToolRisk.DELETE)
        deletion.cancelAndJoin()
        assertThat(broker.pending.value).isEmpty()
        assertThat(memory.stat("/a.txt")).isNotNull()
    }

    @Test fun `读取返回实际字节哈希并拒绝越根模块和大小超限`() = runBlocking {
        memory.write("/a.txt", "原文".toByteArray())
        memory.write("/large.txt", ByteArray(65537) { 65 })
        val gateway = open()
        val read = gateway.invoke("read", args("/mnt/demo/a.txt")).jsonObject
        assertThat(read["sha256"]?.jsonPrimitive?.content).isEqualTo(Sha256Utils.hash("原文"))
        try { gateway.invoke("module", args("/mnt/other/a.js")); error("应拒绝") } catch (_: ScriptFailure) { }
        try { gateway.invoke("read", args("/mnt/demo/large.txt")); error("应超限") } catch (failure: ScriptFailure) { assertThat(failure.code).isEqualTo("FILE_LIMIT") }
    }

    @Test fun `后端提前拒绝超大文件时仍返回可终止进程的资源超限错误`() = runBlocking {
        val failure = runCatching { open().invoke("read", args("/mnt/demo/provider-large.txt")) }.exceptionOrNull()
        assertThat((failure as? ScriptFailure)?.code).isEqualTo("FILE_LIMIT")
    }

    @Test fun `模块代理允许根内mjs并返回源文件正文`() = runBlocking {
        memory.write("/helper.mjs", "export const value = 42".toByteArray())
        assertThat(open().invoke("module", args("/mnt/demo/helper.mjs")).jsonPrimitive.content)
            .isEqualTo("export const value = 42")
    }

    @Test fun `脚本批准并记住挂载写入后另一会话直接命中全局白名单且撤销后重询`() = runBlocking {
        memory.write("/a.txt", "old".toByteArray())
        val first = async { open().invoke("write", args("/mnt/demo/a.txt", "one", Sha256Utils.hash("old"))) }
        val approval = withTimeout(3000) { broker.pending.first { it.isNotEmpty() }.single() }
        broker.resolve(approval.id, true, true)
        first.await()
        assertThat(grants.entries.value.single().scopeKey).isEqualTo("mount:mount")
        assertThat(grants.entries.value.single().sourceSessionId).isEqualTo("session")
        withTimeout(3000) { open("another").invoke("write", args("/mnt/demo/a.txt", "two", Sha256Utils.hash("one"))) }
        assertThat(String(memory.read("/a.txt"))).isEqualTo("two")
        assertThat(broker.pending.value).isEmpty()
        grants.remove(grants.entries.value.single().id)
        val next = async { open("another").invoke("write", args("/mnt/demo/a.txt", "three", Sha256Utils.hash("two"))) }
        withTimeout(3000) { broker.pending.first { it.isNotEmpty() } }
        next.cancelAndJoin()
        assertThat(String(memory.read("/a.txt"))).isEqualTo("two")
    }

    @Test fun `审批期间挂载换源不把原目标记忆到新挂载`() = runBlocking {
        memory.write("/a.txt", "old".toByteArray())
        val pending = async { runCatching { open().invoke("write", args("/mnt/demo/a.txt", "new", Sha256Utils.hash("old"))) } }
        val approval = withTimeout(3000) { broker.pending.first { it.isNotEmpty() }.single() }
        row = row.copy(id = "replacement")
        broker.resolve(approval.id, true, true)
        assertThat((pending.await().exceptionOrNull() as? ScriptFailure)?.code).isEqualTo("MOUNT_CHANGED")
        assertThat(grants.entries.value).isEmpty()
    }
}
