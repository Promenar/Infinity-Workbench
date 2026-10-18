package com.promenar.nexara

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareIntentStagingCoordinatorTest {
    @Test
    fun `首个SEND正在staging时第二个SEND仍按FIFO各执行一次`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val coordinator = ShareIntentStagingCoordinator(
            scope = backgroundScope,
            stage = { intent, _ ->
                val label = intent.getStringExtra(LABEL)!!
                calls += label
                if (label == "first") firstGate.await()
                ShareEnqueueResult.Accepted
            },
            awaitCapacity = {},
        )
        val outcomes = mutableListOf<ShareStageOutcome>()
        backgroundScope.launch { coordinator.outcomes.take(2).toList(outcomes) }
        runCurrent()

        coordinator.submit(share("first"), ShareDelivery.Fresh)
        runCurrent()
        coordinator.submit(share("second"), ShareDelivery.Fresh)
        runCurrent()
        firstGate.complete(Unit)
        runCurrent()

        assertThat(calls).containsExactly("first", "second").inOrder()
        assertThat(outcomes.map { it.result })
            .containsExactly(ShareEnqueueResult.Accepted, ShareEnqueueResult.Accepted).inOrder()
        assertThat(outcomes.map { it.submissionId }.distinct()).hasSize(2)
    }

    @Test
    fun `同一未完成Intent重放只复用submission且不重复stage`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = ShareIntentStagingCoordinator(
            scope = backgroundScope,
            stage = { _, _ ->
                calls++
                gate.await()
                ShareEnqueueResult.Accepted
            },
            awaitCapacity = {},
        )
        val candidate = share("same")

        val firstId = coordinator.submit(candidate, ShareDelivery.Fresh)
        runCurrent()
        val replayId = coordinator.submit(Intent(candidate), ShareDelivery.Redelivered)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertThat(replayId).isEqualTo(firstId)
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `容量满时保留同一submission等待释放后重试`() = runTest {
        val results = ArrayDeque(
            listOf(ShareEnqueueResult.RejectedCapacity, ShareEnqueueResult.Accepted)
        )
        val capacityReleased = CompletableDeferred<Unit>()
        var stageCalls = 0
        var capacityWaits = 0
        val coordinator = ShareIntentStagingCoordinator(
            scope = backgroundScope,
            stage = { _, _ -> stageCalls++; results.removeFirst() },
            awaitCapacity = {
                capacityWaits++
                capacityReleased.await()
            },
        )
        val outcomes = mutableListOf<ShareStageOutcome>()
        backgroundScope.launch { coordinator.outcomes.take(2).toList(outcomes) }
        runCurrent()

        val id = coordinator.submit(share("capacity"), ShareDelivery.Fresh)
        runCurrent()
        assertThat(stageCalls).isEqualTo(1)
        assertThat(capacityWaits).isEqualTo(1)
        capacityReleased.complete(Unit)
        runCurrent()

        assertThat(stageCalls).isEqualTo(2)
        assertThat(capacityWaits).isEqualTo(1)
        assertThat(outcomes.map { it.result }).containsExactly(
            ShareEnqueueResult.RejectedCapacity,
            ShareEnqueueResult.Accepted,
        ).inOrder()
        assertThat(outcomes.map { it.submissionId }).containsExactly(id, id)
    }

    @Test
    fun `Activity尚未收集时所有完成结果仍逐个保留`() = runTest {
        val coordinator = ShareIntentStagingCoordinator(
            scope = backgroundScope,
            stage = { _, _ -> ShareEnqueueResult.Accepted },
            awaitCapacity = {},
        )

        val firstId = coordinator.submit(share("first"), ShareDelivery.Fresh)
        runCurrent()
        val secondId = coordinator.submit(share("second"), ShareDelivery.Fresh)
        runCurrent()
        val outcomes = mutableListOf<ShareStageOutcome>()
        backgroundScope.launch { coordinator.outcomes.take(2).toList(outcomes) }
        runCurrent()

        assertThat(outcomes.map { it.submissionId }).containsExactly(firstId, secondId).inOrder()
    }

    @Test
    fun `完成早于collector时配置重建复用Accepted结果直到显式确认`() = runTest {
        var stageCalls = 0
        val coordinator = ShareIntentStagingCoordinator(
            scope = backgroundScope,
            stage = { _, _ -> stageCalls++; ShareEnqueueResult.Accepted },
            awaitCapacity = {},
        )
        val candidate = share("accepted-before-collector")

        val firstId = coordinator.submit(candidate, ShareDelivery.Fresh)
        runCurrent()
        val recreatedId = coordinator.submit(Intent(candidate), ShareDelivery.Redelivered)
        runCurrent()
        val outcome = mutableListOf<ShareStageOutcome>()
        backgroundScope.launch { coordinator.outcomes.take(1).toList(outcome) }
        runCurrent()

        assertThat(recreatedId).isEqualTo(firstId)
        assertThat(stageCalls).isEqualTo(1)
        coordinator.acknowledge(outcome.single().submissionId)
        val nextId = coordinator.submit(Intent(candidate), ShareDelivery.Redelivered)
        runCurrent()
        assertThat(nextId).isNotEqualTo(firstId)
        assertThat(stageCalls).isEqualTo(2)
    }

    @Test
    fun `完成早于collector时配置重建复用Invalid结果且不重复通知`() = runTest {
        var stageCalls = 0
        val coordinator = ShareIntentStagingCoordinator(
            scope = backgroundScope,
            stage = { _, _ -> stageCalls++; ShareEnqueueResult.RejectedInvalid },
            awaitCapacity = {},
        )
        val candidate = share("invalid-before-collector")

        val firstId = coordinator.submit(candidate, ShareDelivery.Fresh)
        runCurrent()
        val recreatedId = coordinator.submit(Intent(candidate), ShareDelivery.Redelivered)
        runCurrent()
        val outcomes = mutableListOf<ShareStageOutcome>()
        backgroundScope.launch { coordinator.outcomes.take(1).toList(outcomes) }
        runCurrent()

        assertThat(recreatedId).isEqualTo(firstId)
        assertThat(stageCalls).isEqualTo(1)
        assertThat(outcomes.single().result).isEqualTo(ShareEnqueueResult.RejectedInvalid)
        coordinator.acknowledge(firstId)
    }

    @Test
    fun `投递来源随submission传给stage并回到结果`() = runTest {
        val seen = mutableListOf<ShareDelivery>()
        val coordinator = ShareIntentStagingCoordinator(
            scope = backgroundScope,
            stage = { _, delivery -> seen += delivery; ShareEnqueueResult.Duplicate },
            awaitCapacity = {},
        )
        val outcomes = mutableListOf<ShareStageOutcome>()
        backgroundScope.launch { coordinator.outcomes.take(2).toList(outcomes) }
        runCurrent()

        coordinator.submit(share("fresh"), ShareDelivery.Fresh)
        coordinator.submit(share("replayed"), ShareDelivery.Redelivered)
        runCurrent()

        assertThat(seen).containsExactly(ShareDelivery.Fresh, ShareDelivery.Redelivered).inOrder()
        assertThat(outcomes.map { it.delivery })
            .containsExactly(ShareDelivery.Fresh, ShareDelivery.Redelivered).inOrder()
    }

    @Test
    fun `仅无保存状态且非最近任务重启的onCreate与onNewIntent视为新分享`() {
        assertThat(ShareDelivery.of(restoredInstanceState = false, intentFlags = 0)).isEqualTo(ShareDelivery.Fresh)
        assertThat(ShareDelivery.of(restoredInstanceState = true, intentFlags = 0))
            .isEqualTo(ShareDelivery.Redelivered)
        assertThat(
            ShareDelivery.of(
                restoredInstanceState = false,
                intentFlags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        ).isEqualTo(ShareDelivery.Redelivered)
    }

    private fun share(label: String) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, Uri.parse("content://fixture/$label.txt"))
        putExtra(LABEL, label)
    }

    private companion object {
        const val LABEL = "test-label"
    }
}
