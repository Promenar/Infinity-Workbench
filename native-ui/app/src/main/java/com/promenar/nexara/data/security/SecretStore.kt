package com.promenar.nexara.data.security

@JvmInline
value class SecretId(val value: String)

interface SecretStore {
    fun put(id: SecretId, value: ByteArray)
    fun get(id: SecretId): ByteArray?
    fun contains(id: SecretId): Boolean
    fun remove(id: SecretId)
}
