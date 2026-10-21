package com.promenar.nexara.data.script

import com.promenar.nexara.data.exec.PackagedPython
import com.promenar.nexara.domain.script.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** 启动沙箱 Python 子进程的命令与环境；隔离进程中由 [PythonSandboxEngine.packaged] 生成，单元测试可换成宿主 Python。 */
data class PythonSandboxLaunch(val command: List<String>, val environment: Map<String, String>)

/**
 * 在隔离进程内运行打包 Python（`nexara_sandbox` 模块）。用户代码的工作区与网络访问都经控制通道转成
 * [ScriptCapabilities] 调用，由主进程执行与审批；本类不接触任何文件或网络。
 * 结果的 value 为 exit_code、stdout、stderr（各保留尾部）与可选的 result 变量。
 */
class PythonSandboxEngine(private val launch: PythonSandboxLaunch?) : ScriptEngine {
    override val language = ScriptLanguages.PYTHON
    @Volatile private var process: Process? = null

    override fun cancel() { process?.destroyForcibly() }

    override suspend fun execute(request: ScriptRequest, capabilities: ScriptCapabilities): ScriptResult = withContext(Dispatchers.IO) {
        val launch = launch ?: return@withContext ScriptResult(errorCode = "PYTHON_UNAVAILABLE", error = PYTHON_UNAVAILABLE)
        val stdout = OutputTail()
        val stderr = OutputTail()
        val startupErrors = OutputTail()
        val started = try {
            ProcessBuilder(launch.command).apply {
                environment().clear()
                environment().putAll(launch.environment)
            }.start()
        } catch (failure: Exception) {
            return@withContext ScriptResult(errorCode = "PROCESS_DIED", error = "沙箱 Python 无法启动")
        }
        process = started
        val stopOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { started.destroyForcibly() }
        val errorDrain = thread(name = "python-sandbox-stderr", isDaemon = true) {
            runCatching { started.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { startupErrors.append(it + "\n") } }
        }
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        var failure: ScriptFailure? = null
        var done: JsonObject? = null
        try {
            coroutineScope {
                val watchdog = launch {
                    delay(request.timeoutSeconds * 1000L)
                    timedOut.set(true)
                    started.destroyForcibly()
                }
                try {
                    val writer = started.outputStream
                    fun send(frame: JsonObject) {
                        writer.write((frame.toString() + "\n").toByteArray(Charsets.UTF_8))
                        writer.flush()
                    }
                    send(buildJsonObject {
                        put("t", "start"); put("code", request.code); put("stdin", request.stdin); put("args", request.args)
                    })
                    val reader = FrameReader(started.inputStream)
                    var calls = 0
                    while (done == null && failure == null) {
                        val line = reader.next() ?: break
                        val frame = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                            ?: throw ScriptFailure("PROCESS_DIED", "沙箱控制帧格式错误")
                        when (frame["t"]?.jsonPrimitive?.contentOrNull) {
                            "out" -> {
                                val text = frame["d"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val fd = if (frame["fd"]?.jsonPrimitive?.intOrNull == 2) 2 else 1
                                if (fd == 2) stderr.append(text) else stdout.append(text)
                                capabilities.output(fd, text)
                            }
                            "cap" -> {
                                val id = frame["id"]?.jsonPrimitive?.intOrNull ?: throw ScriptFailure("PROCESS_DIED", "沙箱控制帧格式错误")
                                val response = try {
                                    if (++calls > ScriptLimits.CAPABILITY_CALLS) throw ScriptFailure("CAPABILITY_LIMIT", "能力调用次数超过限制")
                                    val operation = frame["op"]?.jsonPrimitive?.contentOrNull ?: throw ScriptFailure("INVALID_ARGUMENT", "缺少能力名称")
                                    if (operation == "module") throw ScriptFailure("CAPABILITY_DENIED", "未授权的文件能力")
                                    val arguments = frame["args"] as? JsonObject ?: throw ScriptFailure("INVALID_ARGUMENT", "能力参数必须为对象")
                                    val value = capabilities.invoke(operation, arguments)
                                    buildJsonObject { put("t", "cap_result"); put("id", id); put("value", value) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    val scriptFailure = error as? ScriptFailure
                                    // 与 JavaScript 引擎一致：次数、大小等上限失败终止整次执行，其它失败交给用户代码处理。
                                    if (scriptFailure?.code?.endsWith("_LIMIT") == true) failure = scriptFailure
                                    buildJsonObject {
                                        put("t", "cap_result"); put("id", id)
                                        put("errorCode", scriptFailure?.code ?: "CAPABILITY_DENIED")
                                        put("error", (scriptFailure?.message ?: "能力请求被拒绝").take(1024))
                                    }
                                }
                                if (failure == null) send(response)
                            }
                            "done" -> done = frame
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ScriptFailure) {
                    failure = error
                } catch (_: Exception) {
                    // 管道在超时或进程退出时关闭；下面按 done 帧与退出状态判断。
                } finally {
                    watchdog.cancel()
                }
            }
        } finally {
            if (done == null || failure != null) started.destroyForcibly()
            started.waitFor(2, TimeUnit.SECONDS)
            started.destroyForcibly()
            stopOnCancel?.dispose()
            errorDrain.join(500)
            process = null
        }
        val finished = done
        val output = output(stdout, stderr, finished)
        when {
            failure != null -> ScriptResult(value = output, errorCode = failure!!.code, error = failure!!.message.take(1024))
            timedOut.get() -> ScriptResult(value = output, errorCode = "TIMEOUT", error = "执行超过 ${request.timeoutSeconds} 秒，沙箱进程已终止")
            finished == null -> ScriptResult(
                value = output,
                errorCode = "PROCESS_DIED",
                error = ("沙箱 Python 意外退出" + startupErrors.text().takeLast(800).let { if (it.isBlank()) "" else "：$it" }).take(1024),
            )
            else -> ScriptResult(value = output)
        }
    }

    private fun output(stdout: OutputTail, stderr: OutputTail, done: JsonObject?): JsonObject {
        val result = done?.get("result")?.takeUnless { it is JsonNull }
        val resultError = done?.get("result_error")?.jsonPrimitive?.contentOrNull
        var stdoutBudget = STDOUT_BYTES
        var stderrBudget = STDERR_BYTES
        while (true) {
            val value = buildJsonObject {
                put("exit_code", done?.get("exit")?.jsonPrimitive?.intOrNull?.let { JsonPrimitive(it) } ?: JsonNull)
                put("stdout", stdout.tail(stdoutBudget))
                put("stderr", stderr.tail(stderrBudget))
                put("stdout_original_length", stdout.length)
                put("stderr_original_length", stderr.length)
                result?.let { put("result", it) }
                resultError?.let { put("result_error", it) }
            }
            // 转义可能使编码变长；结果须留在隔离服务的输出总量上限之内。
            if (Json.encodeToString(value).toByteArray(Charsets.UTF_8).size <= ScriptLimits.OUTPUT_BYTES - 2048 || stdoutBudget < 256) return value
            stdoutBudget /= 2
            stderrBudget /= 2
        }
    }

    /** 保留尾部的输出缓冲；length 为累计的 UTF-16 长度。 */
    private class OutputTail(private val keepChars: Int = 64_000) {
        private val buffer = StringBuilder()
        @Volatile var length = 0L
            private set

        @Synchronized fun append(text: String) {
            length += text.length
            buffer.append(text)
            if (buffer.length > keepChars * 2) buffer.delete(0, buffer.length - keepChars)
        }

        @Synchronized fun text(): String = buffer.toString()

        @Synchronized fun tail(maxBytes: Int): String {
            var start = buffer.length
            var used = 0
            while (start > 0) {
                val point = Character.codePointBefore(buffer, start)
                val bytes = when { point < 0x80 -> 1; point < 0x800 -> 2; point < 0x10000 -> 3; else -> 4 }
                if (used + bytes > maxBytes) break
                used += bytes
                start -= Character.charCount(point)
            }
            return buffer.substring(start)
        }
    }

    /** 按行读取控制帧；单帧超过上限视为协议错误，避免无界内存。 */
    private class FrameReader(input: InputStream) {
        private val input = BufferedInputStream(input)
        private val line = ByteArrayOutputStream()

        fun next(): String? {
            line.reset()
            while (true) {
                val byte = input.read()
                if (byte == -1) return if (line.size() == 0) null else line.toString(Charsets.UTF_8.name())
                if (byte == '\n'.code) return line.toString(Charsets.UTF_8.name())
                if (line.size() >= MAX_FRAME_BYTES) throw ScriptFailure("IPC_LIMIT", "沙箱控制帧超过大小上限")
                line.write(byte)
            }
        }
    }

    companion object {
        const val PYTHON_UNAVAILABLE = "当前设备架构没有内置 Python（只随 arm64-v8a 发行），沙箱模式不可用；请改用 exec_js、termux_exec 或 ssh_exec"
        private const val STDOUT_BYTES = 32 * 1024
        private const val STDERR_BYTES = 12 * 1024
        private const val MAX_FRAME_BYTES = ScriptLimits.IPC_BYTES + 4096
        private const val BOOTSTRAP = "import sys, nexara_sandbox; sys.exit(nexara_sandbox.main())"

        /** 隔离进程中的打包 Python；没有可写目录，HOME 指向根目录，PYTHONNOUSERSITE 保持开启。 */
        fun packaged(nativeLibraryDir: String): PythonSandboxEngine {
            val python = PackagedPython(File(nativeLibraryDir))
            if (!python.isAvailable) return PythonSandboxEngine(null)
            val environment = python.environment() + mapOf(
                "PATH" to "/system/bin",
                "HOME" to "/",
                "LANG" to "C.UTF-8",
                "PYTHONUTF8" to "1",
            )
            return PythonSandboxEngine(PythonSandboxLaunch(listOf(python.launcher.path, "-c", BOOTSTRAP), environment))
        }

        /** 单元测试用：以宿主 Python 运行同一个沙箱模块。 */
        fun bootstrapArguments(): List<String> = listOf("-c", BOOTSTRAP)
    }
}
