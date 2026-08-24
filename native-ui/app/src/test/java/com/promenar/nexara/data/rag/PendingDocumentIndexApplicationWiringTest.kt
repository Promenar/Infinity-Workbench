package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class PendingDocumentIndexApplicationWiringTest {
    @Test
    fun `Application通过协调器动态委托当前Queue并装配文件仓库`() {
        val source = File(
            "app/src/main/java/com/promenar/nexara/NexaraApplication.kt",
        ).readText()
        val coordinatorBlock = source
            .substringAfter("val pendingDocumentIndexCoordinator")
            .substringBefore("val fileOperationRepository")
        val repositoryBlock = source
            .substringAfter("val fileOperationRepository")
            .substringBefore("val taskRepository")

        assertThat(coordinatorBlock).contains("PendingDocumentIndexCoordinator(")
        assertThat(coordinatorBlock).contains("vectorizationQueue.publish(event)")
        assertThat(coordinatorBlock).contains("resolveCurrentTarget")
        assertThat(coordinatorBlock).contains("database.fileEntryDao().getActiveByUuid")
        assertThat(coordinatorBlock).doesNotContain("database.fileEntryDao().getByUuid")
        assertThat(coordinatorBlock).contains("target.copy(")
        assertThat(coordinatorBlock).contains("contentHash = entry.hash")
        assertThat(coordinatorBlock).contains("targetEpoch = entry.updatedAt")
        assertThat(repositoryBlock).contains("indexEventSink = pendingDocumentIndexCoordinator")
        assertThat(repositoryBlock).doesNotContain("vectorizationQueue.publish(event)")
    }

    @Test
    fun `Application在工作区删除提交后清理协调器pending`() {
        val source = File(
            "app/src/main/java/com/promenar/nexara/NexaraApplication.kt",
        ).readText()
        val workspaceBlock = source
            .substringAfter("val workspaceRepository")
            .substringBefore("val pendingDocumentIndexCoordinator")

        assertThat(workspaceBlock).contains("afterDeleteCommitted")
        assertThat(workspaceBlock).contains("pendingDocumentIndexCoordinator.clearCommitted")
    }
}
