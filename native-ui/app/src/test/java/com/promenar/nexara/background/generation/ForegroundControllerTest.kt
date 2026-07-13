package com.promenar.nexara.background.generation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files
import java.nio.file.Path

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ForegroundControllerTest {
    @Test
    fun `通知权限未授予也照常提交TRACK前台服务命令`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val controller = AndroidGenerationForegroundController(context)

        val result = controller.track(
            GenerationTaskSnapshot("task", "session", "assistant", GenerationPhase.PREPARING, 0, 1L),
        )

        val intent = shadowOf(context).nextStartedService
        assertThat(result).isEqualTo(ForegroundStartResult.StartedWithoutNotifications)
        assertThat(intent.action).isEqualTo(GenerationForegroundService.ACTION_TRACK)
        assertThat(intent.getStringExtra(GenerationForegroundService.EXTRA_TASK_ID)).isEqualTo("task")
    }

    @Test
    fun `controller区分系统禁止与FGS权限异常并检查通知权限`() {
        val source = Files.readAllBytes(
            Path.of("app/src/main/java/com/promenar/nexara/background/generation/ForegroundController.kt"),
        ).toString(Charsets.UTF_8)

        assertThat(source).contains("catch (error: ForegroundServiceStartNotAllowedException)")
        assertThat(source).contains("catch (error: SecurityException)")
        assertThat(source).contains("POST_NOTIFICATIONS")
    }
}
