package com.promenar.nexara

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareIntentQueueTest {
    @Test
    fun `two different intents queued during recovery are consumed once in arrival order`() {
        val queue = ShareIntentQueue()
        queue.enqueue(share("content://docs/one"))
        queue.enqueue(share("content://docs/two"))
        val consumed = mutableListOf<List<Uri>>()

        queue.consumeAll { consumed += it.uris }

        assertThat(consumed).containsExactly(
            listOf(Uri.parse("content://docs/one")),
            listOf(Uri.parse("content://docs/two")),
        ).inOrder()
    }

    @Test
    fun `duplicate intent and mirrored clip data are imported only once`() {
        val intent = share("content://docs/one").apply {
            clipData = ClipData.newRawUri("shared", Uri.parse("content://docs/one"))
        }
        val queue = ShareIntentQueue()

        assertThat(queue.enqueue(intent)).isTrue()
        assertThat(queue.enqueue(Intent(intent))).isFalse()
        val consumed = mutableListOf<List<Uri>>()
        queue.consumeAll { consumed += it.uris }

        assertThat(consumed).containsExactly(listOf(Uri.parse("content://docs/one")))
    }

    @Test
    fun `consumed fingerprint survives saved state and prevents rotation replay`() {
        val original = ShareIntentQueue()
        val intent = share("content://docs/one")
        original.enqueue(intent)
        var imports = 0
        original.consumeAll { imports++ }
        val state = Bundle()
        original.save(state)

        val restored = ShareIntentQueue.restore(state)
        assertThat(restored.enqueue(intent)).isFalse()
        restored.consumeAll { imports++ }

        assertThat(imports).isEqualTo(1)
        assertThat(restored.consumedFingerprintCount).isAtMost(ShareIntentQueue.MAX_CONSUMED)
    }

    @Test
    fun `pending queue survives process saved state and remains bounded`() {
        val original = ShareIntentQueue()
        val accepted = (0 until ShareIntentQueue.MAX_PENDING + 3).map { index ->
            original.enqueue(share("content://docs/$index"))
        }
        val state = Bundle()
        original.save(state)

        val restored = ShareIntentQueue.restore(state)
        val consumed = mutableListOf<List<Uri>>()
        restored.consumeAll { consumed += it.uris }

        assertThat(consumed).hasSize(ShareIntentQueue.MAX_PENDING)
        assertThat(consumed.first()).containsExactly(Uri.parse("content://docs/0"))
        assertThat(accepted.take(ShareIntentQueue.MAX_PENDING)).doesNotContain(false)
        assertThat(accepted.takeLast(3)).doesNotContain(true)
    }

    @Test
    fun `fingerprint includes action type extra and ordered clip content`() {
        val first = share("content://docs/extra").apply {
            clipData = ClipData(
                "shared",
                arrayOf("text/plain"),
                ClipData.Item(Uri.parse("content://docs/a")),
            ).also { it.addItem(ClipData.Item(Uri.parse("content://docs/b"))) }
        }
        val reversed = Intent(first).apply {
            clipData = ClipData(
                "shared",
                arrayOf("text/plain"),
                ClipData.Item(Uri.parse("content://docs/b")),
            ).also { it.addItem(ClipData.Item(Uri.parse("content://docs/a"))) }
        }

        assertThat(ShareIntentQueue.fingerprint(first))
            .isNotEqualTo(ShareIntentQueue.fingerprint(reversed))
        assertThat(ShareIntentQueue.fingerprint(first))
            .isNotEqualTo(ShareIntentQueue.fingerprint(Intent(first).apply { type = "image/png" }))
        assertThat(ShareIntentQueue.fingerprint(first))
            .isNotEqualTo(ShareIntentQueue.fingerprint(Intent(first).apply {
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://docs/other"))
            }))
        assertThat(ShareIntentQueue.fingerprint(first))
            .isNotEqualTo(ShareIntentQueue.fingerprint(Intent(first).apply {
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    arrayListOf(Uri.parse("content://docs/extra")),
                )
            }))
    }

    private fun share(uri: String) = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
    }
}
