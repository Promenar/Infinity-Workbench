package com.promenar.nexara.data.script

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.exec.PackagedPython
import com.promenar.nexara.domain.script.*
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 沙箱模式在真实 isolatedProcess 中运行打包 Python：隔离边界、能力往返与超时清理。 */
@RunWith(AndroidJUnit4::class)
class PythonSandboxDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val noCapabilities = ScriptCapabilities { _, _ -> throw ScriptFailure("CAPABILITY_DENIED", "此用例未提供能力") }

    @Before fun requirePython() {
        // 设备测试构建只打包 arm64-v8a；Python 缺失说明构建参数错误，不能静默通过。
        assertThat(PackagedPython.from(context).isAvailable).isTrue()
    }

    private suspend fun run(code: String, capabilities: ScriptCapabilities = noCapabilities, stdin: String = "", timeout: Int = 30): ScriptResult {
        val process = AndroidScriptProcess.bind(context, "python-sandbox-probe")
        return try {
            withTimeout((timeout + 15) * 1000L) {
                process.execute(ScriptRequest(code, timeoutSeconds = timeout, language = ScriptLanguages.PYTHON, stdin = stdin), capabilities)
            }
        } finally { process.terminate() }
    }

    private val ScriptResult.output get() = value.jsonObject
    private fun ScriptResult.stdout() = output["stdout"]!!.jsonPrimitive.content

    @Test fun runsPackagedPythonWithStdinAndResult() = runBlocking {
        val result = run("import sys\nprint(sys.version_info[:2])\nresult = sum(map(int, sys.stdin.read().split()))", stdin = "40 2")
        assertThat(result.errorCode).isNull()
        assertThat(result.stdout()).isEqualTo("(3, 14)\n")
        assertThat(result.output["result"]).isEqualTo(JsonPrimitive(42))
        assertThat(result.output["exit_code"]).isEqualTo(JsonPrimitive(0))
    }

    @Test fun cannotReadApplicationPrivateDataOrWriteFiles() = runBlocking {
        val secret = File(context.filesDir, "python-sandbox-private-probe.txt")
        secret.writeText("仅主进程可读的合成测试内容")
        val database = context.getDatabasePath("nexara.db").path
        try {
            val result = run(
                """
                import os
                for label, action in [
                    ("secret", lambda: open(${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(secret.path))}).read()),
                    ("files", lambda: os.listdir(${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(context.filesDir.path))})),
                    ("db", lambda: open(${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(database))}, "rb").read(1)),
                    ("write", lambda: open("/data/local/tmp/nexara-sandbox-write", "w").write("x")),
                ]:
                    try:
                        action()
                        print(label, "allowed")
                    except OSError as error:
                        print(label, "denied", type(error).__name__)
                print("uid", os.getuid())
                """.trimIndent(),
            )
            assertThat(result.errorCode).isNull()
            val lines = result.stdout().lines()
            listOf("secret", "files", "db", "write").forEach { label ->
                assertThat(lines.single { it.startsWith("$label ") }).contains("denied")
            }
            assertThat(result.stdout()).doesNotContain("仅主进程可读")
            val uid = lines.single { it.startsWith("uid ") }.removePrefix("uid ").trim().toInt()
            assertThat(uid).isNotEqualTo(android.os.Process.myUid())
        } finally { secret.delete() }
    }

    @Test fun cannotOpenSocketsDirectly() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val result = run(
                """
                import socket
                try:
                    socket.create_connection(("127.0.0.1", ${server.localPort}), timeout=3).close()
                    print("connected")
                except OSError as error:
                    print("denied", type(error).__name__)
                """.trimIndent(),
            )
            assertThat(result.stdout()).startsWith("denied")
        }
    }

    @Test fun capabilityCallsRoundTripThroughMainProcess() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val result = run(
            "import nexara\nrows = nexara.read_text('/data.csv').splitlines()\nnexara.save('/out.txt', str(len(rows)))\nprint(nexara.stat('/missing'))",
            ScriptCapabilities { operation, arguments ->
                calls += operation + ":" + arguments["path"]!!.jsonPrimitive.content
                when (operation) {
                    "read" -> buildJsonObject { put("content", "a\nb\nc"); put("sha256", "0".repeat(64)); put("path", "/data.csv") }
                    "stat" -> JsonNull
                    "create" -> buildJsonObject { put("message", arguments["content"]!!); put("path", "/out.txt") }
                    else -> throw ScriptFailure("CAPABILITY_DENIED", "未授权")
                }
            },
        )
        assertThat(result.errorCode).isNull()
        assertThat(result.stdout()).isEqualTo("None\n")
        assertThat(calls).containsExactly("read:/data.csv", "stat:/out.txt", "create:/out.txt", "stat:/missing").inOrder()
    }

    @Test fun timeoutKillsSandboxPythonProcess() = runBlocking {
        val runtime = SupervisedScriptRuntime { AndroidScriptProcess.bind(context, it) }
        val started = System.nanoTime()
        val result = withTimeout(20_000) {
            runtime.execute(
                "timeout-probe",
                ScriptRequest("import time\nprint('tick', flush=True)\nwhile True:\n    time.sleep(0.05)", timeoutSeconds = 2, language = ScriptLanguages.PYTHON),
                noCapabilities,
            )
        }
        assertThat(result.errorCode).isEqualTo("TIMEOUT")
        assertThat(result.value.jsonObject["stdout"]!!.jsonPrimitive.content).isEqualTo("tick\n")
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(10_000)
        withTimeout(5_000) { while (sandboxProcesses().isNotEmpty()) delay(100) }
    }

    private fun sandboxProcesses(): List<String> =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("ps -A -o PID,ARGS").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().readLines()
        }.filter { "nexara_sandbox" in it }
}
