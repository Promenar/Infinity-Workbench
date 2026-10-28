package com.promenar.infinity.data.linux

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 在用户已授权的 SAF 树中创建独立导出目录；文件使用流式复制，失败清理半文件。 */
class LinuxRunModeSafExportTarget(
    private val resolver: ContentResolver,
    private val tree: Uri,
    private val directoryName: String = "linux-home-export-${UUID.randomUUID()}",
) : LinuxRunModeExportTarget {
    private val directories = mutableMapOf<String, Uri>()
    private val createdPaths = mutableSetOf<String>()
    override val location: String get() = directories[""]?.toString() ?: tree.toString()

    override suspend fun createDirectory(relativePath: String) {
        val (parent, name) = if (relativePath.isEmpty()) {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) to validName(directoryName)
        } else parentAndName(relativePath)
        check(relativePath !in createdPaths) { "导出目录已存在" }
        val uri = create(parent, name, Document.MIME_TYPE_DIR)
        directories[relativePath] = uri
        createdPaths += relativePath
    }

    override suspend fun writeFile(relativePath: String, input: InputStream): Long {
        val (parent, name) = parentAndName(relativePath)
        check(relativePath !in createdPaths) { "导出文件已存在" }
        val uri = create(parent, name, "application/octet-stream")
        try {
            val output = resolver.openOutputStream(uri, "wt") ?: error("SAF 提供商无法打开写入流")
            val bytes = output.use { sink ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                    total += count
                }
                total
            }
            createdPaths += relativePath
            return bytes
        } catch (error: Throwable) {
            runCatching { check(DocumentsContract.deleteDocument(resolver, uri)) { "SAF 提供商未清理半文件" } }
                .exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun parentAndName(relative: String): Pair<Uri, String> {
        require(relative.isNotEmpty() && !relative.startsWith('/')) { "导出路径必须相对目标目录" }
        val parts = relative.split('/').map(::validName)
        val parent = parts.dropLast(1).joinToString("/")
        return checkNotNull(directories[parent]) { "导出父目录尚未创建" } to parts.last()
    }

    private fun create(parent: Uri, name: String, mime: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val existing = resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?: error("SAF 提供商无法列出目标目录")
        existing.use { cursor ->
            while (cursor.moveToNext()) check(cursor.getString(0) != name) { "导出目标名称已被占用" }
        }
        val created = DocumentsContract.createDocument(resolver, parent, mime, name) ?: error("SAF 提供商无法创建目标")
        try {
            val metadata = resolver.query(created, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)
                ?: error("SAF 提供商无法验证目标")
            metadata.use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == name &&
                    (mime != Document.MIME_TYPE_DIR || cursor.getString(1) == Document.MIME_TYPE_DIR)) { "SAF 提供商返回的目标名称或类型不符" }
            }
            return created
        } catch (error: Throwable) {
            runCatching { check(DocumentsContract.deleteDocument(resolver, created)) { "SAF 提供商未清理目标" } }
                .exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun validName(name: String): String {
        require(name.isNotEmpty() && name !in setOf(".", "..") && '/' !in name && '\\' !in name && '\u0000' !in name) { "导出名称无效" }
        return name
    }
}
