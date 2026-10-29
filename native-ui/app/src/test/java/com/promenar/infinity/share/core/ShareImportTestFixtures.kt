package com.promenar.infinity.share.core

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.linux.LinuxFileEntry
import com.promenar.infinity.domain.linux.LinuxFileSystem
import com.promenar.infinity.domain.linux.LinuxFileType
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

internal class ShareWorkspaceFixture(private val base: File) {
    val entries = linkedMapOf<Pair<String, String>, FileEntry>()
    val repo = mockk<IWorkspaceRepository>()
    fun root(id: String): FileEntry = entries.getOrPut(id to "/") {
        val directory = File(base, id).apply { mkdirs() }
        FileEntry(id, id, null, "root", "", isDirectory = true, physicalRootPath = directory.path,
            materializedPath = "/", createdAt = 1, updatedAt = 1)
    }
    init {
        coEvery { repo.ensureSessionRoot(any()) } answers { root("root-${firstArg<String>()}") }
        coEvery { repo.getByUuid(any(), any()) } answers {
            entries.values.singleOrNull { it.workspaceRootUuid == firstArg<String>() && it.uuid == secondArg<String>() }
        }
        coEvery { repo.getByMaterializedPath(any(), any()) } answers { entries[firstArg<String>() to secondArg<String>()] }
        every { repo.observeChildren(any(), any()) } answers {
            flowOf(entries.values.filter { it.workspaceRootUuid == firstArg<String>() && it.parentUuid == secondArg<String>() })
        }
        coEvery { repo.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            val path = arg<String>(5)
            val file = File(root(id).physicalRootPath, path.removePrefix("/"))
            val output = java.io.ByteArrayOutputStream()
            arg<(java.io.OutputStream) -> Unit>(7)(output)
            if (output.size() > arg<Long>(6)) throw com.promenar.infinity.data.repository.WorkspaceFileTooLargeException(arg(6))
            file.writeBytes(output.toByteArray())
            root(id).copy(uuid = arg(1), name = arg(2), mimeType = arg(3), parentUuid = arg(4), isDirectory = false,
                materializedPath = path, sizeBytes = file.length(), hash = sha(file.readBytes())).also { entries[id to path] = it }
        }
        coEvery { repo.createDirectoryInWorkspace(any(), any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            val path = arg<String>(4)
            File(root(id).physicalRootPath, path.removePrefix("/")).mkdirs()
            root(id).copy(uuid = arg(1), name = arg(2), parentUuid = arg(3), materializedPath = path)
                .also { entries[id to path] = it }
        }
        coEvery { repo.confirmCreatedEntry(any(), any()) } just Runs
        coEvery { repo.rollbackCreatedEntry(any(), any()) } answers {
            val entry = entries.values.single { it.workspaceRootUuid == firstArg<String>() && it.uuid == secondArg<String>() }
            File(entry.physicalRootPath, entry.materializedPath.removePrefix("/")).delete()
            entries.remove(entry.workspaceRootUuid to entry.materializedPath)
            Unit
        }
    }
    suspend fun folder(root: String, path: String, parent: String = root) = repo.createDirectoryInWorkspace(root,
        UUID.randomUUID().toString(), path.substringAfterLast('/'), parent, path)
}

internal fun shareLinuxFiles(root: File): LinuxFileSystem = mockk<LinuxFileSystem>().also { files ->
    every { files.hostFile(any()) } answers {
        val path = root.toPath().resolve(firstArg<String>().removePrefix("/"))
        path.toFile().takeIf { Files.exists(path, NOFOLLOW_LINKS) }
    }
    coEvery { files.stat(any()) } answers {
        val guest = firstArg<String>()
        val path = root.toPath().resolve(guest.removePrefix("/"))
        if (!Files.exists(path, NOFOLLOW_LINKS)) null else LinuxFileEntry(guest, path.fileName.toString(), when {
            Files.isSymbolicLink(path) -> LinuxFileType.SYMLINK
            Files.isDirectory(path, NOFOLLOW_LINKS) -> LinuxFileType.DIRECTORY
            else -> LinuxFileType.FILE
        }, Files.size(path), 0, 0, "root", "root")
    }
    coEvery { files.createDirectory(any(), any()) } coAnswers {
        val guest = firstArg<String>().trimEnd('/') + "/" + secondArg<String>()
        Files.createDirectory(root.toPath().resolve(guest.removePrefix("/")))
        files.stat(guest)!!
    }
}

internal fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
