package com.promenar.nexara.data.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** 只安装在测试 APK，目录位于该 APK 的缓存区。 */
class ExternalFolderDeviceDocumentsProvider : DocumentsProvider() {
    private lateinit var root: File

    override fun onCreate(): Boolean {
        root = File(context!!.cacheDir, "external-folder-device-fixture").canonicalFile.apply { check(isDirectory || mkdirs()) }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        val values = mapOf<String, Any>(Root.COLUMN_ROOT_ID to "root", Root.COLUMN_DOCUMENT_ID to "root", Root.COLUMN_TITLE to "Nexara 文件夹测试", Root.COLUMN_FLAGS to Root.FLAG_SUPPORTS_CREATE)
        addRow(columnNames.map { values[it] }.toTypedArray())
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addFile(this, resolve(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            resolve(parentDocumentId).listFiles()!!.sortedBy { it.name }.forEach { addFile(this, it) }
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(resolve(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        requireName(displayName)
        val file = File(resolve(parentDocumentId), displayName)
        check(!file.exists())
        check(if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile())
        return id(file)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        requireName(displayName)
        val file = resolve(documentId)
        val target = File(file.parentFile, displayName)
        check(!target.exists() && file.renameTo(target))
        return id(target)
    }

    override fun deleteDocument(documentId: String) {
        require(documentId != "root")
        check(resolve(documentId).deleteRecursively())
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentId.startsWith("$parentDocumentId/")

    private fun resolve(documentId: String): File {
        require(documentId == "root" || documentId.startsWith("root/"))
        val file = if (documentId == "root") root else File(root, documentId.removePrefix("root/"))
        val canonical = file.canonicalFile
        require(canonical.toPath().startsWith(root.canonicalFile.toPath()))
        if (!canonical.exists()) throw FileNotFoundException("测试文档不存在")
        return canonical
    }

    private fun requireName(name: String) {
        require(name.isNotEmpty() && name !in setOf(".", "..") && '/' !in name && '\\' !in name && '\u0000' !in name)
    }

    private fun id(file: File): String = if (file == root) "root" else "root/${file.relativeTo(root).invariantSeparatorsPath}"

    private fun addFile(cursor: MatrixCursor, file: File) {
        val flags = (if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE) or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        val values = mapOf<String, Any>(Document.COLUMN_DOCUMENT_ID to id(file), Document.COLUMN_DISPLAY_NAME to file.name,
            Document.COLUMN_MIME_TYPE to if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream",
            Document.COLUMN_SIZE to file.length(), Document.COLUMN_LAST_MODIFIED to file.lastModified(), Document.COLUMN_FLAGS to flags)
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }

    companion object {
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }
}
