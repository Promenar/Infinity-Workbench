package com.promenar.nexara

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareIntentQueueTest {
    @Test
    fun `saved state contains consumed fingerprints and counts but never pending uri`() {
        val queue = ShareIntentQueue()
        queue.enqueue(share("content://private/pending"))
        val state = Bundle()

        queue.saveConsumedState(state)

        val hashes = state.getStringArrayList(ShareIntentQueue.STATE_CONSUMED_FINGERPRINTS).orEmpty()
        val pendingCount = state.getInt(ShareIntentQueue.STATE_PENDING_COUNT)
        val serializedValues = "$hashes|$pendingCount"
        assertThat(serializedValues).doesNotContain("content://")
        assertThat(serializedValues).doesNotContain("private")
        assertThat(state.keySet()).containsExactly(
            ShareIntentQueue.STATE_CONSUMED_FINGERPRINTS,
            ShareIntentQueue.STATE_PENDING_COUNT,
        )
    }

    @Test
    fun `view model retains pending across rotation store reuse`() {
        val store = ViewModelStore()
        val provider = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
        val first = provider[ShareIntentViewModel::class.java]
        first.queue.enqueue(share("content://docs/rotation"))

        val afterRotation = provider[ShareIntentViewModel::class.java]
        val consumed = mutableListOf<List<Uri>>()
        afterRotation.queue.consumeAll { consumed += it.uris }

        assertThat(afterRotation).isSameInstanceAs(first)
        assertThat(consumed).containsExactly(listOf(Uri.parse("content://docs/rotation")))
    }

    @Test
    fun `process death restores consumed only and drops pending fail closed`() {
        val beforeDeath = ShareIntentViewModel()
        val consumedIntent = share("content://docs/consumed")
        beforeDeath.queue.enqueue(consumedIntent)
        beforeDeath.queue.consumeAll { }
        beforeDeath.queue.enqueue(share("content://docs/pending"))
        val state = Bundle()
        beforeDeath.queue.saveConsumedState(state)

        val afterDeath = ShareIntentViewModel()
        afterDeath.queue.restoreConsumedState(state)

        assertThat(afterDeath.queue.pendingCount).isEqualTo(0)
        assertThat(afterDeath.queue.enqueue(consumedIntent)).isEqualTo(ShareEnqueueResult.Duplicate)
        assertThat(afterDeath.queue.enqueue(share("content://docs/pending")))
            .isEqualTo(ShareEnqueueResult.Accepted)
    }

    @Test
    fun `saved consumed fingerprint metadata is capped at thirty two hashes`() {
        val queue = ShareIntentQueue()
        repeat(40) { index ->
            queue.enqueue(share("content://docs/consumed-$index"))
            queue.consumeAll { }
        }
        val state = Bundle()

        queue.saveConsumedState(state)

        val hashes = state.getStringArrayList(ShareIntentQueue.STATE_CONSUMED_FINGERPRINTS).orEmpty()
        assertThat(hashes).hasSize(ShareIntentQueue.MAX_CONSUMED)
        assertThat(hashes.all { it.matches(Regex("[0-9a-f]{64}")) }).isTrue()
    }

    @Test
    fun `extra then clip uri order is stable and mirrored values are deduplicated`() {
        val intent = share("content://docs/extra").apply {
            clipData = ClipData.newRawUri("shared", Uri.parse("content://docs/extra")).also {
                it.addItem(ClipData.Item(Uri.parse("content://docs/clip")))
            }
        }
        val queue = ShareIntentQueue()
        val consumed = mutableListOf<List<Uri>>()

        assertThat(queue.enqueue(intent)).isEqualTo(ShareEnqueueResult.Accepted)
        queue.consumeAll { consumed += it.uris }

        assertThat(consumed).containsExactly(
            listOf(Uri.parse("content://docs/extra"), Uri.parse("content://docs/clip"))
        )
    }

    @Test
    fun `invalid scheme control characters and oversized metadata are rejected`() {
        val queue = ShareIntentQueue()
        val invalid = listOf(
            share("file:///data/user/0/private"),
            share("https://example.com/file"),
            share("content:///missing-authority"),
            share("content://docs/line\nbreak"),
            share("content://docs/${"x".repeat(4_097)}"),
            share("content://docs/ok").apply { type = "x".repeat(256) },
            share("content://docs/ok").apply { type = "text/plain\nsecret" },
        )

        invalid.forEach { intent ->
            assertThat(queue.enqueue(intent)).isEqualTo(ShareEnqueueResult.RejectedInvalid)
        }
        assertThat(queue.pendingCount).isEqualTo(0)
    }

    @Test
    fun `raw uri count over 32 and canonical request over 64KiB are rejected`() {
        val tooMany = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                ArrayList((0 until 128).map { Uri.parse("content://docs/$it") }),
            )
        }
        val tooLarge = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                ArrayList((0 until 20).map { index ->
                    Uri.parse("content://docs/$index/${"x".repeat(3_500)}")
                }),
            )
        }
        val queue = ShareIntentQueue()

        assertThat(queue.enqueue(tooMany)).isEqualTo(ShareEnqueueResult.RejectedInvalid)
        assertThat(queue.enqueue(tooLarge)).isEqualTo(ShareEnqueueResult.RejectedInvalid)
    }

    @Test
    fun `sixteen small requests fit but queue rejects newest without disturbing order`() {
        val queue = ShareIntentQueue()
        val results = (0 until ShareIntentQueue.MAX_PENDING + 1).map { index ->
            queue.enqueue(share("content://docs/$index"))
        }
        val consumed = mutableListOf<String>()
        queue.consumeAll { consumed += it.uris.single().lastPathSegment.orEmpty() }

        assertThat(results.take(ShareIntentQueue.MAX_PENDING))
            .containsExactlyElementsIn(List(ShareIntentQueue.MAX_PENDING) { ShareEnqueueResult.Accepted })
        assertThat(results.last()).isEqualTo(ShareEnqueueResult.RejectedCapacity)
        assertThat(consumed).containsExactlyElementsIn((0 until ShareIntentQueue.MAX_PENDING).map(Int::toString)).inOrder()
    }

    @Test
    fun `total queue canonical budget rejects newest`() {
        val queue = ShareIntentQueue()
        val large = { request: Int ->
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList((0 until 32).map { uri ->
                        Uri.parse("content://docs/$request/$uri/${"x".repeat(500)}")
                    }),
                )
            }
        }

        repeat(15) { index ->
            assertThat(queue.enqueue(large(index))).isEqualTo(ShareEnqueueResult.Accepted)
        }
        assertThat(queue.enqueue(large(16))).isEqualTo(ShareEnqueueResult.RejectedCapacity)
        assertThat(queue.pendingCount).isEqualTo(15)
    }

    @Test
    fun `duplicate remains typed and consumed fingerprint restore is bounded`() {
        val queue = ShareIntentQueue()
        val intent = share("content://docs/one")
        assertThat(queue.enqueue(intent)).isEqualTo(ShareEnqueueResult.Accepted)
        assertThat(queue.enqueue(Intent(intent))).isEqualTo(ShareEnqueueResult.Duplicate)
        queue.consumeAll { }
        val state = Bundle()
        queue.saveConsumedState(state)

        val restored = ShareIntentQueue()
        restored.restoreConsumedState(state)

        assertThat(restored.enqueue(intent)).isEqualTo(ShareEnqueueResult.Duplicate)
        assertThat(restored.consumedFingerprintCount).isAtMost(ShareIntentQueue.MAX_CONSUMED)
    }

    private fun share(uri: String) = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
    }
}
