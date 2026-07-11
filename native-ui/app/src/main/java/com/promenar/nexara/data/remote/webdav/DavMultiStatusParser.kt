package com.promenar.nexara.data.remote.webdav

import com.promenar.nexara.data.backup.BackupPackageLimits
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal val NEXARA_BACKUP_FILE = Regex("nexara_backup_([0-9]{13})\\.nexara")

data class DavMultiStatus(
    val backups: List<RemoteBackup>,
    val containsTargetCollection: Boolean,
)

class DavMultiStatusParser {
    fun parse(bytes: ByteArray, collection: URI): DavMultiStatus {
        val document = try {
            secureFactory().newDocumentBuilder().apply {
                setErrorHandler(object : DefaultHandler() {
                    override fun warning(error: SAXParseException) = throw error
                    override fun error(error: SAXParseException) = throw error
                    override fun fatalError(error: SAXParseException) = throw error
                })
            }.parse(InputSource(ByteArrayInputStream(bytes)))
        } catch (_: Exception) {
            throw WebDavException("WebDAV 列表响应格式不安全或无效")
        }
        val root = document.documentElement
        if (root.namespaceURI != DAV || root.localName != "multistatus") {
            throw WebDavException("WebDAV 列表响应格式无效")
        }

        var containsTarget = false
        val backups = mutableListOf<RemoteBackup>()
        for (response in root.directDavChildren("response")) {
            val href = response.directDavText("href") ?: continue
            val resolved = resolveHref(collection, href) ?: continue
            val successfulProps = response.directDavChildren("propstat").mapNotNull { propstat ->
                val status = propstat.directDavText("status") ?: return@mapNotNull null
                if (!status.isSuccessfulDavStatus()) return@mapNotNull null
                propstat.directDavChildren("prop").singleOrNull()
            }
            if (successfulProps.isEmpty()) continue

            val isCollection = successfulProps.any { prop ->
                prop.directDavChildren("resourcetype").any { type ->
                    type.directDavChildren("collection").isNotEmpty()
                }
            }
            if (isCollection && sameCollection(collection, resolved)) {
                containsTarget = true
                continue
            }
            if (isCollection) continue

            val fileName = sameDirectoryFileName(collection, resolved) ?: continue
            if (!NEXARA_BACKUP_FILE.matches(fileName)) continue
            val size = successfulProps.firstNotNullOfOrNull { it.directDavText("getcontentlength")?.toLongOrNull() } ?: 0L
            if (size < 0 || size > BackupPackageLimits.MAX_IN_MEMORY_BYTES) continue
            val modified = successfulProps.firstNotNullOfOrNull { it.directDavText("getlastmodified") }
                .toServerEpochMillis()
            backups += RemoteBackup(fileName, size, modified)
        }
        return DavMultiStatus(backups.distinctBy { it.fileName }, containsTarget)
    }

    private fun resolveHref(collection: URI, href: String): URI? {
        if (href.any { it == '\r' || it == '\n' || it.code < 0x20 }) return null
        val resolved = try {
            collection.resolve(URI(href)).normalize()
        } catch (_: Exception) {
            return null
        }
        if (!sameOrigin(collection, resolved) || resolved.rawUserInfo != null ||
            resolved.rawQuery != null || resolved.rawFragment != null
        ) return null
        return resolved
    }

    private fun sameCollection(collection: URI, candidate: URI): Boolean =
        collection.path.trimEnd('/') == candidate.path.trimEnd('/')

    private fun sameDirectoryFileName(collection: URI, candidate: URI): String? {
        val basePath = collection.path.trimEnd('/') + "/"
        val candidatePath = candidate.path ?: return null
        if (!candidatePath.startsWith(basePath)) return null
        val tail = candidatePath.removePrefix(basePath)
        return tail.takeIf { it.isNotEmpty() && !it.contains('/') }
    }

    private fun sameOrigin(left: URI, right: URI): Boolean =
        left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right)

    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else 443

    private fun Element.directDavChildren(localName: String): List<Element> = buildList {
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE && child.namespaceURI == DAV && child.localName == localName) {
                add(child as Element)
            }
        }
    }

    private fun Element.directDavText(localName: String): String? =
        directDavChildren(localName).singleOrNull()?.textContent?.trim()

    private fun String.isSuccessfulDavStatus(): Boolean {
        val code = trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: return false
        return code in 200..299
    }

    private fun String?.toServerEpochMillis(): Long = try {
        if (this == null) 0L
        else ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US))
            .toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    private fun secureFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
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

    private companion object {
        const val DAV = "DAV:"
    }
}
