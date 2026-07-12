package com.promenar.nexara.share.core

import android.net.Uri
import com.promenar.nexara.data.local.db.entity.FileEntry

enum class ShareImportStatus { Pending, Importing, Created, Rejected }
enum class ShareIndexStatus { Pending, Partial, Failed, Completed }

data class ShareIndexReceipt(
    val taskId: String,
    val fileUuid: String,
)

enum class ShareRejectReason {
    TargetRequired,
    UnsupportedMime,
    MimeMismatch,
    InvalidName,
    PermissionDenied,
    EmptyFile,
    ItemTooLarge,
    BatchTooLarge,
    ReadFailed,
    WriteFailed,
    IndexScheduleFailed,
    ;

    val retryable: Boolean
        get() = this == WriteFailed || this == IndexScheduleFailed
}

data class SharedContentMetadata(
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
)

data class ShareImportItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val status: ShareImportStatus = ShareImportStatus.Pending,
    val reason: ShareRejectReason? = null,
    val created: FileEntry? = null,
    val indexStatus: ShareIndexStatus? = null,
    val fileUuid: String? = created?.uuid,
    val indexTaskId: String? = null,
)

data class ShareImportBatchResult(val items: List<ShareImportItem>) {
    val created: List<ShareImportItem> get() = items.filter { it.status == ShareImportStatus.Created }
    val rejected: List<ShareImportItem> get() = items.filter { it.status == ShareImportStatus.Rejected }
}

data class ShareImportTarget(
    val workspaceRootUuid: String,
    val label: String,
    val kind: ShareTargetKind,
)

enum class ShareTargetKind { CurrentSession, KnowledgeBase }
