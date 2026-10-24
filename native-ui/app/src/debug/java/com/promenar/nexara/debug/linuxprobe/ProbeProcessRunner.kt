package com.promenar.nexara.debug.linuxprobe

import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@Serializable
internal data class ProbeStep(
    val id: String,
    val runtime: String,
    val command: String,
    val status: String = "running",
    val exitCode: Int? = null,
    val durationMs: Double = 0.0,
    val timedOut: Boolean = false,
    val stdoutTail: String = "",
    val stderrTail: String = "",
    val stdoutBytes: Long = 0,
    val stderrBytes: Long = 0,
    val note: String = "",
)

internal class ProbeCancelledException(val output: ProbeStep, cause: InterruptedException) : InterruptedException("Probe cancelled") {
    init { initCause(cause) }
}

internal data class ProbeProcessRequest(
    val argv: List<String>,
    val environment: Map<String, String>,
    val directory: File,
    val groupFile: File,
    val timeoutSeconds: Long = 120,
)

/** 限制内存中的输出量；同时排空两条管道，超时或线程取消均回收整个会话。 */
internal class ProbeProcessRunner(
    private val startProcess: (ProbeProcessRequest) -> Process = { request ->
        ProcessBuilder(request.argv).apply {
            directory(request.directory)
            environment().clear()
            environment().putAll(request.environment)
        }.start()
    },
    private val signal: (Int, Int) -> Unit,
) {
    fun run(request: ProbeProcessRequest): ProbeStep {
        require(request.timeoutSeconds > 0)
        request.groupFile.delete()
        val started = System.nanoTime()
        val process = startProcess(request)
        process.outputStream.close()
        val stdout = ByteTail()
        val stderr = ByteTail()
        val readers = listOf(drain(process.inputStream, stdout), drain(process.errorStream, stderr))
        var timedOut = false
        var interruption: InterruptedException? = null
        var group: Int? = null
        fun readGroup(): Int? = runCatching { request.groupFile.readText().trim().toInt().takeIf { it > 1 } }.getOrNull()
        fun endGroup(value: Int) { (group ?: readGroup())?.let { signal(it, value) } }
        fun cleanupWait(timeoutMillis: Long, action: (Long) -> Unit) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                try { action(remaining); return } catch (error: InterruptedException) {
                    if (interruption == null) interruption = error
                    if (System.nanoTime() >= deadline) return
                }
            }
        }
        try {
            val deadline = started + TimeUnit.SECONDS.toNanos(request.timeoutSeconds)
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (group == null) group = readGroup()
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    endGroup(15)
                    process.destroy()
                    process.waitFor(200, TimeUnit.MILLISECONDS)
                    break
                }
            }
        } catch (error: InterruptedException) {
            interruption = error
        } finally {
            // 包括正常退出：不要让遗留后代持有管道或继续改写基准目录。
            try { endGroup(9) } finally {
                if (process.isAlive) process.destroyForcibly()
                cleanupWait(2_000) { process.waitFor(it, TimeUnit.MILLISECONDS) }
                try {
                    // 直接进程结束后不会再发布组号，补读可覆盖启动时取消的竞态。
                    endGroup(9)
                } finally {
                    try {
                        readers.forEach { reader -> cleanupWait(1_000) { reader.join(it) } }
                        runCatching { process.inputStream.close() }
                        runCatching { process.errorStream.close() }
                        readers.forEach { reader -> if (reader.isAlive) cleanupWait(200) { reader.join(it) } }
                    } finally { request.groupFile.delete() }
                }
            }
        }
        if (Thread.interrupted() && interruption == null) interruption = InterruptedException("Cancelled during cleanup")
        val exitCode = if (process.isAlive) null else process.exitValue()
        val output = ProbeStep(
            id = "", runtime = "", command = "",
            status = when { interruption != null -> "cancelled"; timedOut -> "timeout"; exitCode == 0 -> "passed"; else -> "failed" },
            exitCode = exitCode,
            durationMs = (System.nanoTime() - started) / 1_000_000.0,
            timedOut = timedOut, stdoutTail = stdout.text(), stderrTail = stderr.text(),
            stdoutBytes = stdout.total, stderrBytes = stderr.total,
            note = if (process.isAlive) "Process did not exit after SIGKILL" else "",
        )
        interruption?.let { throw ProbeCancelledException(output, it) }
        return output
    }

    private fun drain(input: InputStream, tail: ByteTail): Thread = thread(isDaemon = true, name = "linux-probe-output") {
        try {
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                tail.append(buffer, count)
            }
        } catch (_: java.io.IOException) {
            // 超时清理会主动关闭管道，尾部仍保留已读数据。
        }
    }
}

internal class ByteTail(private val limit: Int = 16 * 1024) {
    private val bytes = ByteArray(limit)
    private var position = 0
    var total: Long = 0
        private set

    @Synchronized fun append(input: ByteArray, count: Int) {
        require(count in 0..input.size)
        for (index in maxOf(0, count - limit) until count) {
            bytes[position] = input[index]
            position = (position + 1) % limit
        }
        total += count
    }

    @Synchronized fun text(): String {
        val size = minOf(total, limit.toLong()).toInt()
        val start = if (total >= limit) position else 0
        return ByteArray(size) { bytes[(start + it) % limit] }.toString(Charsets.UTF_8)
    }
}
