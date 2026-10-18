package com.promenar.nexara.data.termux

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class TermuxRuntimeDispatchTest {
    private val app get() = RuntimeEnvironment.getApplication()

    /** 记录服务启动方式；applicationContext 指回自身，确保运行时经过该包装。 */
    private class RecordingContext(
        base: Context,
        private val plainFailure: RuntimeException? = null,
        private val plainReturnsNull: Boolean = false,
    ) : ContextWrapper(base) {
        val foregroundStarts = mutableListOf<Intent>()
        val plainStarts = mutableListOf<Intent>()
        override fun getApplicationContext(): Context = this
        override fun startService(service: Intent): ComponentName? {
            plainFailure?.let { throw it }
            plainStarts += service
            return if (plainReturnsNull) null else service.component
        }
        override fun startForegroundService(service: Intent): ComponentName? {
            foregroundStarts += service
            return service.component
        }
    }

    @Before fun ready() {
        app.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE).edit()
            .putBoolean(TermuxPreferences.ENABLED_KEY, true).commit()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.termux"
            versionName = "0.119.1"
            applicationInfo = ApplicationInfo().apply { packageName = "com.termux" }
        })
        shadowOf(app).grantPermissions(TermuxRuntime.RUN_COMMAND_PERMISSION)
    }

    @Test fun `默认以普通方式启动RunCommandService不调用前台服务启动`() = runBlocking {
        val context = RecordingContext(app)
        val result = TermuxRuntime(context).executeRequest(TermuxCommandRequest("true", timeoutSeconds = 1))
        assertThat(context.foregroundStarts).isEmpty()
        assertThat(context.plainStarts.single().component!!.flattenToString())
            .isEqualTo("com.termux/com.termux.app.RunCommandService")
        // 无回传时按本地等待超时结束，而非误报启动失败。
        assertThat(result.timedOut).isTrue()
    }

    @Test fun `后台启动限制拒绝普通启动时退回前台服务启动`() = runBlocking {
        val context = RecordingContext(app, plainFailure = IllegalStateException("Not allowed to start service"))
        val result = TermuxRuntime(context).executeRequest(TermuxCommandRequest("true", timeoutSeconds = 1))
        assertThat(context.foregroundStarts).hasSize(1)
        assertThat(result.timedOut).isTrue()
    }

    @Test fun `系统静默返回空组件时报告启动失败与拦截说明且错误码位于结果开头`() = runBlocking {
        val context = RecordingContext(app, plainReturnsNull = true)
        val result = TermuxRuntime(context).executeRequest(TermuxCommandRequest("true", timeoutSeconds = 1))
        assertThat(result.errorCode).isEqualTo("TERMUX_START_FAILED")
        assertThat(result.errmsg).contains("应用关联启动")
        val keys = result.toJson().jsonObject.keys.toList()
        assertThat(keys.take(4)).containsExactly("error_code", "guidance", "exit_code", "timed_out").inOrder()
    }
}
