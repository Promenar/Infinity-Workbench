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
import com.promenar.nexara.domain.repository.SessionTokenUsage
import com.promenar.nexara.domain.repository.TokenUsageAggregate
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.CancellationException
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

enum class TokenStatsErrorCode { LOAD_FAILED, CLEAR_FAILED }
enum class TokenStatsOperation { LOADING, READY, CLEARING }

data class TokenStatsState(
    val globalInput: Long = 0,
    val globalOutput: Long = 0,
    val globalCostUSD: Double = 0.0,
    val globalHasEstimated: Boolean = false,
    val modelBreakdown: List<ModelCostInfo> = emptyList(),
    val topSessions: List<SessionTokenUsage> = emptyList(),
    val dailyTrend: List<DailyTokenStats> = emptyList(),
    val operation: TokenStatsOperation = TokenStatsOperation.READY,
    val error: TokenStatsErrorCode? = null,
    val showClearConfirm: Boolean = false
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
        if (_state.value.operation != TokenStatsOperation.READY) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                operation = TokenStatsOperation.LOADING,
                error = null,
            )
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
                    operation = TokenStatsOperation.READY,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                NexaraLogger.logError("$TAG.loadStats", e)
                _state.value = _state.value.copy(
                    operation = TokenStatsOperation.READY,
                    error = TokenStatsErrorCode.LOAD_FAILED,
                )
            }
        }
    }

    fun showClearConfirm() {
        _state.value = _state.value.copy(showClearConfirm = true)
    }

    fun dismissClearConfirm() {
        _state.value = _state.value.copy(showClearConfirm = false)
    }

    fun clearStats() {
        if (_state.value.operation != TokenStatsOperation.READY) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                operation = TokenStatsOperation.CLEARING,
                error = null,
                showClearConfirm = false,
            )
            try {
                tokenStatsRepository.resetStats()
                _state.value = TokenStatsState(operation = TokenStatsOperation.READY)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                NexaraLogger.logError("$TAG.clearStats", e)
                _state.value = _state.value.copy(
                    operation = TokenStatsOperation.READY,
                    error = TokenStatsErrorCode.CLEAR_FAILED,
                )
            }
        }
    }

    companion object {
        private const val TAG = "TokenUsageVM"

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
