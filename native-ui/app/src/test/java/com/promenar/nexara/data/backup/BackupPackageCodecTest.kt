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
import java.security.MessageDigest
import java.util.Base64
import java.lang.reflect.Modifier
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

    @Test
    fun `manifest must be first and unknown entry is rejected before its content is read`() {
        val manifest = manifest(entries = emptyList())
        val notFirst = rawZip(
            listOf("database.json" to byteArrayOf(1), "manifest.json" to Json.encodeToString(manifest).toByteArray()),
        )
        val smallLimitsCodec = DefaultBackupPackageCodec.forTest(
            maxTotalBytes = 32,
            maxEntryBytes = 32,
            maxEntries = 8,
            maxManifestBytes = 4096,
        )
        val unknownLargeEntry = rawZip(
            listOf(
                "manifest.json" to Json.encodeToString(manifest).toByteArray(),
                "files/not-declared" to ByteArray(1024 * 1024),
            ),
        )

        assertThat(assertThrows<BackupValidationException> { codec.decode(notFirst, null) })
            .hasMessageThat().contains("manifest.json 必须是第一项")
        assertThat(assertThrows<BackupValidationException> { smallLimitsCodec.decode(unknownLargeEntry, null) })
            .hasMessageThat().contains("未声明")
    }

    @Test
    fun `manifest limit is independent and total limit counts only declared content`() {
        val limitedCodec: BackupPackageCodec = DefaultBackupPackageCodec.forTest(
            maxTotalBytes = 10,
            maxEntryBytes = 10,
            maxEntries = 8,
            maxManifestBytes = 4096,
        )
        val exact = fixture().copy(database = ByteArray(6), preferences = ByteArray(4), files = emptyMap())

        val exactEncoded = limitedCodec.encode(
            exact,
            BackupOptions(setOf(BackupContent.DATABASE, BackupContent.PREFERENCES)),
        )
        val oneByteCodec = DefaultBackupPackageCodec.forTest(
            maxTotalBytes = 1,
            maxEntryBytes = 10,
            maxEntries = 8,
            maxManifestBytes = 4096,
        )
        val emptyEncoded = oneByteCodec.encode(exact, BackupOptions(emptySet()))

        assertThat(limitedCodec.decode(exactEncoded, null).database).hasLength(6)
        assertThat(oneByteCodec.decode(emptyEncoded, null).manifest).isNotNull()
        assertThrows<BackupValidationException> {
            limitedCodec.encode(exact.copy(preferences = ByteArray(5)), BackupOptions(setOf(BackupContent.DATABASE, BackupContent.PREFERENCES)))
        }

        val oversized = ByteArray(11)
        val oversizedManifest = manifest(entries = listOf(entry("database.json", oversized)))
        assertThrows<BackupValidationException> {
            limitedCodec.decode(
                rawZip(listOf("manifest.json" to Json.encodeToString(oversizedManifest).toByteArray(), "database.json" to oversized)),
                null,
            )
        }
    }

    @Test
    fun `caller passwords remain unchanged after codec success and failure`() {
        val encodePassword = "passphrase".toCharArray()
        val encoded = codec.encode(
            fixture(),
            BackupOptions(setOf(BackupContent.SECRETS), includeSecrets = true, password = encodePassword),
        )
        assertThat(encodePassword.concatToString()).isEqualTo("passphrase")

        val decodePassword = "passphrase".toCharArray()
        codec.decode(encoded, decodePassword)
        assertThat(decodePassword.concatToString()).isEqualTo("passphrase")

        val wrongPassword = "wrong".toCharArray()
        assertThrows<BackupValidationException> { codec.decode(encoded, wrongPassword) }
        assertThat(wrongPassword.concatToString()).isEqualTo("wrong")

        val invalidEncodePassword = "still-owned".toCharArray()
        assertThrows<BackupValidationException> {
            codec.encode(
                fixture(),
                BackupOptions(setOf(BackupContent.DATABASE), includeSecrets = true, password = invalidEncodePassword),
            )
        }
        assertThat(invalidEncodePassword.concatToString()).isEqualTo("still-owned")
    }

    @Test
    fun `manifest KDF metadata must exactly match authenticated envelope parameters`() {
        val crypto = BackupCrypto()
        val salt = ByteArray(16) { 1 }
        val iv = ByteArray(12) { 2 }
        val actual = BackupKdfMetadata(
            BackupCrypto.KDF_ALGORITHM,
            Base64.getEncoder().encodeToString(salt),
            BackupCrypto.PBKDF2_ITERATIONS,
            BackupCrypto.KEY_SIZE_BITS,
        )
        val mismatches = listOf(
            actual.copy(algorithm = "PBKDF2WithHmacSHA1"),
            actual.copy(iterations = actual.iterations - 1),
            actual.copy(keySizeBits = 128),
            actual.copy(saltBase64 = Base64.getEncoder().encodeToString(ByteArray(16) { 9 })),
        )

        mismatches.forEach { mismatchedKdf ->
            val mismatchedManifest = manifest(encrypted = true, kdf = mismatchedKdf)
            val encrypted = crypto.encryptForTest(
                rawZip(listOf("manifest.json" to Json.encodeToString(mismatchedManifest).toByteArray())),
                "passphrase".toCharArray(),
                salt,
                iv,
            )
            assertThat(assertThrows<BackupValidationException> { codec.decode(encrypted, "passphrase".toCharArray()) })
                .hasMessageThat().contains("envelope")
        }
    }

    @Test
    fun `temporary plaintext arrays are wiped when failure happens after secrets were read`() {
        val observed = mutableListOf<ByteArray>()
        val observingCodec = DefaultBackupPackageCodec.forTest(
            temporaryBytesObserver = { _, bytes -> observed += bytes },
        )
        val crypto = BackupCrypto()
        val salt = ByteArray(16) { 3 }
        val iv = ByteArray(12) { 4 }
        val secretBytes = "[{\"id\":\"provider\",\"valueBase64\":\"${Base64.getEncoder().encodeToString("top-secret".toByteArray())}\"}]".toByteArray()
        val database = byteArrayOf(7)
        val packageManifest = manifest(
            entries = listOf(entry("secrets.json", secretBytes), entry("database.json", database)),
            encrypted = true,
            containsSecrets = true,
            kdf = BackupKdfMetadata(
                BackupCrypto.KDF_ALGORITHM,
                Base64.getEncoder().encodeToString(salt),
                BackupCrypto.PBKDF2_ITERATIONS,
                BackupCrypto.KEY_SIZE_BITS,
            ),
        )
        val duplicateZip = replaceAscii(
            rawZip(
                listOf(
                    "manifest.json" to Json.encodeToString(packageManifest).toByteArray(),
                    "secrets.json" to secretBytes,
                    "database.json" to database,
                    "databaso.json" to database,
                ),
            ),
            "databaso.json",
            "database.json",
        )
        val encrypted = crypto.encryptForTest(duplicateZip, "passphrase".toCharArray(), salt, iv)

        val error = assertThrows<BackupValidationException> {
            observingCodec.decode(encrypted, "passphrase".toCharArray())
        }

        assertThat(error.toString()).doesNotContain("top-secret")
        assertThat(observed).isNotEmpty()
        assertThat(observed.all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
    }

    @Test
    fun `production codec API exposes only safe default constructor`() {
        val publicConstructors = DefaultBackupPackageCodec::class.java.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        val publicMethods = DefaultBackupPackageCodec::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        val limitsClass = runCatching {
            Class.forName("com.promenar.nexara.data.backup.BackupLimits")
        }.getOrNull()

        assertThat(publicConstructors.map { it.parameterCount }).containsExactly(0)
        assertThat(publicMethods.map { it.name }).doesNotContain("forTest")
        assertThat(DefaultBackupPackageCodec.Companion::class.java.declaredMethods
            .filter { it.name.startsWith("forTest") }
            .all { it.isSynthetic }).isTrue()
        assertThat(limitsClass == null || !Modifier.isPublic(limitsClass.modifiers) ||
            limitsClass.declaredConstructors.none { Modifier.isPublic(it.modifiers) && !it.isSynthetic }).isTrue()
    }

    @Test
    fun `throwing observer cannot replace validation failure or stop remaining wipes`() {
        val observed = mutableListOf<ByteArray>()
        val codecWithHostileObserver = DefaultBackupPackageCodec.forTest(
            temporaryBytesObserver = { _, bytes ->
                observed += bytes
                throw IllegalStateException("observer-controlled")
            },
        )
        val encoded = codec.encode(
            fixture(),
            BackupOptions(setOf(BackupContent.DATABASE, BackupContent.PREFERENCES)),
        )
        val tampered = rewriteZip(encoded) { name, bytes ->
            if (name == "preferences.json") "corrupt".toByteArray() else bytes
        }

        val error = assertThrows<BackupValidationException> {
            codecWithHostileObserver.decode(tampered, null)
        }

        assertThat(error.message).contains("完整性校验失败")
        assertThat(error.toString()).doesNotContain("observer-controlled")
        assertThat(observed.size).isAtLeast(2)
        assertThat(observed.all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
    }

    private fun manifest(
        entries: List<BackupManifestEntry> = emptyList(),
        encrypted: Boolean = false,
        containsSecrets: Boolean = false,
        kdf: BackupKdfMetadata? = null,
    ) = BackupManifest(
        formatVersion = 1,
        databaseSchemaVersion = 1,
        appVersion = "test",
        createdAt = 1,
        entries = entries,
        encrypted = encrypted,
        containsSecrets = containsSecrets,
        kdf = kdf,
    )

    private fun entry(path: String, bytes: ByteArray) = BackupManifestEntry(
        path = path,
        sizeBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
    )

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
