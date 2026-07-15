package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.status.NoticeSeverity
import org.junit.Test

class ModelSyncNoticeTest {

    @Test
    fun `provider not found maps code and severity`() {
        val notice = ModelSyncNotice.providerNotFound()
        assertThat(notice.code).isEqualTo(ModelSyncNotice.CODE_PROVIDER_NOT_FOUND)
        assertThat(notice.severity).isEqualTo(NoticeSeverity.Error)
    }

    @Test
    fun `synced notice keeps typed args`() {
        val notice = ModelSyncNotice.synced(newCount = 2, updatedCount = 1)
        assertThat(notice.code).isEqualTo(ModelSyncNotice.CODE_SYNCED)
        assertThat(notice.formatArgs).containsExactly(2, 1).inOrder()
    }

    @Test
    fun `template resolves known codes`() {
        assertThat(ModelSyncNotice.template(ModelSyncNotice.loading())?.resourceId).isEqualTo(
            R.string.shared_loading
        )
        assertThat(ModelSyncNotice.template(ModelSyncNotice.providerNotFound())?.resourceId).isEqualTo(
            R.string.provider_models_sync_provider_not_found
        )
        assertThat(ModelSyncNotice.template(ModelSyncNotice.synced(1, 3))?.resourceId).isEqualTo(
            R.string.provider_models_synced
        )
        assertThat(ModelSyncNotice.template(ModelSyncNotice.syncFailed("io exception"))?.resourceId).isEqualTo(
            R.string.provider_models_sync_failed
        )
        assertThat(ModelSyncNotice.template(ModelSyncNotice.upToDate())?.resourceId).isEqualTo(
            R.string.provider_models_sync_up_to_date
        )
        assertThat(ModelSyncNotice.template(ModelSyncNotice.noModelsFound())?.resourceId).isEqualTo(
            R.string.provider_models_sync_no_models
        )
    }

    @Test
    fun `sync failed stores technical only`() {
        val notice = ModelSyncNotice.syncFailed("network refused")
        assertThat(notice.code).isEqualTo(ModelSyncNotice.CODE_SYNC_FAILED)
        assertThat(notice.technical).isEqualTo("network refused")
    }

    @Test
    fun `unknown code returns null template`() {
        val unknown = ModelSyncNotice.synced(0, 0).copy(code = "unknown")
        assertThat(ModelSyncNotice.template(unknown)).isNull()
    }

    @Test
    fun `list reducer distinguishes loading empty search error and content`() {
        assertThat(
            reduceProviderModelsListState(
                isLoading = true,
                modelCount = 0,
                filteredCount = 0,
                searchQuery = "",
                notice = ModelSyncNotice.loading(),
            ),
        ).isEqualTo(ProviderModelsListState.Loading)

        assertThat(
            reduceProviderModelsListState(
                isLoading = false,
                modelCount = 0,
                filteredCount = 0,
                searchQuery = "",
                notice = ModelSyncNotice.syncFailed("private endpoint detail"),
            ),
        ).isEqualTo(ProviderModelsListState.Error)

        assertThat(
            reduceProviderModelsListState(false, 0, 0, "", null),
        ).isEqualTo(ProviderModelsListState.Empty)
        assertThat(
            reduceProviderModelsListState(false, 2, 0, "vision", null),
        ).isEqualTo(ProviderModelsListState.SearchEmpty)
        assertThat(
            reduceProviderModelsListState(false, 2, 1, "vision", null),
        ).isEqualTo(ProviderModelsListState.Content)
    }

    @Test
    fun `sync gate rejects duplicate acquisition until released`() {
        val gate = ModelSyncGate()

        assertThat(gate.tryAcquire()).isTrue()
        assertThat(gate.tryAcquire()).isFalse()

        gate.release()

        assertThat(gate.tryAcquire()).isTrue()
    }
}
