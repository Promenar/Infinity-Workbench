package com.promenar.nexara.data.local

import com.promenar.nexara.data.model.SessionHeaderPolicy
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 安装盐保存在应用 noBackupFilesDir 内，不随备份跨安装复用。 */
class SessionHeaderIdentityStore(private val directory: File) {
    suspend fun identifier(sessionId: String): String = withContext(Dispatchers.IO) {
        val salt = synchronized(lock) {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建会话标识目录" }
            val file = File(directory, "installation-salt")
            if (file.exists()) {
                file.readText(Charsets.US_ASCII).also { check(it.matches(Regex("[0-9a-f]{64}"))) { "安装盐格式无效" } }
            } else {
                val value = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val temporary = File.createTempFile("salt-", ".tmp", directory)
                try {
                    FileOutputStream(temporary).use { output ->
                        output.write(value.toByteArray(Charsets.US_ASCII))
                        output.fd.sync()
                    }
                    check(temporary.renameTo(file)) { "无法持久化安装盐" }
                } finally {
                    temporary.delete()
                }
                value
            }
        }
        SessionHeaderPolicy.identifier(salt, sessionId)
    }

    private companion object { val lock = Any() }
}
