package com.promenar.nexara.data.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderEntry
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SafExternalFolderBackend(private val contentResolver: ContentResolver, private val treeUri: Uri) : ExternalFolderBackend {
    private val lock = locks.getOrPut(treeUri.toString()) { Mutex() }

    override suspend fun stat(path: String): ExternalFolderEntry? = io {
        val normalized = ExternalFolderPaths.normalize(path)
        find(normalized)?.let { document ->
            val sha256 = if (!document.isDirectory && document.size <= STAT_HASH_LIMIT) {
                requireFile(document)
                input(document).use { boundedStatSha256(it) }
            } else null
            entry(normalized, document, hash = false).copy(sha256 = sha256)
        }
    }

    override suspend fun list(path: String): List<ExternalFolderEntry> = io {
        val normalized = ExternalFolderPaths.normalize(path)
        val directory = find(normalized) ?: fail("NOT_FOUND", "目录不存在")
        requireDirectory(directory)
        children(directory).filter { visibleName(it.name) }.map {
            currentCoroutineContext().ensureActive()
            entry(if (normalized == "/") "/${it.name}" else "$normalized/${it.name}", it, hash = false)
        }.sortedWith(compareByDescending<ExternalFolderEntry> { it.isDirectory }.thenBy { it.name })
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray = io {
        if (maxBytes < 0 || maxBytes > Int.MAX_VALUE - 8L) fail("INVALID_ARGUMENT", "读取上限无效")
        val document = find(ExternalFolderPaths.normalize(path)) ?: fail("NOT_FOUND", "文件不存在")
        requireFile(document)
        if (document.size > maxBytes) fail("TOO_LARGE", "文件超过读取上限")
        input(document).use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) fail("TOO_LARGE", "文件超过读取上限")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry = io {
        val normalized = mutationPath(path)
        val name = normalized.substringAfterLast('/')
        val parent = find(parentPath(normalized)) ?: fail("NOT_FOUND", "父目录不存在")
        requireDirectory(parent)
        requireCapability(parent, Document.FLAG_DIR_SUPPORTS_CREATE, "READ_ONLY", "该目录不允许创建文档")
        val original = child(parent, name)
        if (original?.isDirectory == true) fail("CONFLICT", "目标是目录")
        if (createOnly && original != null) fail("CONFLICT", "目标文件已经存在")
        val originalHash = original?.let { digest(it) }
        if (expectedSha256 != null && expectedSha256 != originalHash) fail("CONFLICT", "文件版本已经变化，请重新读取")
        if (original != null) {
            requireCapability(original, Document.FLAG_SUPPORTS_WRITE, "READ_ONLY", "该文件为只读")
            requireReplacement(original)
        }
        val temporary = createDocument(parent, ".nexara-write-${UUID.randomUUID()}", original?.mimeType ?: "application/octet-stream")
        var primaryFailure: Throwable? = null
        try {
            requireCapability(temporary, Document.FLAG_SUPPORTS_DELETE, "UNSUPPORTED", "提供商不能清理临时文档，无法安全写入")
            writeDocument(temporary, content)
            val expectedContent = hash(content)
            if (digest(temporary) != expectedContent) fail("UNAVAILABLE", "提供商写入校验失败，原文件尚未替换")
            currentCoroutineContext().ensureActive()
            checkCurrent(parent, name, original, originalHash, createOnly)
            val published = if (original != null) {
                requireReplacement(temporary)
                replace(parent, name, original, originalHash!!, temporary, expectedContent)
            } else if (temporary.supports(Document.FLAG_SUPPORTS_RENAME)) {
                val renamed = rename(temporary, name)
                if (renamed.name != name || child(parent, name)?.id != renamed.id) {
                    fail("CONFLICT", "提供商未能以目标名称发布文档")
                }
                renamed
            } else {
                // 新建的回退只创建独立文档；已有文件绝不通过截断方式覆盖。
                publishWithoutRename(parent, name, content, expectedContent)
            }
            if (digest(published) != expectedContent) fail("UNAVAILABLE", "发布后的文件校验失败")
            entry(normalized, queryDocument(published.uri), hash = true)
        } catch (exception: Throwable) {
            primaryFailure = exception
            throw exception
        } finally {
            withContext(NonCancellable) {
                cleanup(temporary, primaryFailure)
            }
        }
    }

    override suspend fun createDirectory(path: String): ExternalFolderEntry = io {
        val normalized = mutationPath(path)
        val parent = find(parentPath(normalized)) ?: fail("NOT_FOUND", "父目录不存在")
        requireDirectory(parent)
        requireCapability(parent, Document.FLAG_DIR_SUPPORTS_CREATE, "READ_ONLY", "该目录不允许创建子目录")
        val name = normalized.substringAfterLast('/')
        if (child(parent, name) != null) fail("CONFLICT", "同名文件或目录已经存在")
        val directory = createDocument(parent, name, Document.MIME_TYPE_DIR)
        if (directory.name != name || child(parent, name)?.id != directory.id) {
            cleanup(directory, null)
            fail("CONFLICT", "同名目录在创建期间已经出现")
        }
        entry(normalized, directory, hash = false)
    }

    override suspend fun delete(path: String, expectedSha256: String?) = io {
        val normalized = mutationPath(path)
        val document = find(normalized) ?: fail("NOT_FOUND", "文件或目录不存在")
        requireCapability(document, Document.FLAG_SUPPORTS_DELETE, "READ_ONLY", "该文档不允许删除")
        if (expectedSha256 != null && (document.isDirectory || digest(document) != expectedSha256)) {
            fail("CONFLICT", "文件版本已经变化，请重新读取")
        }
        requireSafeDeletion(document)
        currentCoroutineContext().ensureActive()
        if (expectedSha256 != null && digest(document) != expectedSha256) fail("CONFLICT", "文件版本已经变化，请重新读取")
        if (!DocumentsContract.deleteDocument(contentResolver, document.uri)) fail("UNAVAILABLE", "提供商未能删除文档")
    }

    private suspend fun requireSafeDeletion(document: SafDocument) {
        if (!document.isDirectory) return
        val pending = ArrayDeque<SafDocument>()
        val visited = mutableSetOf<String>()
        pending.add(document)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = pending.removeLast()
            if (!visited.add(directory.id)) fail("UNSUPPORTED", "提供商返回循环目录，无法安全删除")
            for (child in children(directory)) {
                if (!visibleName(child.name)) fail("INVALID_PATH", "删除范围包含内部恢复文档或保留目录")
                if (child.isDirectory) pending.add(child)
            }
        }
    }

    private suspend fun replace(parent: SafDocument, name: String, original: SafDocument, originalHash: String,
        temporary: SafDocument, expectedContent: String): SafDocument {
        var backup: SafDocument? = null
        var published: SafDocument? = null
        try {
            backup = rename(original, ".nexara-backup-${UUID.randomUUID()}")
            // 重命名后的复核能发现最终检查与旧文档移动之间的内容变化。
            if (digest(backup) != originalHash) fail("CONFLICT", "原文件在提交期间发生变化")
            if (child(parent, name) != null) fail("CONFLICT", "目标名称在提交期间已被占用")
            currentCoroutineContext().ensureActive()
            published = rename(temporary, name)
            if (published.name != name || child(parent, name)?.id != published.id || digest(published) != expectedContent) {
                fail("UNAVAILABLE", "提供商未能正确发布文档")
            }
            // 清理失败只保留隐藏的旧内容备份，不撤销已经校验通过的新文档。
            cleanup(backup, null)
            backup = null
            return published
        } catch (exception: Throwable) {
            var recovered = backup == null
            withContext(NonCancellable) {
                try {
                    val current = child(parent, name)
                    if (published != null && current?.id == published.id && digest(current) == expectedContent) {
                        DocumentsContract.deleteDocument(contentResolver, current.uri)
                    }
                    if (backup != null && child(parent, name) == null) {
                        val restored = rename(backup, name)
                        recovered = restored.name == name && digest(restored) == originalHash
                    }
                } catch (recoveryFailure: Throwable) {
                    exception.addSuppressed(recoveryFailure)
                }
            }
            if (exception is CancellationException) throw exception
            if (!recovered) fail("UNAVAILABLE", "替换失败，旧内容保留在挂载目录的隐藏备份中，需要人工恢复")
            throw exception
        }
    }

    private suspend fun publishWithoutRename(parent: SafDocument, name: String, content: ByteArray, expectedContent: String): SafDocument {
        if (child(parent, name) != null) fail("CONFLICT", "目标文件已经存在")
        val created = createDocument(parent, name, "application/octet-stream")
        var failure: Throwable? = null
        try {
            requireCapability(created, Document.FLAG_SUPPORTS_DELETE, "UNSUPPORTED", "提供商不支持失败清理，无法安全新建")
            if (created.name != name || child(parent, name)?.id != created.id) fail("CONFLICT", "目标名称已经被占用")
            writeDocument(created, content)
            if (digest(created) != expectedContent) fail("UNAVAILABLE", "新建文档校验失败")
            return queryDocument(created.uri)
        } catch (exception: Throwable) {
            failure = exception
            throw exception
        } finally {
            if (failure != null) withContext(NonCancellable) { cleanup(created, failure) }
        }
    }

    private suspend fun checkCurrent(parent: SafDocument, name: String, original: SafDocument?, originalHash: String?, createOnly: Boolean) {
        val current = child(parent, name)
        if ((createOnly && current != null) || current?.id != original?.id ||
            (current != null && digest(current) != originalHash)
        ) fail("CONFLICT", "文件在写入期间发生变化，请重新读取")
    }

    private suspend fun writeDocument(document: SafDocument, content: ByteArray) {
        requireCapability(document, Document.FLAG_SUPPORTS_WRITE, "READ_ONLY", "该文档不允许写入")
        val output = contentResolver.openOutputStream(document.uri, "wt") ?: fail("UNAVAILABLE", "提供商未能打开写入流")
        output.use {
            var offset = 0
            while (offset < content.size) {
                currentCoroutineContext().ensureActive()
                val count = minOf(8192, content.size - offset)
                it.write(content, offset, count)
                offset += count
            }
            it.flush()
        }
    }

    private fun createDocument(parent: SafDocument, name: String, mimeType: String): SafDocument {
        val uri = DocumentsContract.createDocument(contentResolver, parent.uri, mimeType, name) ?: fail("UNAVAILABLE", "提供商未能创建文档")
        return queryDocument(uri)
    }

    private fun rename(document: SafDocument, name: String): SafDocument {
        val uri = DocumentsContract.renameDocument(contentResolver, document.uri, name) ?: fail("UNAVAILABLE", "提供商未能重命名文档")
        return queryDocument(uri)
    }

    private fun requireReplacement(document: SafDocument) {
        requireCapability(document, Document.FLAG_SUPPORTS_RENAME, "UNSUPPORTED", "提供商不支持安全重命名，现有内容保持原状")
        requireCapability(document, Document.FLAG_SUPPORTS_DELETE, "UNSUPPORTED", "提供商不支持替换后的安全清理")
    }

    private fun cleanup(document: SafDocument, primaryFailure: Throwable?) {
        try {
            // 文档标识在重命名后可能保持不变，不能删除已经发布或恢复的同一 URI。
            val current = queryDocument(document.uri)
            if (current.id == document.id && current.name == document.name) {
                DocumentsContract.deleteDocument(contentResolver, current.uri)
            }
        } catch (exception: CancellationException) {
            if (primaryFailure == null) throw exception
            primaryFailure.addSuppressed(exception)
        } catch (exception: Exception) {
            primaryFailure?.addSuppressed(exception)
        }
    }

    private suspend fun entry(path: String, document: SafDocument, hash: Boolean): ExternalFolderEntry =
        ExternalFolderEntry(path, document.name, document.isDirectory, if (document.isDirectory) 0 else document.size,
            document.lastModified, if (hash && !document.isDirectory) digest(document) else null,
            if (document.isDirectory) "doc:${document.id.length}:${document.id}"
            else "doc:${document.id.length}:${document.id}:${document.lastModified}:${document.size}")

    private suspend fun digest(document: SafDocument): String {
        requireFile(document)
        val digest = MessageDigest.getInstance("SHA-256")
        input(document).use { source ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun input(document: SafDocument) = contentResolver.openInputStream(document.uri) ?: fail("UNAVAILABLE", "提供商未能打开读取流")
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun find(path: String): SafDocument? {
        if (treeUri.scheme != ContentResolver.SCHEME_CONTENT || !DocumentsContract.isTreeUri(treeUri)) fail("UNAVAILABLE", "文件夹授权无效，请重新授权")
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        var current = queryDocument(rootUri)
        if (!current.isDirectory) fail("UNAVAILABLE", "挂载来源不是目录")
        if (path == "/") return current
        for (name in path.removePrefix("/").split('/')) {
            requireDirectory(current)
            current = child(current, name) ?: return null
        }
        return current
    }

    private fun child(parent: SafDocument, name: String): SafDocument? {
        val matching = children(parent).filter { it.name == name }
        if (matching.size > 1) fail("CONFLICT", "提供商返回多个同名文档，无法安全定位")
        return matching.singleOrNull()
    }

    private fun children(parent: SafDocument): List<SafDocument> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.id)
        return contentResolver.query(uri, PROJECTION, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(document(cursor)) }
        } ?: fail("UNAVAILABLE", "提供商未能查询目录，请检查授权")
    }

    private fun queryDocument(uri: Uri): SafDocument = contentResolver.query(uri, PROJECTION, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) fail("UNAVAILABLE", "文件夹授权或文档已失效，请重新授权")
        document(cursor)
    } ?: fail("UNAVAILABLE", "提供商未能查询文档，请检查授权")

    private fun document(cursor: Cursor): SafDocument {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)) ?: fail("UNAVAILABLE", "提供商缺少文档标识")
        fun long(column: String): Long = cursor.getColumnIndex(column).let { if (it < 0 || cursor.isNull(it)) 0L else cursor.getLong(it) }
        return SafDocument(id, cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)) ?: "",
            cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)) ?: "application/octet-stream",
            long(Document.COLUMN_SIZE).coerceAtLeast(0), long(Document.COLUMN_LAST_MODIFIED), long(Document.COLUMN_FLAGS).toInt(),
            DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
    }

    private fun requireDirectory(document: SafDocument) { if (!document.isDirectory) fail("NOT_DIRECTORY", "目标不是目录") }
    private fun requireFile(document: SafDocument) {
        if (document.isDirectory) fail("IS_DIRECTORY", "目标是目录")
        if (document.supports(Document.FLAG_VIRTUAL_DOCUMENT)) fail("UNSUPPORTED", "不支持虚拟文档")
    }
    private fun requireCapability(document: SafDocument, flag: Int, code: String, message: String) { if (!document.supports(flag)) fail(code, message) }
    private fun visibleName(name: String): Boolean = try { ExternalFolderPaths.validateName(name); true } catch (_: ExternalFolderException) { false }
    private fun mutationPath(path: String): String = ExternalFolderPaths.normalize(path).also { if (it == "/") fail("INVALID_PATH", "不能修改挂载根目录") }
    private fun parentPath(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

    private suspend fun <T> io(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            currentCoroutineContext().ensureActive()
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ExternalFolderException) {
                throw exception
            } catch (exception: SecurityException) {
                fail("UNAVAILABLE", "文件夹授权已失效，请重新授权", exception)
            } catch (exception: FileNotFoundException) {
                fail("UNAVAILABLE", "提供商或存储不可用，请检查授权", exception)
            } catch (exception: UnsupportedOperationException) {
                throw ExternalFolderException("UNSUPPORTED", "提供商不支持该文件操作").apply { initCause(exception) }
            } catch (exception: IOException) {
                fail("UNAVAILABLE", "提供商读写失败，请检查存储与授权", exception)
            } catch (exception: IllegalArgumentException) {
                fail("UNAVAILABLE", "文件夹授权或提供商响应无效", exception)
            } catch (exception: IllegalStateException) {
                fail("UNAVAILABLE", "提供商当前不可用", exception)
            }
        }
    }

    private fun fail(code: String, message: String, cause: Throwable? = null): Nothing =
        throw ExternalFolderException(code, message).apply { if (cause != null) initCause(cause) }
    private data class SafDocument(val id: String, val name: String, val mimeType: String, val size: Long, val lastModified: Long, val flags: Int, val uri: Uri) {
        val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
        fun supports(flag: Int): Boolean = flags and flag != 0
    }

    companion object {
        private const val STAT_HASH_LIMIT = 1024L * 1024
        internal suspend fun boundedStatSha256(input: InputStream): String? {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val allowed = (STAT_HASH_LIMIT - total + 1).coerceAtMost(buffer.size.toLong()).toInt()
                val count = input.read(buffer, 0, allowed)
                if (count < 0) break
                total += count
                if (total > STAT_HASH_LIMIT) return null
                digest.update(buffer, 0, count)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
