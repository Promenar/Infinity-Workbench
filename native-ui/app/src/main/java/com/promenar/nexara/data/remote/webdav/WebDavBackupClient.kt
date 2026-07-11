package com.promenar.nexara.data.remote.webdav

import com.promenar.nexara.data.backup.BackupPackageLimits
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import java.util.UUID

data class WebDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    override fun toString(): String =
        "WebDavConfig(baseUrl=<已配置>, username=<已配置>, password=****)"
}

data class RemoteBackup(
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
)

data class WebDavTimeouts(
    val testMillis: Long = 15_000,
    val propfindMillis: Long = 30_000,
    val getMillis: Long = 120_000,
    val putMillis: Long = 120_000,
    val moveMillis: Long = 30_000,
    val deleteMillis: Long = 30_000,
    val cleanupMillis: Long = 5_000,
) {
    init {
        require(listOf(testMillis, propfindMillis, getMillis, putMillis, moveMillis, deleteMillis, cleanupMillis).all { it > 0 })
    }
}

data class WebDavPruneWarning(
    val message: String,
    val failedFileNames: List<String> = emptyList(),
)

sealed interface UploadAndPruneResult {
    val fileName: String

    data class Committed(
        override val fileName: String,
        val pruneWarning: WebDavPruneWarning? = null,
    ) : UploadAndPruneResult
}

interface WebDavBackupClient {
    suspend fun test(config: WebDavConfig): Result<Unit>
    suspend fun uploadAtomic(config: WebDavConfig, fileName: String, bytes: ByteArray)
    suspend fun uploadAndPrune(
        config: WebDavConfig,
        fileName: String,
        bytes: ByteArray,
        keep: Int = 5,
    ): UploadAndPruneResult
    suspend fun list(config: WebDavConfig): List<RemoteBackup>
    suspend fun download(config: WebDavConfig, fileName: String): ByteArray
    suspend fun prune(config: WebDavConfig, keep: Int = 5)
}

open class WebDavException(message: String) : IllegalStateException(message)

class WebDavPruneException(
    val failedFileNames: List<String>,
) : WebDavException("远程备份清理未完全成功：${failedFileNames.size} 个文件删除失败")

