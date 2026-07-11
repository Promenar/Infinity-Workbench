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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
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
    fun `small compressed package that expands beyond the Android budget fails while streaming`() {
        val declaredSize = BackupPackageLimits.MAX_IN_MEMORY_BYTES
        val packageManifest = manifest(
            entries = listOf(
                BackupManifestEntry("database.json", declaredSize, "0".repeat(64)),
            ),
        )
        val compressedBomb = rawZip(
            listOf(
                "manifest.json" to Json.encodeToString(packageManifest).toByteArray(),
                "database.json" to ByteArray(declaredSize.toInt() + 1),
            ),
        )
        assertThat(compressedBomb.size).isLessThan(64 * 1024)

        assertThat(assertThrows<BackupValidationException> { codec.decode(compressedBomb, null) })
            .hasMessageThat().contains("解压后超过")
    }

    @Test
    fun `production codec accepts a reasonable payload above four MiB within the sixteen MiB budget`() {
        val database = ByteArray(5 * 1024 * 1024) { index -> (index % 251).toByte() }
        val encoded = codec.encode(
            fixture().copy(database = database, files = emptyMap()),
            BackupOptions(setOf(BackupContent.DATABASE)),
        )

        val decoded = codec.decode(encoded, null)
        try {
            assertThat(decoded.database).isEqualTo(database)
        } finally {
            decoded.close()
            database.fill(0)
            encoded.fill(0)
        }
    }

    @Test
    fun `manifest must be first and unknown entry is rejected before its content is read`() {
        val manifest = manifest(entries = emptyList())
        val notFirst = rawZip(
            listOf("database.json" to byteArrayOf(1), "manifest.json" to Json.encodeToString(manifest).toByteArray()),
        )
        val smallLimitsCodec = codecForTest(
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
        val limitedCodec: BackupPackageCodec = codecForTest(
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
        val oneByteCodec = codecForTest(
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
            val encrypted = encryptWithFixedParametersForTest(
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
        val observingCodec = codecForTest(
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
        val encrypted = encryptWithFixedParametersForTest(duplicateZip, "passphrase".toCharArray(), salt, iv)

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
        assertThat(DefaultBackupPackageCodec::class.java.declaredMethods.any { it.name.contains("forTest") }).isFalse()
        assertThat(DefaultBackupPackageCodec.Companion::class.java.declaredMethods.any { it.name.contains("forTest") }).isFalse()
        assertThat(limitsClass == null || !Modifier.isPublic(limitsClass.modifiers) ||
            limitsClass.declaredConstructors.none { Modifier.isPublic(it.modifiers) && !it.isSynthetic }).isTrue()
    }

    @Test
    fun `throwing observer cannot replace validation failure or stop remaining wipes`() {
        val observed = mutableListOf<ByteArray>()
        val codecWithHostileObserver = codecForTest(
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

    @Test
    fun `cancellation and fatal observer failures propagate only after accumulated arrays are wiped`() {
        val failures = listOf<() -> Throwable>(
            { CancellationException("cancel-observer") },
            { TestVirtualMachineError() },
        )

        failures.forEach { failureFactory ->
            val observed = mutableListOf<ByteArray>()
            val codecWithFatalObserver = codecForTest(
                temporaryBytesObserver = { label, bytes ->
                    observed += bytes
                    if (label.startsWith("decode:")) throw failureFactory()
                },
            )
            val encoded = codec.encode(
                fixture(),
                BackupOptions(setOf(BackupContent.DATABASE, BackupContent.PREFERENCES)),
            )
            val tampered = rewriteZip(encoded) { name, bytes ->
                if (name == "preferences.json") "corrupt".toByteArray() else bytes
            }

            val thrown = assertThrows<BackupValidationException> { codecWithFatalObserver.decode(tampered, null) }

            assertThat(thrown.suppressed.single()).isInstanceOf(failureFactory()::class.java)
            assertThat(observed).isNotEmpty()
            assertThat(observed.all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
        }
    }

    @Test
    fun `decode authenticates and parses metadata from one input snapshot despite caller mutation`() {
        val password = "passphrase".toCharArray()
        val encoded = codec.encode(
            fixture(),
            BackupOptions(setOf(BackupContent.SECRETS), includeSecrets = true, password = password),
        )
        var mutationTriggered = false
        val snapshotCodec = codecForTest(
            temporaryBytesObserver = { label, _ ->
                if (label == "after-authentication") {
                    encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
                    mutationTriggered = true
                }
            },
        )

        val decoded = snapshotCodec.decode(encoded, "passphrase".toCharArray())

        assertThat(mutationTriggered).isTrue()
        assertThat(decoded.secrets[SecretId("provider")]).isEqualTo("api-key".toByteArray())
    }

    @Test
    fun `encode cancellation or fatal propagates after all temporary arrays are wiped`() {
        val cases = listOf<() -> Throwable>(
            { CancellationException("cancel-encode") },
            { TestVirtualMachineError() },
        )

        cases.forEachIndexed { index, failureFactory ->
            val observed = mutableMapOf<String, ByteArray>()
            val failingCodec = codecForTest(
                temporaryBytesObserver = { label, bytes ->
                    observed[label] = bytes
                    if (label == "encode:database.json") throw failureFactory()
                },
            )
            val callerPassword = "passphrase".toCharArray()
            val options = if (index == 0) {
                BackupOptions(setOf(BackupContent.DATABASE, BackupContent.PREFERENCES))
            } else {
                BackupOptions(
                    setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.SECRETS),
                    includeSecrets = true,
                    password = callerPassword,
                )
            }

            val thrown = assertThrows<Throwable> { failingCodec.encode(fixture(), options) }

            assertThat(thrown).isInstanceOf(failureFactory()::class.java)
            assertThat(callerPassword.concatToString()).isEqualTo("passphrase")
            assertThat(observed.keys).containsAtLeast("encode:database.json", "encode:preferences.json")
            assertThat(observed.values.all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
        }
    }

    @Test
    fun `encrypted decode fatal propagates only after input snapshot zip entries and password cleanup`() {
        val callerPassword = "passphrase".toCharArray()
        val encoded = codec.encode(
            fixture(),
            BackupOptions(
                setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.SECRETS),
                includeSecrets = true,
                password = callerPassword,
            ),
        )
        val observed = mutableMapOf<String, ByteArray>()
        val failingCodec = codecForTest(
            temporaryBytesObserver = { label, bytes ->
                observed[label] = bytes
                if (label == "decode:database.json") throw TestVirtualMachineError()
            },
        )
        val decodePassword = "passphrase".toCharArray()

        assertThrows<TestVirtualMachineError> { failingCodec.decode(encoded, decodePassword) }

        assertThat(decodePassword.concatToString()).isEqualTo("passphrase")
        assertThat(observed.keys).containsAtLeast(
            "decode:database.json",
            "decode:preferences.json",
            "decoded-zip",
            "decode-input-snapshot",
        )
        assertThat(observed.values.all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
    }

    @Test
    fun `oversized input is rejected before snapshot observer and crypto work`() {
        var observerCalls = 0
        val limitedCodec = codecForTest(
            maxTotalBytes = 0,
            maxEntryBytes = 0,
            maxEntries = 0,
            maxManifestBytes = 1,
            temporaryBytesObserver = { _, _ -> observerCalls++ },
        )
        val maxInputMethod = DefaultBackupPackageCodec::class.java.getDeclaredMethod("maxInputBytes")
            .apply { isAccessible = true }
        val maxInputBytes = maxInputMethod.invoke(limitedCodec) as Long
        val callerBytes = ByteArray((maxInputBytes + 1).toInt()) { 0x5a.toByte() }

        val error = assertThrows<BackupValidationException> { limitedCodec.decode(callerBytes, null) }

        assertThat(error.message).contains("超过允许大小")
        assertThat(observerCalls).isEqualTo(0)
        assertThat(callerBytes.first()).isEqualTo(0x5a.toByte())
        assertThat(callerBytes.last()).isEqualTo(0x5a.toByte())
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

    private fun codecForTest(
        maxTotalBytes: Long = BackupPackageLimits.MAX_TOTAL_BYTES,
        maxEntryBytes: Long = BackupPackageLimits.MAX_ENTRY_BYTES,
        maxEntries: Int = BackupPackageLimits.MAX_ENTRIES,
        maxManifestBytes: Long = 4L * 1024 * 1024,
        temporaryBytesObserver: ((String, ByteArray) -> Unit)? = null,
    ): DefaultBackupPackageCodec {
        val limitsClass = DefaultBackupPackageCodec::class.java.declaredClasses
            .single { it.simpleName == "BackupLimits" }
        val limitsConstructor = limitsClass.declaredConstructors.single { it.parameterCount == 4 }
            .apply { isAccessible = true }
        val limits = limitsConstructor.newInstance(maxTotalBytes, maxEntryBytes, maxEntries, maxManifestBytes)
        val codecConstructor = DefaultBackupPackageCodec::class.java.declaredConstructors
            .single { constructor ->
                constructor.parameterCount == 3 && constructor.parameterTypes[0] == BackupCrypto::class.java
            }
            .apply { isAccessible = true }
        return codecConstructor.newInstance(BackupCrypto(), limits, temporaryBytesObserver) as DefaultBackupPackageCodec
    }

    private fun encryptWithFixedParametersForTest(
        plaintext: ByteArray,
        password: CharArray,
        salt: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        val algorithmBytes = BackupCrypto.KDF_ALGORITHM.toByteArray(StandardCharsets.UTF_8)
        val header = ByteBuffer.allocate(
            4 + 1 + Short.SIZE_BYTES + algorithmBytes.size + Int.SIZE_BYTES + Int.SIZE_BYTES +
                1 + salt.size + 1 + iv.size,
        )
            .put(byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte()))
            .put(1)
            .putShort(algorithmBytes.size.toShort())
            .put(algorithmBytes)
            .putInt(BackupCrypto.PBKDF2_ITERATIONS)
            .putInt(BackupCrypto.KEY_SIZE_BITS)
            .put(salt.size.toByte())
            .put(salt)
            .put(iv.size.toByte())
            .put(iv)
            .array()
        val spec = PBEKeySpec(password, salt, BackupCrypto.PBKDF2_ITERATIONS, BackupCrypto.KEY_SIZE_BITS)
        val keyBytes = try {
            SecretKeyFactory.getInstance(BackupCrypto.KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            header + cipher.doFinal(plaintext)
        } finally {
            keyBytes.fill(0)
            header.fill(0)
        }
    }

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

    private class TestVirtualMachineError : VirtualMachineError()
}
