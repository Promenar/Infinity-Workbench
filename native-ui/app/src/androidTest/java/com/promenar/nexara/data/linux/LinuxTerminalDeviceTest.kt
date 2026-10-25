package com.promenar.nexara.data.linux

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxTerminalLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 前置条件：B1 运行时已经合入、侧载版已安装 Linux 环境且状态为 Ready。
 * 仅手动设备验收使用；不联网、不安装软件包，也不在 JVM 测试中加载 JNI。
 */
@RunWith(AndroidJUnit4::class)
class LinuxTerminalDeviceTest {
    @Test fun interactiveShellBuffersOutputAndCloseRemovesProcessGroup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<NexaraApplication>()
        assumeTrue("需要 B1 安装完成的侧载版 Linux 环境", app.linux.runtime.state.value is LinuxRuntimeState.Ready)
        val terminals = app.linux.terminals
        val id = terminals.open(
            LinuxTerminalLocation.Directory(),
            "sleep 120 & printf '\\nNEXARA_CHILD=%s\\n' \"\$!\"",
        )
        try {
            val terminal = requireNotNull(terminals.session(id))
            val group = terminal.pid
            assertThat(group).isGreaterThan(0)
            suspend fun output(): String = withContext(Dispatchers.Main) { terminal.emulator.screen.transcriptText }
            val child = withTimeout(20_000) {
                var pid: Int? = null
                while (pid == null) {
                    pid = Regex("NEXARA_CHILD=(\\d+)").find(output())?.groupValues?.get(1)?.toInt()
                    if (pid == null) delay(25)
                }
                pid
            }
            withContext(Dispatchers.Main) { terminal.write("echo nexara-\$((1+1))\r") }
            withTimeout(20_000) { while (!output().contains("nexara-2")) delay(25) }
            terminals.close(id)
            withTimeout(20_000) {
                while (exists(-group) || exists(child)) delay(25)
            }
            assertThat(terminals.sessions.value.none { it.id == id }).isTrue()
            assertThat(terminals.session(id)).isNull()
        } finally {
            terminals.close(id)
        }
    }

    private suspend fun exists(pid: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Os.kill(pid, 0)
            true
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESRCH) throw error
            false
        }
    }
}
