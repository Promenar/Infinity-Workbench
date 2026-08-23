package com.promenar.nexara.data.remote.provider

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class VertexCredentialFailure {
    MISSING_CREDENTIALS,
    INVALID_JSON,
    MISSING_PROJECT_ID,
    MISSING_CLIENT_EMAIL,
    MISSING_PRIVATE_KEY,
    INVALID_PRIVATE_KEY,
    PROJECT_ID_MISMATCH,
}

class VertexCredentialException(
    val reason: VertexCredentialFailure,
) : IllegalArgumentException("Vertex service account credentials are invalid") {
    override fun toString(): String = "VertexCredentialException(reason=$reason)"
}

class ParsedVertexCredential internal constructor(
    val projectId: String,
    val clientEmail: String,
    internal val privateKey: PrivateKey,
) {
    override fun toString(): String =
        "ParsedVertexCredential(projectId=$projectId, clientEmail=$clientEmail, privateKey=<redacted>)"
}

/** Vertex service-account JSON 的唯一解析与签名入口。 */
object VertexCredentialParser {
    private const val TOKEN_AUDIENCE = "https://oauth2.googleapis.com/token"
    private const val CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        serviceAccountJson: String,
        explicitProjectId: String = "",
    ): ParsedVertexCredential {
        if (serviceAccountJson.isBlank()) {
            throw VertexCredentialException(VertexCredentialFailure.MISSING_CREDENTIALS)
        }
        val root = try {
            json.parseToJsonElement(serviceAccountJson).jsonObject
        } catch (_: Exception) {
            throw VertexCredentialException(VertexCredentialFailure.INVALID_JSON)
        }
        val clientEmail = root["client_email"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw VertexCredentialException(VertexCredentialFailure.MISSING_CLIENT_EMAIL)
        val privateKeyPem = root["private_key"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw VertexCredentialException(VertexCredentialFailure.MISSING_PRIVATE_KEY)
        val privateKey = parsePkcs8RsaKey(privateKeyPem)

        val requestedProjectId = explicitProjectId.trim()
        val credentialProjectId = root["project_id"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (requestedProjectId.isNotEmpty() &&
            credentialProjectId != null &&
            requestedProjectId != credentialProjectId
        ) {
            throw VertexCredentialException(VertexCredentialFailure.PROJECT_ID_MISMATCH)
        }
        val effectiveProjectId = credentialProjectId ?: requestedProjectId.takeIf(String::isNotEmpty)
            ?: throw VertexCredentialException(VertexCredentialFailure.MISSING_PROJECT_ID)

        return ParsedVertexCredential(
            projectId = effectiveProjectId,
            clientEmail = clientEmail,
            privateKey = privateKey,
        )
    }

    fun createJwtAssertion(
        credential: ParsedVertexCredential,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        audience: String = TOKEN_AUDIENCE,
    ): String {
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = buildJsonObject {
            put("iss", credential.clientEmail)
            put("scope", CLOUD_PLATFORM_SCOPE)
            put("aud", audience)
            put("exp", nowEpochSeconds + 3600L)
            put("iat", nowEpochSeconds)
        }.toString()
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val claimsBytes = claims.toByteArray(Charsets.UTF_8)
        try {
            val signInput = "${base64UrlEncode(headerBytes)}.${base64UrlEncode(claimsBytes)}"
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(credential.privateKey)
            val signInputBytes = signInput.toByteArray(Charsets.UTF_8)
            return try {
                signature.update(signInputBytes)
                val signatureBytes = signature.sign()
                try {
                    "$signInput.${base64UrlEncode(signatureBytes)}"
                } finally {
                    signatureBytes.fill(0)
                }
            } finally {
                signInputBytes.fill(0)
            }
        } catch (_: Exception) {
            throw VertexCredentialException(VertexCredentialFailure.INVALID_PRIVATE_KEY)
        } finally {
            headerBytes.fill(0)
            claimsBytes.fill(0)
        }
    }

    private fun parsePkcs8RsaKey(pem: String): PrivateKey {
        val normalizedPem = pem.trim()
        if (!normalizedPem.startsWith("-----BEGIN PRIVATE KEY-----") ||
            !normalizedPem.endsWith("-----END PRIVATE KEY-----")
        ) {
            throw VertexCredentialException(VertexCredentialFailure.INVALID_PRIVATE_KEY)
        }
        val encoded = try {
            Base64.getDecoder().decode(
                normalizedPem.removePrefix("-----BEGIN PRIVATE KEY-----")
                    .removeSuffix("-----END PRIVATE KEY-----")
                    .replace(Regex("\\s"), ""),
            )
        } catch (_: Exception) {
            throw VertexCredentialException(VertexCredentialFailure.INVALID_PRIVATE_KEY)
        }
        return try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded))
        } catch (_: Exception) {
            throw VertexCredentialException(VertexCredentialFailure.INVALID_PRIVATE_KEY)
        } finally {
            encoded.fill(0)
        }
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
