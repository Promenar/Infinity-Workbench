package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.catalog.CatalogUpdatePhase
import com.promenar.infinity.data.model.catalog.CatalogUpdateStatus
import org.junit.Test

class ModelSpecLibraryTest {
    private fun downloaded(phase: CatalogUpdatePhase, date: String = "2026-09-22T03:10:00Z", records: Int = 422, version: Long = 20) =
        CatalogUpdateStatus(phase, date, records, version, cached = true)

    @Test
    fun `生成时间只取月日且格式不符时为空`() {
        assertThat(specLibraryDate("2026-09-22T03:10:00Z")).isEqualTo("09-22")
        assertThat(specLibraryDate("2026-01-05")).isEqualTo("01-05")
        assertThat(specLibraryDate("22/09/2026")).isNull()
        assertThat(specLibraryDate(null)).isNull()
    }

    @Test
    fun `未缓存下载版本时是内置快照`() {
        assertThat(CatalogUpdateStatus().specVersion()).isEqualTo(SpecLibraryVersion.Bundled)
        assertThat(downloaded(CatalogUpdatePhase.IDLE).specVersion()).isEqualTo(SpecLibraryVersion("09-22", 422))
        // 生成时间损坏时不编造日期，按内置快照显示。
        assertThat(downloaded(CatalogUpdatePhase.IDLE, date = "unknown").specVersion().bundled).isTrue()
    }

    @Test
    fun `正常与更新中不显示失败提示`() {
        val idle = specLibraryUi(downloaded(CatalogUpdatePhase.IDLE), settled = null)
        assertThat(idle).isEqualTo(SpecLibraryUi(SpecLibraryVersion("09-22", 422), updating = false, failure = null, kept = null))
        val updating = specLibraryUi(downloaded(CatalogUpdatePhase.UPDATING), settled = null)
        assertThat(updating.updating).isTrue()
        assertThat(updating.failure).isNull()
        assertThat(updating.current).isEqualTo(SpecLibraryVersion("09-22", 422))
        assertThat(specLibraryUi(CatalogUpdateStatus(CatalogUpdatePhase.CURRENT), null).current?.bundled).isTrue()
    }

    @Test
    fun `下载或签名失败沿用状态里保留的原版本`() {
        val ui = specLibraryUi(downloaded(CatalogUpdatePhase.FAILED), settled = null)
        assertThat(ui.failure).isEqualTo(SpecLibraryFailure.UPDATE)
        assertThat(ui.kept).isEqualTo(SpecLibraryVersion("09-22", 422))
        val bundled = specLibraryUi(CatalogUpdateStatus(CatalogUpdatePhase.FAILED), settled = null)
        assertThat(bundled.kept).isEqualTo(SpecLibraryVersion.Bundled)
    }

    @Test
    fun `应用失败沿用最近一次生效的版本而不是未生效的新版本`() {
        val settled = downloaded(CatalogUpdatePhase.IDLE, date = "2026-09-01T00:00:00Z", records = 400, version = 19)
        val failed = downloaded(CatalogUpdatePhase.APPLY_FAILED, date = "2026-09-22T00:00:00Z", version = 20)
        val ui = specLibraryUi(failed, settled)
        assertThat(ui.failure).isEqualTo(SpecLibraryFailure.APPLY)
        assertThat(ui.kept).isEqualTo(SpecLibraryVersion("09-01", 400))
        assertThat(ui.current).isEqualTo(SpecLibraryVersion("09-01", 400))
        // 页面没见过生效状态时如实写“沿用现有版本”。
        assertThat(specLibraryUi(failed, settled = null).kept).isNull()
        assertThat(specLibraryUi(failed, settled = CatalogUpdateStatus()).kept).isEqualTo(SpecLibraryVersion.Bundled)
    }

    @Test
    fun `最近生效状态只由空闲与已是最新刷新`() {
        val idle = downloaded(CatalogUpdatePhase.IDLE)
        assertThat(nextSettledStatus(null, idle)).isEqualTo(idle)
        assertThat(nextSettledStatus(idle, downloaded(CatalogUpdatePhase.UPDATING))).isEqualTo(idle)
        assertThat(nextSettledStatus(idle, downloaded(CatalogUpdatePhase.FAILED))).isEqualTo(idle)
        assertThat(nextSettledStatus(idle, downloaded(CatalogUpdatePhase.APPLY_FAILED, version = 21))).isEqualTo(idle)
        val current = downloaded(CatalogUpdatePhase.CURRENT, version = 21)
        assertThat(nextSettledStatus(idle, current)).isEqualTo(current)
        assertThat(nextSettledStatus(idle, null)).isEqualTo(idle)
    }

    @Test
    fun `只有更新中到已是最新才提示完成并区分是否有新版本`() {
        val updating = downloaded(CatalogUpdatePhase.UPDATING, version = 19, date = "2026-09-01T00:00:00Z")
        val newer = downloaded(CatalogUpdatePhase.CURRENT, version = 20)
        assertThat(specLibraryCompletion(updating, newer)).isEqualTo(SpecLibraryCompletion(SpecLibraryVersion("09-22", 422), changed = true))
        val same = downloaded(CatalogUpdatePhase.CURRENT, version = 19, date = "2026-09-01T00:00:00Z")
        assertThat(specLibraryCompletion(updating, same)?.changed).isFalse()
        // 从内置快照第一次下载到版本即视为有更新。
        assertThat(specLibraryCompletion(CatalogUpdateStatus(CatalogUpdatePhase.UPDATING), newer)?.changed).isTrue()
        assertThat(specLibraryCompletion(downloaded(CatalogUpdatePhase.IDLE), newer)).isNull()
        assertThat(specLibraryCompletion(updating, downloaded(CatalogUpdatePhase.FAILED))).isNull()
        assertThat(specLibraryCompletion(null, newer)).isNull()
    }
}
