package com.promenar.nexara.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import com.promenar.nexara.distribution.DistributionCapabilities
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

object RealPathMountSupport {
    fun hasPermission(context: Context): Boolean = DistributionCapabilities.allFilesAccessAvailable &&
        try { Environment.isExternalStorageManager() } catch (_: SecurityException) { false }

    fun permissionIntent(context: Context): Intent? = if (DistributionCapabilities.allFilesAccessAvailable) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    } else null

    fun normalizeSource(context: Context, source: String): String {
        if (!DistributionCapabilities.allFilesAccessAvailable) unsupported()
        if (source.isBlank() || !File(source).isAbsolute || '\u0000' in source || '\\' in source ||
            source.split('/').any { it == "." || it == ".." }
        ) invalid()
        val path = File(source).absoluteFile.toPath().normalize()
        val volume = sharedVolumes(context).firstOrNull { path.startsWith(it) } ?: invalid()
        val relative = volume.relativize(path)
        if (relative.any { it.toString().equals("Android", ignoreCase = true) }) invalid()
        if (relative.toString().isNotEmpty()) relative.forEach { ExternalFolderPaths.validateName(it.toString()) }
        verifyNoLinks(path)
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw ExternalFolderException("NOT_FOUND", "挂载来源不是可用目录")
        return path.toString()
    }

    fun createBackend(context: Context, source: String): ExternalFolderBackend {
        if (!DistributionCapabilities.allFilesAccessAvailable) unsupported()
        if (!hasPermission(context)) throw ExternalFolderException("UNAVAILABLE", "请先授予所有文件访问权限")
        val normalized = normalizeSource(context, source)
        val forbidden = sharedVolumes(context).map { it.resolve("Android") }.toSet()
        val application = context.applicationContext
        return RealPathExternalFolderBackend(File(normalized), forbidden, { hasPermission(application) }, AndroidRealPathFileSystem)
    }

    /** 可浏览的共享存储卷根目录（已规范化、去重）；是否已授权由 [hasPermission] 另行判断。 */
    fun sharedVolumeRoots(context: Context): List<File> =
        if (DistributionCapabilities.allFilesAccessAvailable) sharedVolumes(context).map(Path::toFile) else emptyList()

    private fun sharedVolumes(context: Context): List<Path> {
        val volumes = try {
            context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty().mapNotNull { it.directory }
        } catch (_: SecurityException) {
            emptyList()
        }
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory()
        return (volumes + primary).map { it.absoluteFile.toPath().normalize() }.distinct()
    }

    private fun verifyNoLinks(path: Path) {
        var component = path.root
        for (name in path) {
            component = component.resolve(name)
            if (Files.isSymbolicLink(component)) invalid()
        }
    }

    private fun invalid(): Nothing = throw ExternalFolderException("INVALID_PATH", "只允许共享存储卷内的普通目录，不能挂载 Android 子树或符号链接")
    private fun unsupported(): Nothing = throw ExternalFolderException("UNSUPPORTED", "此发行渠道不支持真实路径挂载")
}
