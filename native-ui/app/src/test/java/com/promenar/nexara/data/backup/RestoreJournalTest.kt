package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.repository.PersistentFileIdentity
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RestoreJournalTest {
    private val nonce = "11111111-1111-4111-8111-111111111111"

    @Test
    fun `journal只持久化nonce与inode身份且设备号变化不改变记录`() {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".restore-journal-test")
        try {
            val before = PersistentFileIdentity.encode(nonce, "(dev=1,ino=42)")
            val after = PersistentFileIdentity.encode(nonce, "(dev=999,ino=42)")
            val record = RestoreJournalRecord(
                "tx-1", "a".repeat(64), "0:" + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(before.toByteArray()) + ":-", "restore-tx-1", before,
                RestoreJournalPhase.PREPARED, SkillRestoreJournalRecord("skills", before, before, before),
            )

            FileRestoreJournal(base, TestRestoreJournalAuthenticator).write(record)

            assertThat(after).isEqualTo(before)
            assertThat(FileRestoreJournal(base, TestRestoreJournalAuthenticator).read()).isEqualTo(
                record.copy(newRootFileKey = after, skills = SkillRestoreJournalRecord("skills", after, after, after)),
            )
        } finally { base.toFile().deleteRecursively() }
    }

    @Test
    fun `journal拒绝所有持久字段中的旧完整fileKey`() {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".restore-journal-test")
        try {
            val record = RestoreJournalRecord("tx-1", "a".repeat(64), "", "restore-tx-1", null, RestoreJournalPhase.PREPARED)
            val rawKey = "(dev=1,ino=42)"
            val invalid = listOf(
                record.copy(newRootFileKey = rawKey),
                record.copy(oldRootIdentity = "0:" + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(rawKey.toByteArray()) + ":-"),
                record.copy(skills = SkillRestoreJournalRecord("skills", rawKey)),
                record.copy(skills = SkillRestoreJournalRecord("skills", null, rawKey)),
                record.copy(skills = SkillRestoreJournalRecord("skills", null, null, rawKey)),
            )

            invalid.forEach { value ->
                assertThat(runCatching { FileRestoreJournal(base, TestRestoreJournalAuthenticator).write(value) }.exceptionOrNull())
                    .isInstanceOf(BackupValidationException::class.java)
            }
            assertThat(Files.exists(base.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        } finally { base.toFile().deleteRecursively() }
    }

    @Test
    fun `journal is durable across store recreation and contains metadata only`() {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".restore-journal-test")
        try {
            val record = RestoreJournalRecord(
                txId = "tx-1",
                expectedDatabaseFingerprint = "a".repeat(64),
                oldRootIdentity = "",
                newRootIdentity = "restore-tx-1",
                newRootFileKey = null,
                phase = RestoreJournalPhase.PREPARED,
            )

            FileRestoreJournal(base, TestRestoreJournalAuthenticator).write(record)

            assertThat(FileRestoreJournal(base, TestRestoreJournalAuthenticator).read()).isEqualTo(record)
            val raw = Files.readAllBytes(base.resolve(FileRestoreJournal.FILE_NAME)).toString(Charsets.UTF_8)
            assertThat(raw).doesNotContain("secret")
            assertThat(raw).doesNotContain("user content")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `journal rejects a valid-json record whose authenticated bytes were changed`() {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".restore-journal-test")
        try {
            val journal = FileRestoreJournal(base, TestRestoreJournalAuthenticator)
            journal.write(
                RestoreJournalRecord(
                    txId = "tx-1",
                    expectedDatabaseFingerprint = "a".repeat(64),
                    oldRootIdentity = "0:" + java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(PersistentFileIdentity.encode(nonce, "(dev=1,ino=42)").toByteArray()) + ":" + "b".repeat(64),
                    newRootIdentity = "restore-tx-1",
                    newRootFileKey = null,
                    phase = RestoreJournalPhase.PREPARED,
                )
            )
            val path = base.resolve(FileRestoreJournal.FILE_NAME)
            val envelope = kotlinx.serialization.json.Json.parseToJsonElement(
                Files.readAllBytes(path).toString(Charsets.UTF_8)
            ).jsonObject
            val signature = envelope.getValue("signatureBase64").jsonPrimitive.content
            Files.write(
                path,
                kotlinx.serialization.json.JsonObject(
                    envelope + ("signatureBase64" to kotlinx.serialization.json.JsonPrimitive(
                        (if (signature.first() == 'A') "B" else "A") + signature.drop(1)
                    ))
                ).toString().toByteArray(),
            )

            assertThat(runCatching { journal.read() }.exceptionOrNull()).isInstanceOf(BackupValidationException::class.java)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `journal validates decoded record before returning it`() {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".restore-journal-test")
        try {
            val journal = FileRestoreJournal(base, TestRestoreJournalAuthenticator)
            journal.write(
                RestoreJournalRecord(
                    txId = "tx-1",
                    expectedDatabaseFingerprint = "a".repeat(64),
                    oldRootIdentity = "",
                    newRootIdentity = "restore-tx-1",
                    newRootFileKey = null,
                    phase = RestoreJournalPhase.PREPARED,
                )
            )
            val path = base.resolve(FileRestoreJournal.FILE_NAME)
            val envelope = kotlinx.serialization.json.Json.parseToJsonElement(
                Files.readAllBytes(path).toString(Charsets.UTF_8)
            ).jsonObject
            val payload = java.util.Base64.getDecoder().decode(
                envelope.getValue("payloadBase64").jsonPrimitive.content
            )
            val invalidPayload = payload.toString(Charsets.UTF_8)
                .replace("restore-tx-1", "../escape").toByteArray()
            val signature = TestRestoreJournalAuthenticator.sign(invalidPayload)
            Files.write(
                path,
                kotlinx.serialization.json.JsonObject(
                    mapOf(
                        "payloadBase64" to kotlinx.serialization.json.JsonPrimitive(
                            java.util.Base64.getEncoder().encodeToString(invalidPayload)
                        ),
                        "signatureBase64" to kotlinx.serialization.json.JsonPrimitive(
                            java.util.Base64.getEncoder().encodeToString(signature)
                        ),
                    )
                ).toString().toByteArray(),
            )

            assertThat(runCatching { journal.read() }.exceptionOrNull()).isInstanceOf(BackupValidationException::class.java)
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}

internal object TestRestoreJournalAuthenticator : RestoreJournalAuthenticator {
    override fun sign(payload: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256")
        .digest("test-only-key".toByteArray() + payload)

    override fun verify(payload: ByteArray, signature: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(sign(payload), signature)
}
