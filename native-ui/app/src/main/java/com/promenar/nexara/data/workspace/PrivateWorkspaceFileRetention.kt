package com.promenar.nexara.data.workspace

import com.promenar.nexara.data.local.db.dao.WorkspaceDao
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.storage.AndroidRealPathFileSystem
import com.promenar.nexara.data.storage.BoundRealPathDirectory
import com.promenar.nexara.data.storage.RealPathFileState
import com.promenar.nexara.data.storage.RealPathFileSystem
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import com.promenar.nexara.domain.workspace.PrivateWorkspaceSummary
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class RetainedSessionWorkspace(
    val workspaceId: String,
    val displayName: String,
    val rollback: suspend () -> Unit,
    val register: suspend () -> Unit = {},
)

/** 调用方在删除屏障内准备文件，登记须与会话数据库删除一起提交。 */
class PrivateWorkspaceFileRetention internal constructor(
    private val projectsRoot: File,
    private val workspaces: WorkspaceDao,
    private val clock: Clock,
    private val fileSystem: RealPathFileSystem,
) {
    constructor(projectsRoot: File, workspaces: WorkspaceDao, clock: Clock = Clock.systemDefaultZone()) :
        this(projectsRoot, workspaces, clock, AndroidRealPathFileSystem)

    suspend fun summary(root: Path): PrivateWorkspaceSummary? = withContext(Dispatchers.IO) {
        withDirectory(root) { source ->
            val files = scan(source).values.filter { it.isRegularFile }
            if (files.isEmpty()) null else PrivateWorkspaceSummary(files.size, files.sumOf { it.size })
        }
    }

    suspend fun prepare(root: Path, title: String): RetainedSessionWorkspace? = withContext(Dispatchers.IO + NonCancellable) {
        withDirectory(root) sourceBlock@ { source ->
            val nodes = scan(source)
            if (nodes.values.none { it.isRegularFile }) return@sourceBlock null
            require(!Files.isSymbolicLink(projectsRoot.toPath())) { "项目根不能是符号链接" }
            val projects = projectsRoot.canonicalFile.toPath()
            Files.createDirectories(projects)
            withDirectory(projects) { parent ->
                val parentId = parent.state().objectId
                val base = "session-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(clock.zone).format(clock.instant())}"
                var suffix = 1
                var name: String
                while (true) {
                    name = if (suffix == 1) base else "$base-$suffix"
                    suffix++
                    check(WorkspaceEntity.isValidLocation(WorkspaceEntity.KIND_PROJECT, name))
                    if (workspaces.getByLocation(WorkspaceEntity.KIND_PROJECT, name) != null || parent.stat(name) != null) continue
                    try {
                        parent.createDirectory(name)
                        break
                    } catch (failure: Exception) {
                        if (parent.stat(name) == null) throw failure
                    }
                }
                val destinationName = name
                val destinationId = checkNotNull(parent.stat(name)).objectId
                val created = linkedMapOf<String, RealPathFileState>()
                val row = WorkspaceEntity(UUID.randomUUID().toString(), WorkspaceEntity.KIND_PROJECT,
                    title.trim().ifEmpty { name }, name, clock.millis())
                val rollback: suspend () -> Unit = {
                    withContext(Dispatchers.IO + NonCancellable) {
                        val registered = workspaces.getByLocation(row.kind, row.location)
                        check(registered == null || registered.id == row.id) { "保留目录已被其它登记采用，副本需人工核查" }
                        if (registered != null) {
                            check(workspaces.countSessionReferences(row.id) == 0 && workspaces.countAgentReferences(row.id) == 0) {
                                "保留目录已被引用，副本需人工核查"
                            }
                        }
                        withDirectory(projects) { currentParent ->
                            check(currentParent.state().objectId == parentId && currentParent.stat(destinationName)?.objectId == destinationId) {
                                "保留目录身份发生变化，副本需人工核查"
                            }
                            currentParent.openDirectory(destinationName).use { destination ->
                                check(sameTree(scan(destination, includeReserved = true), created)) { "保留目录内容发生变化，副本需人工核查" }
                            }
                            deleteIsolated(currentParent, destinationName, destinationId, "", created)
                        }
                        workspaces.deleteById(row.id)
                    }
                }
                try {
                    parent.openDirectory(name).use { destination ->
                        for ((path, state) in nodes) {
                            withRelativeDirectory(source, path.substringBeforeLast('/', ""), nodes) { sourceParent ->
                                val leaf = path.substringAfterLast('/')
                                check(sourceParent.stat(leaf)?.revision == state.revision) { "保留文件期间来源发生变化" }
                                withRelativeDirectory(destination, path.substringBeforeLast('/', "")) { targetParent ->
                                    if (state.isDirectory) targetParent.createDirectory(leaf)
                                    else sourceParent.openRead(leaf).use { input ->
                                        check(input.state().revision == state.revision) { "来源文件身份发生变化" }
                                        targetParent.createFile(leaf).use { output ->
                                            created[path] = output.state()
                                            try {
                                                input.input.copyTo(output.output)
                                                output.sync()
                                                check(output.state().size == state.size) { "目标副本长度不一致" }
                                            } finally {
                                                created[path] = output.state()
                                            }
                                        }
                                        check(input.state().revision == state.revision) { "复制期间来源文件发生变化" }
                                    }
                                    val published = checkNotNull(targetParent.stat(leaf))
                                    val written = created[path]
                                    check(written == null || published.revision == written.revision) { "目标副本在写入后被替换或修改" }
                                    created[path] = published
                                }
                            }
                        }
                        check(sameTree(scan(source), nodes)) { "保留文件期间来源树发生变化" }
                    }
                    RetainedSessionWorkspace(row.id, row.displayName, rollback, register = {
                        withContext(Dispatchers.IO) {
                            withDirectory(projects) { currentParent ->
                                check(currentParent.state().objectId == parentId && currentParent.stat(destinationName)?.objectId == destinationId) {
                                    "登记前保留目录身份发生变化"
                                }
                                currentParent.openDirectory(destinationName).use { destination ->
                                    check(sameTree(scan(destination, includeReserved = true), created)) { "登记前保留目录内容发生变化" }
                                }
                            }
                        }
                        workspaces.insert(row)
                    })
                } catch (failure: Throwable) {
                    runCatching { rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            }
        }
    }

    private fun deleteIsolated(
        parent: BoundRealPathDirectory,
        name: String,
        expectedId: String,
        path: String,
        expected: Map<String, RealPathFileState>,
    ) {
        val quarantine = ".nexara-delete-${UUID.randomUUID()}"
        var isolated = false
        try {
            parent.atomicReplace(name, quarantine)
            isolated = true
            val state = checkNotNull(parent.stat(quarantine))
            check(state.objectId == expectedId && !state.isSymbolicLink) { "回滚删除前目标身份发生变化" }
            if (state.isDirectory) {
                parent.openDirectory(quarantine).use { directory ->
                    val children = directory.names()
                    check(children.toSet() == expected.keys.filter {
                        it.substringBeforeLast('/', "") == path
                    }.map { it.substringAfterLast('/') }.toSet()) { "回滚目录内容发生变化" }
                    for (child in children) {
                        val childPath = if (path.isEmpty()) child else "$path/$child"
                        val original = checkNotNull(expected[childPath])
                        val current = checkNotNull(directory.stat(child))
                        check(current.objectId == original.objectId &&
                            (current.isDirectory || current.contentVersion == original.contentVersion)) { "回滚文件内容发生变化" }
                        deleteIsolated(directory, child, original.objectId, childPath, expected)
                    }
                }
                parent.deleteDirectory(quarantine)
            } else {
                check(state.isRegularFile && state.contentVersion == expected[path]?.contentVersion) { "回滚文件内容发生变化" }
                parent.deleteFile(quarantine)
            }
            isolated = false
        } catch (failure: Throwable) {
            if (isolated && parent.stat(name) == null) {
                runCatching { parent.atomicReplace(quarantine, name) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    private fun sameTree(actual: Map<String, RealPathFileState>, expected: Map<String, RealPathFileState>): Boolean =
        actual.keys == expected.keys && actual.all { (path, state) ->
            val before = checkNotNull(expected[path])
            if (state.isDirectory) state.objectId == before.objectId else state.revision == before.revision
        }

    private fun scan(root: BoundRealPathDirectory, includeReserved: Boolean = false): Map<String, RealPathFileState> {
        val nodes = linkedMapOf<String, RealPathFileState>()
        fun visit(directory: BoundRealPathDirectory, prefix: String) {
            for (name in directory.names().sorted()) {
                if (!includeReserved && (name.startsWith(".nexara", ignoreCase = true) || name.equals(".recycle_bin", ignoreCase = true))) continue
                ExternalFolderPaths.validateName(name)
                val state = checkNotNull(directory.stat(name)) { "文件在扫描期间消失" }
                require(!state.isSymbolicLink && (state.isDirectory || state.isRegularFile)) { "会话专用空间包含链接或特殊文件" }
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                nodes[path] = state
                if (state.isDirectory) directory.openDirectory(name).use { child ->
                    check(child.state().objectId == state.objectId) { "扫描期间目录身份发生变化" }
                    visit(child, path)
                }
            }
        }
        visit(root, "")
        return nodes
    }

    private suspend fun <T> withDirectory(path: Path, block: suspend (BoundRealPathDirectory) -> T): T {
        val opened = mutableListOf<BoundRealPathDirectory>()
        try {
            var current = fileSystem.openFileSystemRoot().also { opened += it }
            for (part in path.toAbsolutePath().normalize()) current = current.openDirectory(part.toString()).also { opened += it }
            return block(current)
        } finally { opened.asReversed().forEach { it.close() } }
    }

    private fun <T> withRelativeDirectory(
        root: BoundRealPathDirectory,
        path: String,
        expected: Map<String, RealPathFileState>? = null,
        block: (BoundRealPathDirectory) -> T,
    ): T {
        val opened = mutableListOf<BoundRealPathDirectory>()
        try {
            var current = root
            var prefix = ""
            if (path.isNotEmpty()) for (name in path.split('/')) {
                current = current.openDirectory(name).also { opened += it }
                prefix = if (prefix.isEmpty()) name else "$prefix/$name"
                expected?.get(prefix)?.let { check(current.state().objectId == it.objectId) { "来源祖先目录身份发生变化" } }
            }
            return block(current)
        } finally { opened.asReversed().forEach { it.close() } }
    }
}
