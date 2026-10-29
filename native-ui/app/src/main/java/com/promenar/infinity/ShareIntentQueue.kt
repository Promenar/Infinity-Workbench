package com.promenar.infinity

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.share.core.DurableShareInbox
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportDestination
import kotlinx.coroutines.flow.StateFlow

enum class ShareEnqueueResult {
    Accepted,
    Duplicate,
    RejectedInvalid,
    RejectedCapacity,
}

/**
 * 分享 Intent 的投递来源。Fresh 是用户刚发起的一次分享；Redelivered 是系统在 Activity 重建、
 * 进程死亡恢复或从最近任务重启时重放的同一 Intent 实例，只有它需要与已消费记录去重。
 */
enum class ShareDelivery {
    Fresh,
    Redelivered,
    ;

    companion object {
        /** onCreate 携带保存状态或从最近任务重启时，Intent 必为系统重放而非用户新的分享。 */
        fun of(restoredInstanceState: Boolean, intentFlags: Int): ShareDelivery =
            if (restoredInstanceState || intentFlags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
                Redelivered
            } else {
                Fresh
            }
    }
}

class ShareRequest internal constructor(
    val uris: List<Uri>,
    val mimeType: String,
    internal val fingerprint: String,
    internal val canonicalSizeBytes: Int,
    val requestId: String,
    val targetWorkspaceRootUuid: String? = null,
    val targetDestination: ShareImportDestination? = null,
)

class ShareLease internal constructor(
    val token: String,
    val request: ShareRequest,
)

class ShareIntentViewModel(
    inbox: DurableShareInbox,
    resolver: ContentResolver,
) : ViewModel() {
    val queue = ShareIntentQueue(inbox)
    val stagingCoordinator = ShareIntentStagingCoordinator(
        scope = viewModelScope,
        stage = { intent, delivery -> queue.stageDurably(intent, resolver, delivery) },
        awaitCapacity = queue::awaitDurableCapacity,
    )

    companion object {
        fun factory(inbox: DurableShareInbox, resolver: ContentResolver): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ShareIntentViewModel(inbox, resolver) as T
            }
    }
}

/** DurableShareInbox 的薄 UI lease 门面；不维护第二份 pending/consumed 状态。 */
class ShareIntentQueue(
    val durableInbox: DurableShareInbox,
) {
    val durablePendingCount: StateFlow<Int> = durableInbox.pendingCount

    suspend fun stageDurably(
        intent: Intent,
        resolver: ContentResolver,
        delivery: ShareDelivery,
    ): ShareEnqueueResult = durableInbox.stage(intent, resolver, delivery)

    suspend fun claimNextDurably(): ShareLease? = durableInbox.claimNext()
    suspend fun ackDurably(token: String): Boolean = durableInbox.ack(token)
    suspend fun nackDurably(token: String): Boolean = durableInbox.nack(token)
    suspend fun dropDurably(token: String): Boolean = durableInbox.drop(token)

    suspend fun recordCreatedDurably(requestId: String, created: List<ShareImportItem>) {
        durableInbox.recordCreated(requestId, created)
    }

    suspend fun recordTargetDurably(requestId: String, workspaceRootUuid: String) {
        durableInbox.recordTarget(requestId, workspaceRootUuid)
    }

    suspend fun recordTargetDurably(requestId: String, destination: ShareImportDestination) {
        durableInbox.recordTarget(requestId, destination)
    }

    suspend fun refreshDurableCount() = durableInbox.refresh()
    suspend fun awaitDurableCapacity() = durableInbox.awaitCapacity()

    companion object {
        fun isShareIntent(intent: Intent?): Boolean = intent?.action.let { action ->
            action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
        }
    }
}
