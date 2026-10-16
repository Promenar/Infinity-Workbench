package com.promenar.nexara.data.rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Serializable
internal data class GraphCheckpointIdentity(
    val contentHash: String,
    val textHash: String,
    val modelId: String?,
    val promptHash: String,
    val chunkSize: Int,
    val chunkOverlap: Int,
    val validationVersion: Int = 1,
)

@Serializable
private data class GraphChunkCheckpoint(
    val identity: GraphCheckpointIdentity,
    val chunkHash: String,
    val result: ExtractionResult,
)

/** 路径不接受原始文件名；正文和配置身份必须同时匹配才能恢复。 */
internal class GraphExtractionCheckpoints(private val root: File) {
    fun directory(docId: String): File = File(root, kgSha256(docId))

    fun load(docId: String, identity: GraphCheckpointIdentity, index: Int, chunk: String): ExtractionResult? = try {
        val file = chunkFile(docId, identity, index)
        if (!file.isFile) null else {
            val saved = Json.decodeFromString<GraphChunkCheckpoint>(file.readText())
            if (saved.identity == identity && saved.chunkHash == kgSha256(chunk) && saved.result.isSuccess) saved.result
            else { file.delete(); null }
        }
    } catch (_: Exception) { null }

    fun prepare(docId: String, identity: GraphCheckpointIdentity) {
        legacyDirectory(docId)?.deleteRecursively()
        val dir = directory(docId)
        val current = identityKey(identity)
        dir.listFiles()?.filter { it.name != current }?.forEach { it.deleteRecursively() }
    }

    fun save(docId: String, identity: GraphCheckpointIdentity, index: Int, chunk: String, result: ExtractionResult) {
        val file = chunkFile(docId, identity, index)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建图谱断点目录" }
        val temp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(Json.encodeToString(GraphChunkCheckpoint.serializer(), GraphChunkCheckpoint(identity, kgSha256(chunk), result)))
            check(temp.renameTo(file)) { "无法写入图谱断点" }
        } finally { temp.delete() }
    }

    fun clear(docId: String) {
        directory(docId).deleteRecursively()
        legacyDirectory(docId)?.deleteRecursively()
    }

    private fun legacyDirectory(docId: String): File? = File(root, docId).canonicalFile
        .takeIf { it.parentFile == root.canonicalFile && it.name != kgSha256(docId) }

    private fun identityKey(identity: GraphCheckpointIdentity) = kgSha256(Json.encodeToString(GraphCheckpointIdentity.serializer(), identity))
    private fun chunkFile(docId: String, identity: GraphCheckpointIdentity, index: Int) =
        File(File(directory(docId), identityKey(identity)), "chunk_$index.json")
}

internal fun kgSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
