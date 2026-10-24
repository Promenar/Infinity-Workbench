package com.promenar.nexara.debug.linuxprobe

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ProbeProcessRunnerTest {
    @TempDir lateinit var temporary: Path

    @Test fun `持续双流输出保留独立尾部和退出码并清空环境`() {
        val result = runner().run(request("i=0; while [ \"\$i\" -lt 3000 ]; do printf 'abcdefghijklmnop'; printf 'error-error-error' >&2; i=\$((i+1)); done; printf '\\nEND'; printf '\\nERR' >&2; [ -z \"\$HOME\" ] || exit 11; exit 7"))
        assertEquals(7, result.exitCode)
        assertEquals("failed", result.status)
        assertTrue(result.stdoutBytes > 16384)
        assertEquals(16384, result.stdoutTail.toByteArray().size)
        assertEquals(16384, result.stderrTail.toByteArray().size)
        assertTrue(result.stdoutTail.endsWith("END"))
        assertTrue(result.stderrTail.endsWith("ERR"))
    }

    @Test fun `超时会请求结束整个组并回收子进程`() {
        val signals = mutableListOf<Pair<Int, Int>>()
        val group = temporary.resolve("group").toFile()
        val command = "echo 12345 > ${ProbeCommands.quote(group.path)}; while :; do :; done"
        val result = ProbeProcessRunner { pid, signal -> signals += pid to signal }.run(request(command).copy(timeoutSeconds = 1))
        assertTrue(result.timedOut)
        assertEquals("timeout", result.status)
        assertTrue(signals.contains(12345 to 15))
        assertTrue(signals.contains(12345 to 9))
        assertFalse(group.exists())
    }

    @Test fun `取消等待线程也会清理进程组`() {
        val killed = AtomicBoolean(false)
        val interrupted = AtomicBoolean(false)
        val group = temporary.resolve("group").toFile()
        val worker = Thread {
            try {
                ProbeProcessRunner { _, signal -> if (signal == 9) killed.set(true) }.run(
                    request("echo 12345 > ${ProbeCommands.quote(group.path)}; while :; do :; done"),
                )
            } catch (_: InterruptedException) { interrupted.set(true) }
        }
        worker.start()
        val deadline = System.nanoTime() + 2_000_000_000
        while (group.length() == 0L && System.nanoTime() < deadline) Thread.sleep(10)
        worker.interrupt()
        worker.join(4000)
        assertFalse(worker.isAlive)
        assertTrue(interrupted.get())
        assertTrue(killed.get())
    }

    @Test fun `取消期间延迟发布的进程组也必须收到终止信号`() {
        val group = temporary.resolve("group").toFile()
        val signals = mutableListOf<Pair<Int, Int>>()
        val process = object : Process() {
            private var alive = true
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun isAlive() = alive
            override fun waitFor(): Int = 137
            override fun exitValue(): Int = 137
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                if (alive) throw InterruptedException("cancelled")
                return true
            }
            override fun destroy() { alive = false }
            override fun destroyForcibly(): Process {
                group.writeText("12345\n")
                alive = false
                return this
            }
        }
        val cancelled = assertThrows(ProbeCancelledException::class.java) {
            ProbeProcessRunner(signal = { pid, signal -> signals += pid to signal }, startProcess = { process }).run(request("true"))
        }
        assertEquals("cancelled", cancelled.output.status)
        assertTrue(cancelled.output.durationMs >= 0)
        assertTrue(signals.contains(12345 to 9))
        assertFalse(group.exists())
    }

    @Test fun `尾部跨缓冲块和大块输入保持最后字节`() {
        val tail = ByteTail(4)
        tail.append("abc".toByteArray(), 3)
        tail.append("defghij".toByteArray(), 7)
        assertEquals("ghij", tail.text())
        tail.append("kl".toByteArray(), 2)
        assertEquals("ijkl", tail.text())
        assertEquals(12L, tail.total)
    }

    @Test fun `首次取消落在清理等待时仍关闭管道删除组号并返回取消结果`() {
        val group = temporary.resolve("group").toFile()
        val closed = AtomicBoolean(false)
        var waits = 0
        val process = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = object : ByteArrayInputStream(byteArrayOf()) {
                override fun close() { closed.set(true); super.close() }
            }
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun isAlive() = false
            override fun waitFor(): Int = 0
            override fun exitValue(): Int = 0
            override fun destroy() = Unit
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                if (++waits == 2) throw InterruptedException("cancel during cleanup")
                return true
            }
        }
        val fake = ProbeProcessRunner(startProcess = { group.writeText("12345\n"); process }, signal = { _, _ -> })
        assertThrows(ProbeCancelledException::class.java) { fake.run(request("true")) }
        assertTrue(closed.get())
        assertFalse(group.exists())
    }

    @Test fun `统计使用常规中位数和最近秩p90`() {
        assertEquals(10.5 to 18.0, ProbeCommands.statistics((20 downTo 1).map(Int::toDouble)))
        assertEquals(1.0 to 1.0, ProbeCommands.statistics(listOf(1.0)))
    }

    @Test fun `基准文件数大小一致且清理目标为固定名称`() {
        val workspace = temporary.resolve("with ' quote").toFile().apply { mkdirs() }
        assertEquals(0, runner().run(request(ProbeCommands.createFiles(workspace.path, false))).exitCode)
        assertEquals(20000, workspace.resolve("bench").walkTopDown().count { it.isFile })
        assertEquals(4096, workspace.resolve("bench/d99/f199").length())
        assertEquals(0, runner().run(request(ProbeCommands.countFiles(workspace.path, false))).exitCode)
        assertEquals(0, runner().run(request(ProbeCommands.tarFiles(workspace.path, false))).exitCode)
        assertTrue(workspace.resolve("bench-extracted/bench/d0/f0").isFile)
        workspace.resolve("bench-keep").writeText("keep")
        assertEquals(0, runner().run(request(ProbeCommands.cleanup(workspace.path, false))).exitCode)
        assertTrue(workspace.resolve("bench-keep").isFile)
        assertFalse(workspace.resolve("bench").exists())
    }

    private fun runner() = ProbeProcessRunner { _, _ -> }
    private fun request(command: String) = ProbeProcessRequest(listOf("/bin/sh", "-c", command), emptyMap(), temporary.toFile(), temporary.resolve("group").toFile())
}
