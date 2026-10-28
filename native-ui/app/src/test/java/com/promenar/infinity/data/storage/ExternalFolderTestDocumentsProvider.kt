package com.promenar.infinity.data.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** JVM 测试使用真实文件描述符和 DocumentsProvider 协议，故障在提供商边界注入。 */
class ExternalFolderTestDocumentsProvider : DocumentsProvider() {
    lateinit var root: File
        private set
    var renameSupported = true
    var moveSupported = true
    var failNextMoveAfterCommit = false
    var failBackupRenameAfterCommit = false
    var failBackupRenameQueryAfterCommit = false
    var revoked = false
    var reportedSize: Long? = null
    var omitReportedSize = false
    var queryFailure: RuntimeException? = null
    var onTemporaryWrite: (() -> Unit)? = null
    var failTemporaryRename = false
    var lastProjection: Array<out String>? = null
    var stableDocumentIds = false
    var bumpDirectoryRevisions = false
    val openedDocuments = mutableListOf<String>()
    private val stableFiles = mutableMapOf<String, File>()
    private var failNextDocumentQuery = false

    override fun onCreate(): Boolean {
        root = File(context!!.cacheDir, "external-folder-fixture-${System.nanoTime()}").canonicalFile.apply { check(mkdirs()) }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        val values = mapOf<String, Any>(Root.COLUMN_ROOT_ID to "root", Root.COLUMN_DOCUMENT_ID to "root", Root.COLUMN_TITLE to "外部文件夹测试", Root.COLUMN_FLAGS to Root.FLAG_SUPPORTS_CREATE)
        addRow(columnNames.map { values[it] }.toTypedArray())
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        checkAvailable()
        if (failNextDocumentQuery) {
            failNextDocumentQuery = false
            throw FileNotFoundException("测试备份重命名已提交但查询回执丢失")
        }
        lastProjection = projection
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addFile(this, resolve(documentId)) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        checkAvailable()
        lastProjection = projection
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            resolve(parentDocumentId).listFiles()!!.sortedBy { it.name }.forEach { addFile(this, it) }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        checkAvailable()
        openedDocuments.add(documentId)
        val file = resolve(documentId)
        if (mode.contains('w') && file.name.startsWith(".infinity-write-")) {
            onTemporaryWrite?.also { onTemporaryWrite = null }?.invoke()
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        checkAvailable()
        require('/' !in displayName && '\\' !in displayName && displayName !in setOf(".", ".."))
        val file = File(resolve(parentDocumentId), displayName)
        check(!file.exists())
        check(if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile())
        if (bumpDirectoryRevisions) file.parentFile!!.setLastModified(file.parentFile!!.lastModified() + 1000)
        return id(file)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        checkAvailable()
        if (!renameSupported) throw UnsupportedOperationException("测试提供商不支持重命名")
        val file = resolve(documentId)
        if (failTemporaryRename && file.name.startsWith(".infinity-write-") && !displayName.startsWith(".infinity")) {
            throw FileNotFoundException("测试提供商拒绝提交临时文档")
        }
        val target = File(file.parentFile, displayName)
        check(!target.exists() && file.renameTo(target))
        val result = if (documentId.startsWith("stable:")) {
            stableFiles[documentId] = target
            documentId
        } else id(target)
        if (displayName.startsWith(".infinity-backup-")) {
            if (failBackupRenameAfterCommit) {
                failBackupRenameAfterCommit = false
                throw FileNotFoundException("测试备份重命名已提交但回执丢失")
            }
            if (failBackupRenameQueryAfterCommit) {
                failBackupRenameQueryAfterCommit = false
                failNextDocumentQuery = true
            }
        }
        return result
    }

    override fun deleteDocument(documentId: String) {
        checkAvailable()
        check(resolve(documentId).deleteRecursively())
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        checkAvailable()
        if (!moveSupported) throw UnsupportedOperationException("测试提供商不支持移动")
        val file = resolve(sourceDocumentId)
        check(file.parentFile == resolve(sourceParentDocumentId))
        val target = File(resolve(targetParentDocumentId), file.name)
        check(!target.exists() && file.renameTo(target))
        val result = if (sourceDocumentId.startsWith("stable:")) {
            stableFiles[sourceDocumentId] = target
            sourceDocumentId
        } else id(target)
        if (failNextMoveAfterCommit) {
            failNextMoveAfterCommit = false
            throw FileNotFoundException("测试移动已提交但回执丢失")
        }
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        resolve(documentId).toPath().startsWith(resolve(parentDocumentId).toPath()) && documentId != parentDocumentId

    private fun checkAvailable() {
        if (revoked) throw SecurityException("测试授权已撤销")
        queryFailure?.let { throw it }
    }

    private fun resolve(documentId: String): File {
        if (documentId.startsWith("stable:")) {
            return stableFiles[documentId]?.takeIf { it.exists() } ?: throw FileNotFoundException("测试文档不存在")
        }
        val relative = if (documentId == "root") "" else documentId.removePrefix("root/")
        val file = File(root, relative).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()))
        if (!file.exists()) throw FileNotFoundException("测试文档不存在")
        return file
    }

    private fun id(file: File): String {
        if (file == root) return "root"
        if (!stableDocumentIds) return "root/${file.relativeTo(root).invariantSeparatorsPath}"
        return stableFiles.entries.firstOrNull { it.value == file }?.key ?: "stable:${stableFiles.size}".also { stableFiles[it] = file }
    }

    private fun addFile(cursor: MatrixCursor, file: File) {
        val flags = (if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE) or
            Document.FLAG_SUPPORTS_DELETE or (if (renameSupported) Document.FLAG_SUPPORTS_RENAME else 0) or
            (if (moveSupported) Document.FLAG_SUPPORTS_MOVE else 0)
        val values = mapOf<String, Any?>(Document.COLUMN_DOCUMENT_ID to id(file), Document.COLUMN_DISPLAY_NAME to file.name,
            Document.COLUMN_MIME_TYPE to if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream",
            Document.COLUMN_SIZE to if (omitReportedSize) null else (reportedSize ?: file.length()), Document.COLUMN_LAST_MODIFIED to file.lastModified(), Document.COLUMN_FLAGS to flags)
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }

    companion object {
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }
}
