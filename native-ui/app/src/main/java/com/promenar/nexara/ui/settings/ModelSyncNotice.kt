package com.promenar.nexara.ui.settings

import com.promenar.nexara.R
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.promenar.nexara.ui.common.status.ResolvedStatus
import com.promenar.nexara.ui.common.status.UiStatusNotice
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provider 模型同步的结构化状态码（F-2）。稳定、不随 locale 变化。
 * 展示文案由 [template] 映射到 stringResource，UI 据 [UiStatusNotice.severity] 着色。
 */
object ModelSyncNotice {
    const val CODE_LOADING = "provider_models.loading"
    const val CODE_PROVIDER_NOT_FOUND = "provider_models.provider_not_found"
    const val CODE_SYNCED = "provider_models.synced"
    const val CODE_UP_TO_DATE = "provider_models.up_to_date"
    const val CODE_NO_MODELS_FOUND = "provider_models.no_models_found"
    const val CODE_SYNC_FAILED = "provider_models.sync_failed"

    fun loading(): UiStatusNotice = UiStatusNotice(
        severity = NoticeSeverity.Info,
        code = CODE_LOADING,
    )

    fun providerNotFound(): UiStatusNotice = UiStatusNotice(
        severity = NoticeSeverity.Error,
        code = CODE_PROVIDER_NOT_FOUND,
    )

    fun synced(newCount: Int, updatedCount: Int): UiStatusNotice = UiStatusNotice(
        severity = NoticeSeverity.Success,
        code = CODE_SYNCED,
        formatArgs = listOf(newCount, updatedCount),
    )

    fun upToDate(): UiStatusNotice = UiStatusNotice(
        severity = NoticeSeverity.Success,
        code = CODE_UP_TO_DATE,
    )

    fun noModelsFound(): UiStatusNotice = UiStatusNotice(
        severity = NoticeSeverity.Warning,
        code = CODE_NO_MODELS_FOUND,
    )

    fun syncFailed(technical: String?): UiStatusNotice = UiStatusNotice(
        severity = NoticeSeverity.Error,
        code = CODE_SYNC_FAILED,
        technical = technical,
    )

    /**
     * 纯函数：将 [notice] 映射到展示用的 string resource 模板。
     * 未知 code 返回 null，调用方须显示安全通用文案，不得泄漏 code/裸串。
     */
    fun template(notice: UiStatusNotice): ResolvedStatus? = when (notice.code) {
        CODE_LOADING -> ResolvedStatus(R.string.shared_loading)
        CODE_PROVIDER_NOT_FOUND -> ResolvedStatus(R.string.provider_models_sync_provider_not_found)
        CODE_SYNCED -> ResolvedStatus(
            R.string.provider_models_synced,
            listOf(
                notice.formatArgs.getOrElse(0) { 0 },
                notice.formatArgs.getOrElse(1) { 0 },
            ),
        )
        CODE_UP_TO_DATE -> ResolvedStatus(R.string.provider_models_sync_up_to_date)
        CODE_NO_MODELS_FOUND -> ResolvedStatus(R.string.provider_models_sync_no_models)
        CODE_SYNC_FAILED -> ResolvedStatus(R.string.provider_models_sync_failed)
        else -> null
    }
}

internal enum class ProviderModelsListState {
    Loading,
    Error,
    Empty,
    SearchEmpty,
    Content,
}

internal fun reduceProviderModelsListState(
    isLoading: Boolean,
    modelCount: Int,
    filteredCount: Int,
    searchQuery: String,
    notice: UiStatusNotice?,
): ProviderModelsListState = when {
    isLoading && modelCount == 0 -> ProviderModelsListState.Loading
    notice?.severity == NoticeSeverity.Error && modelCount == 0 -> ProviderModelsListState.Error
    modelCount == 0 -> ProviderModelsListState.Empty
    searchQuery.isNotBlank() && filteredCount == 0 -> ProviderModelsListState.SearchEmpty
    else -> ProviderModelsListState.Content
}

/** 同步请求的进程内幂等门禁，避免快速连点启动重复网络请求。 */
internal class ModelSyncGate {
    private val acquired = AtomicBoolean(false)

    fun tryAcquire(): Boolean = acquired.compareAndSet(false, true)

    fun release() {
        acquired.set(false)
    }
}
