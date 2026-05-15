package com.promenar.nexara.infra.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.promenar.nexara.data.local.db.entity.FileEntry

class SafImportHandler(private val context: Context) {

    fun createOpenDirectoryIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    fun persistUriPermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    suspend fun scanSafDirectory(
        uri: Uri,
        parentUuid: String?,
        physicalRootPath: String
    ): List<FileEntry> {
        return emptyList()
    }
}
