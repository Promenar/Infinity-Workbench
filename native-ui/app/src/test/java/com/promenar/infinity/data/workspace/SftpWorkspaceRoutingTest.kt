package com.promenar.infinity.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.linux.LinuxFileSystemImpl
import com.promenar.infinity.data.linux.TestLinuxFileMetadata
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class SftpWorkspaceRoutingTest {
    private val base = Files.createTempDirectory("sftp-workspace-routing").toFile().canonicalFile
    private val primary = File(base, "selected").apply { mkdir() }
    private val other = File(base, "other").apply { mkdir() }
    private val entry = WorkspaceEntity("selected", "project", "选定目录", "selected", 1)
    private var current: SessionWorkspace = SessionWorkspace.Directory(entry, primary)
    private var mount: ExternalMount? = ExternalMount("mount-selected", "", "real_path", "selected", "s", 1)
    private var visible = emptyList<ExternalMount>()
    private val session = Session("s", "a", workspaceRootUuid = "private-root", workspaceId = "selected")
    private val sessions = proxy<ISessionRepository> { method, _ -> when (method) {
        "getById" -> session
        else -> error("不应调用 $method")
    } }
    private val repository = proxy<IWorkspaceRepository> { method, _ -> error("不得退回私有根：$method") }
    private val mounts = proxy<ExternalWorkspaceAccess> { method, _ -> when (method) {
        "workspaceMount" -> mount
        "visible" -> visible
        else -> error("不应调用 $method")
    } }
    private val files = SftpWorkspaceFiles(sessions, repository, mounts, { current }, File(base, "skills"),
        directoryFiles = { LinuxFileSystemImpl(it, { true }, TestLinuxFileMetadata).asWorkspaceDirectory() })
    @After fun cleanup() { base.deleteRecursively() }

    @Test fun `上传读取当前选定目录而不是私有根`() = runBlocking<Unit> {
        File(primary, "data").writeBytes(byteArrayOf(0, 1, -1))
        val bound = files.resolve("s", "private-root", "/data", false)
        assertThat(bound.file.read { it.readBytes() }).isEqualTo(byteArrayOf(0, 1, -1))
    }
    @Test fun `选定目录不可用时禁止退回私有根`() = runBlocking<Unit> {
        current = SessionWorkspace.Unavailable("selected", entry, "不可用")
        assertThrows(com.promenar.infinity.domain.storage.ExternalFolderException::class.java) { runBlocking {
            files.resolve("s", "private-root", "/data", false)
        } }
    }
    @Test fun `审批绑定后切换同路径工作区身份会拒绝访问`() = runBlocking<Unit> {
        File(primary, "data").writeText("原目录")
        File(other, "data").writeText("其它目录")
        val bound = files.resolve("s", "private-root", "/data", false)
        current = SessionWorkspace.Directory(entry.copy(id = "other", location = "other"), other)
        mount = ExternalMount("mount-other", "", "real_path", "other", "s", 1)
        val failure = assertThrows(SshFailure::class.java) { runBlocking { bound.file.read { it.readBytes() } } }
        assertThat(failure.code).isEqualTo("LOCAL_AUTHORIZATION_CHANGED")
    }
    @Test fun `目录中名为workspace的普通子目录可正常读取`() = runBlocking<Unit> {
        for (name in listOf("workspace")) {
            val directory = File(primary, name).apply { mkdir() }
            File(directory, "data").writeText(name)
            assertThat(files.resolve("s", "private-root", "/$name/data", false).file.read { it.readBytes().decodeToString() }).isEqualTo(name)
        }
    }
    @Test fun `命名挂载路径相对于挂载根而不是重复拼接虚拟前缀`() = runBlocking<Unit> {
        val named = File(base, "named").apply { mkdir() }
        File(named, "data").writeText("命名挂载")
        visible = listOf(ExternalMount("named", "nas", "real_path", named.path, "s", 1))
        val bound = files.resolve("s", "private-root", "/mnt/nas/data", false)
        assertThat(bound.file.read { it.readBytes().decodeToString() }).isEqualTo("命名挂载")
        val destination = files.resolve("s", "private-root", "/mnt/nas/copy", true)
        destination.file.write(3, false, null) { output ->
            val bytes = byteArrayOf(0, -1, 2)
            output.write(bytes)
            bytes.inputStream().use { com.promenar.infinity.data.ssh.sftpHash(it, 3) }
        }
        assertThat(File(named, "copy").readBytes()).isEqualTo(byteArrayOf(0, -1, 2))
    }
    @Test fun `技能挂载上传读取真实技能根的相对路径`() = runBlocking<Unit> {
        val skills = File(base, "skills").apply { mkdir() }
        File(skills, "data").writeText("合成技能")
        visible = listOf(ExternalMount("builtin:skills", "skills", "builtin_skills", "skills", "s", 1))
        val bound = files.resolve("s", "private-root", "/skills/data", false)
        assertThat(bound.file.read { it.readBytes().decodeToString() }).isEqualTo("合成技能")
    }
    private inline fun <reified T> proxy(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            if (method.name == "toString") "合成测试端口" else body(method.name, args ?: emptyArray())
        } as T
}