class KtorWebDavBackupClient(
    private val httpClient: HttpClient = HttpClient(OkHttp),
    private val uuidFactory: () -> UUID = UUID::randomUUID,
    private val timeouts: WebDavTimeouts = WebDavTimeouts(),
    private val parser: DavMultiStatusParser = DavMultiStatusParser(),
) : WebDavBackupClient {
    override suspend fun test(config: WebDavConfig): Result<Unit> {
        return try {
            val collection = validatedCollection(config)
            val response = timedRequest("连接测试", timeouts.testMillis) {
                httpClient.request(collection.toASCIIString()) {
                    method = PROPFIND
                    authenticated(config)
                    header(DEPTH, "0")
                    contentType(ContentType.Application.Xml)
                    setBody(PROPFIND_BODY)
                }
            }
            requireExactStatus("连接测试", response, PROPFIND_STATUSES)
            val body = readLimited(response, LIST_RESPONSE_LIMIT, "WebDAV 列表响应")
            if (!parser.parse(body, collection).containsTargetCollection) {
                throw WebDavException("WebDAV 连接测试失败：目标目录未确认")
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: WebDavException) {
            Result.failure(error)
        } catch (_: Exception) {
            Result.failure(WebDavException("WebDAV 连接测试失败"))
        }
    }

    override suspend fun uploadAtomic(config: WebDavConfig, fileName: String, bytes: ByteArray) {
        val collection = validatedCollection(config)
        val safeName = validatedBackupFileName(fileName)
        requirePayloadSize(bytes.size.toLong(), "上传")
        val temporaryUrl = childUri(collection, "$safeName.tmp-${uuidFactory()}")
        val finalUrl = childUri(collection, safeName)

        try {
            val putResponse = timedRequest("上传", timeouts.putMillis) {
                httpClient.request(temporaryUrl.toASCIIString()) {
                    method = HttpMethod.Put
                    authenticated(config)
                    contentType(ContentType.Application.OctetStream)
                    setBody(bytes)
                }
            }
            requireAndDiscard("上传", putResponse, PUT_STATUSES)

            val moveResponse = timedRequest("提交上传", timeouts.moveMillis) {
                httpClient.request(temporaryUrl.toASCIIString()) {
                    method = MOVE
                    authenticated(config)
                    header(DESTINATION, finalUrl.toASCIIString())
                    header(OVERWRITE, "F")
                }
            }
            try {
                when (moveResponse.status) {
                    HttpStatusCode.PreconditionFailed, HttpStatusCode.Conflict ->
                        throw WebDavException("远程备份文件已存在，未覆盖现有文件")
                    HttpStatusCode.MethodNotAllowed ->
                        throw WebDavException("WebDAV 服务端不支持原子 MOVE，无法安全提交备份")
                    !in MOVE_STATUSES ->
                        throw WebDavException("WebDAV 提交上传失败（HTTP ${moveResponse.status.value}）")
                }
            } finally {
                discard(moveResponse)
            }
        } catch (error: CancellationException) {
            cleanupTemporary(config, temporaryUrl)?.let(error::addSuppressed)
            throw error
        } catch (error: WebDavException) {
            cleanupTemporary(config, temporaryUrl)?.let(error::addSuppressed)
            throw error
        } catch (_: Exception) {
            val safe = WebDavException("远程备份提交失败，临时文件已尝试清理")
            cleanupTemporary(config, temporaryUrl)?.let(safe::addSuppressed)
            throw safe
        }
    }

    override suspend fun uploadAndPrune(
        config: WebDavConfig,
        fileName: String,
        bytes: ByteArray,
        keep: Int,
    ): UploadAndPruneResult {
        uploadAtomic(config, fileName, bytes)
        val warning = try {
            prune(config, keep)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: WebDavPruneException) {
            WebDavPruneWarning("备份已提交，但部分旧备份清理失败", error.failedFileNames)
        } catch (_: WebDavException) {
            WebDavPruneWarning("备份已提交，但远程保留策略未完成")
        }
        return UploadAndPruneResult.Committed(fileName, warning)
    }

    override suspend fun list(config: WebDavConfig): List<RemoteBackup> {
        val collection = validatedCollection(config)
        val response = timedRequest("列出备份", timeouts.propfindMillis) {
            httpClient.request(collection.toASCIIString()) {
                method = PROPFIND
                authenticated(config)
                header(DEPTH, "1")
                contentType(ContentType.Application.Xml)
                setBody(PROPFIND_BODY)
            }
        }
        requireExactStatus("列出备份", response, PROPFIND_STATUSES)
        val parsed = parser.parse(readLimited(response, LIST_RESPONSE_LIMIT, "WebDAV 列表响应"), collection)
        return parsed.backups.sortedWith(
            compareByDescending<RemoteBackup> { it.lastModifiedEpochMillis }
                .thenByDescending { backupTimestamp(it.fileName) }
                .thenByDescending { it.fileName },
        )
    }

    override suspend fun download(config: WebDavConfig, fileName: String): ByteArray {
        val collection = validatedCollection(config)
        val safeName = validatedBackupFileName(fileName)
        val response = timedRequest("下载", timeouts.getMillis) {
            httpClient.request(childUri(collection, safeName).toASCIIString()) {
                method = HttpMethod.Get
                authenticated(config)
            }
        }
        requireExactStatus("下载", response, GET_STATUSES)
        return readLimited(response, BackupPackageLimits.MAX_IN_MEMORY_BYTES, "远程备份")
    }

    override suspend fun prune(config: WebDavConfig, keep: Int) {
        if (keep < 1) throw WebDavException("远程备份保留数量必须至少为 1")
        val collection = validatedCollection(config)
        val failures = mutableListOf<String>()
        for (backup in list(config).drop(keep)) {
            try {
                val response = timedRequest("删除旧备份", timeouts.deleteMillis) {
                    httpClient.delete(childUri(collection, validatedBackupFileName(backup.fileName)).toASCIIString()) {
                        authenticated(config)
                    }
                }
                try {
                    if (response.status !in DELETE_STATUSES) failures += backup.fileName
                } finally {
                    discard(response)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failures += backup.fileName
            }
        }
        if (failures.isNotEmpty()) throw WebDavPruneException(failures)
    }

    private suspend fun cleanupTemporary(config: WebDavConfig, temporaryUrl: URI): WebDavException? =
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                withTimeout(timeouts.cleanupMillis) {
                    val response = httpClient.delete(temporaryUrl.toASCIIString()) { authenticated(config) }
                    try {
                        if (response.status !in DELETE_STATUSES) {
                            return@withTimeout WebDavException("WebDAV 临时文件清理未完成（HTTP ${response.status.value}）")
                        }
                    } finally {
                        discard(response)
                    }
                    null
                }
            } catch (_: TimeoutCancellationException) {
                WebDavException("WebDAV 临时文件清理超时")
            } catch (_: Exception) {
                WebDavException("WebDAV 临时文件清理失败")
            }
        }

    private suspend fun timedRequest(
        operation: String,
        timeoutMillis: Long,
        block: suspend () -> HttpResponse,
    ): HttpResponse = try {
        withContext(Dispatchers.IO) { withTimeout(timeoutMillis) { block() } }
    } catch (_: TimeoutCancellationException) {
        throw WebDavException("WebDAV $operation 超时")
    } catch (error: CancellationException) {
        if (error.hasTimeoutCause()) throw WebDavException("WebDAV $operation 超时")
        throw error
    } catch (_: Exception) {
        throw WebDavException("WebDAV $operation 失败")
    }

    private fun Throwable.hasTimeoutCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is TimeoutCancellationException) return true
            current = current.cause
        }
        return false
    }

    private suspend fun requireExactStatus(operation: String, response: HttpResponse, allowed: Set<HttpStatusCode>) {
        if (response.status !in allowed) {
            discard(response)
            throw WebDavException("WebDAV $operation 失败（HTTP ${response.status.value}）")
        }
    }

    private suspend fun requireAndDiscard(operation: String, response: HttpResponse, allowed: Set<HttpStatusCode>) {
        try {
            if (response.status !in allowed) {
                throw WebDavException("WebDAV $operation 失败（HTTP ${response.status.value}）")
            }
        } finally {
            discard(response)
        }
    }

    private suspend fun discard(response: HttpResponse) {
        response.bodyAsChannel().cancel(null)
    }

    private suspend fun readLimited(response: HttpResponse, limit: Long, label: String): ByteArray {
        val channel = response.bodyAsChannel()
        try {
            val rawLength = response.headers[HttpHeaders.ContentLength]
            val declaredLength = rawLength?.toLongOrNull()
            if (rawLength != null && (declaredLength == null || declaredLength < 0 || declaredLength > limit)) {
                throw WebDavException("$label 过大或长度无效，已在读取前拒绝")
            }
            val output = ByteArrayOutputStream((declaredLength ?: 0L).coerceAtMost(64L * 1024).toInt())
            val buffer = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > limit) throw WebDavException("$label 过大，已停止读取")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        } finally {
            channel.cancel(null)
        }
    }

    private fun validatedCollection(config: WebDavConfig): URI {
        if (config.username.any { it == ':' || it == '\r' || it == '\n' || it.code < 0x20 } ||
            config.password.any { it == '\r' || it == '\n' || it.code < 0x20 }
        ) throw WebDavException("WebDAV 认证信息格式无效")

        val parsed = try {
            URI(config.baseUrl.trim())
        } catch (_: Exception) {
            throw WebDavException("WebDAV 地址格式无效")
        }
        if (!parsed.scheme.equals("https", ignoreCase = true)) throw WebDavException("WebDAV 地址必须使用 HTTPS")
        if (parsed.host.isNullOrBlank() || parsed.rawUserInfo != null || parsed.rawQuery != null || parsed.rawFragment != null) {
            throw WebDavException("WebDAV 地址格式无效，不得包含凭证、查询参数或片段")
        }
        val decodedPath = parsed.path ?: "/"
        if (decodedPath.split('/').any { it == "." || it == ".." } ||
            decodedPath.any { it == '\r' || it == '\n' || it.code < 0x20 }
        ) throw WebDavException("WebDAV 地址路径无效")

        val ascii = parsed.normalize().toASCIIString()
        return URI(ascii + if (parsed.rawPath.isNullOrEmpty() || !ascii.endsWith('/')) "/" else "")
    }

    private fun validatedBackupFileName(fileName: String): String =
        fileName.takeIf(NEXARA_BACKUP_FILE::matches)
            ?: throw WebDavException("远程备份文件名格式无效")

    private fun childUri(collection: URI, fileName: String): URI {
        if (!SAFE_CHILD.matches(fileName)) throw WebDavException("远程文件名格式无效")
        val child = collection.resolve(fileName)
        if (!sameOrigin(collection, child) || child.path.substringBeforeLast('/', "") != collection.path.trimEnd('/')) {
            throw WebDavException("远程文件路径越界")
        }
        return child
    }

    private fun sameOrigin(left: URI, right: URI): Boolean =
        left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right)

    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else 443

    private fun backupTimestamp(fileName: String): Long =
        NEXARA_BACKUP_FILE.matchEntire(fileName)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun requirePayloadSize(size: Long, operation: String) {
        if (size > BackupPackageLimits.MAX_IN_MEMORY_BYTES) throw WebDavException("WebDAV $operation 内容过大")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authenticated(config: WebDavConfig) {
        val token = Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))
        header(HttpHeaders.Authorization, "Basic $token")
    }

    private companion object {
        val PROPFIND = HttpMethod("PROPFIND")
        val MOVE = HttpMethod("MOVE")
        val PROPFIND_STATUSES = setOf(HttpStatusCode.MultiStatus)
        val GET_STATUSES = setOf(HttpStatusCode.OK)
        val PUT_STATUSES = setOf(HttpStatusCode.OK, HttpStatusCode.Created, HttpStatusCode.NoContent)
        val MOVE_STATUSES = setOf(HttpStatusCode.Created, HttpStatusCode.NoContent)
        val DELETE_STATUSES = setOf(HttpStatusCode.OK, HttpStatusCode.Accepted, HttpStatusCode.NoContent, HttpStatusCode.NotFound)
        const val DEPTH = "Depth"
        const val DESTINATION = "Destination"
        const val OVERWRITE = "Overwrite"
        const val LIST_RESPONSE_LIMIT = 4L * 1024 * 1024
        val SAFE_CHILD = Regex("[A-Za-z0-9._-]{1,220}")
        const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="UTF-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}
