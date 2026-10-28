package com.promenar.infinity.data.backup

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.repository.PersistentFileIdentity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.UserDefinedFileAttributeView

class SkillRestoreTransactionTest {
    @Test
    fun `更名前技能暂存目录仍可凭原身份提交并清理`() = withRoot { base, root, preparing ->
        val txId = "legacy-prefix"
        val operations = TestRestoreFileOperations()
        val transaction = SkillRestoreTransaction(root, operations, RestoreCrashHook {})
        val record = transaction.stage(txId, partition(), transaction.plan(txId), preparing)
        Files.move(base.resolve(".infinity-skills-restore-$txId"), base.resolve(".nexara-skills-restore-$txId"))
        transaction.commit(txId, record)
        transaction.finish(txId, record)
        assertThat(Files.readAllBytes(root.resolve("alpha/SKILL.md")).toString(Charsets.UTF_8)).isEqualTo("技能")
        assertThat(Files.exists(base.resolve(".nexara-skills-restore-$txId"))).isFalse()
    }

    @Test
    fun `技能计划与暂存产生的全部journal身份均绑定对应目录nonce和inode`() = withRoot { base, root, preparing ->
        val txId = "skill-persistent-fields"
        val transaction = SkillRestoreTransaction(root, TestRestoreFileOperations(), RestoreCrashHook {})

        val planned = transaction.plan(txId)
        val staged = transaction.stage(txId, partition(), planned, preparing)
        val staging = base.resolve(".infinity-skills-restore-$txId")

        assertThat(planned.oldRootFileKey).isEqualTo(PersistentFileIdentity.read(root))
        assertThat(staged.oldRootFileKey).isEqualTo(planned.oldRootFileKey)
        assertThat(staged.newRootFileKey).isEqualTo(PersistentFileIdentity.read(staging.resolve("new")))
        assertThat(staged.stagingFileKey).isEqualTo(PersistentFileIdentity.read(staging))
        listOfNotNull(staged.oldRootFileKey, staged.newRootFileKey, staged.stagingFileKey).forEach { identity ->
            assertThat(PersistentFileIdentity.isValid(identity)).isTrue()
            assertThat(identity).doesNotContain("dev=")
        }
    }

    @Test
    fun `技能恢复journal在设备号变化后可提交并清理旧目录`() = withRoot { base, root, preparing ->
        Files.write(root.resolve("old.txt"), "old".toByteArray())
        val operations = TestRestoreFileOperations()
        val txId = "skill-device-change"
        val transaction = SkillRestoreTransaction(root, operations, RestoreCrashHook {})
        val record = transaction.stage(txId, partition(), transaction.plan(txId), preparing)
        val staging = base.resolve(".infinity-skills-restore-$txId")
        val afterUpdate = record.copy(
            oldRootFileKey = afterDeviceChange(root),
            newRootFileKey = afterDeviceChange(staging.resolve("new")),
            stagingFileKey = afterDeviceChange(staging),
        )

        SkillRestoreTransaction(root, operations, RestoreCrashHook {}).commit(txId, afterUpdate)
        SkillRestoreTransaction(root, operations, RestoreCrashHook {}).finish(txId, afterUpdate)

        assertThat(Files.readAllBytes(root.resolve("alpha/SKILL.md")).toString(Charsets.UTF_8)).isEqualTo("技能")
        assertThat(Files.exists(root.resolve("old.txt"))).isFalse()
        assertThat(Files.exists(staging)).isFalse()
    }

    @Test
    fun `技能恢复拒绝把原nonce复制到另一inode的替换根`() = withRoot { base, root, preparing ->
        Files.write(root.resolve("sentinel"), "keep".toByteArray())
        val operations = TestRestoreFileOperations()
        val txId = "skill-root-swap"
        val transaction = SkillRestoreTransaction(root, operations, RestoreCrashHook {})
        val record = transaction.stage(txId, partition(), transaction.plan(txId), preparing)
        val original = Files.move(root, base.resolve("original"))
        Files.createDirectory(root)
        copyAttributes(original, root)

        assertThrows<BackupValidationException> { transaction.commit(txId, record) }

        assertThat(Files.readAllBytes(original.resolve("sentinel")).toString(Charsets.UTF_8)).isEqualTo("keep")
        assertThat(Files.exists(base.resolve(".infinity-skills-restore-$txId/new/alpha/SKILL.md"))).isTrue()
    }

    @Test
    fun `技能恢复拒绝同inode但nonce不同的journal`() = withRoot { _, root, preparing ->
        val operations = TestRestoreFileOperations()
        val txId = "skill-nonce-swap"
        val transaction = SkillRestoreTransaction(root, operations, RestoreCrashHook {})
        val record = transaction.stage(txId, partition(), transaction.plan(txId), preparing)
        val wrongIdentity = PersistentFileIdentity.encode(
            "22222222-2222-4222-8222-222222222222",
            Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey(),
        )

        assertThrows<BackupValidationException> { transaction.commit(txId, record.copy(oldRootFileKey = wrongIdentity)) }

        assertThat(Files.isDirectory(root)).isTrue()
    }

    private fun partition() = SkillBackupPartition(
        mapOf("alpha/SKILL.md" to "技能".toByteArray()), SkillBackupMetadata(listOf("alpha"), SkillBackupSummary(skillCount = 1)),
    )

    private fun afterDeviceChange(path: Path): String {
        val identity = PersistentFileIdentity.read(path)
        val inode = PersistentFileIdentity.inode(Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
        return PersistentFileIdentity.encode(PersistentFileIdentity.nonce(identity), "(dev=999,$inode)")
    }

    private fun copyAttributes(source: Path, target: Path) {
        val from = Files.getFileAttributeView(source, UserDefinedFileAttributeView::class.java, NOFOLLOW_LINKS)
        val to = Files.getFileAttributeView(target, UserDefinedFileAttributeView::class.java, NOFOLLOW_LINKS)
        from.list().forEach { name ->
            val bytes = ByteBuffer.allocate(from.size(name))
            from.read(name, bytes)
            bytes.flip()
            to.write(name, bytes)
        }
    }

    private fun withRoot(block: (Path, Path, Path) -> Unit) {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".skill-restore-identity-test")
        try {
            block(base, Files.createDirectory(base.resolve("skills")), Files.createDirectory(base.resolve("preparing")))
        } finally { base.toFile().deleteRecursively() }
    }
}
