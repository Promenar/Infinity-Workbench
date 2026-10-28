package com.promenar.nexara.data.workspace

import com.promenar.nexara.domain.workspace.WorkspaceInternalPaths
import com.promenar.nexara.data.local.db.dao.WorkspaceDao
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 共享存储卷的渠道能力与授权状态；Play 渠道 [supported] 为 false 且 [roots] 为空。 */
interface SharedStorageVolumes {
    val supported: Boolean
    fun hasPermission(): Boolean
    /** 已规范化的卷根目录。 */
    fun roots(): List<File>
    /** 校验并规范化共享目录：须在卷内、不进入 `Android/`、不经过符号链接；不合规时抛出 [ExternalFolderException]。 */
    fun normalize(path: String): String
}

/** 选择器中可进入或选定的一个目录。 */
data class WorkspaceFolder(val name: String, val path: File)

enum class FolderNameError { EMPTY, INVALID, RESERVED, EXISTS }

sealed interface CreateFolderResult {
    data class Created(val folder: WorkspaceFolder) : CreateFolderResult
    data class Rejected(val error: FolderNameError) : CreateFolderResult
}

/**
 * 自选工作区的目录浏览与登记。项目文件夹只能是 App 私有项目根下的一级目录；
 * 共享存储只列目录、跳过符号链接与卷顶层的 `Android`。浏览与新建只创建空目录，不写任何标记文件。
 */
