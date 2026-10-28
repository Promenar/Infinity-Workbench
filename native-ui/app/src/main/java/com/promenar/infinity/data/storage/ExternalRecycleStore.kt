package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalRecycleItem
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.json.*

class ExternalRecycleStore(
    private val directory: File,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val retentionMillis: Long = 7L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun save(mountId: String, path: String, tree: ExternalTree): ExternalRecycleItem {
        val existing = items()
        val folders = directory.listFiles().orEmpty().filter { it.isDirectory }
        val storedBytes = folders.sumOf { folder -> folder.listFiles().orEmpty().filter { it.extension == "blob" }.sumOf { it.length() } }
        if (tree.size > maxBytes - storedBytes || existing.size >= 100 || folders.size >= 100) {
            throw ExternalFolderException("RECYCLE_LIMIT", "挂载回收区空间不足；若确需删除，请使用 irreversible=true 重新提交 DELETE 审批（不可恢复）")
        }
        val id = UUID.randomUUID().toString()
        val staging = File(directory, "$id.pending")
        check(staging.mkdirs()) { "无法创建挂载回收副本" }
        try {
            val nodes = buildJsonArray {
                tree.nodes.forEachIndexed { index, node ->
                    if (!node.isDirectory) FileOutputStream(File(staging, "$index.blob")).use {
                        it.write(node.bytes)
                        it.fd.sync()
                    }
                    add(buildJsonObject {
                        put("path", node.relativePath)
                        put("directory", node.isDirectory)
                        put("blob", "$index.blob")
                        put("sha256", externalSha256(node.bytes))
                    })
                }
            }
            val item = ExternalRecycleItem(id, mountId, path, clock(), tree.size)
            val metadata = buildJsonObject {
                put("id", id); put("mountId", mountId); put("path", path)
                put("deletedAt", item.deletedAt); put("size", item.size); put("nodes", nodes)
            }.toString().toByteArray(Charsets.UTF_8)
            if (metadata.size > 1024 * 1024) throw ExternalFolderException("RECYCLE_LIMIT", "回收元数据超过上限，请缩小目录范围")
            FileOutputStream(File(staging, "manifest.json")).use { it.write(metadata); it.fd.sync() }
            check(staging.renameTo(File(directory, id))) { "无法保存挂载回收副本" }
            return item
        } catch (failure: Exception) { staging.deleteRecursively(); throw failure }
    }

    fun load(id: String): ExternalTree {
        val folder = folder(id)
        val metadata = metadata(folder)
        var size = 0L
        val nodes = metadata.getValue("nodes").jsonArray.map { element ->
            val node = element.jsonObject
            val path = node.getValue("path").jsonPrimitive.content
            if (path.isNotEmpty()) com.promenar.infinity.domain.storage.ExternalFolderPaths.normalize(path)
            val isDirectory = node.getValue("directory").jsonPrimitive.boolean
            val blob = node.getValue("blob").jsonPrimitive.content
            if (!Regex("[0-9]+\\.blob").matches(blob)) throw ExternalFolderException("CORRUPT", "回收副本元数据无效")
            val file = File(folder, blob)
            if (!isDirectory && file.length() > maxBytes - size) throw ExternalFolderException("CORRUPT", "回收副本超过限制")
            val bytes = if (isDirectory) byteArrayOf() else file.readBytes()
            size += bytes.size
            if (externalSha256(bytes) != node.getValue("sha256").jsonPrimitive.content) {
                throw ExternalFolderException("CORRUPT", "回收副本校验失败")
            }
            ExternalTreeNode(path, isDirectory, bytes)
        }
        return ExternalTree(nodes)
    }

    fun items(): List<ExternalRecycleItem> {
        directory.mkdirs()
        directory.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith(".pending") && clock() - it.lastModified() >= retentionMillis }
            .forEach { it.deleteRecursively() }
        return directory.listFiles().orEmpty().filter { it.isDirectory && !it.name.endsWith(".pending") }.mapNotNull { folder ->
            val metadata = try { metadata(folder) } catch (_: Exception) {
                if (clock() - folder.lastModified() >= retentionMillis) folder.deleteRecursively()
                return@mapNotNull null
            }
            val deletedAt = metadata.getValue("deletedAt").jsonPrimitive.long
            if (clock() - deletedAt >= retentionMillis) { folder.deleteRecursively(); return@mapNotNull null }
            ExternalRecycleItem(
                metadata.getValue("id").jsonPrimitive.content, metadata.getValue("mountId").jsonPrimitive.content,
                metadata.getValue("path").jsonPrimitive.content, deletedAt, metadata.getValue("size").jsonPrimitive.long,
            )
        }.sortedByDescending { it.deletedAt }
    }

    fun remove(id: String) { folder(id).deleteRecursively() }

    private fun folder(id: String): File {
        if (!Regex("[0-9a-f-]{36}").matches(id)) throw ExternalFolderException("NOT_FOUND", "回收副本不存在")
        return File(directory, id)
    }
    private fun metadata(folder: File): JsonObject {
        val file = File(folder, "manifest.json")
        if (!file.isFile || file.length() > 1024 * 1024) throw ExternalFolderException("CORRUPT", "回收副本元数据无效")
        return Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    }
}
