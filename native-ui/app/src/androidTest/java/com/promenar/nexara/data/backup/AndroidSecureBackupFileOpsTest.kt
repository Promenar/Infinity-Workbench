package com.promenar.nexara.data.backup

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.NexaraApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

class AndroidSecureBackupFileOpsTest {
    private lateinit var testRoot: Path

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testRoot = Files.createDirectory(context.noBackupFilesDir.toPath().resolve("secure-fs-${UUID.randomUUID()}"))
    }

    @After
    fun tearDown() {
        testRoot.toFile().deleteRecursively()
    }

    @Test
    fun api36AppPrivateStorageProvidesSecureDirectoryStreamAndCompletesTombstoneLifecycle() {
        Files.newDirectoryStream(testRoot).use { stream ->
            assertThat(stream).isInstanceOf(SecureDirectoryStream::class.java)
        }
        val txId = UUID.randomUUID().toString()
        SecureBackupFileOps.createTransactionRoot(testRoot, "staging")
        val staging = testRoot.resolve("staging")
        SecureBackupFileOps.writeNew(staging, listOf(".restore-owner"), txId.toByteArray())
        SecureBackupFileOps.createDirectory(staging, listOf("workspace"))
        SecureBackupFileOps.writeNew(staging, listOf("workspace", "document.bin"), byteArrayOf(1, 2, 3))
        val expected = setOf(
            RestoreTreeEntry(".restore-owner", false),
            RestoreTreeEntry("workspace", true),
            RestoreTreeEntry("workspace/document.bin", false),
        )
        SecureBackupFileOps.verifyAndSync(staging, expected)

        SecureBackupFileOps.moveTree(testRoot, "staging", "tombstone")
        val tombstone = testRoot.resolve("tombstone")
        val fileKey = Files.readAttributes(
            tombstone,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).fileKey().toString()
        val sibling = Files.write(testRoot.resolve("sibling.keep"), byteArrayOf(9))

        SecureBackupFileOps.deleteTree(
            testRoot,
            "tombstone",
            fileKey,
            ".restore-owner" to txId,
            expected,
        )

        assertThat(Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)).isFalse()
        assertThat(Files.readAllBytes(sibling)).isEqualTo(byteArrayOf(9))
    }

    @Test
    fun descriptorDeleteRejectsSymlinkSwappedRootAndPreservesOutsideSentinel() {
        val txId = UUID.randomUUID().toString()
        SecureBackupFileOps.createTransactionRoot(testRoot, "owned")
        val owned = testRoot.resolve("owned")
        SecureBackupFileOps.writeNew(owned, listOf(".restore-owner"), txId.toByteArray())
        val expected = setOf(RestoreTreeEntry(".restore-owner", false))
        val fileKey = Files.readAttributes(
            owned,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).fileKey().toString()
        val parked = testRoot.resolve("parked")
        Files.move(owned, parked)
        val outside = Files.createDirectory(testRoot.resolve("outside"))
        val sentinel = Files.write(outside.resolve("sentinel.keep"), byteArrayOf(7))
        Files.createSymbolicLink(owned, outside)

        val failed = runCatching {
            SecureBackupFileOps.deleteTree(
                testRoot,
                "owned",
                fileKey,
                ".restore-owner" to txId,
                expected,
            )
        }.isFailure

        assertThat(failed).isTrue()
        assertThat(Files.readAllBytes(sentinel)).isEqualTo(byteArrayOf(7))
        assertThat(Files.isDirectory(parked, LinkOption.NOFOLLOW_LINKS)).isTrue()
    }

    @Test
    fun applicationStartupRecoveryOpensGateAndRestoreDirectorySupportsFsync() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NexaraApplication
        val restoreRoot = app.noBackupFilesDir.toPath().resolve("backup-restore-runtime-v1")

        runBlocking {
            withTimeout(5_000) {
                app.startupState.first { it == BackupStartupState.Ready }
            }
        }
        Files.newDirectoryStream(restoreRoot).use { stream ->
            assertThat(stream).isInstanceOf(SecureDirectoryStream::class.java)
        }
        FileRestoreJournal.syncDirectory(restoreRoot)
    }
}
