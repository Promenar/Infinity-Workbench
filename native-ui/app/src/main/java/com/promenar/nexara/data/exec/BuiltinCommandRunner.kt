package com.promenar.nexara.data.exec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

data class BuiltinCommandRequest(
    /** 在新进程组内执行的程序与参数。 */
    val argv: List<String>,
    val workingDirectory: File,
    /** 完整环境；不继承主进程环境。 */
    val environment: Map<String, String>,
    val stdin: String? = null,
    val timeoutSeconds: Int,
)

data class BuiltinCommandOutput(
    /** 超时或被我们结束时为 null。 */
    val exitCode: Int?,
    val timedOut: Boolean,
    val stdout: String,
    val stderr: String,
    val stdoutOriginalLength: Long,
    val stderrOriginalLength: Long,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    /** 进程被非本端发出的 SIGKILL 结束（常见于系统内存回收或冻结）。 */
    val killedExternally: Boolean = false,
)

/** 向整个进程组发送信号；进程组已不存在时静默返回。 */
fun interface ProcessGroupSignaller {
    fun signal(processGroupId: Int, signal: Int)
}

/**
 * 在独立进程组中启动命令：[groupLauncher]（Android 为 toybox `setsid`）让命令成为新会话首进程，
 * 包装 sh 把自身 pid（即进程组号）写入临时文件后 exec 目标程序。超时或取消时先 SIGTERM 整个进程组，
 * [killGraceMillis] 后 SIGKILL；命令正常结束后同组残留的后台进程也会被 SIGKILL，避免在释放工作区写锁后继续改动文件。
 */
class BuiltinCommandRunner(
    private val groupLauncher: List<String>,
    private val shell: String,
    private val signaller: ProcessGroupSignaller,
    private val scratchDirectory: () -> File,
    private val outputLimit: Int = OUTPUT_LIMIT,
    private val killGraceMillis: Long = 2_000,
    private val drainTimeoutMillis: Long = 2_000,
) {
    suspend fun run(request: BuiltinCommandRequest): BuiltinCommandOutput = withContext(Dispatchers.IO) {
        require(request.argv.isNotEmpty()) { "命令不能为空" }
        require(request.timeoutSeconds in 1..MAX_TIMEOUT_SECONDS) { "timeout_seconds 必须为 1–$MAX_TIMEOUT_SECONDS" }
        val scratch = File(scratchDirectory(), "exec-${UUID.randomUUID()}").apply { mkdirs() }
        val pidFile = File(scratch, "pgid")
        try {
            val builder = ProcessBuilder(groupLauncher + listOf(shell, "-c", WRAPPER, "nexara-exec", pidFile.path) + request.argv)
                .directory(request.workingDirectory)
            builder.environment().clear()
            builder.environment().putAll(request.environment)
            val process = builder.start()
            val stdout = TailTextCollector(outputLimit)
            val stderr = TailTextCollector(outputLimit)
            val readers = listOf(
                daemon("nexara-exec-stdout") { stdout.consume(process.inputStream) },
                daemon("nexara-exec-stderr") { stderr.consume(process.errorStream) },
            )
            daemon("nexara-exec-stdin") {
                process.outputStream.use { output -> request.stdin?.let { output.write(it.toByteArray(Charsets.UTF_8)) } }
            }
            var signalled = false
            val finished = try {
                runInterruptible { process.waitFor(request.timeoutSeconds.toLong(), TimeUnit.SECONDS) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { terminate(process, pidFile) }
                throw cancelled
            }
            if (!finished) {
                signalled = true
                terminate(process, pidFile)
            } else {
                // 主进程已退出；同组后台进程不能跨过写锁继续运行。
                readProcessGroup(pidFile)?.let { runCatching { signaller.signal(it, SIGKILL) } }
            }
            readers.forEach { it.join(drainTimeoutMillis) }
            val exit = if (signalled) null else process.exitValue()
            BuiltinCommandOutput(
                exitCode = exit,
                timedOut = !finished,
                stdout = stdout.text(),
                stderr = stderr.text(),
                stdoutOriginalLength = stdout.totalChars,
                stderrOriginalLength = stderr.totalChars,
                stdoutTruncated = stdout.truncated,
                stderrTruncated = stderr.truncated,
                killedExternally = exit == SIGNAL_EXIT_BASE + SIGKILL,
            )
        } finally {
            scratch.deleteRecursively()
        }
    }

    private suspend fun terminate(process: Process, pidFile: File) {
        val group = readProcessGroup(pidFile) ?: awaitProcessGroup(pidFile)
        if (group == null) {
            process.destroyForcibly()
            runInterruptible { process.waitFor(killGraceMillis, TimeUnit.MILLISECONDS) }
            return
        }
        runCatching { signaller.signal(group, SIGTERM) }
        val exited = runInterruptible { process.waitFor(killGraceMillis, TimeUnit.MILLISECONDS) }
        runCatching { signaller.signal(group, SIGKILL) }
        if (!exited) {
            process.destroyForcibly()
            runInterruptible { process.waitFor(killGraceMillis, TimeUnit.MILLISECONDS) }
        }
    }

    /** 命令刚启动就超时或取消时，包装 sh 可能尚未写出进程组号。 */
    private suspend fun awaitProcessGroup(pidFile: File): Int? {
        repeat(10) {
            readProcessGroup(pidFile)?.let { return it }
            runInterruptible { Thread.sleep(50) }
        }
        return null
    }

    private fun readProcessGroup(pidFile: File): Int? =
        runCatching { pidFile.readText().trim().toInt() }.getOrNull()?.takeIf { it > 1 }

    private fun daemon(name: String, body: () -> Unit): Thread =
        Thread({ runCatching(body) }, name).apply { isDaemon = true; start() }

    companion object {
        const val OUTPUT_LIMIT = 32_000
        const val MAX_TIMEOUT_SECONDS = 600
        const val SIGKILL = 9
        const val SIGTERM = 15
        private const val SIGNAL_EXIT_BASE = 128
        /** $1 为进程组号文件；写入失败时不执行命令。 */
        private const val WRAPPER = "echo \$\$ > \"\$1\" || exit 125; shift; exec \"\$@\""
    }
}

/** 按 UTF-16 计数并只保留尾部；解码在读取线程完成，多字节字符不会在块边界被拆开。 */
internal class TailTextCollector(private val limit: Int) {
    private val buffer = StringBuilder()
    @Volatile var totalChars: Long = 0L
        private set

    val truncated: Boolean get() = totalChars > limit

    fun consume(input: InputStream) {
        InputStreamReader(input, Charsets.UTF_8).use { reader ->
            val chunk = CharArray(8 * 1024)
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                append(chunk, count)
            }
        }
    }

    @Synchronized
    fun append(chars: CharArray, count: Int) {
        buffer.appendRange(chars, 0, count)
        totalChars += count
        if (buffer.length > limit * 2) buffer.delete(0, buffer.length - limit)
    }

    @Synchronized
    fun text(): String {
        if (buffer.length > limit) buffer.delete(0, buffer.length - limit)
        return if (truncated && buffer.isNotEmpty() && Character.isLowSurrogate(buffer[0])) buffer.substring(1) else buffer.toString()
    }
}
