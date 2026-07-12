package com.promenar.nexara.data.security

import java.security.MessageDigest

object SecretCatalog {
    fun providerApiKey(providerId: String): SecretId =
        SecretId("provider_api_key:${providerHash(providerId)}")

    fun vertexServiceAccount(providerId: String): SecretId =
        SecretId("vertex_service_account:${providerHash(providerId)}")

    val tavilyApiKey = SecretId("tavily_api_key")
    val embeddingApiKey = SecretId("embedding_api_key")
    val webDavPassword = SecretId("webdav_password")
    val webDavAuthRecord = SecretId("webdav_auth_record_v1")
    val automaticBackupPassword = SecretId("automatic_backup_password")

    fun backupEligible(providerIds: Collection<String>): Set<SecretId> = buildSet {
        add(tavilyApiKey)
        add(embeddingApiKey)
        add(webDavAuthRecord)
        add(automaticBackupPassword)
        providerIds.forEach { providerId ->
            add(providerApiKey(providerId))
            add(vertexServiceAccount(providerId))
        }
    }

    private fun providerHash(providerId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(providerId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
