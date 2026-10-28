package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.SessionWorkspace
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolAllowlistStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val host = """["host","example.com"]"""
    private val path = """["path","/docs/a.md"]"""

    @Test fun `启动清空旧授权持久数据且清理标记只执行一次`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val store = ToolAllowlistStore(root)
        store.add("web_fetch", host, null)
        store.clearLegacyOnce()
        assertThat(store.entries.value).isEmpty()
        assertThat(ToolAllowlistStore(root).contains("web_fetch", host, null)).isFalse()
        store.add("web_fetch", host, null)
        ToolAllowlistStore(root).clearLegacyOnce()
        assertThat(store.contains("web_fetch", host, null)).isTrue()
    }

    @Test fun `持久化往返保留稳定身份时间来源且按时间倒序并去重`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        var now = 100L
        val store = ToolAllowlistStore(root) { now }
        store.add("web_fetch", host, null, "first-session")
        val original = store.entries.value.single()
        now = 200L
        store.add("web_fetch", host, null, "other-session")
        store.add("write_file", path, "workspace:project", "other-session")
        val restarted = ToolAllowlistStore(root)
        assertThat(restarted.contains("web_fetch", host, null)).isTrue()
        assertThat(restarted.contains("delete_file", host, null)).isFalse()
        assertThat(restarted.entries.value.map { it.createdAt }).containsExactly(200L, 100L).inOrder()
        assertThat(restarted.entries.value.last()).isEqualTo(original)
        assertThat(original.id).isNotEmpty()
        assertThat(original.sourceSessionId).isEqualTo("first-session")
        assertThat(root.listFiles().orEmpty()).hasLength(1)
    }

    @Test fun `按作用域删除只移除该作用域的条目`() = runBlocking<Unit> {
        val store = ToolAllowlistStore(temporary.newFolder())
        store.add("write_file", path, "session:s1", "s1")
        store.add("write_file", path, "session:s2", "s2")
        store.add("web_fetch", host, null, "s1")
        store.removeScope("session:s1")
        assertThat(store.contains("write_file", path, "session:s1")).isFalse()
        assertThat(store.contains("write_file", path, "session:s2")).isTrue()
        assertThat(store.contains("web_fetch", host, null)).isTrue()
    }

    @Test fun `损坏和未知版本及不完整条目均失败关闭并刷新已加载列表`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val store = ToolAllowlistStore(root)
        store.add("web_fetch", host, null, "session")
        val file = root.listFiles()!!.single()
        val valid = file.readText()
        listOf("{broken", valid.replace("\"version\":1", "\"version\":99"),
            valid.replace("\"toolName\":\"web_fetch\"", "\"toolName\":\"\""),
            valid.replace("\"scopeKey\":null", "\"scopeKey\":\"session:other\""),
        ).forEach { invalid ->
            file.writeText(invalid)
            assertThat(store.contains("web_fetch", host, null)).isFalse()
            assertThat(store.entries.value).isEmpty()
            assertThat(ToolAllowlistStore(root).contains("web_fetch", host, null)).isFalse()
        }
    }

    @Test fun `不可记忆和身份不明目标拒绝持久化`() = runBlocking<Unit> {
        val store = ToolAllowlistStore(temporary.newFolder())
        val oneShot = """["path","/a",{"oneShot":true}]"""
        store.add("delete_file", oneShot, "session:s", "s")
        store.add("write_file", path, null, "s")
        store.add("write_file", "not-a-tuple", null, "s")
        assertThat(store.entries.value).isEmpty()
        assertThat(store.contains("delete_file", oneShot, "session:s")).isFalse()
        assertThat(store.contains("write_file", path, null)).isFalse()
    }

    @Test fun `remove和clear同步更新订阅且其它实例立即按磁盘拒绝`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val store = ToolAllowlistStore(root)
        val other = ToolAllowlistStore(root)
        store.add("web_fetch", host, null, "s")
        val entry = withTimeout(3000) { other.entries.first { it.isNotEmpty() }.single() }
        other.remove(entry.id)
        assertThat(store.contains("web_fetch", host, null)).isFalse()
        assertThat(store.entries.value).isEmpty()
        store.add("write_file", path, "session:s", "s")
        other.clear()
        assertThat(store.contains("write_file", path, "session:s")).isFalse()
        assertThat(other.entries.value).isEmpty()
        other.remove("missing")
        assertThat(ToolAllowlistStore(root).contains("write_file", path, "session:s")).isFalse()
    }

    @Test fun `新建实例主动加载已有磁盘列表`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        ToolAllowlistStore(root).add("web_fetch", host, null, "s")
        val file = root.listFiles()!!.single()
        val freshRoot = temporary.newFolder()
        file.copyTo(File(freshRoot, file.name))
        val entries = withTimeout(3000) { ToolAllowlistStore(freshRoot).entries.first { it.isNotEmpty() } }
        assertThat(entries.single().toolName).isEqualTo("web_fetch")
    }

    @Test fun `并发实例写入不丢条目`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val stores = List(4) { ToolAllowlistStore(root) }
        (0 until 60).map { index -> async(Dispatchers.Default) {
            stores[index % 4].add("tool-$index", """["tool","tool-$index"]""", null, "s-$index")
        } }.awaitAll()
        assertThat(stores.first().entries.value).hasSize(60)
        assertThat(stores.last().entries.value.map { it.id }.distinct()).hasSize(60)
    }

    @Test fun `进程完整命令精确匹配且不按前缀授权`() = runBlocking<Unit> {
        val store = ToolAllowlistStore(temporary.newFolder())
        val target = """["process","builtin","git status"]"""
        store.add("shell_exec", target, null, "s")
        assertThat(store.contains("shell_exec", target, null)).isTrue()
        assertThat(store.contains("shell_exec", """["process","builtin","git status && rm x"]""", null)).isFalse()
        assertThat(store.contains("shell_exec", """["process","builtin","git"]""", null)).isFalse()
    }

    @Test fun `空白元组拒绝且超长目标按完整内容区分`() = runBlocking<Unit> {
        val store = ToolAllowlistStore(temporary.newFolder())
        assertThat(runCatching { store.add(" ", host, null) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.contains("", host, null)).isFalse()
        val prefix = "x".repeat(20000)
        store.add("shell_exec", """["process","builtin","${prefix}a"]""", null)
        assertThat(store.contains("shell_exec", """["process","builtin","${prefix}a"]""", null)).isTrue()
        assertThat(store.contains("shell_exec", """["process","builtin","${prefix}b"]""", null)).isFalse()
    }

    @Test fun `同一目录工作区的另一会话命中而其它工作区相同路径不命中`() = runBlocking<Unit> {
        val store = ToolAllowlistStore(temporary.newFolder())
        val first = scope(path, "one", directory("project"))!!
        assertThat(first.scopeKey).isEqualTo("workspace:project")
        store.add("write_file", path, first.scopeKey, "one")
        assertThat(store.contains("write_file", path, scope(path, "two", directory("project"))!!.scopeKey)).isTrue()
        assertThat(store.contains("write_file", path, scope(path, "two", directory("other"))!!.scopeKey)).isFalse()
    }

    @Test fun `私有工作区授权只在来源会话命中`() = runBlocking<Unit> {
        val store = ToolAllowlistStore(temporary.newFolder())
        val first = scope(path, "one", SessionWorkspace.Private)!!
        assertThat(first.scopeKey).isEqualTo("session:one")
        store.add("write_file", path, first.scopeKey, "one")
        assertThat(store.contains("write_file", path, scope(path, "one", SessionWorkspace.Private)!!.scopeKey)).isTrue()
        assertThat(store.contains("write_file", path, scope(path, "two", SessionWorkspace.Private)!!.scopeKey)).isFalse()
    }

    @Test fun `挂载路径按稳定挂载id跨会话复用且同名换源不命中`() = runBlocking<Unit> {
        val target = """["path","/mnt/demo/a"]"""
        val store = ToolAllowlistStore(temporary.newFolder())
        val first = scope(target, "one", directory("project"), mapOf("demo" to "mount-1"))!!
        assertThat(first.scopeKey).isEqualTo("mount:mount-1")
        store.add("write_file", target, first.scopeKey, "one")
        assertThat(store.contains("write_file", target, scope(target, "two", null, mapOf("demo" to "mount-1"))!!.scopeKey)).isTrue()
        assertThat(store.contains("write_file", target, scope(target, "two", null, mapOf("demo" to "mount-2"))!!.scopeKey)).isFalse()
    }

    @Test fun `多路径必须属于同一存储身份才可记忆`() {
        assertThat(scope("""["move","/a","/b"]""", "s", directory("p"))?.scopeKey).isEqualTo("workspace:p")
        assertThat(scope("""["move","/mnt/a/x","/mnt/b/y"]""", "s", null, mapOf("a" to "shared", "b" to "shared"))?.scopeKey).isEqualTo("mount:shared")
        assertThat(scope("""["move","/a","/mnt/demo/b"]""", "s", directory("p"), mapOf("demo" to "m"))).isNull()
        assertThat(scope("""["move","/mnt/a/x","/mnt/b/y"]""", "s", null, mapOf("a" to "first", "b" to "second"))).isNull()
    }

    @Test fun `主机进程MCP工具及SSH目标跨会话和工作区全局生效`() = runBlocking<Unit> {
        val store = ToolAllowlistStore(temporary.newFolder())
        listOf(host, """["process","builtin","git status"]""", """["mcp","server","tool"]""",
            """["tool","exec_js"]""", """["remote_path","lab","/a",{"sshHostId":"id","sshIdentity":"digest"}]""",
        ).forEach { target ->
            val first = scope(target, "one", directory("first"))!!
            val second = scope(target, "two", directory("second"))!!
            assertThat(first.scopeKey).isNull()
            assertThat(second.scopeKey).isNull()
            store.add("tool", target, first.scopeKey, "one")
            assertThat(store.contains("tool", target, second.scopeKey)).isTrue()
        }
    }

    @Test fun `存储身份缺失失效或目标结构异常时不可记忆`() {
        assertThat(scope(path, "s", null)).isNull()
        assertThat(scope(path, "s", SessionWorkspace.Unavailable("p", null, "不可用"))).isNull()
        assertThat(scope(path, "", SessionWorkspace.Private)).isNull()
        assertThat(scope("""["path","/mnt"]""", "s", directory("p"))).isNull()
        assertThat(scope("""["path","/mnt/missing/a"]""", "s", directory("p"))).isNull()
        assertThat(scope("""["path","/../a"]""", "s", directory("p"))).isNull()
        assertThat(scope("broken", "s", directory("p"))).isNull()
        assertThat(scope("""["path","/a",{"oneShot":true}]""", "s", directory("p"))).isNull()
        assertThat(scope("""["unknown","x"]""", "s", directory("p"))).isNull()
    }

    @Test fun `目标快照中的挂载身份与当前映射不符时不产生作用域`() {
        val target = """["path","/mnt/demo/a",{"mountIds":{"demo":"old"}}]"""
        assertThat(scope(target, "s", null, mapOf("demo" to "new"))).isNull()
        assertThat(scope(target, "s", null, mapOf("demo" to "old"))?.scopeKey).isEqualTo("mount:old")
        val legacy = """["path","/mnt/demo/a",{"legacyWorkspaceUuids":["private-uuid"]}]"""
        assertThat(scope(legacy, "s", SessionWorkspace.Private, mapOf("demo" to "old"))).isNull()
    }

    private fun directory(id: String) = SessionWorkspace.Directory(
        WorkspaceEntity(id, WorkspaceEntity.KIND_PROJECT, id, id, 1), File("/project/$id"),
    )
    private fun scope(target: String, session: String, workspace: SessionWorkspace?, mounts: Map<String, String> = emptyMap()) =
        ToolAllowlistScopes.resolve(target, session, workspace, mounts)
}
