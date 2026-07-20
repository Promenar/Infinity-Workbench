package com.promenar.nexara.ui.settings

import android.os.Build
import android.os.Debug
import android.util.Log
import android.util.SparseIntArray
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.FrameMetricsAggregator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import kotlin.math.ceil
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class ProviderModelsPerformanceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun settleGuestBackgroundWork() {
        Thread.sleep(GUEST_BACKGROUND_SETTLE_MILLIS)
    }

    @Test
    fun `500模型列表在搜索滚动切换和编辑场景下保持性能门禁`() {
        assertTrue("性能门禁仅允许固定 API 36 AVD", Build.VERSION.SDK_INT == 36)
        val models = buildModelFixture(500)
        val modelState = mutableStateOf(models)

        rule.setContent {
            NexaraTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "性能基准",
                        providerId = PROVIDER_ID,
                        isFetching = false,
                        syncNotice = null,
                        models = modelState.value,
                        modelTestStates = emptyMap(),
                    ),
                    actions = idleActions { modelId ->
                        modelState.value = modelState.value.map { model ->
                            if (model.id == modelId) model.copy(enabled = !model.enabled) else model
                        }
                    },
                    onNavigateBack = {},
                )
            }
        }
        rule.waitForIdle()

        val exerciseOnce = {
            setSearchText("")
            rule.onNodeWithTag(UiTags.PROVIDER_MODELS_LIST).performTouchInput { swipeUp() }
            setSearchText("model-250")
            rule.waitForIdle()
            rule.onNodeWithTag(UiTags.providerModelsToggleAction("$PROVIDER_ID::model-250"))
                .performClick()
            rule.onNodeWithTag(UiTags.providerModelsModelCard("$PROVIDER_ID::model-250"))
                .assertExists()
                .performClick()
            rule.onNodeWithTag(UiTags.providerModelsEditorSheet("$PROVIDER_ID::model-250"))
                .assertExists()
            rule.onNodeWithTag(UiTags.providerModelsEditorCloseAction("$PROVIDER_ID::model-250"))
                .performClick()
            setSearchText("")
            rule.waitForIdle()
        }
        val exercise = {
            repeat(CYCLES_PER_ROUND) { exerciseOnce() }
        }

        warmUpFrameMetrics(exercise)

        val baselinePssKiB = stablePssKiB()
        val snapshots = List(MEASURED_ROUNDS) { index ->
            collectRound(index + 1, exercise)
        }
        val rounds = snapshots.map(FrameMetricsSnapshot::total)
        val finalPssKiB = stablePssKiB()
        val pssDeltaKiB = (finalPssKiB - baselinePssKiB).coerceAtLeast(0L)

        val report = buildString {
            append("PROVIDER_MODELS_PERF")
            append(" device=").append(Build.MODEL)
            append(" api=").append(Build.VERSION.SDK_INT)
            append(" warmups=").append(WARMUP_ROUNDS)
            append(" measured=").append(MEASURED_ROUNDS)
            append(" cyclesPerRound=").append(CYCLES_PER_ROUND)
            append(" frames=").append(rounds.joinToString(",") { it.frameCount.toString() })
            append(" p95Ms=").append(rounds.joinToString(",") { it.p95Millis.toString() })
            append(" maxMs=").append(rounds.joinToString(",") { it.maxMillis.toString() })
            append(" baselinePssKiB=").append(baselinePssKiB)
            append(" finalPssKiB=").append(finalPssKiB)
            append(" deltaPssKiB=").append(pssDeltaKiB)
            append(" breakdown=").append(
                snapshots.joinToString("|") { it.describe() },
            )
        }
        Log.i(LOG_TAG, report)

        val variance = rounds.map(RoundMetrics::p95Millis).trimmedNormalizedRange()
        assertTrue(
            "PERF_FIXTURE_NOISE: 去极值 p95 极差/均值=$variance > $MAX_VARIANCE\n$report",
            variance <= MAX_VARIANCE,
        )
        assertTrue(
            "FRAME_GATE_FAILED: p95>$P95_GATE_MILLIS ms 或 max>$MAX_GATE_MILLIS ms\n$report",
            rounds.all { it.p95Millis <= P95_GATE_MILLIS && it.maxMillis <= MAX_GATE_MILLIS },
        )
        assertTrue(
            "PSS_GATE_FAILED: PSS 增量 $pssDeltaKiB KiB > $PSS_DELTA_GATE_KIB KiB\n$report",
            pssDeltaKiB <= PSS_DELTA_GATE_KIB,
        )
    }

    private fun collectRound(round: Int, exercise: () -> Unit): FrameMetricsSnapshot {
        prepareRound()
        settle()
        val aggregator = FrameMetricsAggregator(FrameMetricsAggregator.EVERY_DURATION)
        aggregator.add(rule.activity)
        var removed = false
        return try {
            exercise()
            rule.waitForIdle()
            Thread.sleep(FRAME_CALLBACK_SETTLE_MILLIS)
            val metrics = aggregator.remove(rule.activity)
            removed = true
            metrics.toFrameMetricsSnapshot(round)
        } finally {
            if (!removed) {
                aggregator.remove(rule.activity)
            }
            settle()
        }
    }

    private fun warmUpFrameMetrics(exercise: () -> Unit) {
        val aggregator = FrameMetricsAggregator(FrameMetricsAggregator.EVERY_DURATION)
        aggregator.add(rule.activity)
        try {
            repeat(WARMUP_ROUNDS) {
                prepareRound()
                exercise()
                settle()
            }
            rule.waitForIdle()
            Thread.sleep(FRAME_CALLBACK_SETTLE_MILLIS)
        } finally {
            aggregator.remove(rule.activity)
        }
    }

    private fun searchInput() = rule.onNode(
        matcher = hasSetTextAction() and
            (hasTestTag(UiTags.PROVIDER_MODELS_SEARCH_FIELD) or
                hasAnyAncestor(hasTestTag(UiTags.PROVIDER_MODELS_SEARCH_FIELD))),
        useUnmergedTree = true,
    )

    private fun prepareRound() {
        setSearchText("")
        rule.waitForIdle()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_LIST).performScrollToIndex(DEEP_LIST_INDEX)
        rule.waitForIdle()
    }

    private fun setSearchText(value: String) {
        searchInput().performSemanticsAction(SemanticsActions.SetText) { action ->
            action(AnnotatedString(value))
        }
    }

    private fun Array<SparseIntArray?>?.toFrameMetricsSnapshot(round: Int): FrameMetricsSnapshot =
        FrameMetricsSnapshot(
            round = round,
            metricsByIndex = FRAME_METRIC_NAMES.indices.map { index ->
                this?.getOrNull(index)
                    ?.takeIf { it.size() > 0 }
                    ?.toRoundMetrics(round)
            },
        )

    private fun SparseIntArray.toRoundMetrics(round: Int): RoundMetrics {
        val frameCount = (0 until size()).sumOf { valueAt(it) }
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

    private fun stablePssKiB(): Long = List(PSS_SAMPLES) {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        Thread.sleep(PSS_SAMPLE_INTERVAL_MILLIS)
        memoryInfo.totalPss.toLong()
    }.sorted()[PSS_SAMPLES / 2]

    private fun settle() {
        Thread.sleep(ROUND_SETTLE_MILLIS)
    }

    private fun buildModelFixture(count: Int): List<ModelInfo> = List(count) { index ->
        ModelInfo(
            id = "$PROVIDER_ID::model-$index",
            providerId = PROVIDER_ID,
            providerName = "Performance",
            name = "Model $index",
            remoteModelId = "model-$index",
            description = "fixture-$index",
            enabled = index % 3 != 0,
            type = if (index % 4 == 0) "reasoning" else "chat",
            contextLength = 128_000,
            capabilities = listOf("chat", "vision", "structuredoutput"),
        )
    }

    private fun List<Int>.trimmedNormalizedRange(): Double {
        val ordered = sorted()
        val core = if (ordered.size >= 5) ordered.drop(1).dropLast(1) else ordered
        val average = core.average()
        return if (average == 0.0) 0.0 else (core.last() - core.first()) / average
    }

    private fun idleActions(onToggle: (String) -> Unit) = ProviderModelsScreenActions(
        onRefresh = {},
        onAdd = { _, _ -> true },
        onDisableAll = {},
        onDeleteAll = {},
        onUpdate = {},
        onToggle = onToggle,
        onTest = {},
        onCancelTest = {},
        onDelete = {},
        onClearNotice = {},
    )

    private data class RoundMetrics(
        val round: Int,
        val frameCount: Int,
        val p95Millis: Int,
        val maxMillis: Int,
    )

    private data class FrameMetricsSnapshot(
        val round: Int,
        val metricsByIndex: List<RoundMetrics?>,
    ) {
        val total: RoundMetrics
            get() = checkNotNull(metricsByIndex.getOrNull(FrameMetricsAggregator.TOTAL_INDEX)) {
                "FRAME_METRICS_UNAVAILABLE: round=$round"
            }

        fun describe(): String = FRAME_METRIC_NAMES.mapIndexedNotNull { index, name ->
            metricsByIndex.getOrNull(index)?.let { metrics ->
                "$name(p95=${metrics.p95Millis},max=${metrics.maxMillis})"
            }
        }.joinToString(prefix = "r$round[", postfix = "]")
    }

    private companion object {
        const val LOG_TAG = "ProviderModelsPerf"
        const val PROVIDER_ID = "provider-performance"
        const val DEEP_LIST_INDEX = 440
        const val WARMUP_ROUNDS = 3
        const val MEASURED_ROUNDS = 5
        const val CYCLES_PER_ROUND = 10
        const val P95_GATE_MILLIS = 50
        const val MAX_GATE_MILLIS = 150
        const val PSS_DELTA_GATE_KIB = 64L * 1024L
        const val MAX_VARIANCE = 0.20
        const val FRAME_CALLBACK_SETTLE_MILLIS = 120L
        const val ROUND_SETTLE_MILLIS = 120L
        const val PSS_SAMPLE_INTERVAL_MILLIS = 100L
        const val PSS_SAMPLES = 3
        const val GUEST_BACKGROUND_SETTLE_MILLIS = 1_000L
        val FRAME_METRIC_NAMES = listOf(
            "total",
            "input",
            "layout",
            "draw",
            "sync",
            "command",
            "swap",
            "delay",
            "animation",
        )
    }
}
