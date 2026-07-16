package com.promenar.nexara.share.core

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.rag.VectorizationQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidShareIndexSchedulerTest {
    @Test
    fun `分享调度把已提交FileEntry目标原样交给持久队列`() = runTest {
        val app = mockk<NexaraApplication>()
        val queue = mockk<VectorizationQueue>()
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { app.vectorizationQueue } returns queue
        every { app.getSharedPreferences("rag_settings", Context.MODE_PRIVATE) } returns preferences
        coEvery {
            queue.enqueueDocumentReference(
                workspaceRootUuid = ROOT,
                docId = FILE,
                docTitle = "shared.txt",
                sourceMimeType = "text/plain",
                targetContentHash = HASH,
                targetEpoch = EPOCH,
                kgStrategy = null,
            )
        } returns TASK
        val entry = FileEntry(
            uuid = FILE,
            workspaceRootUuid = ROOT,
            parentUuid = ROOT,
            name = "shared.txt",
            hash = HASH,
            mimeType = "text/plain",
            physicalRootPath = "/tmp/root",
            materializedPath = "/shared.txt",
            createdAt = 1,
            updatedAt = EPOCH,
        )

        val receipt = AndroidShareIndexScheduler(app).schedule(ROOT, entry)

        assertThat(receipt).isEqualTo(ShareIndexReceipt(TASK, FILE))
        coVerify(exactly = 1) {
            queue.enqueueDocumentReference(
                workspaceRootUuid = ROOT,
                docId = FILE,
                docTitle = "shared.txt",
                sourceMimeType = "text/plain",
                targetContentHash = HASH,
                targetEpoch = EPOCH,
                kgStrategy = null,
            )
        }
    }

    @Test
    fun `vectorizedAt旧于updatedAt时不得伪装completed`() = runTest {
        val app = mockk<NexaraApplication>()
        val queue = mockk<VectorizationQueue>()
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { app.vectorizationQueue } returns queue
        every { app.getSharedPreferences("rag_settings", Context.MODE_PRIVATE) } returns preferences
        coEvery { queue.enqueueDocumentReference(any(), any(), any(), any(), any(), any(), any(), any()) } returns TASK
        val entry = entry().copy(vectorizedAt = EPOCH - 1)

        val receipt = AndroidShareIndexScheduler(app).schedule(ROOT, entry)

        assertThat(receipt.taskId).isEqualTo(TASK)
        coVerify(exactly = 1) { queue.enqueueDocumentReference(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `KG启用时只有新鲜vector仍需排队抽取KG`() = runTest {
        val app = mockk<NexaraApplication>()
        val queue = mockk<VectorizationQueue>()
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { preferences.getBoolean("enable_kg", false) } returns true
        every { app.vectorizationQueue } returns queue
        every { app.getSharedPreferences("rag_settings", Context.MODE_PRIVATE) } returns preferences
        coEvery { queue.enqueueDocumentReference(any(), any(), any(), any(), any(), any(), any(), any()) } returns TASK
        val entry = entry().copy(vectorizedAt = EPOCH, kgExtractedAt = null)

        val receipt = AndroidShareIndexScheduler(app).schedule(ROOT, entry)

        assertThat(receipt.taskId).isEqualTo(TASK)
        coVerify(exactly = 1) {
            queue.enqueueDocumentReference(
                ROOT, FILE, "shared.txt", "text/plain", HASH, EPOCH, "full", false,
            )
        }
    }

    private fun entry() = FileEntry(
        uuid = FILE, workspaceRootUuid = ROOT, parentUuid = ROOT, name = "shared.txt",
        hash = HASH, mimeType = "text/plain", physicalRootPath = "/tmp/root",
        materializedPath = "/shared.txt", createdAt = 1, updatedAt = EPOCH,
    )

    private companion object {
        const val ROOT = "root"
        const val FILE = "file"
        const val HASH = "content-hash"
        const val EPOCH = 42L
        const val TASK = "task"
    }
}
