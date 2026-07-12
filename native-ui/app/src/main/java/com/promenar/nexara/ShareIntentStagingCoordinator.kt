package com.promenar.nexara

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class ShareStageOutcome(
    val submissionId: Long,
    val candidate: Intent,
    val result: ShareEnqueueResult,
)

/**
 * 进程内分享 staging 的唯一串行协调器。它由 ShareIntentViewModel 持有，因此配置重建不会取消队列，
 * 且不持有 Activity；真正的进程死亡恢复仍由当前 Activity intent 与 DurableShareInbox 负责。
 */
class ShareIntentStagingCoordinator(
    scope: CoroutineScope,
    private val stage: suspend (Intent) -> ShareEnqueueResult,
    private val awaitCapacity: suspend () -> Unit,
) {
    private data class Submission(val id: Long, val key: String, val candidate: Intent)

    private val nextId = AtomicLong(0)
    private val lock = Any()
    private val inFlightByKey = mutableMapOf<String, Long>()
    private val keyBySubmissionId = mutableMapOf<Long, String>()
    private val terminalSubmissionIds = mutableSetOf<Long>()
    private val submissions = Channel<Submission>(Channel.UNLIMITED)
    private val outcomeChannel = Channel<ShareStageOutcome>(Channel.UNLIMITED)
    val outcomes: Flow<ShareStageOutcome> = outcomeChannel.receiveAsFlow()

    init {
        scope.launch {
            for (submission in submissions) {
                var result: ShareEnqueueResult
                do {
                    result = stage(submission.candidate)
                    if (result != ShareEnqueueResult.RejectedCapacity) {
                        synchronized(lock) { terminalSubmissionIds += submission.id }
                    }
                    outcomeChannel.send(ShareStageOutcome(submission.id, submission.candidate, result))
                    if (result == ShareEnqueueResult.RejectedCapacity) awaitCapacity()
                } while (result == ShareEnqueueResult.RejectedCapacity)
            }
        }
    }

    /** 返回稳定 submission id；同一未完成 payload 的配置重放只复用已有 id。 */
    fun submit(intent: Intent): Long {
        val candidate = Intent(intent)
        val key = stableKey(candidate)
        synchronized(lock) {
            inFlightByKey[key]?.let { return it }
            val id = nextId.incrementAndGet()
            inFlightByKey[key] = id
            keyBySubmissionId[id] = key
            if (submissions.trySend(Submission(id, key, candidate)).isFailure) {
                inFlightByKey.remove(key)
                keyBySubmissionId.remove(id)
            }
            return id
        }
    }

    /** Activity 完成终态 Intent/UI 处理后释放配置重放去重键；容量等待态不得确认。 */
    fun acknowledge(submissionId: Long) {
        synchronized(lock) {
            if (!terminalSubmissionIds.remove(submissionId)) return
            val key = keyBySubmissionId.remove(submissionId) ?: return
            if (inFlightByKey[key] == submissionId) inFlightByKey.remove(key)
        }
    }

    private fun stableKey(intent: Intent): String {
        val uris = mutableListOf<Uri>()
        try {
            @Suppress("DEPRECATION")
            if (intent.action == Intent.ACTION_SEND) {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
            } else {
                uris += intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
            intent.clipData?.let { clip ->
                repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(uris::add) }
            }
        } catch (_: IllegalArgumentException) {
            // 永久无效 payload 仍须进入 DurableShareInbox 得到 RejectedInvalid；仅禁用这次合并优化。
            return "invalid-${nextId.incrementAndGet()}"
        } catch (_: ClassCastException) {
            return "invalid-${nextId.incrementAndGet()}"
        }
        val canonical = buildString {
            append(intent.action).append('\u0000').append(intent.type)
            uris.distinct().forEach { append('\u0000').append(it) }
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(canonical)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
