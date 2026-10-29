package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.trigger.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxWakeDispatchTest {
    private fun message(key: String, body: String = key, time: Long = 0) = LinuxWakeMessage(key, "标题$key", body, Instant.ofEpochMilli(time))
    private fun TestScope.dispatch(requests: MutableList<SystemTurnRequest>, hold: Boolean = false, log: (String) -> Unit = {}) =
        LinuxWakeDispatch(backgroundScope, { object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome {
                requests += request
                if (hold) awaitCancellation()
                return SystemTurnOutcome.Completed
            }
        } }, { testScheduler.currentTime }, log)

    @Test fun `60秒内缓冲并合并且不等待前轮结束`() = runTest {
        val requests = mutableListOf<SystemTurnRequest>()
        val dispatch = dispatch(requests, hold = true)
        dispatch.accept("s", message("first")); dispatch.accept("s", message("build", time = 1))
        dispatch.accept("s", message("download", time = 2))
        assertEquals(1, requests.size)
        runCurrent(); advanceTimeBy(LinuxWakeLimits.MIN_DISPATCH_INTERVAL - 1); runCurrent()
        assertEquals(1, requests.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, requests.size)
        assertEquals("wake:download", requests.last().sourceId)
        assertEquals("标题download", requests.last().title)
        assertTrue(requests.last().body.indexOf("key：build") < requests.last().body.indexOf("key：download"))
        dispatch.accept("s", message("next")); runCurrent()
        advanceTimeBy(LinuxWakeLimits.MIN_DISPATCH_INTERVAL); runCurrent()
        assertEquals(3, requests.size)
    }

    @Test fun `相同key仅保留最新且最多8个key`() = runTest {
        val requests = mutableListOf<SystemTurnRequest>(); val logs = mutableListOf<String>()
        val dispatch = dispatch(requests, log = logs::add)
        dispatch.accept("s", message("initial"))
        for (index in 0..8) dispatch.accept("s", message("k$index", "正文$index", index.toLong()))
        dispatch.accept("s", message("k1", "替换后的正文", 20))
        runCurrent(); advanceTimeBy(LinuxWakeLimits.MIN_DISPATCH_INTERVAL); runCurrent()
        val request = requests.last()
        assertFalse(request.body.contains("key：k0")); assertFalse(request.body.contains("正文1\n"))
        assertTrue(request.body.contains("替换后的正文")); assertEquals("wake:k1", request.sourceId)
        assertEquals(8, Regex("key：").findAll(request.body).count())
        assertEquals(1, logs.size)
    }

    @Test fun `合并总长度限制16384并从最旧条目省略`() = runTest {
        val requests = mutableListOf<SystemTurnRequest>(); val dispatch = dispatch(requests)
        dispatch.accept("s", message("first"))
        for (index in 1..8) dispatch.accept("s", message("key$index", "字".repeat(LinuxWakeLimits.MAX_BODY_CHARS), index.toLong()))
        runCurrent(); advanceTimeBy(LinuxWakeLimits.MIN_DISPATCH_INTERVAL); runCurrent()
        val request = requests.last()
        assertTrue(request.body.length <= LinuxWakeLimits.MAX_MERGED_BODY_CHARS)
        assertTrue(request.body.contains("已省略最旧的 5 条消息"))
        assertFalse(request.body.contains("key：key5")); assertTrue(request.body.contains("key：key6"))
        assertEquals("wake:key8", request.sourceId)
    }

    @Test fun `跨会话独立且删除会话撤销缓冲和挂起派发`() = runTest {
        val requests = mutableListOf<SystemTurnRequest>(); val dispatch = dispatch(requests, hold = true)
        dispatch.accept("a", message("default")); dispatch.accept("a", message("later"))
        dispatch.accept("b", message("default"))
        assertEquals(listOf("a", "b"), requests.map { it.sessionId })
        dispatch.forget("a"); runCurrent(); advanceTimeBy(LinuxWakeLimits.MIN_DISPATCH_INTERVAL); runCurrent()
        assertEquals(2, requests.size)
    }

    @Test fun `调度器不可用或失败不传播且只记录安全日志`() = runTest {
        val logs = mutableListOf<String>()
        LinuxWakeDispatch(backgroundScope, { null }, log = logs::add).accept("s", message("default", "秘密正文"))
        LinuxWakeDispatch(backgroundScope, { object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome = error("秘密正文")
        } }, log = logs::add).accept("s", message("default", "秘密正文"))
        assertEquals(2, logs.size); assertFalse(logs.joinToString().contains("秘密正文"))
    }
}
