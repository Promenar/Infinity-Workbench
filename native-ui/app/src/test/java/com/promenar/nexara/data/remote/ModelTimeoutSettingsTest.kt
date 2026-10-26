package com.promenar.nexara.data.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelTimeoutSettingsTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("nexara_settings", 0)

    @Before
    fun clear() { prefs.edit().clear().commit() }

    @Test
    fun `未存储时各模型使用各自默认值`() {
        val settings = ModelTimeoutPreferenceStore(prefs).settings.value
        assertThat(settings).isEqualTo(ModelTimeoutSettings())
        assertThat(ModelTimeoutKind.entries.map(settings::seconds)).containsExactly(120, 120, 30, 30).inOrder()
    }

    @Test
    fun `类别取值不会串用其它模型配置`() {
        val settings = ModelTimeoutSettings(11, 22, 33, 44)
        assertThat(ModelTimeoutKind.entries.map(settings::seconds)).containsExactly(11, 22, 33, 44).inOrder()
    }

    @Test
    fun `各类别写入夹取边界且重建存储后保留结果`() {
        val store = ModelTimeoutPreferenceStore(prefs)
        val flow = store.settings
        ModelTimeoutKind.entries.forEach { kind ->
            store.set(kind, Int.MIN_VALUE)
            assertThat(flow.value.seconds(kind)).isEqualTo(5)
            assertThat(ModelTimeoutPreferenceStore(prefs).settings.value.seconds(kind)).isEqualTo(5)
            store.set(kind, Int.MAX_VALUE)
            assertThat(flow.value.seconds(kind)).isEqualTo(1800)
            assertThat(ModelTimeoutPreferenceStore(prefs).settings.value.seconds(kind)).isEqualTo(1800)
        }
        listOf(5, 71, 1800, 94).forEachIndexed { index, seconds ->
            store.set(ModelTimeoutKind.entries[index], seconds)
        }
        assertThat(ModelTimeoutPreferenceStore(prefs).settings.value).isEqualTo(ModelTimeoutSettings(5, 71, 1800, 94))
    }

    @Test
    fun `磁盘越界值在读取时也夹取`() {
        prefs.edit().putInt("model_timeout_llm_seconds", -10)
            .putInt("model_timeout_image_seconds", 9000).commit()
        assertThat(ModelTimeoutPreferenceStore(prefs).settings.value).isEqualTo(ModelTimeoutSettings(5, 1800, 30, 30))
    }
}
