package com.promenar.nexara.data.termux

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class TermuxIntentsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `Android包与权限检测将真实适配结果映射为可操作状态`() {
        val runtime = TermuxRuntime(context)
        assertThat(runtime.availability().errorCode).isEqualTo("TERMUX_NOT_INSTALLED")
        fun install(version: String) {
            shadowOf(context.packageManager).installPackage(PackageInfo().apply {
                packageName = "com.termux"
                versionName = version
                applicationInfo = ApplicationInfo().apply { packageName = "com.termux" }
            })
        }
        install("0.108")
        assertThat(runtime.availability().errorCode).isEqualTo("TERMUX_VERSION_UNSUPPORTED")
        install("0.118.3")
        shadowOf(context).denyPermissions(TermuxRuntime.RUN_COMMAND_PERMISSION)
        assertThat(runtime.availability().errorCode).isEqualTo("TERMUX_PERMISSION_DENIED")
        shadowOf(context).grantPermissions(TermuxRuntime.RUN_COMMAND_PERMISSION)
        assertThat(runtime.availability().errorCode).isNull()
        assertThat(runtime.availability().versionName).isEqualTo("0.118.3")
    }

    @Test fun `后台Intent仅执行bash参数并保留stdin与工作目录`() {
        val callback = PendingIntent.getBroadcast(context, 1, Intent("test").setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
        val intent = TermuxIntents.command(context, TermuxCommandRequest("printf '%s' '\$x'", "/tmp/work", 12, "a\nb"), callback)
        assertThat(intent.component!!.flattenToString()).isEqualTo("com.termux/com.termux.app.RunCommandService")
        assertThat(intent.action).isEqualTo("com.termux.RUN_COMMAND")
        assertThat(intent.getStringExtra("com.termux.RUN_COMMAND_PATH")).isEqualTo("/data/data/com.termux/files/usr/bin/bash")
        assertThat(intent.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!!.toList()).containsExactly("-c", "printf '%s' '\$x'").inOrder()
        assertThat(intent.getStringExtra("com.termux.RUN_COMMAND_STDIN")).isEqualTo("a\nb")
        assertThat(intent.getStringExtra("com.termux.RUN_COMMAND_WORKDIR")).isEqualTo("/tmp/work")
        assertThat(intent.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", false)).isTrue()
        assertThat(intent.getParcelableExtra("com.termux.RUN_COMMAND_PENDING_INTENT", PendingIntent::class.java)).isEqualTo(callback)
        callback.cancel()
    }

    @Test fun `默认目录及回调身份显式固定且不信任可变extra中的请求ID`() {
        val first = TermuxIntents.callback(context, "a")
        val second = TermuxIntents.callback(context, "b")
        assertThat(first.component!!.className).isEqualTo(TermuxResultReceiver::class.java.name)
        assertThat(first.data).isNotEqualTo(second.data)
        first.putExtra("request_id", "b")
        assertThat(TermuxIntents.requestId(first)).isEqualTo("a")
        assertThat(TermuxIntents.requestId(Intent().setData(first.data))).isNull()
        val callback = PendingIntent.getBroadcast(context, 2, first, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val command = TermuxIntents.command(context, TermuxCommandRequest("pwd"), callback)
        assertThat(command.getStringExtra("com.termux.RUN_COMMAND_WORKDIR")).isEqualTo("/data/data/com.termux/files/home")
        callback.cancel()
    }

    @Test fun `Bundle适配器读取官方数字字符串长度且空包失败关闭`() {
        val bundle = Bundle().apply {
            putString("stdout", "tail"); putString("stderr", "e")
            putInt("exitCode", 0); putInt("err", -1)
            putString("stdout_original_length", "100010"); putInt("stderr_original_length", 1)
        }
        val result = TermuxIntents.result(Intent().putExtra("result", bundle))
        assertThat(result.stdoutOriginalLength).isEqualTo(100010L)
        assertThat(result.stdoutTruncated).isTrue()
        assertThat(result.stderrTruncated).isFalse()
        assertThat(TermuxIntents.result(Intent()).errorCode).isEqualTo("TERMUX_RESULT_MALFORMED")
    }

    @Test fun `非法超时NUL及超长参数在跨应用派发前拒绝`() {
        assertThat(TermuxCommandRequest("id").timeoutSeconds).isEqualTo(60)
        assertThat(TermuxCommandRequest("id", timeoutSeconds = 600).timeoutSeconds).isEqualTo(600)
        listOf(0, -1, 601).forEach { timeout -> assertThrows(IllegalArgumentException::class.java) { TermuxCommandRequest("id", timeoutSeconds = timeout) } }
        listOf("", " ", "id\u0000whoami", "x".repeat(131072)).forEach { command ->
            assertThrows(IllegalArgumentException::class.java) { TermuxCommandRequest(command) }
        }
        assertThrows(IllegalArgumentException::class.java) { TermuxCommandRequest("id", workdir = "relative") }
        assertThrows(IllegalArgumentException::class.java) { TermuxCommandRequest("id", stdin = "x".repeat(250000)) }
    }
}
