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
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

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

interface WebDavBackupClient {
    suspend fun test(config: WebDavConfig): Result<Unit>
    suspend fun uploadAtomic(config: WebDavConfig, fileName: String, bytes: ByteArray)
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
) : WebDavBackupClient {
    override suspend fun test(config: WebDavConfig): Result<Unit> {
        return try {
            val endpoint = validatedCollection(config)
            val response = execute("连接测试") {
                httpClient.request(endpoint.toASCIIString()) {
                    method = PROPFIND
                    authenticated(config)
                    header(DEPTH, "0")
                    contentType(ContentType.Application.Xml)
                    setBody(PROPFIND_BODY)
                }
            }
            requireStatus("连接测试", response, setOf(HttpStatusCode.MultiStatus))
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
        val temporaryName = "$safeName.tmp-${uuidFactory()}"
        val temporaryUrl = childUri(collection, temporaryName)
        val finalUrl = childUri(collection, safeName)

        val putResponse = execute("上传") {
            httpClient.request(temporaryUrl.toASCIIString()) {
                method = HttpMethod.Put
                authenticated(config)
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }
        }
        requireSuccess("上传", putResponse)

        try {
            val moveResponse = execute("提交上传") {
                httpClient.request(temporaryUrl.toASCIIString()) {
                    method = MOVE
                    authenticated(config)
                    header(DESTINATION, finalUrl.toASCIIString())
                    header(OVERWRITE, "F")
                }
            }
            if (moveResponse.status == HttpStatusCode.PreconditionFailed || moveResponse.status == HttpStatusCode.Conflict) {
                throw WebDavException("远程备份文件已存在，未覆盖现有文件")
            }
            requireSuccess("提交上传", moveResponse)
        } catch (error: CancellationException) {
            cleanupTemporary(config, temporaryUrl)
            throw error
        } catch (error: WebDavException) {
            cleanupTemporary(config, temporaryUrl)
            throw error
        } catch (_: Exception) {
            cleanupTemporary(config, temporaryUrl)
            throw WebDavException("远程备份提交失败，临时文件已尝试清理")
        }
    }

    override suspend fun list(config: WebDavConfig): List<RemoteBackup> {
        val collection = validatedCollection(config)
        val response = execute("列出备份") {
            httpClient.request(collection.toASCIIString()) {
                method = PROPFIND
                authenticated(config)
                header(DEPTH, "1")
                contentType(ContentType.Application.Xml)
                setBody(PROPFIND_BODY)
            }
        }
        requireStatus("列出备份", response, setOf(HttpStatusCode.MultiStatus))
        val xmlBytes = readLimited(response, LIST_RESPONSE_LIMIT, "WebDAV 列表响应")
        return parseMultistatus(xmlBytes, collection)
            .sortedWith(
                compareByDescending<RemoteBackup> { it.lastModifiedEpochMillis }
                    .thenByDescending { backupTimestamp(it.fileName) }
                    .thenByDescending { it.fileName },
            )
    }

    override suspend fun download(config: WebDavConfig, fileName: String): ByteArray {
        val collection = validatedCollection(config)
        val safeName = validatedBackupFileName(fileName)
        val response = execute("下载") {
            httpClient.request(childUri(collection, safeName).toASCIIString()) {
                method = HttpMethod.Get
                authenticated(config)
            }
        }
        requireSuccess("下载", response)
        return readLimited(response, BackupPackageLimits.MAX_IN_MEMORY_BYTES, "远程备份")
    }

    override suspend fun prune(config: WebDavConfig, keep: Int) {
        if (keep < 1) throw WebDavException("远程备份保留数量必须至少为 1")
        val collection = validatedCollection(config)
        val expired = list(config).drop(keep)
        val failures = mutableListOf<String>()
        for (backup in expired) {
            try {
                val response = execute("删除旧备份") {
                    httpClient.delete(childUri(collection, validatedBackupFileName(backup.fileName)).toASCIIString()) {
                        authenticated(config)
                    }
                }
                if (!response.status.isSuccessStatus() && response.status != HttpStatusCode.NotFound) {
                    failures += backup.fileName
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failures += backup.fileName
            }
        }
        if (failures.isNotEmpty()) throw WebDavPruneException(failures.toList())
    }

    private suspend fun cleanupTemporary(config: WebDavConfig, temporaryUrl: URI) {
        try {
            httpClient.delete(temporaryUrl.toASCIIString()) {
                authenticated(config)
            }
        } catch (error: CancellationException) {
            // 清理是尽力而为；不得用清理取消覆盖原始 MOVE 结果。
        } catch (_: Exception) {
            // 清理是尽力而为；调用方仍收到脱敏后的原始提交错误。
        }
    }

    private suspend fun execute(operation: String, block: suspend () -> HttpResponse): HttpResponse {
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: WebDavException) {
            throw error
        } catch (_: Exception) {
            throw WebDavException("WebDAV $operation 失败")
        }
    }

    private fun requireSuccess(operation: String, response: HttpResponse) {
        if (!response.status.isSuccessStatus()) {
            throw WebDavException("WebDAV $operation 失败（HTTP ${response.status.value}）")
        }
    }

    private fun requireStatus(operation: String, response: HttpResponse, allowed: Set<HttpStatusCode>) {
        if (!response.status.isSuccessStatus() && response.status !in allowed) {
            throw WebDavException("WebDAV $operation 失败（HTTP ${response.status.value}）")
        }
    }

    private suspend fun readLimited(response: HttpResponse, limit: Long, label: String): ByteArray {
        val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && (declaredLength < 0 || declaredLength > limit)) {
            throw WebDavException("$label 过大，已在读取前拒绝")
        }

        val channel = response.bodyAsChannel()
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
    }

    private fun parseMultistatus(bytes: ByteArray, collection: URI): List<RemoteBackup> {
        val document = try {
            val factory = secureDocumentBuilderFactory()
            factory.newDocumentBuilder().apply {
                setErrorHandler(object : DefaultHandler() {
                    override fun warning(error: SAXParseException) = throw error
                    override fun error(error: SAXParseException) = throw error
                    override fun fatalError(error: SAXParseException) = throw error
                })
            }.parse(InputSource(ByteArrayInputStream(bytes)))
        } catch (_: Exception) {
            throw WebDavException("WebDAV 列表响应格式不安全或无效")
        }

        val responses = document.getElementsByTagNameNS(DAV_NAMESPACE, "response")
        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index) as? Element ?: continue
                val propStatus = response.firstDavText("status") ?: continue
                if (!propStatus.contains(" 200 ")) continue
                if (response.getElementsByTagNameNS(DAV_NAMESPACE, "collection").length > 0) continue
                val href = response.firstDavText("href") ?: continue
                val fileName = sameCollectionFileName(collection, href) ?: continue
                if (!BACKUP_FILE.matches(fileName)) continue
                val size = response.firstDavText("getcontentlength")?.toLongOrNull() ?: 0L
                if (size < 0 || size > BackupPackageLimits.MAX_IN_MEMORY_BYTES) continue
                val modified = parseServerTime(response.firstDavText("getlastmodified"))
                add(RemoteBackup(fileName, size, modified))
            }
        }.distinctBy { it.fileName }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }

    private fun Element.firstDavText(localName: String): String? {
        val nodes = getElementsByTagNameNS(DAV_NAMESPACE, localName)
        return if (nodes.length == 0) null else nodes.item(0).textContent?.trim()
    }

    private fun sameCollectionFileName(collection: URI, href: String): String? {
        if (href.any { it == '\r' || it == '\n' || it.code < 0x20 }) return null
        val resolved = try {
            collection.resolve(URI(href)).normalize()
        } catch (_: Exception) {
            return null
        }
        if (!sameOrigin(collection, resolved) || resolved.rawUserInfo != null ||
            resolved.rawQuery != null || resolved.rawFragment != null
        ) return null
        val decodedPath = resolved.path ?: return null
        val basePath = collection.path
        if (!decodedPath.startsWith(basePath)) return null
        val tail = decodedPath.removePrefix(basePath)
        if (tail.isEmpty() || tail.contains('/')) return null
        return tail
    }

    private fun validatedCollection(config: WebDavConfig): URI {
        if (config.username.any { it == ':' || it == '\r' || it == '\n' || it.code < 0x20 } ||
            config.password.any { it == '\r' || it == '\n' || it.code < 0x20 }
        ) {
            throw WebDavException("WebDAV 认证信息格式无效")
        }
        val raw = config.baseUrl.trim()
        val parsed = try {
            URI(raw)
        } catch (_: Exception) {
            throw WebDavException("WebDAV 地址格式无效")
        }
        if (!parsed.scheme.equals("https", ignoreCase = true)) {
            throw WebDavException("WebDAV 地址必须使用 HTTPS")
        }
        if (parsed.host.isNullOrBlank() || parsed.rawUserInfo != null || parsed.rawQuery != null || parsed.rawFragment != null) {
            throw WebDavException("WebDAV 地址格式无效，不得包含凭证、查询参数或片段")
        }
        val decodedPath = parsed.path ?: "/"
        if (decodedPath.split('/').any { it == "." || it == ".." } ||
            decodedPath.any { it == '\r' || it == '\n' || it.code < 0x20 }
        ) {
            throw WebDavException("WebDAV 地址路径无效")
        }
        val normalized = parsed.normalize()
        val safePath = normalized.rawPath?.let { if (it.endsWith('/')) it else "$it/" } ?: "/"
        return URI(normalized.scheme, null, normalized.host, normalized.port, safePath, null, null)
    }

    private fun validatedBackupFileName(fileName: String): String {
        if (!BACKUP_FILE.matches(fileName)) {
            throw WebDavException("远程备份文件名格式无效")
        }
        return fileName
    }

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

    private fun parseServerTime(value: String?): Long = try {
        if (value == null) 0L
        else ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    private fun backupTimestamp(fileName: String): Long =
        BACKUP_FILE.matchEntire(fileName)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun requirePayloadSize(size: Long, operation: String) {
        if (size > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
            throw WebDavException("WebDAV $operation 内容过大")
        }
    }

    private fun HttpStatusCode.isSuccessStatus(): Boolean = value in 200..299

    private fun io.ktor.client.request.HttpRequestBuilder.authenticated(config: WebDavConfig) {
        val token = Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))
        header(HttpHeaders.Authorization, "Basic $token")
    }

    private companion object {
        val PROPFIND = HttpMethod("PROPFIND")
        val MOVE = HttpMethod("MOVE")
        const val DEPTH = "Depth"
        const val DESTINATION = "Destination"
        const val OVERWRITE = "Overwrite"
        const val DAV_NAMESPACE = "DAV:"
        const val LIST_RESPONSE_LIMIT = 4L * 1024 * 1024
        val BACKUP_FILE = Regex("nexara_backup_([0-9]{13})\\.nexara")
        val SAFE_CHILD = Regex("[A-Za-z0-9._-]{1,220}")
        const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="UTF-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}
