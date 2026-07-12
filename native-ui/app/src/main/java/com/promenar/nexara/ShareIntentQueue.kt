package com.promenar.nexara

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque

class ShareRequest internal constructor(
    val action: String,
    val type: String,
    private val extraUris: List<Uri>,
    private val clipUris: List<Uri>,
) {
    val uris: List<Uri> = buildList {
        (extraUris + clipUris).forEach { uri -> if (uri !in this) add(uri) }
    }

    internal fun fingerprint(): String = sha256(
        canonicalField(action) + canonicalField(type) +
            canonicalList(extraUris) + canonicalList(clipUris)
    )

    internal fun toBundle() = Bundle().apply {
        putString(KEY_ACTION, action)
        putString(KEY_TYPE, type)
        putStringArrayList(KEY_EXTRA_URIS, ArrayList(extraUris.map(Uri::toString)))
        putStringArrayList(KEY_CLIP_URIS, ArrayList(clipUris.map(Uri::toString)))
    }

    companion object {
        private const val KEY_ACTION = "action"
        private const val KEY_TYPE = "type"
        private const val KEY_EXTRA_URIS = "extra_uris"
        private const val KEY_CLIP_URIS = "clip_uris"
        private const val MAX_URIS = 64
        private const val MAX_TYPE_LENGTH = 256
        private const val MAX_URI_LENGTH = 4_096

        internal fun fromBundle(bundle: Bundle): ShareRequest? {
            val action = bundle.getString(KEY_ACTION)?.takeIf(::isShareAction) ?: return null
            val type = bundle.getString(KEY_TYPE)
                ?.takeIf { it.isNotBlank() && it.length <= MAX_TYPE_LENGTH }
                ?: return null
            val extras = bundle.safeUris(KEY_EXTRA_URIS)
            val clips = bundle.safeUris(KEY_CLIP_URIS)
            return ShareRequest(action, type, extras, clips).takeIf { it.uris.isNotEmpty() }
        }

        private fun Bundle.safeUris(key: String): List<Uri> =
            getStringArrayList(key).orEmpty().take(MAX_URIS).mapNotNull { value ->
                value.takeIf { it.isNotBlank() && it.length <= MAX_URI_LENGTH }?.let(Uri::parse)
            }

        private fun isShareAction(action: String) =
            action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE

        private fun canonicalList(values: List<Uri>) = buildString {
            append(values.size).append(':')
            values.forEach { append(canonicalField(it.toString())) }
        }

        private fun canonicalField(value: String) = "${value.length}:$value"

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

class ShareIntentQueue private constructor(
    private val pending: ArrayDeque<ShareRequest>,
    consumed: Collection<String>,
) {
    constructor() : this(ArrayDeque(), emptyList())

    private val consumedFingerprints: LinkedHashSet<String> =
        LinkedHashSet(consumed.toList().takeLast(MAX_CONSUMED))
    private val pendingFingerprints = pending.mapTo(mutableSetOf()) { it.fingerprint() }

    val consumedFingerprintCount: Int get() = consumedFingerprints.size

    fun enqueue(intent: Intent?): Boolean {
        val request = intent?.toShareRequest() ?: return false
        val fingerprint = request.fingerprint()
        if (fingerprint in consumedFingerprints || fingerprint in pendingFingerprints) return false
        if (pending.size >= MAX_PENDING) return false
        pending.addLast(request)
        pendingFingerprints += fingerprint
        return true
    }

    fun consumeAll(consumer: (ShareRequest) -> Unit) {
        while (pending.isNotEmpty()) {
            val request = pending.first
            consumer(request)
            pending.removeFirst()
            val fingerprint = request.fingerprint()
            pendingFingerprints -= fingerprint
            consumedFingerprints += fingerprint
            while (consumedFingerprints.size > MAX_CONSUMED) {
                consumedFingerprints.remove(consumedFingerprints.first())
            }
        }
    }

    fun save(outState: Bundle) {
        outState.putParcelableArrayList(KEY_PENDING, ArrayList(pending.map(ShareRequest::toBundle)))
        outState.putStringArrayList(KEY_CONSUMED, ArrayList(consumedFingerprints))
    }

    companion object {
        const val MAX_PENDING = 16
        const val MAX_CONSUMED = 32
        private const val KEY_PENDING = "share_queue_pending_v1"
        private const val KEY_CONSUMED = "share_queue_consumed_v1"
        private const val MAX_URIS_PER_INTENT = 64
        private val FINGERPRINT = Regex("[0-9a-f]{64}")

        fun restore(state: Bundle?): ShareIntentQueue {
            if (state == null) return ShareIntentQueue()
            val consumed = runCatching { state.getStringArrayList(KEY_CONSUMED).orEmpty() }
                .getOrDefault(emptyList())
                .filter { FINGERPRINT.matches(it) }
                .takeLast(MAX_CONSUMED)
            @Suppress("DEPRECATION")
            val pendingBundles = runCatching { state.getParcelableArrayList<Bundle>(KEY_PENDING).orEmpty() }
                .getOrDefault(emptyList())
                .take(MAX_PENDING)
            val seen = mutableSetOf<String>()
            val pending = pendingBundles
                .mapNotNull { bundle -> runCatching { ShareRequest.fromBundle(bundle) }.getOrNull() }
                .filter { request ->
                    val fingerprint = request.fingerprint()
                    fingerprint !in consumed && seen.add(fingerprint)
                }
            return ShareIntentQueue(ArrayDeque(pending), consumed)
        }

        fun fingerprint(intent: Intent): String? = intent.toShareRequest()?.fingerprint()

        private fun Intent.toShareRequest(): ShareRequest? {
            val shareAction = action?.takeIf {
                it == Intent.ACTION_SEND || it == Intent.ACTION_SEND_MULTIPLE
            } ?: return null
            val shareType = type ?: return null
            @Suppress("DEPRECATION")
            val extras = when (shareAction) {
                Intent.ACTION_SEND -> listOfNotNull(getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                else -> getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }.take(MAX_URIS_PER_INTENT)
            val clips = buildList {
                val data = clipData ?: return@buildList
                repeat(minOf(data.itemCount, MAX_URIS_PER_INTENT)) { index ->
                    data.getItemAt(index).uri?.let(::add)
                }
            }
            return ShareRequest(shareAction, shareType, extras, clips)
                .takeIf { it.uris.isNotEmpty() }
        }
    }
}
