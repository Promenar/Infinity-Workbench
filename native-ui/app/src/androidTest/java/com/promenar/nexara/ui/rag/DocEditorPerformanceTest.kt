package com.promenar.nexara.ui.rag

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Debug
import android.util.Log
import android.util.SparseIntArray
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.FrameMetricsAggregator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import kotlin.math.ceil
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class DocEditorPerformanceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var screenState: MutableState<DocEditorScreenState>
    private lateinit var contentChangeAction: (String) -> Unit
    private var editableRoundSequence: Int = 0

    @After
    fun restoreOrientation() {
        rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @Test
    fun tenThousandLinesStayInsideFrameAndPssGate() {
        benchmark(
            dataset = "10000-lines",
            initialContent = buildString {
                repeat(10_000) { index ->
                    if (index > 0) append('\n')
                    append("line-")
                    append(index + 1)
                    append(" markdown benchmark payload")
                }
            },
        )
    }

    @Test
    fun nearOneMiBMarkdownStayInsideFrameAndPssGate() {
        benchmark(
            dataset = "near-1MiB-markdown",
            initialContent = buildNearOneMiBMarkdown(),
        )
    }

    @Test
    fun nearOneMiBSingleLineStayInsideFrameAndPssGate() {
        benchmark(
            dataset = "near-1MiB-single-line",
            initialContent = "x".repeat(NEAR_ONE_MIB_CHARS),
        )
    }

    @Test
    fun editableContentLengthBoundaryStayInsideFrameAndPssGate() {
        val initialContent = buildLengthBoundaryContent(
            MAX_EDITABLE_CONTENT_LENGTH - CONTENT_APPENDS_PER_ROUND,
        )
        check(initialContent.length == MAX_EDITABLE_CONTENT_LENGTH - CONTENT_APPENDS_PER_ROUND)
        benchmarkEditable("editable-content-length-boundary", initialContent)
    }

    @Test
    fun editableLineCountBoundaryStayInsideFrameAndPssGate() {
        val initialContent = buildString {
            repeat(MAX_EDITABLE_LINE_COUNT - 1) { append("x\n") }
            append("tail")
        }
        check(analyzeDocEditorText(initialContent).lineCount == MAX_EDITABLE_LINE_COUNT)
        benchmarkEditable("editable-line-count-boundary", initialContent)
    }

    @Test
    fun editableLogicalLineBoundaryStayInsideFrameAndPssGate() {
        val initialContent = "x".repeat(
            MAX_EDITABLE_LOGICAL_LINE_LENGTH - CONTENT_APPENDS_PER_ROUND,
        )
        benchmarkEditable("editable-logical-line-boundary", initialContent)
    }

    private fun benchmark(dataset: String, initialContent: String) {
        require(initialContent.length <= 1024 * 1024)
        prepareLandscapeSurface(initialContent)
        assertPerformanceProtectedSurface()

        repeat(WARMUP_ROUNDS) {
            exerciseRound(initialContent)
        }

        val baselinePssKb = stablePssKb()
        val rounds = buildList {
            repeat(SAMPLE_ROUNDS) { round ->
                settleManagedRuntimeBeforeRound()
                val aggregator = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
                aggregator.add(rule.activity)
                var histogram: SparseIntArray? = null
                try {
                    exerciseRound(initialContent)
                    rule.waitForIdle()
                    Thread.sleep(FRAME_CALLBACK_SETTLE_MILLIS)
                } finally {
                    histogram = aggregator.remove(rule.activity)
                        ?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX)
                        ?: SparseIntArray()
                }
                add(checkNotNull(histogram).toRoundMetrics(round + 1))
            }
        }
        val finalPssKb = stablePssKb()
        val pssDeltaKb = (finalPssKb - baselinePssKb).coerceAtLeast(0L)
        val report = buildReport(
            dataset = dataset,
            contentLength = initialContent.length,
            rounds = rounds,
            baselinePssKb = baselinePssKb,
            finalPssKb = finalPssKb,
            pssDeltaKb = pssDeltaKb,
        )
        Log.i(LOG_TAG, report)
        println(report)

        val p95Values = rounds.map { it.p95Millis }
        val normalizedRange = p95Values.trimmedNormalizedRange()
        assertTrue(
            "PERF_FIXTURE_NOISE: 连续轮去极值 p95 极差/均值=${"%.3f".format(normalizedRange)} > 0.20\n$report",
            normalizedRange <= MAX_NORMALIZED_ROUND_VARIANCE,
        )
        assertTrue(
            "FRAME_GATE_FAILED: p95>${P95_GATE_MILLIS}ms 或 max>${MAX_GATE_MILLIS}ms\n$report",
            rounds.all {
                it.p95Millis <= P95_GATE_MILLIS && it.maxMillis <= MAX_GATE_MILLIS
            },
        )
        assertTrue(
            "PSS_GATE_FAILED: 稳定 PSS 增量 ${pssDeltaKb}KiB > ${PSS_DELTA_GATE_KIB}KiB\n$report",
            pssDeltaKb <= PSS_DELTA_GATE_KIB,
        )
    }

    private fun benchmarkEditable(dataset: String, initialContent: String) {
        val initialStatistics = analyzeDocEditorText(initialContent)
        require(contentAccessFor(initialContent.length, initialStatistics) == DocEditorContentAccess.Editable)
        prepareLandscapeSurface(initialContent)
        assertEditableSurface()

        repeat(WARMUP_ROUNDS) {
            exerciseEditableRound(initialContent)
        }

        val baselinePssKb = stablePssKb()
        val rounds = buildList {
            repeat(SAMPLE_ROUNDS) { round ->
                settleManagedRuntimeBeforeRound()
                val aggregator = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
                aggregator.add(rule.activity)
                var histogram: SparseIntArray? = null
                try {
                    exerciseEditableRound(initialContent)
                    rule.waitForIdle()
                    Thread.sleep(FRAME_CALLBACK_SETTLE_MILLIS)
                } finally {
                    histogram = aggregator.remove(rule.activity)
                        ?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX)
                        ?: SparseIntArray()
                }
                add(checkNotNull(histogram).toRoundMetrics(round + 1))
            }
        }
        val finalPssKb = stablePssKb()
        val pssDeltaKb = (finalPssKb - baselinePssKb).coerceAtLeast(0L)
        val report = buildReport(
            dataset = dataset,
            contentLength = initialContent.length,
            rounds = rounds,
            baselinePssKb = baselinePssKb,
            finalPssKb = finalPssKb,
            pssDeltaKb = pssDeltaKb,
        )
        Log.i(LOG_TAG, report)
        println(report)

        val p95Values = rounds.map { it.p95Millis }
        val normalizedRange = p95Values.trimmedNormalizedRange()
        assertTrue(
            "PERF_FIXTURE_NOISE: 连续轮去极值 p95 极差/均值=${"%.3f".format(normalizedRange)} > 0.20\n$report",
            normalizedRange <= MAX_NORMALIZED_ROUND_VARIANCE,
        )
        assertTrue(
            "FRAME_GATE_FAILED: p95>${P95_GATE_MILLIS}ms 或 max>${MAX_GATE_MILLIS}ms\n$report",
            rounds.all {
                it.p95Millis <= P95_GATE_MILLIS && it.maxMillis <= MAX_GATE_MILLIS
            },
        )
        assertTrue(
            "PSS_GATE_FAILED: 稳定 PSS 增量 ${pssDeltaKb}KiB > ${PSS_DELTA_GATE_KIB}KiB\n$report",
            pssDeltaKb <= PSS_DELTA_GATE_KIB,
        )
    }

    private fun prepareLandscapeSurface(initialContent: String) {
        rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        rule.waitUntil(ORIENTATION_TIMEOUT_MILLIS) {
            rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        val statistics = analyzeDocEditorText(initialContent)
        contentChangeAction = { content -> applyEditableContentChange(content) }
        screenState = mutableStateOf(
            DocEditorScreenState(
                editorState = DocEditorUiState(
                    phase = DocEditorPhase.Ready,
                    workspaceRootUuid = "perf-root",
                    documentId = "perf-doc",
                    documentEpoch = 1L,
                    title = "performance.md",
                    content = initialContent,
                    persistedTitle = "performance.md",
                    persistedContent = initialContent,
                    currentHash = "perf-hash",
                    totalLines = statistics.lineCount,
                    wordCount = statistics.wordCount,
                    contentAccess = contentAccessFor(initialContent.length, statistics),
                    lastModified = 1L,
                    sizeBytes = initialContent.length.toLong(),
                    hasLoadedDocument = true,
                ),
            ),
        )
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                DocEditorScreenContent(
                    state = screenState.value,
                    actions = DocEditorScreenActions(
                        // 大文本 IME 注入在仿真器上易受输入法影响；此回调与真实 onValueChange 共用语义。
                        onContentChange = contentChangeAction,
                        onViewModeChange = { mode ->
                            screenState.value = screenState.value.copy(viewMode = mode)
                        },
                    ),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun exerciseRound(initialContent: String) {
        rule.runOnIdle {
            check(screenState.value.editorState.content === initialContent)
            screenState.value = screenState.value.copy(viewMode = DocEditorViewMode.EDIT)
        }
        rule.waitForIdle()

        repeat(BACKING_RECOMPOSITIONS_PER_ROUND) {
            rule.runOnIdle {
                val editor = screenState.value.editorState
                screenState.value = screenState.value.copy(
                    editorState = editor.copy(
                        title = if (it % 2 == 0) {
                            VISIBLE_TITLE_A
                        } else {
                            VISIBLE_TITLE_B
                        },
                    ),
                )
            }
            rule.waitForIdle()
        }

        repeat(MODE_SWITCHES_PER_ROUND) { index ->
            val mode = MODE_SEQUENCE[index % MODE_SEQUENCE.size]
            rule.runOnIdle {
                screenState.value = screenState.value.copy(viewMode = mode)
            }
            rule.waitForIdle()
        }
        assertPerformanceProtectedSurface()
    }

    private fun exerciseEditableRound(initialContent: String) {
        val appendedCharacter = if (editableRoundSequence++ % 2 == 0) "x" else "y"
        val initialStatistics = analyzeDocEditorText(initialContent)
        rule.runOnIdle {
            val editor = screenState.value.editorState
            screenState.value = screenState.value.copy(
                editorState = editor.copy(
                    content = initialContent,
                    persistedContent = initialContent,
                    totalLines = initialStatistics.lineCount,
                    wordCount = initialStatistics.wordCount,
                    contentAccess = DocEditorContentAccess.Editable,
                    contentDirty = false,
                ),
                viewMode = DocEditorViewMode.EDIT,
            )
        }
        rule.waitForIdle()

        repeat(CONTENT_APPENDS_PER_ROUND) {
            rule.runOnIdle {
                contentChangeAction(screenState.value.editorState.content + appendedCharacter)
            }
            rule.waitForIdle()
        }
        rule.runOnIdle {
            val editor = screenState.value.editorState
            check(editor.content.length == initialContent.length + CONTENT_APPENDS_PER_ROUND)
            check(editor.contentAccess == DocEditorContentAccess.Editable)
        }

        repeat(MODE_SWITCHES_PER_ROUND) { index ->
            val mode = MODE_SEQUENCE[index % MODE_SEQUENCE.size]
            rule.onNodeWithTag(mode.testTag).performClick()
            rule.waitForIdle()
        }
        assertEditableSurface()
    }

    private fun applyEditableContentChange(content: String) {
        val currentState = screenState.value
        val editor = currentState.editorState
        check(editor.contentAccess == DocEditorContentAccess.Editable)
        val statistics = analyzeDocEditorText(content)
        screenState.value = currentState.copy(
            editorState = editor.copy(
                content = content,
                totalLines = statistics.lineCount,
                wordCount = statistics.wordCount,
                contentAccess = contentAccessFor(content.length, statistics),
                contentDirty = content != editor.persistedContent,
            ),
        )
    }

    private fun assertEditableSurface() {
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_PERFORMANCE_PROTECTED).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_EDIT).assertExists()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_PREVIEW).assertExists()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT).assertExists()
    }

    private fun assertPerformanceProtectedSurface() {
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_PERFORMANCE_PROTECTED).assertExists()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_EDIT).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_PREVIEW).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT).assertDoesNotExist()
    }

    private val DocEditorViewMode.testTag: String
        get() = when (this) {
            DocEditorViewMode.EDIT -> UiTags.DOC_EDITOR_MODE_EDIT
            DocEditorViewMode.PREVIEW -> UiTags.DOC_EDITOR_MODE_PREVIEW
            DocEditorViewMode.SPLIT -> UiTags.DOC_EDITOR_MODE_SPLIT
        }

    private fun stablePssKb(): Long {
        Runtime.getRuntime().gc()
        System.runFinalization()
        Thread.sleep(PSS_SETTLE_MILLIS)
        return buildList {
            repeat(PSS_SAMPLES) {
                val memoryInfo = Debug.MemoryInfo()
                Debug.getMemoryInfo(memoryInfo)
                add(memoryInfo.totalPss.toLong())
                Thread.sleep(PSS_SAMPLE_INTERVAL_MILLIS)
            }
        }.sorted()[PSS_SAMPLES / 2]
    }

    private fun settleManagedRuntimeBeforeRound() {
        Runtime.getRuntime().gc()
        System.runFinalization()
        Thread.sleep(PRE_ROUND_SETTLE_MILLIS)
    }

    private fun SparseIntArray.toRoundMetrics(round: Int): RoundMetrics {
        val frameCount = (0 until size()).sumOf { valueAt(it) }
        check(frameCount > 0) {
            "FRAME_METRICS_UNAVAILABLE: round=$round，FrameMetricsAggregator 未返回总帧数据"
        }
        val p95Target = ceil(frameCount * 0.95).toInt().coerceAtLeast(1)
        var cumulative = 0
        var p95Millis = 0
        for (index in 0 until size()) {
            cumulative += valueAt(index)
            if (cumulative >= p95Target) {
                p95Millis = keyAt(index)
                break
            }
        }
        return RoundMetrics(
            round = round,
            frameCount = frameCount,
            p95Millis = p95Millis,
            maxMillis = keyAt(size() - 1),
        )
    }

    private fun List<Int>.trimmedNormalizedRange(): Double {
        val ordered = sorted()
        val stableCore = if (ordered.size >= 5) ordered.drop(1).dropLast(1) else ordered
        val average = stableCore.average()
        return if (average == 0.0) {
            0.0
        } else {
            (stableCore.max() - stableCore.min()) / average
        }
    }

    private fun buildReport(
        dataset: String,
        contentLength: Int,
        rounds: List<RoundMetrics>,
        baselinePssKb: Long,
        finalPssKb: Long,
        pssDeltaKb: Long,
    ): String = buildString {
        append("DOC_EDITOR_PERF dataset=")
        append(dataset)
        append(" chars=")
        append(contentLength)
        append(" device=")
        append(Build.MODEL)
        append(" api=")
        append(Build.VERSION.SDK_INT)
        append(" densityDpi=")
        append(rule.activity.resources.displayMetrics.densityDpi)
        append(" cores=")
        append(Runtime.getRuntime().availableProcessors())
        append(" warmups=")
        append(WARMUP_ROUNDS)
        append(" samples=")
        append(SAMPLE_ROUNDS)
        append(" rounds=")
        append(rounds.joinToString(prefix = "[", postfix = "]"))
        append(" baselinePssKiB=")
        append(baselinePssKb)
        append(" finalPssKiB=")
        append(finalPssKb)
        append(" deltaPssKiB=")
        append(pssDeltaKb)
    }

    private fun buildNearOneMiBMarkdown(): String {
        val unit = "## Benchmark paragraph\nMaterial 3 editor preview payload with **markdown**.\n\n"
        return buildString(NEAR_ONE_MIB_CHARS) {
            while (length + unit.length <= NEAR_ONE_MIB_CHARS) append(unit)
            append(unit, 0, NEAR_ONE_MIB_CHARS - length)
        }
    }

    private fun buildLengthBoundaryContent(targetLength: Int): String = buildString(targetLength) {
        val unit = "x".repeat(63) + '\n'
        repeat(targetLength / unit.length) { append(unit) }
        append("x".repeat(targetLength - length))
    }

    private data class RoundMetrics(
        val round: Int,
        val frameCount: Int,
        val p95Millis: Int,
        val maxMillis: Int,
    )

    private companion object {
        const val LOG_TAG = "DocEditorPerf"
        const val VISIBLE_TITLE_A = "performance-a.md"
        const val VISIBLE_TITLE_B = "performance-b.md"
        const val WARMUP_ROUNDS = 3
        const val SAMPLE_ROUNDS = 5
        const val BACKING_RECOMPOSITIONS_PER_ROUND = 30
        const val CONTENT_APPENDS_PER_ROUND = 30
        const val MODE_SWITCHES_PER_ROUND = 10
        const val NEAR_ONE_MIB_CHARS = 1024 * 1024 - 8192
        const val P95_GATE_MILLIS = 50
        const val MAX_GATE_MILLIS = 150
        const val PSS_DELTA_GATE_KIB = 64L * 1024L
        const val MAX_NORMALIZED_ROUND_VARIANCE = 0.20
        const val ORIENTATION_TIMEOUT_MILLIS = 10_000L
        const val FRAME_CALLBACK_SETTLE_MILLIS = 100L
        const val PSS_SETTLE_MILLIS = 250L
        const val PSS_SAMPLE_INTERVAL_MILLIS = 100L
        const val PRE_ROUND_SETTLE_MILLIS = 100L
        const val PSS_SAMPLES = 3
        val MODE_SEQUENCE = listOf(
            DocEditorViewMode.SPLIT,
            DocEditorViewMode.PREVIEW,
            DocEditorViewMode.EDIT,
        )
    }
}
