package com.promenar.infinity.data.backup

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProductRenameBackupCompatibilityTest {
    @Test
    fun `旧偏好物理别名仍被策略识别`() {
        listOf(
            "nexara_provider" to "model", "nexara_settings" to "user_name",
            "nexara_search" to "web_search_enabled", "nexara_prefs" to "language",
            "nexara_backup_settings" to "last_backup_time",
        ).forEach { (namespace, key) -> assertThat(BackupPreferencePolicy.isAllowed(namespace, key)).isTrue() }
        assertThat(BackupPreferencePolicy.isKnown("nexara_onboarding", "step")).isTrue()
        assertThat(BackupPreferencePolicy.isKnown("nexara_provider_status", "statuses")).isTrue()
    }

    @Test
    fun `技能分区拒绝新旧内部前缀并允许普通技能路径`() {
        listOf(".nexara/config", ".nexara-old/config", "skill/.NEXARA_secret", ".infinity/config", "skill/.INFINITY_secret").forEach {
            assertThrows<BackupValidationException> { SkillBackupPaths.parts(it) }
        }
        assertThat(SkillBackupPaths.parts("writer/SKILL.md")).containsExactly("writer", "SKILL.md").inOrder()
    }

    @Test
    fun `独立构造的旧 NX BK 密码信封可由新应用解码`() {
        val preferences = """{"entries":[{"namespace":"nexara_settings","key":"user_name","value":"迁移用户","type":"STRING"}],"providerIds":[]}""".toByteArray()
        val salt = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(12) { (it + 17).toByte() }
        val kdf = BackupKdfMetadata("PBKDF2WithHmacSHA256", Base64.getEncoder().encodeToString(salt), 600000, 256)
        val manifest = BackupManifest(1, 29, "0.2.2-beta", 1700000000000,
            listOf(BackupManifestEntry("preferences.json", preferences.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(preferences).joinToString("") { "%02x".format(it.toInt() and 255) })),
            encrypted = true, containsSecrets = false, kdf = kdf)
        val zipBytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                listOf("manifest.json" to Json.encodeToString(manifest).toByteArray(), "preferences.json" to preferences).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
        }.toByteArray()
        val algorithm = "PBKDF2WithHmacSHA256".toByteArray()
        val header = ByteBuffer.allocate(4 + 1 + 2 + algorithm.size + 4 + 4 + 1 + 16 + 1 + 12)
            .put(byteArrayOf(78, 88, 66, 75)).put(1.toByte()).putShort(algorithm.size.toShort()).put(algorithm)
            .putInt(600000).putInt(256).put(16.toByte()).put(salt).put(12.toByte()).put(iv).array()
        val password = "测试用迁移口令".toCharArray()
        val spec = PBEKeySpec(password, salt, 600000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        val envelope = header + cipher.doFinal(zipBytes)
        try {
            DefaultBackupPackageCodec().decode(envelope, password).use {
                assertThat(it.preferences).isEqualTo(preferences)
                assertThat(it.manifest.appVersion).isEqualTo("0.2.2-beta")
            }
        } finally { spec.clearPassword(); password.fill('\u0000'); key.fill(0); envelope.fill(0) }
    }
}
