package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupPackageCodecTest {
    private val codec: BackupPackageCodec = DefaultBackupPackageCodec()

    @Test
    fun `backup without secrets round trips selected content in stable ZIP order`() {
        val snapshot = fixture()

        val encoded = codec.encode(
            snapshot,
            BackupOptions(setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)),
        )
        val decoded = codec.decode(encoded, null)

        assertThat(zipEntries(encoded)).containsExactly(
            "manifest.json", "database.json", "preferences.json", "files/file-a", "files/file-b",
        ).inOrder()
        assertThat(decoded.database).isEqualTo(snapshot.database)
        assertThat(decoded.preferences).isEqualTo(snapshot.preferences)
        assertThat(decoded.files.keys).containsExactlyElementsIn(snapshot.files.keys)
        snapshot.files.forEach { (id, content) -> assertThat(decoded.files[id]).isEqualTo(content) }
        assertThat(decoded.secrets).isEmpty()
        assertThat(decoded.manifest.encrypted).isFalse()
        assertThat(decoded.manifest.containsSecrets).isFalse()
    }

    @Test
    fun `backup with secrets encrypts whole ZIP and round trips keys`() {
        val snapshot = fixture()
        val encoded = codec.encode(
            snapshot,
            BackupOptions(BackupContent.entries.toSet(), includeSecrets = true, password = "passphrase".toCharArray()),
        )

        val decoded = codec.decode(encoded, "passphrase".toCharArray())

        assertThat(encoded.copyOfRange(0, 2)).isNotEqualTo(byteArrayOf('P'.code.toByte(), 'K'.code.toByte()))
        assertThat(decoded.secrets[SecretId("provider")]).isEqualTo("api-key".toByteArray())
        assertThat(decoded.manifest.encrypted).isTrue()
        assertThat(decoded.manifest.containsSecrets).isTrue()
        assertThat(decoded.manifest.kdf?.iterations).isEqualTo(BackupCrypto.PBKDF2_ITERATIONS)
    }

    @Test
    fun `including secrets requires a non-empty password`() {
        val options = BackupOptions(setOf(BackupContent.SECRETS), includeSecrets = true, password = CharArray(0))

        assertThrows<BackupValidationException> { codec.encode(fixture(), options) }
    }

    @Test
    fun `automatic backup password is never included in its own snapshot`() {
        val decoded = codec.decode(
            codec.encode(
                fixture(),
                BackupOptions(setOf(BackupContent.SECRETS), includeSecrets = true, password = "passphrase".toCharArray()),
            ),
            "passphrase".toCharArray(),
        )

        assertThat(decoded.secrets).doesNotContainKey(SecretCatalog.automaticBackupPassword)
        assertThat(decoded.secrets).containsKey(SecretId("provider"))
    }

    @Test
    fun `content hash mismatch is rejected`() {
        val encoded = codec.encode(fixture(), BackupOptions(setOf(BackupContent.DATABASE)))
        val tampered = rewriteZip(encoded) { name, bytes ->
            if (name == "database.json") "corrupt".toByteArray() else bytes
        }

        assertThrows<BackupValidationException> { codec.decode(tampered, null) }
    }

    @Test
    fun `unknown format version is rejected`() {
        val encoded = codec.encode(fixture(), BackupOptions(setOf(BackupContent.DATABASE)))
        val tampered = rewriteZip(encoded) { name, bytes ->
            if (name == "manifest.json") {
                bytes.toString(Charsets.UTF_8).replace("\"formatVersion\":1", "\"formatVersion\":999").toByteArray()
            } else bytes
        }

        assertThrows<BackupValidationException> { codec.decode(tampered, null) }
    }

    @Test
    fun `missing declared entry is rejected`() {
        val encoded = codec.encode(fixture(), BackupOptions(setOf(BackupContent.DATABASE)))
        val tampered = rewriteZip(encoded, drop = "database.json") { _, bytes -> bytes }

        assertThrows<BackupValidationException> { codec.decode(tampered, null) }
    }

    @Test
    fun `zip slip and duplicate paths are rejected`() {
        val manifest = BackupManifest(
            formatVersion = 1,
            databaseSchemaVersion = 1,
            appVersion = "test",
            createdAt = 1,
            entries = emptyList(),
            encrypted = false,
            containsSecrets = false,
        )
        val manifestBytes = Json.encodeToString(manifest).toByteArray()

        val zipSlip = rawZip(listOf("manifest.json" to manifestBytes, "../escape" to byteArrayOf(1)))
        val duplicate = replaceAscii(
            rawZip(listOf("manifest.json" to manifestBytes, "same" to byteArrayOf(1), "sane" to byteArrayOf(2))),
            "sane",
            "same",
        )

        assertThrows<BackupValidationException> { codec.decode(zipSlip, null) }
        assertThrows<BackupValidationException> { codec.decode(duplicate, null) }
    }

    @Test
    fun `entry count and declared size limits are rejected`() {
        val oversizedManifest = BackupManifest(
            formatVersion = 1,
            databaseSchemaVersion = 1,
            appVersion = "test",
            createdAt = 1,
            entries = listOf(BackupManifestEntry("database.json", BackupPackageLimits.MAX_ENTRY_BYTES + 1, "00")),
            encrypted = false,
            containsSecrets = false,
        )
        val tooMany = (0..BackupPackageLimits.MAX_ENTRIES).map { "files/$it" to byteArrayOf() }

        assertThrows<BackupValidationException> {
            codec.decode(rawZip(listOf("manifest.json" to Json.encodeToString(oversizedManifest).toByteArray())), null)
        }
        assertThrows<BackupValidationException> {
            codec.decode(rawZip(listOf("manifest.json" to Json.encodeToString(oversizedManifest.copy(entries = emptyList())).toByteArray()) + tooMany), null)
        }
    }

    private fun fixture() = BackupSnapshot(
        database = "{\"sessions\":[1]}".toByteArray(),
        preferences = "{\"language\":\"zh\"}".toByteArray(),
        files = linkedMapOf("file-b" to byteArrayOf(2), "file-a" to byteArrayOf(1)),
        secrets = linkedMapOf(
            SecretId("provider") to "api-key".toByteArray(),
            SecretCatalog.automaticBackupPassword to "must-not-leak".toByteArray(),
        ),
        databaseSchemaVersion = 1,
        appVersion = "0.2-test",
        createdAt = 1234,
    )

    private fun zipEntries(bytes: ByteArray): List<String> = buildList {
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) add(input.nextEntry?.name ?: break)
        }
    }

    private fun rewriteZip(
        bytes: ByteArray,
        drop: String? = null,
        transform: (String, ByteArray) -> ByteArray,
    ): ByteArray {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name != drop) entries += entry.name to transform(entry.name, input.readBytes())
            }
        }
        return rawZip(entries)
    }

    private fun rawZip(entries: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun replaceAscii(bytes: ByteArray, old: String, new: String): ByteArray {
        require(old.length == new.length)
        val result = bytes.copyOf()
        val oldBytes = old.toByteArray()
        val newBytes = new.toByteArray()
        for (index in 0..result.size - oldBytes.size) {
            if (result.copyOfRange(index, index + oldBytes.size).contentEquals(oldBytes)) {
                newBytes.copyInto(result, index)
            }
        }
        return result
    }
}
