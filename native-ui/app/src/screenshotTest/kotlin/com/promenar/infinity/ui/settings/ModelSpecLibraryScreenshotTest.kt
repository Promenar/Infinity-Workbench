package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.catalog.CatalogUpdatePhase
import com.promenar.infinity.data.model.catalog.CatalogUpdateStatus
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 模型管理页的规格库：摘要下方的更新失败提示，以及 ⋮ 菜单里“更新规格库”的常态、更新中与内置快照三种样子。
 * 同步时间取固定的过去日期，相对时间落到“具体日期”档，截图稳定。
 */

@Composable
private fun SpecLibraryTheme(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

private val SpecPreviewModels = listOf(
    ModelInfo(id = "deepseek::deepseek-chat", name = "DeepSeek V4", description = "", providerId = "deepseek", remoteModelId = "deepseek-chat", enabled = true),
    ModelInfo(id = "deepseek::deepseek-reasoner", name = "DeepSeek V4 Reasoner", description = "", providerId = "deepseek", remoteModelId = "deepseek-reasoner", enabled = false),
)

@Composable
private fun SpecLibraryFailurePage(status: CatalogUpdateStatus) {
    ProviderModelsScreenContent(
        state = ProviderModelsScreenState(
            providerName = "DeepSeek",
            providerId = "deepseek",
            isFetching = false,
            syncNotice = null,
            models = SpecPreviewModels,
            modelTestStates = emptyMap(),
            catalogStatus = status,
            lastSyncAtMillis = 1_767_225_600_000L,
        ),
        actions = ProviderModelsScreenActions({}, { _, _ -> true }, {}, {}, {}, {}, {}, {}, {}, {}),
        onNavigateBack = {},
    )
}

@PreviewTest
@Preview(name = "规格库 下载失败 深色中文 411", widthDp = 411, heightDp = 600, locale = "zh-rCN")
@Composable
fun specLibraryUpdateFailedDarkPreview() = SpecLibraryTheme(dark = true) {
    SpecLibraryFailurePage(CatalogUpdateStatus(CatalogUpdatePhase.FAILED, "2026-09-22T00:00:00Z", 1204, 20, cached = true))
}

@PreviewTest
@Preview(name = "规格库 应用失败 浅色英文 360 大字号", widthDp = 360, heightDp = 640, locale = "en", fontScale = 1.3f)
@Composable
fun specLibraryApplyFailedLightPreview() = SpecLibraryTheme(dark = false) {
    SpecLibraryFailurePage(CatalogUpdateStatus(CatalogUpdatePhase.APPLY_FAILED, "2026-09-22T00:00:00Z", 1204, 20, cached = true))
}

@Composable
private fun SpecLibraryMenuItems() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(16.dp).width(280.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            ModelSpecLibraryMenuItem(SpecLibraryUi(SpecLibraryVersion("09-22", 1204), updating = false, failure = null, kept = null), onUpdate = {})
            InfinityMenuDivider()
            ModelSpecLibraryMenuItem(SpecLibraryUi(SpecLibraryVersion("09-22", 1204), updating = true, failure = null, kept = null), onUpdate = {})
            InfinityMenuDivider()
            ModelSpecLibraryMenuItem(SpecLibraryUi(SpecLibraryVersion.Bundled, updating = false, failure = null, kept = null), onUpdate = {})
        }
    }
}

@PreviewTest
@Preview(name = "规格库菜单项 深色中文", widthDp = 320, heightDp = 280, locale = "zh-rCN")
@Composable
fun specLibraryMenuItemDarkPreview() = SpecLibraryTheme(dark = true) { SpecLibraryMenuItems() }

@PreviewTest
@Preview(name = "规格库菜单项 浅色英文 大字号", widthDp = 320, heightDp = 340, locale = "en", fontScale = 1.3f)
@Composable
fun specLibraryMenuItemLightPreview() = SpecLibraryTheme(dark = false) { SpecLibraryMenuItems() }
