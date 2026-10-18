package com.promenar.nexara.data.exec

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 宿主机用 perl setpgrp 代替 Android 的 toybox setsid 建立进程组，用 /bin/kill 代替 Os.kill。 */
class BuiltinCommandRunnerTest {
    private lateinit var work: File
    private lateinit var runner: BuiltinCommandRunner

    @Before
    fun setUp() {
        assumeTrue("需要 /usr/bin/perl 建立进程组", File(PERL).canExecute())
        work = Files.createTempDirectory("builtin-runner").toFile()
        runner = runner()
    }

    @After
    fun tearDown() {
        if (::work.isInitialized) work.deleteRecursively()
    }

    private fun runner(outputLimit: Int = BuiltinCommandRunner.OUTPUT_LIMIT) = BuiltinCommandRunner(
        groupLauncher = listOf(PERL, "-e", "setpgrp(0, 0); exec @ARGV or exit 127"),
        shell = "/bin/sh",
        signaller = HostSignaller,
        scratchDirectory = { File(work, "scratch") },
        outputLimit = outputLimit,
        killGraceMillis = 500,
    )

    private fun request(command: String, timeout: Int = 20, stdin: String? = null, env: Map<String, String> = mapOf("PATH" to "/usr/bin:/bin")) =
        BuiltinCommandRequest(listOf("/bin/sh", "-c", command), work, env, stdin, timeout)

    @Test
    fun `退出码、两路输出与标准输入原样返回`() = runBlocking<Unit> {
        val output = runner.run(request("read line; echo \"out:\$line\"; echo err >&2; exit 3", stdin = "你好\n"))
        assertThat(output.exitCode).isEqualTo(3)
        assertThat(output.timedOut).isFalse()
        assertThat(output.stdout).isEqualTo("out:你好\n")
        assertThat(output.stderr).isEqualTo("err\n")
        assertThat(output.stdoutTruncated).isFalse()
        assertThat(output.killedExternally).isFalse()
    }

    @Test
    fun `环境变量只来自请求且工作目录为指定目录`() = runBlocking<Unit> {
        val output = runner.run(request("env | sort; pwd", env = mapOf("PATH" to "/usr/bin:/bin", "NEXARA_X" to "1")))
        val lines = output.stdout.lines().filter(String::isNotBlank)
        assertThat(lines.filter { it.startsWith("NEXARA_X=") }).containsExactly("NEXARA_X=1")
        assertThat(lines.none { it.startsWith("HOME=") }).isTrue()
        assertThat(File(lines.last()).canonicalPath).isEqualTo(work.canonicalPath)
    }

    @Test
    fun `超限输出保留尾部并给出 UTF-16 原始长度`() = runBlocking<Unit> {
        val output = runner(outputLimit = 100).run(request("i=0; while [ \$i -lt 50 ]; do printf '0123456789'; i=\$((i+1)); done; printf END"))
        assertThat(output.stdoutOriginalLength).isEqualTo(503)
        assertThat(output.stdoutTruncated).isTrue()
        assertThat(output.stdout).hasLength(100)
        assertThat(output.stdout).endsWith("789END")
    }

    @Test
    fun `截断边界不留下半个代理对`() = runBlocking<Unit> {
        // 每个 😀 占两个 UTF-16 单元；奇数上限会切在代理对中间。
        val output = runner(outputLimit = 5).run(request("printf '😀😀😀😀😀'"))
        assertThat(output.stdoutOriginalLength).isEqualTo(10)
        assertThat(output.stdout).isEqualTo("😀😀")
    }

    @Test
    fun `超时结束整个进程组包括后台子进程`() = runBlocking<Unit> {
        val pidFile = File(work, "bg.pid")
        val started = System.nanoTime()
        val output = runner.run(request("sleep 60 & echo \$! > ${pidFile.path}; sleep 60", timeout = 1))
        assertThat(output.timedOut).isTrue()
        assertThat(output.exitCode).isNull()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(10_000)
        assertThat(alive(pidFile.readText().trim().toInt())).isFalse()
    }

    @Test
    fun `正常结束后同组后台进程被结束`() = runBlocking<Unit> {
        val pidFile = File(work, "bg.pid")
        val output = runner.run(request("sleep 60 > /dev/null 2>&1 & echo \$! > ${pidFile.path}; echo done"))
        assertThat(output.exitCode).isEqualTo(0)
        assertThat(output.stdout).isEqualTo("done\n")
        assertThat(alive(pidFile.readText().trim().toInt())).isFalse()
    }

    @Test
    fun `取消时结束进程组并传播取消`() = runBlocking<Unit> {
        val pidFile = File(work, "main.pid")
        val job = async { runner.run(request("echo \$\$ > ${pidFile.path}; sleep 60")) }
        withTimeout(5_000) { while (!pidFile.isFile || pidFile.readText().isBlank()) delay(20) }
        job.cancel()
        val cancelled = runCatching { job.await() }.exceptionOrNull()
        assertThat(cancelled).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        val pid = pidFile.readText().trim().toInt()
        withTimeout(5_000) { while (alive(pid)) delay(20) }
    }

    @Test
    fun `用户停止时结束整个进程组并标记为停止而非超时`() = runBlocking<Unit> {
        val pidFile = File(work, "bg.pid")
        val control = BuiltinCommandControl()
        val job = async { runner.run(request("sleep 60 & echo \$! > ${pidFile.path}; sleep 60"), control) }
        withTimeout(5_000) { while (!pidFile.isFile || pidFile.readText().isBlank()) delay(20) }
        control.requestStop()
        val output = withTimeout(10_000) { job.await() }
        assertThat(output.stoppedByUser).isTrue()
        assertThat(output.timedOut).isFalse()
        assertThat(output.exitCode).isNull()
        assertThat(alive(pidFile.readText().trim().toInt())).isFalse()
    }

    @Test
    fun `正常运行的等待间隔不记为暂停`() = runBlocking<Unit> {
        val output = runner.run(request("sleep 1"))
        assertThat(output.exitCode).isEqualTo(0)
        assertThat(output.pausedMillis).isEqualTo(0)
    }

    @Test
    fun `临时目录在结束后清理`() = runBlocking<Unit> {
        runner.run(request("true"))
        assertThat(File(work, "scratch").listFiles().orEmpty().toList()).isEmpty()
    }

    private fun alive(pid: Int): Boolean =
        ProcessBuilder("/bin/kill", "-0", pid.toString()).redirectErrorStream(true).start().waitFor() == 0

    private object HostSignaller : ProcessGroupSignaller {
        override fun signal(processGroupId: Int, signal: Int) {
            ProcessBuilder("/bin/kill", "-$signal", "-$processGroupId").redirectErrorStream(true).start().waitFor()
        }
    }

    private companion object {
        const val PERL = "/usr/bin/perl"
    }
}
