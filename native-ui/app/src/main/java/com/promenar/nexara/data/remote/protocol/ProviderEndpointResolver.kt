package com.promenar.nexara.data.remote.protocol

import java.net.URI

enum class ProviderEndpointOperation {
    INFERENCE,
    MODELS,
    CONNECTION_PROBE,
}

object ProviderEndpointResolver {
    fun resolve(
        protocol: ProtocolType,
        configuredBaseUrl: String,
        operation: ProviderEndpointOperation,
    ): String {
        val raw = configuredBaseUrl.trim().ifEmpty { protocol.defaultBaseUrl }
        if (protocol is ProtocolType.Local) return raw.trimEnd('/')
        require(raw.isNotEmpty()) { "Provider base URL 不能为空" }

        val uri = try {
            URI(raw)
        } catch (error: Exception) {
            throw IllegalArgumentException("Provider base URL 无效", error)
        }
        require(uri.scheme == "http" || uri.scheme == "https") { "Provider base URL 必须使用 HTTP(S)" }
        require(!uri.rawAuthority.isNullOrBlank()) { "Provider base URL 缺少 host" }
        require(uri.rawFragment == null) { "Provider base URL 不支持 fragment" }

        var basePath = normalizePath(uri.rawPath.orEmpty())
        if (operation == ProviderEndpointOperation.CONNECTION_PROBE &&
            protocol == ProtocolType.Anthropic_Messages
        ) {
            throw IllegalArgumentException("Anthropic 连接探测尚未接线，拒绝回退模型列表")
        }
        val operationPath = operationPath(protocol, operation)
        if (operation != ProviderEndpointOperation.INFERENCE &&
            protocol.defaultPath.isNotEmpty() &&
            basePath.endsWith(normalizePath(protocol.defaultPath))
        ) {
            basePath = basePath.removeSuffix(normalizePath(protocol.defaultPath))
        } else if (operation == ProviderEndpointOperation.MODELS &&
            looksLikeAmbiguousInferenceResource(basePath, protocol.defaultPath)
        ) {
            throw IllegalArgumentException(
                "Provider base URL 的完整推理路径无法无歧义转换为模型列表端点",
            )
        }
        val resolvedPath = appendWithOverlap(basePath, operationPath)
        return buildString {
            append(uri.scheme)
            append("://")
            append(uri.rawAuthority)
            append(resolvedPath)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    private fun operationPath(protocol: ProtocolType, operation: ProviderEndpointOperation): String = when (operation) {
        ProviderEndpointOperation.INFERENCE -> normalizePath(protocol.defaultPath)
        ProviderEndpointOperation.MODELS,
        ProviderEndpointOperation.CONNECTION_PROBE,
        -> when (protocol) {
            ProtocolType.Google_VertexAI -> normalizePath(protocol.defaultPath)
            ProtocolType.Local -> ""
            else -> apiVersionPrefix(protocol.defaultPath) + "/models"
        }
    }

    private fun apiVersionPrefix(path: String): String {
        val first = normalizePath(path).split('/').firstOrNull(String::isNotEmpty)
        return first?.let { "/$it" }.orEmpty()
    }

    private fun looksLikeAmbiguousInferenceResource(basePath: String, defaultPath: String): Boolean {
        val defaultSegments = normalizePath(defaultPath).split('/').filter(String::isNotEmpty)
        val resourceSegments = defaultSegments.dropWhile { it.matches(Regex("v\\d+")) }
        val baseSegments = normalizePath(basePath).split('/').filter(String::isNotEmpty)
        return resourceSegments.isNotEmpty() && baseSegments.takeLast(resourceSegments.size) == resourceSegments
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank() || path == "/") return ""
        return "/" + path.split('/').filter(String::isNotEmpty).joinToString("/")
    }

    private fun appendWithOverlap(basePath: String, targetPath: String): String {
        val base = normalizePath(basePath)
        val target = normalizePath(targetPath)
        if (target.isEmpty()) return base
        val baseSegments = base.split('/').filter(String::isNotEmpty)
        val targetSegments = target.split('/').filter(String::isNotEmpty)
        val overlap = (minOf(baseSegments.size, targetSegments.size) downTo 1)
            .firstOrNull { size -> baseSegments.takeLast(size) == targetSegments.take(size) }
            ?: 0
        return "/" + (baseSegments + targetSegments.drop(overlap)).joinToString("/")
    }
}
