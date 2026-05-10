package com.promenar.nexara.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SearchConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("nexara_search", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(SearchConfigState())
    val uiState: StateFlow<SearchConfigState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val includeJson = prefs.getString("include_domains", "[]") ?: "[]"
        val excludeJson = prefs.getString("exclude_domains", "[]") ?: "[]"
        
        _uiState.update {
            it.copy(
                webSearchEnabled = prefs.getBoolean("web_search_enabled", true),
                searchEngine = prefs.getString("search_engine", "duckduckgo") ?: "duckduckgo",
                searXngUrl = prefs.getString("searxng_url", "https://searx.be") ?: "https://searx.be",
                tavilyApiKey = prefs.getString("tavily_api_key", "") ?: "",
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

    fun updateTavilyApiKey(key: String) {
        _uiState.update { it.copy(tavilyApiKey = key) }
        prefs.edit().putString("tavily_api_key", key).apply()
    }

    fun updateSearchDepth(depth: String) {
        _uiState.update { it.copy(searchDepth = depth) }
        prefs.edit().putString("search_depth", depth).apply()
    }

    fun updateResultCount(count: Int) {
        _uiState.update { it.copy(resultCount = count) }
        prefs.edit().putInt("result_count", count).apply()
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

data class SearchConfigState(
    val webSearchEnabled: Boolean = true,
    val searchEngine: String = "duckduckgo",
    val searXngUrl: String = "https://searx.be",
    val tavilyApiKey: String = "",
    val searchDepth: String = "advanced",
    val resultCount: Int = 5,
    val includeDomains: List<String> = emptyList(),
    val excludeDomains: List<String> = emptyList()
)
