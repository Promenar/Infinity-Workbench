package com.promenar.infinity.data.backup

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 技能目录恢复事务在真实 Android 文件系统上执行：技能根位于 filesDir（/data/user/0 系统别名之下），
 * 暂存父目录位于 noBackupFilesDir，与主恢复流程的目录布局一致。
 */
@RunWith(AndroidJUnit4::class)
class SkillRestoreTransactionDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val id = UUID.randomUUID().toString()
    private val base = File(context.filesDir, "skill-restore-device-$id").apply { mkdirs() }
    private val preparing = File(requireNotNull(ContextCompat.getNoBackupFilesDir(context)), "skill-restore-device-$id").apply { mkdirs() }

    @After
    fun tearDown() {
        base.deleteRecursively()
        preparing.deleteRecursively()
    }

    @Test
    fun stageCommitAndFinishReplaceSkillsDirectory() {
        val root = File(base, "agent_skills")
        File(root, "old-skill").apply { mkdirs() }.resolve("SKILL.md").writeText("old")
        val transaction = SkillRestoreTransaction(root.toPath(), SecureBackupFileOps, RestoreCrashHook {})
        val txId = UUID.randomUUID().toString()
        val partition = SkillBackupPartition(
            files = mapOf("restored/SKILL.md" to "---\nname: restored\ndescription: d\n---\n".toByteArray()),
            metadata = SkillBackupMetadata(directories = listOf("restored"), summary = SkillBackupSummary(skillCount = 1)),
        )

        var record = transaction.plan(txId)
        record = transaction.stage(txId, partition, record, preparing.toPath())
        transaction.commit(txId, record)
        transaction.finish(txId, record)

        assertThat(File(root, "restored/SKILL.md").readText()).contains("name: restored")
        assertThat(File(root, "old-skill").exists()).isFalse()
        assertThat(base.list()!!.toList()).containsExactly("agent_skills")
    }
}
