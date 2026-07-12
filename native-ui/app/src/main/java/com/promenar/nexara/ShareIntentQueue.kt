package com.promenar.nexara

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.share.core.DurableShareInbox
import com.promenar.nexara.share.core.ShareImportItem
import kotlinx.coroutines.flow.StateFlow

enum class ShareEnqueueResult {
    Accepted,
    Duplicate,
    RejectedInvalid,
    RejectedCapacity,
}

class ShareRequest internal constructor(
    val uris: List<Uri>,
    val mimeType: String,
    internal val fingerprint: String,
    internal val canonicalSizeBytes: Int,
    val requestId: String,
    val targetWorkspaceRootUuid: String? = null,
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
        stage = { intent -> queue.stageDurably(intent, resolver) },
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

    suspend fun stageDurably(intent: Intent, resolver: ContentResolver): ShareEnqueueResult =
        durableInbox.stage(intent, resolver)

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

    suspend fun refreshDurableCount() = durableInbox.refresh()
    suspend fun awaitDurableCapacity() = durableInbox.awaitCapacity()

    companion object {
        fun isShareIntent(intent: Intent?): Boolean = intent?.action.let { action ->
            action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
        }
    }
}
