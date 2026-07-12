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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.Dispatchers
import org.junit.After
import kotlinx.coroutines.ExperimentalCoroutinesApi

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SearchConfigViewModelTest {
    private lateinit var app: Application
    private lateinit var secrets: MemorySecretStore
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("nexara_search", 0).edit().clear().commit()
        secrets = MemorySecretStore()
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `production factory creates the dependency injected AndroidViewModel`() {
        val created = SearchConfigViewModel.factory(app, secrets, dispatcher)
            .create(SearchConfigViewModel::class.java)

        assertThat(created).isInstanceOf(SearchConfigViewModel::class.java)
    }

    @Test
    fun `旧 Tavily 明文初始化后迁入 SecretStore 且状态不回显`() = runTest(dispatcher) {
        val prefs = app.getSharedPreferences("nexara_search", 0)
        prefs.edit().putString("tavily_api_key", "fake-tavily-key").commit()

        val viewModel = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()

        assertThat(secrets.text(SecretCatalog.tavilyApiKey)).isEqualTo("fake-tavily-key")
        assertThat(prefs.contains("tavily_api_key")).isFalse()
        assertThat(viewModel.uiState.value.hasTavilyApiKey).isTrue()
        assertThat(viewModel.uiState.value.toString()).doesNotContain("fake-tavily-key")
    }

    @Test
    fun `更新 Tavily Key 只写 SecretStore 并显式更新存在状态`() = runTest(dispatcher) {
        val prefs = app.getSharedPreferences("nexara_search", 0)
        val viewModel = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()
        val input = "fake-updated-key".toCharArray()

        assertThat(viewModel.saveTavilyApiKey(input)).isTrue()
        assertThat(input).isEqualTo(CharArray("fake-updated-key".length))
        assertThat(viewModel.uiState.value.hasTavilyApiKey).isFalse()
        advanceUntilIdle()

        assertThat(secrets.text(SecretCatalog.tavilyApiKey)).isEqualTo("fake-updated-key")
        assertThat(prefs.contains("tavily_api_key")).isFalse()
        assertThat(viewModel.uiState.value.hasTavilyApiKey).isTrue()
    }

    @Test
    fun `完整 Tavily Key 只能通过临时可清零数组读取且不进入状态`() = runTest(dispatcher) {
        secrets.put(SecretCatalog.tavilyApiKey, "temporary-secret".encodeToByteArray())
        val viewModel = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()

        val revealed = viewModel.revealTavilyApiKey()

        assertThat(revealed?.concatToString()).isEqualTo("temporary-secret")
        assertThat(viewModel.uiState.value.toString()).doesNotContain("temporary-secret")
        revealed?.fill('\u0000')
        assertThat(revealed).isEqualTo(CharArray("temporary-secret".length))
    }

    @Test
    fun `保存失败不乐观更新存在状态且返回结构化错误`() = runTest(dispatcher) {
        val viewModel = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()
        secrets.failPut = true

        viewModel.saveTavilyApiKey("will-fail".toCharArray())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.hasTavilyApiKey).isFalse()
        assertThat(viewModel.uiState.value.secretOperation).isEqualTo(
            SearchSecretOperation.Error(SearchSecretErrorCode.SAVE_FAILED),
        )
    }

    private class MemorySecretStore : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        var failPut = false
        override fun put(id: SecretId, value: ByteArray) {
            if (failPut) error("put failed")
            values[id] = value.copyOf()
        }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId): Boolean = id in values
        override fun remove(id: SecretId) { values.remove(id) }
        fun text(id: SecretId): String? = get(id)?.toString(Charsets.UTF_8)
    }
}
