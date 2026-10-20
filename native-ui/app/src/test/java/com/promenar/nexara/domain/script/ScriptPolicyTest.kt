package com.promenar.nexara.domain.script

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScriptPolicyTest {
    @Test fun `工作区脚本不能跨虚拟根导入技能而技能内相对模块可用`() {
        assertThrows(ScriptFailure::class.java) { ScriptModules.resolve("/", "/main.mjs", "./skills/helper.mjs") }
        assertThat(ScriptModules.resolve("/skills/demo", "/skills/demo/main.mjs", "./helper.mjs"))
            .isEqualTo("/skills/demo/helper.mjs")
    }

    @Test fun `相对模块允许根内父目录但拒绝越根和绝对导入`() {
        assertThat(ScriptModules.resolve("/mnt/demo/project", "/mnt/demo/project/lib/a.mjs", "../b.js"))
            .isEqualTo("/mnt/demo/project/b.js")
        listOf("../../outside.js", "/absolute.js", "https://example.org/a.js", "fs", "./.nexara/a.js", "./x.py").forEach {
            assertThrows(ScriptFailure::class.java) { ScriptModules.resolve("/project", "/project/lib/a.js", it) }
        }
    }

    @Test fun `日志保留顺序并按字节截断且完整保留Unicode`() {
        val output = ScriptOutput(logBytes = 40, outputBytes = 128)
        output.log("log", "第一条")
        output.log("warn", "😀".repeat(30))
        output.log("error", "尾部")
        val result = output.finish(kotlinx.serialization.json.JsonNull)
        assertThat(result.logs.map { it.level }).containsExactly("log", "warn").inOrder()
        assertThat(result.logsTruncated).isTrue()
        assertThat(result.logs.sumOf { it.text.toByteArray().size }).isAtMost(40)
        assertThat(result.logs.last().text).doesNotContain("�")
    }

    @Test fun `结果超限不可作为成功返回`() {
        assertThrows(ScriptFailure::class.java) {
            ScriptOutput(outputBytes = 30).finish(kotlinx.serialization.json.JsonPrimitive("x".repeat(100)))
        }
    }

    @Test fun `主进程超时终止服务且下一次重建`() = runTest {
        val processes = mutableListOf<FakeProcess>()
        val runtime = SupervisedScriptRuntime { FakeProcess().also(processes::add) }
        val pending = async { runtime.execute("s", ScriptRequest("while(true){}", timeoutSeconds = 1), noCapabilities) }
        runCurrent()
        assertThat(pending.await().errorCode).isEqualTo("TIMEOUT")
        assertThat(processes.single().terminated).isTrue()
        val next = async { runtime.execute("s", ScriptRequest("1", timeoutSeconds = 1), noCapabilities) }
        runCurrent()
        assertThat(processes.size).isEqualTo(2)
        next.cancel()
    }

    @Test fun `生成取消传播并终止进程而非转为脚本错误`() = runTest {
        val process = FakeProcess()
        val runtime = SupervisedScriptRuntime { process }
        val pending = async { runtime.execute("s", ScriptRequest("1"), noCapabilities) }
        runCurrent()
        pending.cancel()
        runCurrent()
        assertThat(pending.isCancelled).isTrue()
        assertThat(process.terminated).isTrue()
    }

    @Test fun `同会话正常结果复用进程但死亡后重建`() = runTest {
        val processes = mutableListOf<FakeProcess>()
        val runtime = SupervisedScriptRuntime { FakeProcess(ScriptResult()).also(processes::add) }
        runtime.execute("s", ScriptRequest("1"), noCapabilities)
        runtime.execute("s", ScriptRequest("2"), noCapabilities)
        assertThat(processes.size).isEqualTo(1)
        processes.single().terminate()
        runtime.execute("s", ScriptRequest("3"), noCapabilities)
        assertThat(processes.size).isEqualTo(2)
    }

    private class FakeProcess(private val result: ScriptResult? = null) : ScriptProcess {
        var terminated = false
        override val alive get() = !terminated
        override fun terminate() { terminated = true }
        override suspend fun execute(request: ScriptRequest, capabilities: ScriptCapabilities): ScriptResult = result ?: awaitCancellation()
    }
    private val noCapabilities = ScriptCapabilities { _, _ -> error("不应调用能力") }
}
