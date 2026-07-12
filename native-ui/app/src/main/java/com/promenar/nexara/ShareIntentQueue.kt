package com.promenar.nexara

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModel
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque

enum class ShareEnqueueResult {
    Accepted,
    Duplicate,
    RejectedInvalid,
    RejectedCapacity,
}

class ShareRequest internal constructor(
    val uris: List<Uri>,
    internal val fingerprint: String,
    internal val canonicalSizeBytes: Int,
)

class ShareIntentViewModel : ViewModel() {
    val queue = ShareIntentQueue()
}

class ShareIntentQueue {
    private val pending = ArrayDeque<ShareRequest>()
    private val pendingFingerprints = mutableSetOf<String>()
    private val consumedFingerprints = LinkedHashSet<String>()
    private var pendingCanonicalBytes = 0

    val pendingCount: Int get() = pending.size
    val consumedFingerprintCount: Int get() = consumedFingerprints.size

    fun enqueue(intent: Intent?): ShareEnqueueResult {
        val request = intent?.toShareRequest() ?: return ShareEnqueueResult.RejectedInvalid
        if (request.fingerprint in consumedFingerprints || request.fingerprint in pendingFingerprints) {
            return ShareEnqueueResult.Duplicate
        }
        if (
            pending.size >= MAX_PENDING ||
            pendingCanonicalBytes + request.canonicalSizeBytes > MAX_QUEUE_CANONICAL_BYTES
        ) {
            return ShareEnqueueResult.RejectedCapacity
        }
        pending.addLast(request)
        pendingFingerprints += request.fingerprint
        pendingCanonicalBytes += request.canonicalSizeBytes
        return ShareEnqueueResult.Accepted
    }

    fun consumeAll(consumer: (ShareRequest) -> Unit) {
        while (pending.isNotEmpty()) {
            val request = pending.first
            consumer(request)
            pending.removeFirst()
            pendingFingerprints -= request.fingerprint
            pendingCanonicalBytes -= request.canonicalSizeBytes
            consumedFingerprints += request.fingerprint
            trimConsumedFingerprints()
        }
    }

    fun saveConsumedState(outState: Bundle) {
        outState.putStringArrayList(
            STATE_CONSUMED_FINGERPRINTS,
            ArrayList(consumedFingerprints.toList().takeLast(MAX_CONSUMED)),
        )
        outState.putInt(STATE_PENDING_COUNT, pending.size)
    }

    fun restoreConsumedState(state: Bundle?) {
        if (state == null) return
        val restored = runCatching {
            state.getStringArrayList(STATE_CONSUMED_FINGERPRINTS).orEmpty()
        }.getOrDefault(emptyList())
        restored.filter(FINGERPRINT::matches).takeLast(MAX_CONSUMED).forEach(consumedFingerprints::add)
        trimConsumedFingerprints()
    }

    private fun trimConsumedFingerprints() {
        while (consumedFingerprints.size > MAX_CONSUMED) {
            consumedFingerprints.remove(consumedFingerprints.first())
        }
    }

    companion object {
        const val MAX_PENDING = 16
        const val MAX_CONSUMED = 32
        const val STATE_CONSUMED_FINGERPRINTS = "share_consumed_fingerprints_v2"
        const val STATE_PENDING_COUNT = "share_pending_count_v2"

        private const val MAX_URIS_PER_REQUEST = 32
        private const val MAX_URI_UTF8_BYTES = 4_096
        private const val MAX_MIME_UTF8_BYTES = 255
        private const val MAX_REQUEST_CANONICAL_BYTES = 64 * 1_024
        private const val MAX_QUEUE_CANONICAL_BYTES = 256 * 1_024
        private val FINGERPRINT = Regex("[0-9a-f]{64}")
        private val MIME_TYPE = Regex(
            "[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*-]+"
        )

        fun isShareIntent(intent: Intent?): Boolean = intent?.action.let { action ->
            action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
        }

        private fun Intent.toShareRequest(): ShareRequest? = runCatching {
            val shareAction = action?.takeIf {
                it == Intent.ACTION_SEND || it == Intent.ACTION_SEND_MULTIPLE
            } ?: return null
            val shareType = type?.takeIf { value ->
                isSafeTextField(value) && MIME_TYPE.matches(value)
            } ?: return null
            if (shareType.toByteArray(Charsets.UTF_8).size > MAX_MIME_UTF8_BYTES) return null

            @Suppress("DEPRECATION")
            val extraUris = when (shareAction) {
                Intent.ACTION_SEND -> listOfNotNull(getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                else -> getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
            val clipItemCount = clipData?.itemCount ?: 0
            if (extraUris.size + clipItemCount !in 1..MAX_URIS_PER_REQUEST) return null
            val clipUris = buildList {
                val sharedClipData = clipData ?: return@buildList
                repeat(sharedClipData.itemCount) { index ->
                    sharedClipData.getItemAt(index).uri?.let(::add)
                }
            }

            val orderedUris = LinkedHashSet<Uri>()
            (extraUris + clipUris).forEach { uri ->
                val value = uri.toString()
                if (
                    !uri.scheme.equals("content", ignoreCase = true) ||
                    !uri.isHierarchical ||
                    uri.authority.isNullOrBlank() ||
                    !isSafeTextField(value) ||
                    value.toByteArray(Charsets.UTF_8).size > MAX_URI_UTF8_BYTES
                ) return null
                orderedUris += uri
            }
            if (orderedUris.isEmpty()) return null

            val canonical = canonicalBytes(shareAction, shareType, orderedUris.map(Uri::toString))
            if (canonical.size > MAX_REQUEST_CANONICAL_BYTES) return null
            ShareRequest(
                uris = orderedUris.toList(),
                fingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(canonical)
                    .joinToString("") { byte -> "%02x".format(byte) },
                canonicalSizeBytes = canonical.size,
            )
        }.getOrNull()

        private fun canonicalBytes(action: String, type: String, uris: List<String>): ByteArray {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { data ->
                data.writeUtf8Field(action)
                data.writeUtf8Field(type)
                data.writeInt(uris.size)
                uris.forEach { uri -> data.writeUtf8Field(uri) }
            }
            return output.toByteArray()
        }

        private fun DataOutputStream.writeUtf8Field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }

        private fun isSafeTextField(value: String): Boolean =
            value.isNotEmpty() && value.none { character -> character.isISOControl() }
    }
}
