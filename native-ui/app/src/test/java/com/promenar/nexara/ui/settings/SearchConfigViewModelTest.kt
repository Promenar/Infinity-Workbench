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
    fun `域名清单按换行逗号空白拆分并规整为主机名`() {
        assertThat(parseDomainList("https://Example.com/path, *.docs.rs\n\tgithub.com.  example.com\n\n"))
            .containsExactly("example.com", "docs.rs", "github.com").inOrder()
        assertThat(parseDomainList("  ")).isEmpty()
    }

    @Test
    fun `设置包含与排除域名会写入状态并在重建后恢复`() = runTest(dispatcher) {
        val viewModel = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()

        viewModel.setIncludeDomains("a.com\nb.com")
        viewModel.setExcludeDomains("spam.net")
        assertThat(viewModel.uiState.value.includeDomains).containsExactly("a.com", "b.com").inOrder()
        assertThat(viewModel.uiState.value.excludeDomains).containsExactly("spam.net")

        val restored = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()
        assertThat(restored.uiState.value.includeDomains).containsExactly("a.com", "b.com").inOrder()
        viewModel.setIncludeDomains("")
        assertThat(viewModel.uiState.value.includeDomains).isEmpty()
    }

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

    @Test
    fun `load and clear failures retry only their originating operation`() = runTest(dispatcher) {
        secrets.failContains = true
        val viewModel = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.secretOperation).isEqualTo(
            SearchSecretOperation.Error(SearchSecretErrorCode.LOAD_FAILED),
        )

        secrets.failContains = false
        assertThat(viewModel.retryTavilySecretFailure()).isTrue()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.secretOperation).isEqualTo(SearchSecretOperation.Idle)

        secrets.put(SecretCatalog.tavilyApiKey, "stored".encodeToByteArray())
        secrets.failRemove = true
        assertThat(viewModel.clearTavilyApiKey()).isTrue()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.secretOperation).isEqualTo(
            SearchSecretOperation.Error(SearchSecretErrorCode.CLEAR_FAILED),
        )

        secrets.failRemove = false
        assertThat(viewModel.retryTavilySecretFailure()).isTrue()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.hasTavilyApiKey).isFalse()
        assertThat(viewModel.uiState.value.secretOperation).isEqualTo(SearchSecretOperation.Saved)
    }

    @Test
    fun `Keenable 选择与结果数量在重新创建后恢复且不改变默认引擎`() = runTest(dispatcher) {
        val initial = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()
        assertThat(initial.uiState.value.searchEngine).isEqualTo("duckduckgo")

        initial.updateSearchEngine("keenable")
        initial.updateResultCount(8)
        val restored = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()

        assertThat(restored.uiState.value.searchEngine).isEqualTo("keenable")
        assertThat(restored.uiState.value.resultCount).isEqualTo(8)
        assertThat(restored.uiState.value.hasKeenableApiKey).isFalse()
    }

    @Test
    fun `Keenable 密钥只写安全存储且可临时读取清除而不影响 Tavily`() = runTest(dispatcher) {
        val prefs = app.getSharedPreferences("nexara_search", 0)
        secrets.put(SecretCatalog.tavilyApiKey, "fake-tavily".encodeToByteArray())
        val viewModel = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()
        val input = "fake-keenable".toCharArray()

        assertThat(viewModel.saveKeenableApiKey(input)).isTrue()
        assertThat(input).isEqualTo(CharArray(input.size))
        assertThat(viewModel.uiState.value.hasKeenableApiKey).isFalse()
        advanceUntilIdle()

        assertThat(secrets.text(SecretCatalog.keenableApiKey)).isEqualTo("fake-keenable")
        assertThat(prefs.all.toString()).doesNotContain("fake-keenable")
        assertThat(prefs.contains("keenable_api_key")).isFalse()
        assertThat(viewModel.uiState.value.toString()).doesNotContain("fake-keenable")
        assertThat(viewModel.uiState.value.hasKeenableApiKey).isTrue()
        val revealed = viewModel.revealKeenableApiKey()
        assertThat(revealed?.concatToString()).isEqualTo("fake-keenable")
        revealed?.fill('\u0000')

        val restored = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()
        assertThat(restored.uiState.value.hasKeenableApiKey).isTrue()
        assertThat(restored.clearKeenableApiKey()).isTrue()
        advanceUntilIdle()
        assertThat(restored.uiState.value.hasKeenableApiKey).isFalse()
        assertThat(secrets.contains(SecretCatalog.keenableApiKey)).isFalse()
        assertThat(secrets.text(SecretCatalog.tavilyApiKey)).isEqualTo("fake-tavily")
    }

    @Test
    fun `Keenable 保存与清除失败保持原状态并只重试对应提供商`() = runTest(dispatcher) {
        val viewModel = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()
        secrets.failPut = true
        viewModel.saveKeenableApiKey("fake-retry-key".toCharArray())
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.hasKeenableApiKey).isFalse()
        assertThat(viewModel.uiState.value.keenableSecretOperation).isEqualTo(
            SearchSecretOperation.Error(SearchSecretErrorCode.SAVE_FAILED),
        )
        assertThat(viewModel.uiState.value.secretOperation).isEqualTo(SearchSecretOperation.Idle)

        secrets.failPut = false
        val retryInput = "fake-retry-key".toCharArray()
        assertThat(viewModel.retryKeenableSecretFailure(retryInput)).isTrue()
        assertThat(retryInput).isEqualTo(CharArray(retryInput.size))
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.hasKeenableApiKey).isTrue()

        secrets.failRemove = true
        viewModel.clearKeenableApiKey()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.hasKeenableApiKey).isTrue()
        assertThat(viewModel.uiState.value.keenableSecretOperation).isEqualTo(
            SearchSecretOperation.Error(SearchSecretErrorCode.CLEAR_FAILED),
        )
        secrets.failRemove = false
        assertThat(viewModel.retryKeenableSecretFailure()).isTrue()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.hasKeenableApiKey).isFalse()
        assertThat(secrets.contains(SecretCatalog.tavilyApiKey)).isFalse()
    }

    @Test
    fun `Keenable 初始化失败可重试且不丢失引擎选择`() = runTest(dispatcher) {
        app.getSharedPreferences("nexara_search", 0).edit().putString("search_engine", "keenable").commit()
        secrets.failContains = true
        val viewModel = SearchConfigViewModel(app, secrets, dispatcher)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.searchEngine).isEqualTo("keenable")
        assertThat(viewModel.uiState.value.keenableSecretOperation).isEqualTo(
            SearchSecretOperation.Error(SearchSecretErrorCode.LOAD_FAILED),
        )

        secrets.failContains = false
        viewModel.retryKeenableSecretFailure()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.keenableSecretOperation).isEqualTo(SearchSecretOperation.Idle)
    }

    private class MemorySecretStore : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        var failPut = false
        var failContains = false
        var failRemove = false
        override fun put(id: SecretId, value: ByteArray) {
            if (failPut) error("put failed")
            values[id] = value.copyOf()
        }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId): Boolean {
            if (failContains) error("contains failed")
            return id in values
        }
        override fun remove(id: SecretId) {
            if (failRemove) error("remove failed")
            values.remove(id)
        }
        fun text(id: SecretId): String? = get(id)?.toString(Charsets.UTF_8)
    }
}
