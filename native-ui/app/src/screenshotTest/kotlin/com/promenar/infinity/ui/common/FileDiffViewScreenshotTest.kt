package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.common.diff.FileDiffView
import com.promenar.infinity.ui.common.diff.FileDiffViewMode
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private val DiffOldText = """
    package com.example.sync

    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext

    class SyncWorker(private val api: SyncApi) {
        suspend fun run(): Result = withContext(Dispatchers.IO) {
            val items = api.fetch()
            items.forEach { save(it) }
            Result.success()
        }

        private fun save(item: Item) {
            database.insert(item)
        }

        private fun log(message: String) {
            println(message)
        }
    }
""".trimIndent() + "\n"

private val DiffNewText = """
    package com.example.sync

    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext

    class SyncWorker(private val api: SyncApi, private val clock: Clock) {
        suspend fun run(): Result = withContext(Dispatchers.IO) {
            val items = api.fetch(since = clock.lastSyncedAt(), pageSize = DEFAULT_PAGE_SIZE, includeDeleted = true)
            // 同步前先去重，避免重复写入数据库
            items.distinctBy { it.id }.forEach { save(it) }
            Result.success()
        }

        private fun save(item: Item) {
            database.insert(item)
        }

        private fun log(message: String) {
            logger.info(message)
        }
    }
""".trimIndent() + "\n"

private val FoldOldText = (1..60).joinToString("\n") { "val setting$it = load(\"key.$it\")" } + "\n"
private val FoldNewText = FoldOldText
    .replace("val setting8 = load(\"key.8\")", "val setting8 = load(\"key.8\", fallback = true)")
    .replace("val setting30 = load(\"key.30\")\n", "")
    .replace("val setting52 = load(\"key.52\")", "val setting52 = loadSecure(\"key.52\")")

@Composable
private fun DiffPreviewTheme(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@PreviewTest
@Preview(name = "差异统一 深中360", widthDp = 360, heightDp = 700, locale = "zh-rCN")
@Composable
fun fileDiffUnifiedDarkPreview() = DiffPreviewTheme(InfinityThemeMode.DARK) {
    FileDiffView(oldText = DiffOldText, newText = DiffNewText, fileName = "app/src/main/java/com/example/sync/SyncWorker.kt")
}

@PreviewTest
@Preview(name = "差异统一 浅中360", widthDp = 360, heightDp = 700, locale = "zh-rCN")
@Composable
fun fileDiffUnifiedLightPreview() = DiffPreviewTheme(InfinityThemeMode.LIGHT) {
    FileDiffView(oldText = DiffOldText, newText = DiffNewText, fileName = "app/src/main/java/com/example/sync/SyncWorker.kt")
}

@PreviewTest
@Preview(name = "差异并排 深中840", widthDp = 840, heightDp = 640, locale = "zh-rCN")
@Composable
fun fileDiffSideBySidePreview() = DiffPreviewTheme(InfinityThemeMode.DARK) {
    FileDiffView(oldText = DiffOldText, newText = DiffNewText, fileName = "SyncWorker.kt")
}

@PreviewTest
@Preview(name = "差异并排 浅英840", widthDp = 840, heightDp = 640, locale = "en")
@Composable
fun fileDiffSideBySideLightEnglishPreview() = DiffPreviewTheme(InfinityThemeMode.LIGHT) {
    FileDiffView(oldText = DiffOldText, newText = DiffNewText, fileName = "SyncWorker.kt")
}

@PreviewTest
@Preview(name = "差异并排 窄屏手动 浅中411", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable
fun fileDiffSideBySideNarrowPreview() = DiffPreviewTheme(InfinityThemeMode.LIGHT) {
    FileDiffView(
        oldText = "a\nb\nc\nd\n",
        newText = "a\nB 修改后的第二行\nc\nX\nd",
        fileName = "notes.md",
        initialMode = FileDiffViewMode.SIDE_BY_SIDE,
    )
}

@PreviewTest
@Preview(name = "差异折叠区段 深中411", widthDp = 411, heightDp = 820, locale = "zh-rCN")
@Composable
fun fileDiffFoldedSegmentsPreview() = DiffPreviewTheme(InfinityThemeMode.DARK) {
    FileDiffView(oldText = FoldOldText, newText = FoldNewText, fileName = "config/Settings.kt", maxViewportHeight = 720.dp)
}

@PreviewTest
@Preview(name = "差异新建删除与降级 深中360", widthDp = 360, heightDp = 760, locale = "zh-rCN")
@Composable
fun fileDiffKindsAndFallbacksPreview() = DiffPreviewTheme(InfinityThemeMode.DARK) {
    FileDiffView(oldText = "", newText = "# 标题\n\n第一段内容\n", fileName = "docs/新建说明.md")
    FileDiffView(oldText = "obsolete = true\nlegacy = 1\n", newText = "", fileName = "legacy.properties")
    FileDiffView(oldText = "", newText = "", fileName = "assets/logo.png", isBinary = true)
    FileDiffView(
        oldText = (1..5_001).joinToString("\n") { "row $it" },
        newText = (1..5_001).joinToString("\n") { if (it % 1_000 == 0) "row $it changed" else "row $it" } + "\nappended",
        fileName = "data/export.csv",
    )
}

@PreviewTest
@Preview(name = "差异统一 大字体 深中360", widthDp = 360, heightDp = 900, locale = "zh-rCN", fontScale = 2f)
@Composable
fun fileDiffLargeFontPreview() = DiffPreviewTheme(InfinityThemeMode.DARK) {
    FileDiffView(oldText = DiffOldText, newText = DiffNewText, fileName = "app/src/main/java/com/example/sync/SyncWorker.kt")
}
