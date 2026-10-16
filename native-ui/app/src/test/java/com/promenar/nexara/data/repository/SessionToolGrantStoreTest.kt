package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class SessionToolGrantStoreTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("nexara-tool-grants").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `授予后只命中相同会话工具与完整目标且重建 store 后保持`() = runBlocking<Unit> {
        val store = SessionToolGrantStore(root)
        val target = """["path","/docs/a.md"]"""
        assertThat(store.contains("session-a", "write_file", target)).isFalse()

        store.grant("session-a", "write_file", target)
        store.grant("session-a", "write_file", target)

        val restarted = SessionToolGrantStore(root)
        assertThat(restarted.contains("session-a", "write_file", target)).isTrue()
        assertThat(restarted.contains("session-b", "write_file", target)).isFalse()
        assertThat(restarted.contains("session-a", "delete_file", target)).isFalse()
        assertThat(restarted.contains("session-a", "write_file", """["path","/docs/b.md"]""")).isFalse()
        assertThat(restarted.list("session-a")).containsExactly(SessionToolGrant("write_file", target))
    }

    @Test
    fun `撤销一个目标不会影响其它目标或其它会话`() = runBlocking<Unit> {
        val store = SessionToolGrantStore(root)
        store.grant("session-a", "delete_file", "/a")
        store.grant("session-a", "delete_file", "/b")
        store.grant("session-b", "delete_file", "/a")

        store.revoke("session-a", "delete_file", "/a")
        store.revoke("session-a", "delete_file", "/missing")

        val restarted = SessionToolGrantStore(root)
        assertThat(restarted.contains("session-a", "delete_file", "/a")).isFalse()
        assertThat(restarted.contains("session-a", "delete_file", "/b")).isTrue()
        assertThat(restarted.contains("session-b", "delete_file", "/a")).isTrue()
        assertThat(restarted.list("session-a")).containsExactly(SessionToolGrant("delete_file", "/b"))
    }

    @Test
    fun `删除会话清理持久授权且不接受会话路径穿越`() = runBlocking<Unit> {
        val store = SessionToolGrantStore(root)
        val unusualSession = "../session/with:separator"
        store.grant(unusualSession, "write_file", "/a")
        store.grant("session-b", "write_file", "/b")
        assertThat(root.listFiles().orEmpty()).hasLength(2)
        assertThat(root.listFiles().orEmpty().all { it.isFile && it.parentFile == root }).isTrue()

        store.deleteSession(unusualSession)
        store.deleteSession(unusualSession)

        val restarted = SessionToolGrantStore(root)
        assertThat(restarted.list(unusualSession)).isEmpty()
        assertThat(restarted.contains(unusualSession, "write_file", "/a")).isFalse()
        assertThat(restarted.contains("session-b", "write_file", "/b")).isTrue()
        assertThat(root.listFiles().orEmpty()).hasLength(1)
    }

    @Test
    fun `分隔符与超长前缀不会造成不同授权元组碰撞`() = runBlocking<Unit> {
        val store = SessionToolGrantStore(root)
        val longTarget = "x".repeat(20000)
        store.grant("session", "a:b", "c")
        store.grant("session", "long", longTarget + "a")

        assertThat(store.contains("session", "a", "b:c")).isFalse()
        assertThat(store.contains("session", "long", longTarget + "a")).isTrue()
        assertThat(store.contains("session", "long", longTarget + "b")).isFalse()
        assertThat(SessionToolGrantStore(root).list("session"))
            .containsExactly(SessionToolGrant("a:b", "c"), SessionToolGrant("long", longTarget + "a"))
    }

    @Test
    fun `并发实例的授权写入不会丢失且撤销在其它实例立即生效`() = runBlocking<Unit> {
        val stores = List(4) { SessionToolGrantStore(root) }
        (0 until 60).map { index ->
            async(Dispatchers.Default) { stores[index % stores.size].grant("session", "tool-$index", "/$index") }
        }.awaitAll()

        assertThat(stores.first().list("session")).hasSize(60)
        stores.last().revoke("session", "tool-0", "/0")
        assertThat(stores.first().contains("session", "tool-0", "/0")).isFalse()
        assertThat(SessionToolGrantStore(root).list("session")).hasSize(59)
    }

    @Test
    fun `损坏或会话身份不匹配的存储文件不能授予权限`() = runBlocking<Unit> {
        val store = SessionToolGrantStore(root)
        store.grant("session-a", "tool", "/a")
        val file = root.listFiles()!!.single()
        val validContents = file.readText()
        file.writeText("{broken")
        assertThat(SessionToolGrantStore(root).contains("session-a", "tool", "/a")).isFalse()
        assertThat(SessionToolGrantStore(root).list("session-a")).isEmpty()

        file.writeText(validContents.replace("session-a", "session-b"))
        assertThat(SessionToolGrantStore(root).contains("session-a", "tool", "/a")).isFalse()
    }

    @Test
    fun `空白授权元组不写入存储且不会命中`() = runBlocking<Unit> {
        val store = SessionToolGrantStore(root)
        listOf(Triple("", "tool", "/a"), Triple("s", " ", "/a"), Triple("s", "tool", " ")).forEach { (session, tool, target) ->
            assertThat(runCatching { store.grant(session, tool, target) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(store.contains(session, tool, target)).isFalse()
        }
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }
}
