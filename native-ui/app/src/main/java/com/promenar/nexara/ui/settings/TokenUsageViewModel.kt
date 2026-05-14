package com.promenar.nexara.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.model.findModelPricing
import com.promenar.nexara.data.model.findModelSpec
import com.promenar.nexara.domain.repository.DailyTokenStats
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.domain.repository.ModelTokenStats
import com.promenar.nexara.domain.repository.SessionTokenUsage
import com.promenar.nexara.domain.repository.TokenUsageAggregate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModelCostInfo(
    val name: String,
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUSD: Double,
    val pricingAvailable: Boolean
)

data class TokenStatsState(
    val globalInput: Long = 0,
    val globalOutput: Long = 0,
    val globalCostUSD: Double = 0.0,
    val globalHasEstimated: Boolean = false,
    val modelBreakdown: List<ModelCostInfo> = emptyList(),
    val topSessions: List<SessionTokenUsage> = emptyList(),
    val dailyTrend: List<DailyTokenStats> = emptyList(),
    val isLoading: Boolean = true
)

class TokenUsageViewModel(
    application: Application,
    private val tokenStatsRepository: ITokenStatsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TokenStatsState())
    val state: StateFlow<TokenStatsState> = _state.asStateFlow()

    init {
        loadStats()
    }

    fun loadStats() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val totalUsage = tokenStatsRepository.getTotalUsage()
                val byModel = tokenStatsRepository.getUsageByModel()
                val topSessions = tokenStatsRepository.getTopSessions(10)
                val dailyTrend = tokenStatsRepository.getDailyTrend(7)

                var globalCost = 0.0
                val modelBreakdown = byModel.map { stats ->
                    val spec = findModelSpec(stats.modelId)
                    val pricing = findModelPricing(stats.modelId)
                    val cost = if (pricing != null) {
                        (stats.usage.inputTokens / 1_000_000.0) * pricing.inputPerMillion +
                        (stats.usage.outputTokens / 1_000_000.0) * pricing.outputPerMillion
                    } else 0.0
                    globalCost += cost
                    ModelCostInfo(
                        name = spec?.note ?: stats.modelId,
                        modelId = stats.modelId,
                        inputTokens = stats.usage.inputTokens,
                        outputTokens = stats.usage.outputTokens,
                        costUSD = cost,
                        pricingAvailable = pricing != null
                    )
                }

                _state.value = TokenStatsState(
                    globalInput = totalUsage.inputTokens,
                    globalOutput = totalUsage.outputTokens,
                    globalCostUSD = globalCost,
                    globalHasEstimated = totalUsage.estimated,
                    modelBreakdown = modelBreakdown,
                    topSessions = topSessions,
                    dailyTrend = dailyTrend,
                    isLoading = false
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    fun clearStats() {
        viewModelScope.launch {
            try {
                tokenStatsRepository.resetStats()
                _state.value = TokenStatsState(isLoading = false)
            } catch (_: Exception) { }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as NexaraApplication
                    return TokenUsageViewModel(application, app.tokenStatsRepository) as T
                }
            }
    }
}
