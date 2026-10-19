package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.PatchOperation
import com.promenar.nexara.domain.repository.PatchResult
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.skills.FilePatchSkill
import com.promenar.nexara.ui.chat.manager.skills.MountedWorkspaceSkill
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class OriginalCoordinatePatchTest {
    private lateinit var db: NexaraDatabase
    private lateinit var repository: FileOperationRepository
    private lateinit var directory: File
    private var nextCase = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), TestWorkspaceFileOps())
        directory = File(System.getProperty("user.dir"), ".nexara_patch_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        directory.deleteRecursively()
    }

    @Test
    fun `私有补丁删除和原行后插入的任意排列结果相同`() = runBlocking<Unit> {
        assertPrivatePermutations(deleteAndInsert)
    }

    @Test
    fun `挂载补丁删除和原行后插入的任意排列结果相同`() = runBlocking<Unit> {
        assertMountedPermutations(deleteAndInsert)
    }

    @Test
    fun `私有补丁替换和原行后插入的任意排列结果相同`() = runBlocking<Unit> {
        assertPrivatePermutations(replaceAndInsert)
    }

    @Test
    fun `挂载补丁替换和原行后插入的任意排列结果相同`() = runBlocking<Unit> {
        assertMountedPermutations(replaceAndInsert)
    }

    @Test
    fun `私有补丁允许首尾空文件与相邻区间边界`() = runBlocking<Unit> {
        boundaryFixtures.forEach { assertPrivatePermutations(it) }
    }

    @Test
    fun `挂载补丁允许首尾空文件与相邻区间边界`() = runBlocking<Unit> {
        boundaryFixtures.forEach { assertMountedPermutations(it) }
    }

    @Test
    fun `私有补丁拒绝交叉与重复插入并保留原文和历史`() = runBlocking<Unit> {
        conflictOperations.forEach { operations ->
            permutations(operations).forEach { ordered ->
                val (entry, file) = privateFile("A\nB\nC\nD")
                val result = repository.patchFile(entry.workspaceRootUuid, entry.uuid, ordered, entry.hash)

                assertThat(result).isInstanceOf(PatchResult.Failure::class.java)
                assertThat(file.readText()).isEqualTo("A\nB\nC\nD")
                assertThat(db.fileVersionDao().getByFile(entry.workspaceRootUuid, entry.uuid)).isEmpty()
            }
        }
    }

    @Test
    fun `挂载补丁拒绝交叉与重复插入并保留原文`() = runBlocking<Unit> {
        conflictOperations.forEach { operations ->
            permutations(operations).forEach { ordered ->
                val (result, content) = patchMounted("A\nB\nC\nD", ordered)

                assertThat(result.status).isEqualTo("error")
                assertThat(content).isEqualTo("A\nB\nC\nD")
            }
        }
    }

    private suspend fun assertPrivatePermutations(fixture: Fixture) {
        permutations(fixture.operations).forEach { ordered ->
            val (entry, file) = privateFile(fixture.original)
            val result = repository.patchFile(entry.workspaceRootUuid, entry.uuid, ordered, entry.hash)

            assertThat(result).isInstanceOf(PatchResult.Success::class.java)
            assertThat(file.readText()).isEqualTo(fixture.expected)
        }
    }

    private suspend fun assertMountedPermutations(fixture: Fixture) {
        permutations(fixture.operations).forEach { ordered ->
            val (result, content) = patchMounted(fixture.original, ordered)

            assertThat(result.status).isEqualTo("success")
            assertThat(content).isEqualTo(fixture.expected)
        }
    }

    private suspend fun privateFile(content: String): Pair<FileEntry, File> {
        val case = nextCase++
        val root = "root-$case"
        val physicalRoot = File(directory, "case-$case").apply { mkdirs() }
        val file = File(physicalRoot, "notes.txt").apply { writeText(content) }
        db.fileEntryDao().insert(FileEntry(
            uuid = root, workspaceRootUuid = root, parentUuid = null, name = "workspace",
            hash = "root-identity-$case", isDirectory = true, physicalRootPath = physicalRoot.absolutePath,
            materializedPath = "/", createdAt = 1, updatedAt = 1,
        ))
        val entry = FileEntry(
            uuid = "file-$case", workspaceRootUuid = root, parentUuid = root, name = "notes.txt",
            hash = Sha256Utils.hash(content), sizeBytes = file.length(), physicalRootPath = physicalRoot.absolutePath,
            materializedPath = "/notes.txt", createdAt = 1, updatedAt = 1,
        )
        db.fileEntryDao().insert(entry)
        return entry to file
    }

    private suspend fun patchMounted(content: String, operations: List<PatchOperation>): Pair<ToolResult, String> {
        val path = "/mnt/test/notes.txt"
        val uuid = "external:mount:notes"
        val expectedHash = Sha256Utils.hash(content)
        var bytes = content.toByteArray(Charsets.UTF_8)
        val mountedEntry = FileEntry(
            uuid = uuid, workspaceRootUuid = "root", parentUuid = null, name = "notes.txt",
            hash = expectedHash, sizeBytes = bytes.size.toLong(), physicalRootPath = "", materializedPath = path,
            createdAt = 1, updatedAt = 1,
        )
        val mounts = mockk<ExternalWorkspaceAccess> {
            coEvery { workspaceMount(any()) } returns null
            coEvery { visible("session") } returns emptyList()
            coEvery { entry("session", path) } answers { mountedEntry.copy(hash = Sha256Utils.hash(bytes)) }
            coEvery { resolveUuid("session", uuid) } returns path
            coEvery { read("session", path, any()) } answers { bytes.copyOf() }
            coEvery { write("session", path, any(), expectedHash, false) } answers {
                bytes = thirdArg<ByteArray>().copyOf()
                mountedEntry.copy(hash = Sha256Utils.hash(bytes), sizeBytes = bytes.size.toLong())
            }
        }
        val workspace = mockk<IWorkspaceRepository>()
        val files = mockk<IFileOperationRepository>()
        val context = object : SkillExecutionContext {
            override val sessionId = "session"
            override val agentId = "agent"
            override val workspaceRootUuid = "root"
            override val workspacePath: String? = null
        }
        val arguments = buildJsonObject {
            put("path", path)
            put("expectedHash", expectedHash)
            put("operations", buildJsonArray {
                operations.forEach { operation ->
                    add(buildJsonObject {
                        put("action", operation.action)
                        operation.startLine?.let { put("startLine", it) }
                        operation.endLine?.let { put("endLine", it) }
                        operation.afterLine?.let { put("afterLine", it) }
                        operation.newContent?.let { put("newContent", it) }
                        operation.expectedContent?.let { put("expectedContent", it) }
                    })
                }
            })
        }
        val result = MountedWorkspaceSkill(FilePatchSkill(files, workspace), mounts, workspace, files, FileIndexEventSink.None)
            .execute(arguments, context)
        return result to bytes.toString(Charsets.UTF_8)
    }

    private data class Fixture(val original: String, val operations: List<PatchOperation>, val expected: String)

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.isEmpty()) listOf(emptyList())
        else items.indices.flatMap { index ->
            permutations(items.filterIndexed { other, _ -> other != index }).map { listOf(items[index]) + it }
        }

    private val deleteAndInsert = Fixture(
        "A\nB\nC\nD",
        listOf(
            PatchOperation("delete_lines", startLine = 2, endLine = 2, expectedContent = "B"),
            PatchOperation("insert_after", afterLine = 2, newContent = "X"),
            PatchOperation("replace_lines", startLine = 4, endLine = 4, newContent = "Z", expectedContent = "D"),
        ),
        "A\nX\nC\nZ",
    )

    private val replaceAndInsert = Fixture(
        "A\nB\nC\nD",
        listOf(
            PatchOperation("replace_lines", startLine = 2, endLine = 2, newContent = "R1\nR2", expectedContent = "B"),
            PatchOperation("insert_after", afterLine = 2, newContent = "X"),
            PatchOperation("delete_lines", startLine = 4, endLine = 4, expectedContent = "D"),
        ),
        "A\nR1\nR2\nX\nC",
    )

    private val boundaryFixtures = listOf(
        Fixture("", listOf(PatchOperation("insert_after", afterLine = 0, newContent = "X")), "X"),
        Fixture("A\nB", listOf(
            PatchOperation("insert_after", afterLine = 0, newContent = "HEAD"),
            PatchOperation("insert_after", afterLine = 2, newContent = "TAIL"),
        ), "HEAD\nA\nB\nTAIL"),
        Fixture("A\nB\nC\nD", listOf(
            PatchOperation("replace_lines", startLine = 1, endLine = 2, newContent = "R", expectedContent = "A\nB"),
            PatchOperation("delete_lines", startLine = 3, endLine = 3, expectedContent = "C"),
            PatchOperation("insert_after", afterLine = 2, newContent = "X"),
        ), "R\nX\nD"),
        Fixture("A\nB", listOf(
            PatchOperation("delete_lines", startLine = 1, endLine = 2),
            PatchOperation("insert_after", afterLine = 2, newContent = "X"),
        ), "X"),
        Fixture("A", listOf(PatchOperation("replace_lines", startLine = 1, endLine = 1, newContent = "")), ""),
    )

    private val conflictOperations = listOf(
        listOf(
            PatchOperation("delete_lines", startLine = 2, endLine = 3),
            PatchOperation("insert_after", afterLine = 2, newContent = "X"),
        ),
        listOf(
            PatchOperation("replace_lines", startLine = 1, endLine = 3, newContent = "R"),
            PatchOperation("delete_lines", startLine = 2, endLine = 4),
        ),
        listOf(
            PatchOperation("replace_lines", startLine = 2, endLine = 2, newContent = "R"),
            PatchOperation("delete_lines", startLine = 2, endLine = 2),
        ),
        listOf(
            PatchOperation("insert_after", afterLine = 2, newContent = "X"),
            PatchOperation("insert_after", afterLine = 2, newContent = "Y"),
        ),
    )
}
