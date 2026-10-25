package com.promenar.nexara.data.linux

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.domain.linux.*
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 由 MANAGE_DOCUMENTS 保护；SAF 对子树的授权始终局限于 `/root`，包括链接解析后的路径。 */
open class LinuxDocumentsProvider : DocumentsProvider() {
    protected open val linuxRuntime: LinuxRuntime get() = (context!!.applicationContext as NexaraApplication).linux.runtime
    protected open val linuxFiles: LinuxFileSystem get() = (context!!.applicationContext as NexaraApplication).linux.files
    private val homeFiles: LinuxFileSystem by lazy { (linuxFiles as? LinuxFileSystemImpl)?.forHome() ?: UnavailableLinuxFiles() }
    private val authority get() = context!!.packageName + ".linux.documents"
    private val paths get() = (homeFiles as? LinuxFileSystemImpl)?.paths ?: LinuxFilePaths(linuxRuntime.paths.rootfs, LinuxFileIo(true))

    override fun onCreate(): Boolean {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            linuxRuntime.state.collect { context?.contentResolver?.notifyChange(DocumentsContract.buildRootsUri(authority), null) }
        }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = blocking {
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            val home = runCatching { checkedFile("/root") }.getOrNull()
            if (home == null) {
                extras = Bundle().apply { putString(DocumentsContract.EXTRA_INFO, context!!.getString(R.string.linux_files_not_ready)) }
            } else {
                row(this, mapOf(
                    Root.COLUMN_ROOT_ID to "linux-home",
                    Root.COLUMN_DOCUMENT_ID to LinuxDocumentsIds.encode("/root"),
                    Root.COLUMN_TITLE to context!!.getString(R.string.linux_files_home),
                    Root.COLUMN_SUMMARY to context!!.getString(R.string.linux_files_home_summary),
                    Root.COLUMN_FLAGS to (Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY),
                    Root.COLUMN_MIME_TYPES to "*/*",
                    Root.COLUMN_AVAILABLE_BYTES to paths.access.directory(home.toPath()) { it.toFile().usableSpace },
                ))
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = blocking {
        val path = LinuxDocumentsIds.decode(documentId)
        checkedFile(path, followFinal = false)
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(this, homeFiles.stat(path) ?: missing()) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = blocking {
        val path = LinuxDocumentsIds.decode(parentDocumentId)
        checkedFile(path)
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            homeFiles.list(path).forEach { entry ->
                if (runCatching { checkedFile(entry.path, followFinal = false) }.isSuccess) addDocument(this, entry)
            }
            setNotificationUri(context!!.contentResolver, DocumentsContract.buildDocumentUri(authority, parentDocumentId))
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = blocking {
        signal?.throwIfCanceled()
        val file = checkedFile(LinuxDocumentsIds.decode(documentId))
        if (!paths.access.isFile(file.toPath())) missing()
        val parsed = ParcelFileDescriptor.parseMode(mode)
        val readWrite = parsed and ParcelFileDescriptor.MODE_READ_WRITE
        var flags = when (readWrite) {
            ParcelFileDescriptor.MODE_READ_ONLY -> OsConstants.O_RDONLY
            ParcelFileDescriptor.MODE_WRITE_ONLY -> OsConstants.O_WRONLY
            ParcelFileDescriptor.MODE_READ_WRITE -> OsConstants.O_RDWR
            else -> throw FileNotFoundException("不支持的打开模式")
        }
        flags = flags or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK
        if (parsed and ParcelFileDescriptor.MODE_APPEND != 0) flags = flags or OsConstants.O_APPEND
        val descriptor = paths.access.file(file.toPath()) { Os.open(it.toString(), flags, 0) }
        try {
            if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) missing()
            if (parsed and ParcelFileDescriptor.MODE_TRUNCATE != 0) Os.ftruncate(descriptor, 0)
            signal?.throwIfCanceled()
            ParcelFileDescriptor.dup(descriptor)
        } finally { Os.close(descriptor) }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = blocking {
        val parent = LinuxDocumentsIds.decode(parentDocumentId)
        checkedFile(parent)
        val created = if (mimeType == Document.MIME_TYPE_DIR) homeFiles.createDirectory(parent, displayName) else homeFiles.createFile(parent, displayName)
        notifyParent(parent)
        LinuxDocumentsIds.encode(created.path)
    }

    override fun deleteDocument(documentId: String) { blocking {
        val path = mutationPath(documentId)
        checkedFile(path, followFinal = false)
        homeFiles.delete(listOf(path))[path]?.let { throw LinuxFileException(it) }
        notifyParent(path.substringBeforeLast('/'))
    } }

    override fun renameDocument(documentId: String, displayName: String): String = blocking {
        val path = mutationPath(documentId)
        checkedFile(path, followFinal = false)
        val renamed = homeFiles.rename(path, displayName)
        notifyParent(path.substringBeforeLast('/'))
        LinuxDocumentsIds.encode(renamed.path)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching { blocking {
        val parent = LinuxDocumentsIds.decode(parentDocumentId)
        val child = LinuxDocumentsIds.decode(documentId)
        val parentFile = checkedFile(parent).toPath()
        val childFile = checkedFile(child).toPath()
        if (child == parent || !child.startsWith("$parent/")) return@blocking false
        if (childFile != parentFile && childFile.startsWith(parentFile)) return@blocking true
        val scoped = homeFiles as? LinuxFileSystemImpl ?: return@blocking false
        val link = scoped.resolvePath(child, followFinal = false)
        // 假硬链接的逻辑文件属于所选目录；普通链接及链接祖先不能进入兄弟子树。
        link.startsWith(parentFile) && scoped.paths.isEmulatedHardLink(link)
    } }.getOrDefault(false)

    private suspend fun addDocument(cursor: MatrixCursor, entry: LinuxFileEntry) {
        val readable = runCatching { checkedFile(entry.path) }.isSuccess
        val directory = readable && (entry.type == LinuxFileType.DIRECTORY || entry.targetIsDirectory)
        val flags = (if (entry.path == "/root") 0 else Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME) or
            (if (directory) Document.FLAG_DIR_SUPPORTS_CREATE else if (readable && entry.type in setOf(LinuxFileType.FILE, LinuxFileType.SYMLINK)) Document.FLAG_SUPPORTS_WRITE else 0)
        val mime = if (directory) Document.MIME_TYPE_DIR else MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
        row(cursor, mapOf(Document.COLUMN_DOCUMENT_ID to LinuxDocumentsIds.encode(entry.path), Document.COLUMN_DISPLAY_NAME to if (entry.path == "/root") context!!.getString(R.string.linux_files_home) else entry.name,
            Document.COLUMN_MIME_TYPE to mime, Document.COLUMN_FLAGS to flags, Document.COLUMN_SIZE to entry.size, Document.COLUMN_LAST_MODIFIED to entry.modifiedMs))
    }

    private fun checkedFile(path: String, followFinal: Boolean = true): File {
        if (linuxRuntime.state.value !is LinuxRuntimeState.Ready) missing()
        val resolver = paths
        val home = resolver.resolve("/root", followFinal = false)
        if (!resolver.access.isDirectory(home) || resolver.access.isLink(home)) missing()
        val resolved = (homeFiles as? LinuxFileSystemImpl)?.resolvePath(path, followFinal) ?: missing()
        if (!resolver.access.exists(resolved)) missing()
        return resolved.toFile()
    }

    private fun mutationPath(documentId: String): String = LinuxDocumentsIds.decode(documentId).also { if (it == "/root") missing() }
    private fun notifyParent(path: String) { context!!.contentResolver.notifyChange(DocumentsContract.buildDocumentUri(authority, LinuxDocumentsIds.encode(path)), null) }
    private fun row(cursor: MatrixCursor, values: Map<String, Any>) { cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray()) }
    private fun <T> blocking(action: suspend () -> T): T = try { runBlocking(Dispatchers.IO) { action() } }
        catch (failure: FileNotFoundException) { throw failure }
        catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
        catch (failure: Exception) { throw FileNotFoundException("Linux 文件不可用").apply { initCause(failure) } }
    private fun missing(): Nothing = throw FileNotFoundException("Linux 主目录或文档不可用")

    companion object {
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
