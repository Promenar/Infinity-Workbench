package com.promenar.infinity.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.security.AndroidKeystoreSecretStore
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

enum class SearchSecretErrorCode { LOAD_FAILED, SAVE_FAILED, CLEAR_FAILED }

sealed interface SearchSecretOperation {
    data object Initializing : SearchSecretOperation
    data object Idle : SearchSecretOperation
    data object Saving : SearchSecretOperation
    data object Saved : SearchSecretOperation
    data class Error(val code: SearchSecretErrorCode) : SearchSecretOperation
}

private enum class SearchSecretProvider(val secretId: SecretId) {
    TAVILY(SecretCatalog.tavilyApiKey),
    KEENABLE(SecretCatalog.keenableApiKey),
}

class SearchConfigViewModel(
    application: Application,
    private val secretStore: SecretStore =
        (application as? InfinityApplication)?.secretStore ?: AndroidKeystoreSecretStore(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
    companion object {
        fun factory(
            application: Application,
            secretStore: SecretStore? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SearchConfigViewModel::class.java)) {
                        "unsupported_view_model"
                    }
                    @Suppress("UNCHECKED_CAST")
                    return if (secretStore == null) {
                        SearchConfigViewModel(application, ioDispatcher = ioDispatcher) as T
                    } else {
                        SearchConfigViewModel(application, secretStore, ioDispatcher) as T
                    }
                }
            }
    }

    private val prefs = application.getSharedPreferences("infinity_search", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(SearchConfigState())
    val uiState: StateFlow<SearchConfigState> = _uiState.asStateFlow()
    private val secretJobs = mutableMapOf<SearchSecretProvider, Job>()

    init {
        loadSecretState(SearchSecretProvider.TAVILY)
        loadSecretState(SearchSecretProvider.KEENABLE)
    }

    private fun updateSecretState(
        provider: SearchSecretProvider,
        operation: SearchSecretOperation,
        present: Boolean? = null,
    ) {
        _uiState.update { state ->
            when (provider) {
                SearchSecretProvider.TAVILY -> state.copy(
                    secretOperation = operation,
                    hasTavilyApiKey = present ?: state.hasTavilyApiKey,
                )
                SearchSecretProvider.KEENABLE -> state.copy(
                    keenableSecretOperation = operation,
                    hasKeenableApiKey = present ?: state.hasKeenableApiKey,
                )
            }
        }
    }

    private fun loadSecretState(provider: SearchSecretProvider): Boolean {
        if (secretJobs[provider]?.isActive == true) return false
        updateSecretState(provider, SearchSecretOperation.Initializing)
        secretJobs[provider] = viewModelScope.launch {
            try {
                val present = withContext(ioDispatcher) {
                    if (provider == SearchSecretProvider.TAVILY) {
                        // 引擎选择先于凭据读取，凭据故障不应阻止普通设置恢复。
                        loadSettings()
                        migrateLegacyTavilyKey()
                    }
                    secretStore.contains(provider.secretId)
                }
                updateSecretState(provider, SearchSecretOperation.Idle, present)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateSecretState(provider, SearchSecretOperation.Error(SearchSecretErrorCode.LOAD_FAILED), false)
            }
        }
        return true
    }

    private fun migrateLegacyTavilyKey() {
        if (!prefs.contains("tavily_api_key")) return
        val legacy = prefs.getString("tavily_api_key", null) ?: return
        if (legacy.isNotEmpty()) {
            secretStore.put(SecretCatalog.tavilyApiKey, legacy.toByteArray(Charsets.UTF_8))
        }
        check(prefs.edit().remove("tavily_api_key").commit()) { "旧 Tavily 明文凭证删除失败" }
    }

    private fun loadSettings() {
        val includeJson = prefs.getString("include_domains", "[]") ?: "[]"
        val excludeJson = prefs.getString("exclude_domains", "[]") ?: "[]"
        
        _uiState.update {
            it.copy(
                webSearchEnabled = prefs.getBoolean("web_search_enabled", true),
                searchEngine = prefs.getString("search_engine", "duckduckgo") ?: "duckduckgo",
                searXngUrl = prefs.getString("searxng_url", "https://searx.be") ?: "https://searx.be",
                searchDepth = prefs.getString("search_depth", "advanced") ?: "advanced",
                resultCount = prefs.getInt("result_count", 5),
                includeDomains = try { Json.decodeFromString(includeJson) } catch (_: Exception) { emptyList() },
                excludeDomains = try { Json.decodeFromString(excludeJson) } catch (_: Exception) { emptyList() }
            )
        }
    }

    fun updateWebSearchEnabled(enabled: Boolean) {
        _uiState.update { it.copy(webSearchEnabled = enabled) }
        prefs.edit().putBoolean("web_search_enabled", enabled).apply()
    }

    fun updateSearchEngine(engine: String) {
        _uiState.update { it.copy(searchEngine = engine) }
        prefs.edit().putString("search_engine", engine).apply()
    }

    fun updateSearXngUrl(url: String) {
        _uiState.update { it.copy(searXngUrl = url) }
        prefs.edit().putString("searxng_url", url).apply()
    }

    fun saveTavilyApiKey(key: CharArray): Boolean = saveApiKey(SearchSecretProvider.TAVILY, key)

    fun saveKeenableApiKey(key: CharArray): Boolean = saveApiKey(SearchSecretProvider.KEENABLE, key)

    /** 输入数组由本方法接管并立即清零；持久化成功前不改变密钥存在状态。 */
    private fun saveApiKey(provider: SearchSecretProvider, key: CharArray): Boolean {
        val owned = key.copyOf()
        key.fill('\u0000')
        if (owned.isEmpty() || owned.all(Char::isWhitespace) || secretJobs[provider]?.isActive == true) {
            owned.fill('\u0000')
            return false
        }
        updateSecretState(provider, SearchSecretOperation.Saving)
        secretJobs[provider] = viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val bytes = owned.concatToString().toByteArray(Charsets.UTF_8)
                    try { secretStore.put(provider.secretId, bytes) } finally { bytes.fill(0) }
                }
                updateSecretState(provider, SearchSecretOperation.Saved, true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateSecretState(provider, SearchSecretOperation.Error(SearchSecretErrorCode.SAVE_FAILED))
            } finally {
                owned.fill('\u0000')
            }
        }
        return true
    }

    fun clearTavilyApiKey(): Boolean = clearApiKey(SearchSecretProvider.TAVILY)

    fun clearKeenableApiKey(): Boolean = clearApiKey(SearchSecretProvider.KEENABLE)

    private fun clearApiKey(provider: SearchSecretProvider): Boolean {
        if (secretJobs[provider]?.isActive == true) return false
        updateSecretState(provider, SearchSecretOperation.Saving)
        secretJobs[provider] = viewModelScope.launch {
            try {
                withContext(ioDispatcher) { secretStore.remove(provider.secretId) }
                updateSecretState(provider, SearchSecretOperation.Saved, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateSecretState(provider, SearchSecretOperation.Error(SearchSecretErrorCode.CLEAR_FAILED))
            }
        }
        return true
    }

    /**
     * 只重放当前结构化错误对应的操作。保存失败的明文仍由可见编辑器短暂持有，
     * ViewModel 不缓存该值，并立即接管/清零传入数组。
     */
    fun retryTavilySecretFailure(key: CharArray? = null): Boolean =
        retrySecretFailure(SearchSecretProvider.TAVILY, key)

    fun retryKeenableSecretFailure(key: CharArray? = null): Boolean =
        retrySecretFailure(SearchSecretProvider.KEENABLE, key)

    private fun retrySecretFailure(provider: SearchSecretProvider, key: CharArray?): Boolean {
        val operation = when (provider) {
            SearchSecretProvider.TAVILY -> _uiState.value.secretOperation
            SearchSecretProvider.KEENABLE -> _uiState.value.keenableSecretOperation
        }
        val code = (operation as? SearchSecretOperation.Error)?.code
        return when (code) {
            SearchSecretErrorCode.LOAD_FAILED -> {
                key?.fill('\u0000')
                loadSecretState(provider)
            }
            SearchSecretErrorCode.SAVE_FAILED -> {
                if (key == null) false else saveApiKey(provider, key)
            }
            SearchSecretErrorCode.CLEAR_FAILED -> {
                key?.fill('\u0000')
                clearApiKey(provider)
            }
            null -> {
                key?.fill('\u0000')
                false
            }
        }
    }

    suspend fun revealTavilyApiKey(): CharArray? = revealApiKey(SearchSecretProvider.TAVILY)

    suspend fun revealKeenableApiKey(): CharArray? = revealApiKey(SearchSecretProvider.KEENABLE)

    /** 返回值由当前可见组件取得所有权，组件隐藏或销毁时必须清零。 */
    private suspend fun revealApiKey(provider: SearchSecretProvider): CharArray? = withContext(ioDispatcher) {
        val bytes = secretStore.get(provider.secretId) ?: return@withContext null
        try {
            bytes.toString(Charsets.UTF_8).toCharArray()
        } finally {
            bytes.fill(0)
        }
    }

    fun updateSearchDepth(depth: String) {
        _uiState.update { it.copy(searchDepth = depth) }
        prefs.edit().putString("search_depth", depth).apply()
    }

    fun updateResultCount(count: Int) {
        _uiState.update { it.copy(resultCount = count) }
        prefs.edit().putInt("result_count", count).apply()
    }

    /** 对话框按行编辑整份名单（T2）。 */
    fun setIncludeDomains(text: String) {
        val list = parseDomainList(text)
        _uiState.update { it.copy(includeDomains = list) }
        prefs.edit().putString("include_domains", Json.encodeToString(list)).apply()
    }

    fun setExcludeDomains(text: String) {
        val list = parseDomainList(text)
        _uiState.update { it.copy(excludeDomains = list) }
        prefs.edit().putString("exclude_domains", Json.encodeToString(list)).apply()
    }

    fun addIncludeDomain(domain: String) {
        val newList = _uiState.value.includeDomains + domain
        _uiState.update { it.copy(includeDomains = newList) }
        prefs.edit().putString("include_domains", Json.encodeToString(newList)).apply()
    }

    fun removeIncludeDomain(domain: String) {
        val newList = _uiState.value.includeDomains - domain
        _uiState.update { it.copy(includeDomains = newList) }
        prefs.edit().putString("include_domains", Json.encodeToString(newList)).apply()
    }

    fun addExcludeDomain(domain: String) {
        val newList = _uiState.value.excludeDomains + domain
        _uiState.update { it.copy(excludeDomains = newList) }
        prefs.edit().putString("exclude_domains", Json.encodeToString(newList)).apply()
    }

    fun removeExcludeDomain(domain: String) {
        val newList = _uiState.value.excludeDomains - domain
        _uiState.update { it.copy(excludeDomains = newList) }
        prefs.edit().putString("exclude_domains", Json.encodeToString(newList)).apply()
    }
}

/**
 * 把“每行一个域名”的文本整理为名单：去掉首尾空白、协议、路径与 `*.` 前缀，转小写，丢弃空行与重复项。
 * 逗号与空格也视为分隔，便于粘贴。
 */
internal fun parseDomainList(text: String): List<String> =
    text.split('\n', ',', ' ', '\t')
        .map { raw ->
            raw.trim().lowercase()
                .substringAfter("://")
                .substringBefore('/')
                .removePrefix("*.")
                .trimEnd('.')
        }
        .filter { it.isNotEmpty() }
        .distinct()

data class SearchConfigState(
    val webSearchEnabled: Boolean = true,
    val searchEngine: String = "duckduckgo",
    val searXngUrl: String = "https://searx.be",
    val hasTavilyApiKey: Boolean = false,
    val secretOperation: SearchSecretOperation = SearchSecretOperation.Initializing,
    val hasKeenableApiKey: Boolean = false,
    val keenableSecretOperation: SearchSecretOperation = SearchSecretOperation.Initializing,
    val searchDepth: String = "advanced",
    val resultCount: Int = 5,
    val includeDomains: List<String> = emptyList(),
    val excludeDomains: List<String> = emptyList()
)
