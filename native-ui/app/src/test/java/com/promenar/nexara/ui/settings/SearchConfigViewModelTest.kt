package com.promenar.nexara.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchConfigViewModelTest {
    private lateinit var app: Application
    private lateinit var secrets: MemorySecretStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("nexara_search", 0).edit().clear().commit()
        secrets = MemorySecretStore()
    }

    @Test
    fun `旧 Tavily 明文初始化后迁入 SecretStore 且状态不回显`() {
        val prefs = app.getSharedPreferences("nexara_search", 0)
        prefs.edit().putString("tavily_api_key", "fake-tavily-key").commit()

        val viewModel = SearchConfigViewModel(app, secrets)

        assertThat(secrets.text(SecretCatalog.tavilyApiKey)).isEqualTo("fake-tavily-key")
        assertThat(prefs.contains("tavily_api_key")).isFalse()
        assertThat(viewModel.uiState.value.hasTavilyApiKey).isTrue()
        assertThat(viewModel.uiState.value.tavilyApiKey).isEmpty()
        assertThat(viewModel.uiState.value.toString()).doesNotContain("fake-tavily-key")
    }

    @Test
    fun `更新 Tavily Key 只写 SecretStore 并显式更新存在状态`() {
        val prefs = app.getSharedPreferences("nexara_search", 0)
        val viewModel = SearchConfigViewModel(app, secrets)

        viewModel.updateTavilyApiKey("fake-updated-key")

        assertThat(secrets.text(SecretCatalog.tavilyApiKey)).isEqualTo("fake-updated-key")
        assertThat(prefs.contains("tavily_api_key")).isFalse()
        assertThat(viewModel.uiState.value.hasTavilyApiKey).isTrue()
        assertThat(viewModel.uiState.value.tavilyApiKey).isEmpty()
    }

    private class MemorySecretStore : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId): Boolean = id in values
        override fun remove(id: SecretId) { values.remove(id) }
        fun text(id: SecretId): String? = get(id)?.toString(Charsets.UTF_8)
    }
}