class WorkspaceDirectoryBrowser(
    /** App 私有项目根（`filesDir/projects`），使用前规范化，避开 `/data/user/0` 这一系统符号链接。 */
    private val projectsRoot: File,
    private val shared: SharedStorageVolumes,
    private val workspaceDao: WorkspaceDao,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val linuxHome: LinuxHomeWorkspaceSource? = null,
) {
    val sharedSupported: Boolean get() = shared.supported

    fun hasSharedPermission(): Boolean = shared.supported && shared.hasPermission()

    fun sharedRoots(): List<File> = if (shared.supported) shared.roots() else emptyList()

    suspend fun linuxHomeAvailability(): LinuxHomeAvailability = withContext(io) {
        linuxHome?.availability() ?: LinuxHomeAvailability.UNSUPPORTED
    }

    /** 顶层候选只包含真实存在且不经过符号链接的目录，/root 始终排在首位。 */
    suspend fun linuxRoots(): List<WorkspaceFolder> = withContext(io) {
        val source = requireLinux()
        source.root()
        LinuxWorkspaceLocation.roots.mapNotNull { directory ->
            try { source.directory(directory); WorkspaceFolder(File(directory).name, File(directory)) }
            catch (_: ExternalFolderException) { null }
        }
    }

    suspend fun listLinux(directory: String): List<WorkspaceFolder> = withContext(io) {
        requireLinux().children(directory)
    }

    suspend fun createLinuxFolder(parent: String, rawName: String): CreateFolderResult = withContext(io) {
        val source = requireLinux()
        source.directory(parent)
        val name = rawName.trim()
        if (name.isEmpty()) return@withContext CreateFolderResult.Rejected(FolderNameError.EMPTY)
        if (!isVisibleSharedName(name) || !LinuxWorkspaceLocation.isAllowedDirectory("$parent/$name")) {
            return@withContext CreateFolderResult.Rejected(FolderNameError.INVALID)
        }
        source.createDirectory(parent, name)
    }

    /** 相同容器路径与环境身份重复登记返回已有记录。 */
    suspend fun registerLinux(directory: String, displayName: String? = null): WorkspaceEntity = withContext(io) {
        val source = requireLinux()
        val location = source.location(directory)
        workspaceDao.register(WorkspaceEntity(idFactory(), WorkspaceEntity.KIND_LINUX_HOME,
            displayName ?: File(directory).name, location, clock()))
    }

    suspend fun registerLinuxHome(displayName: String = "Linux /root"): WorkspaceEntity = registerLinux("/root", displayName)

    private fun requireLinux(): LinuxHomeWorkspaceSource = linuxHome
        ?: throw ExternalFolderException("UNSUPPORTED", "此发行渠道不支持 Linux 主目录")

    suspend fun listProjects(): List<WorkspaceFolder> = withContext(io) {
        val root = canonicalProjectsRoot()
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) return@withContext emptyList()
        directoryChildren(root.toPath()) { name -> validateProjectName(name) == null }
    }

    suspend fun createProject(rawName: String): CreateFolderResult = withContext(io) {
        val name = rawName.trim()
        validateProjectName(name)?.let { return@withContext CreateFolderResult.Rejected(it) }
        val root = canonicalProjectsRoot()
        Files.createDirectories(root.toPath())
        create(root.toPath(), name)
    }

    /** [directory] 必须位于某个共享卷内；卷顶层不列出 `Android`。 */
    suspend fun listShared(directory: File): List<WorkspaceFolder> = withContext(io) {
        val (volume, path) = requireSharedDirectory(directory)
        val atVolumeTop = path == volume
        directoryChildren(path) { name ->
            isVisibleSharedName(name) && !(atVolumeTop && name.equals(ANDROID_DIR, ignoreCase = true))
        }
    }

    suspend fun createSharedFolder(parent: File, rawName: String): CreateFolderResult = withContext(io) {
        val name = rawName.trim()
        if (name.isEmpty()) return@withContext CreateFolderResult.Rejected(FolderNameError.EMPTY)
        if (!isVisibleSharedName(name)) return@withContext CreateFolderResult.Rejected(FolderNameError.INVALID)
        val (volume, path) = requireSharedDirectory(parent)
        if (path == volume && name.equals(ANDROID_DIR, ignoreCase = true)) {
            return@withContext CreateFolderResult.Rejected(FolderNameError.RESERVED)
        }
        create(path, name)
    }

    /** 登记项目根下已存在的一级目录；同一目录重复登记返回已有记录。 */
    suspend fun registerProject(name: String): WorkspaceEntity = withContext(io) {
        if (validateProjectName(name) != null) throw ExternalFolderException("INVALID_PATH", "项目名称无效")
        val folder = File(canonicalProjectsRoot(), name)
        if (!Files.isDirectory(folder.toPath(), NOFOLLOW_LINKS)) throw ExternalFolderException("NOT_FOUND", "项目文件夹不存在")
        workspaceDao.register(WorkspaceEntity(idFactory(), WorkspaceEntity.KIND_PROJECT, name, name, clock()))
    }

    /** 登记共享存储目录：先按真实路径挂载规则规范化，显示名默认取目录名。 */
    suspend fun registerShared(directory: File): WorkspaceEntity = withContext(io) {
        if (!shared.supported) throw ExternalFolderException("UNSUPPORTED", "此发行渠道不支持共享存储工作区")
        if (!shared.hasPermission()) throw ExternalFolderException("UNAVAILABLE", "请先授予所有文件访问权限")
        val normalized = shared.normalize(directory.path)
        val name = File(normalized).name.ifEmpty { normalized }
        workspaceDao.register(WorkspaceEntity(idFactory(), WorkspaceEntity.KIND_SHARED, name, normalized, clock()))
    }

    private fun canonicalProjectsRoot(): File = projectsRoot.canonicalFile

    private fun requireSharedDirectory(directory: File): Pair<Path, Path> {
        if (!shared.supported) throw ExternalFolderException("UNSUPPORTED", "此发行渠道不支持共享存储工作区")
        if (!shared.hasPermission()) throw ExternalFolderException("UNAVAILABLE", "请先授予所有文件访问权限")
        val path = directory.absoluteFile.toPath().normalize()
        val volume = shared.roots().map { it.absoluteFile.toPath().normalize() }.firstOrNull { path.startsWith(it) }
            ?: throw ExternalFolderException("INVALID_PATH", "目录不在共享存储卷内")
        val relative = volume.relativize(path)
        if (relative.toString().isNotEmpty() && relative.firstOrNull()?.toString()?.equals(ANDROID_DIR, ignoreCase = true) == true) {
            throw ExternalFolderException("INVALID_PATH", "不能进入共享存储的 Android 目录")
        }
        // 只经列出的子目录逐级进入；仍逐级复核，防止目录在浏览期间被替换为符号链接。
        var current = volume
        if (relative.toString().isNotEmpty()) relative.forEach { name ->
            current = current.resolve(name)
            if (Files.isSymbolicLink(current)) throw ExternalFolderException("INVALID_PATH", "不能经过符号链接")
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw ExternalFolderException("NOT_FOUND", "目录不存在")
        return volume to path
    }

    private fun directoryChildren(directory: Path, accept: (String) -> Boolean): List<WorkspaceFolder> = try {
        Files.newDirectoryStream(directory).use { stream ->
            stream.mapNotNull { child ->
                val name = child.fileName?.toString() ?: return@mapNotNull null
                if (!accept(name)) return@mapNotNull null
                val attributes = try {
                    Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                } catch (_: IOException) {
                    return@mapNotNull null
                }
                if (attributes.isSymbolicLink || !attributes.isDirectory) null else WorkspaceFolder(name, child.toFile())
            }.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    } catch (_: AccessDeniedException) {
        throw ExternalFolderException("UNAVAILABLE", "目录访问被拒绝")
    } catch (_: NoSuchFileException) {
        throw ExternalFolderException("NOT_FOUND", "目录不存在")
    } catch (failure: SecurityException) {
        throw ExternalFolderException("UNAVAILABLE", failure.message ?: "目录访问被拒绝")
    }

    private fun create(parent: Path, name: String): CreateFolderResult {
        val target = parent.resolve(name)
        if (Files.exists(target, NOFOLLOW_LINKS)) return CreateFolderResult.Rejected(FolderNameError.EXISTS)
        return try {
            Files.createDirectory(target)
            CreateFolderResult.Created(WorkspaceFolder(name, target.toFile()))
        } catch (_: FileAlreadyExistsException) {
            CreateFolderResult.Rejected(FolderNameError.EXISTS)
        } catch (_: AccessDeniedException) {
            throw ExternalFolderException("UNAVAILABLE", "目录访问被拒绝")
        }
    }

    companion object {
        private const val ANDROID_DIR = "Android"

        /** 项目名须符合 [WorkspaceEntity.isValidLocation]，并避开 `.nexara*` 保留名。 */
        fun validateProjectName(name: String): FolderNameError? = when {
            name.isEmpty() -> FolderNameError.EMPTY
            !WorkspaceEntity.isValidLocation(WorkspaceEntity.KIND_PROJECT, name) -> FolderNameError.INVALID
            WorkspaceInternalPaths.isReservedName(name) -> FolderNameError.RESERVED
            else -> null
        }

        /** 共享目录沿用外部文件夹的名称规则：拒绝越界片段、`.nexara*` 与回收站保留名。 */
        private fun isVisibleSharedName(name: String): Boolean = try {
            ExternalFolderPaths.validateName(name)
            true
        } catch (_: ExternalFolderException) {
            false
        }
    }
}
