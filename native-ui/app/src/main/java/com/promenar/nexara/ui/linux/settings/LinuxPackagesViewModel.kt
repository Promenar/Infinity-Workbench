package com.promenar.nexara.ui.linux.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.domain.linux.LinuxPackage
import com.promenar.nexara.domain.linux.LinuxPackageAction
import com.promenar.nexara.domain.linux.LinuxPackageManager
import com.promenar.nexara.domain.linux.LinuxPackageOperation
import com.promenar.nexara.domain.linux.LinuxPackageOrigin
import com.promenar.nexara.domain.linux.LinuxPackageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LinuxPackageFilter { ALL, INSTALLED, UPDATES, AGENT }

/** 一次失败的软件包操作，界面用于提示。 */
data class LinuxPackageFailure(val action: LinuxPackageAction, val names: List<String>, val result: LinuxPackageResult?)

data class LinuxPackagesUiState(
    /** 已安装列表尚未读取时为 null。 */
    val installed: List<LinuxPackage>? = null,
    val filter: LinuxPackageFilter = LinuxPackageFilter.ALL,
    val query: String = "",
    /** 查询为空时为 null；搜索进行中为空列表且 [searching] 为 true。 */
    val searchResults: List<LinuxPackage>? = null,
    val searching: Boolean = false,
    val operation: LinuxPackageOperation? = null,
    val failure: LinuxPackageFailure? = null,
) {
    val installedCount: Int get() = installed?.size ?: 0
    val updateCount: Int get() = installed?.count { it.availableVersion != null } ?: 0

    /** 当前页签下的已安装条目；搜索时按名称与描述再过滤一次。 */
    val visibleInstalled: List<LinuxPackage>
        get() = filterInstalled(installed.orEmpty(), filter, query)
}

internal fun filterInstalled(packages: List<LinuxPackage>, filter: LinuxPackageFilter, query: String): List<LinuxPackage> {
    val needle = query.trim()
    return packages
        .filter {
            when (filter) {
                LinuxPackageFilter.ALL -> true
                LinuxPackageFilter.INSTALLED -> it.explicit
                LinuxPackageFilter.UPDATES -> it.availableVersion != null
                LinuxPackageFilter.AGENT -> it.origin is LinuxPackageOrigin.Agent
            }
        }
        .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) || it.description?.contains(needle, ignoreCase = true) == true }
        // 显式安装的在前，同组按名称排序。
        .sortedWith(compareBy<LinuxPackage> { !it.explicit }.thenBy { it.name })
}

/** 搜索结果中去掉已在已安装区出现的包，避免同一页出现两次。 */
internal fun searchOnlyResults(results: List<LinuxPackage>?, installed: List<LinuxPackage>?): List<LinuxPackage>? {
    results ?: return null
    val installedNames = installed.orEmpty().mapTo(HashSet()) { it.name }
    return results.filterNot { it.installed || it.name in installedNames }
}

class LinuxPackagesViewModel(private val packages: LinuxPackageManager) : ViewModel() {
    private val local = MutableStateFlow(LinuxPackagesUiState())
    private var searchJob: Job? = null
    private var indexRefreshed = false

    val uiState: StateFlow<LinuxPackagesUiState> = combine(local, packages.operation) { state, operation ->
        state.copy(operation = operation)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LinuxPackagesUiState())

    init {
        reload()
    }

    fun setFilter(filter: LinuxPackageFilter) = local.update { it.copy(filter = filter) }

    fun setQuery(query: String) {
        local.update { it.copy(query = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY) {
            local.update { it.copy(searchResults = null, searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            local.update { it.copy(searching = true) }
            val results = try {
                packages.search(trimmed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            local.update { it.copy(searchResults = results, searching = false) }
        }
    }

    /** 读取已安装列表；首次进入时再在后台刷新一次索引，得到可更新信息。 */
    fun reload() {
        viewModelScope.launch {
            loadInstalled()
            if (!indexRefreshed) {
                indexRefreshed = true
                if (runCatching { packages.refreshIndex() }.getOrNull()?.success == true) loadInstalled()
            }
        }
    }

    fun refreshIndex() = run(LinuxPackageAction.REFRESH, emptyList()) { packages.refreshIndex() }

    fun install(name: String) = run(LinuxPackageAction.INSTALL, listOf(name)) { packages.install(listOf(name), LinuxPackageOrigin.User) }

    fun remove(name: String) = run(LinuxPackageAction.REMOVE, listOf(name)) { packages.remove(listOf(name)) }

    fun upgrade(name: String) = run(LinuxPackageAction.UPGRADE, listOf(name)) { packages.upgrade(listOf(name)) }

    fun upgradeAll() = run(LinuxPackageAction.UPGRADE, emptyList()) { packages.upgrade() }

    fun consumeFailure() = local.update { it.copy(failure = null) }

    private suspend fun loadInstalled() {
        val installed = runCatching { packages.installed() }.getOrNull() ?: return
        local.update { it.copy(installed = installed) }
    }

    private fun run(action: LinuxPackageAction, names: List<String>, block: suspend () -> LinuxPackageResult) {
        if (uiState.value.operation != null) return
        viewModelScope.launch {
            val result = try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (result?.success != true) local.update { it.copy(failure = LinuxPackageFailure(action, names, result)) }
            loadInstalled()
            // 搜索结果里的“已安装”标记也要随之更新。
            if (local.value.searchResults != null) setQuery(local.value.query)
        }
    }

    companion object {
        private const val MIN_QUERY = 2
        private const val SEARCH_DEBOUNCE_MS = 300L

        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { LinuxPackagesViewModel((application as NexaraApplication).linux.packages) }
        }
    }
}
