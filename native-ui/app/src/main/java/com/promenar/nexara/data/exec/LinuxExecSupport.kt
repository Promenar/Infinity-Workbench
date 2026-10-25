package com.promenar.nexara.data.exec

import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.data.storage.isUserSkills
import com.promenar.nexara.domain.linux.*
import com.promenar.nexara.domain.storage.ExternalMount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.logging.Logger

/** 只把有真实路径且当前可用的会话挂载交给 proot；SAF 不伪装成本机目录。 */
internal fun linuxExecSpec(
    command: String,
    workspace: File,
    kind: String,
    mounts: List<ExternalMount>,
    skills: File,
): LinuxLaunchSpec {
    val binds = mounts.filterNot { it.isUserSkills }.mapNotNull { mount ->
        if (runCatching { ExternalMountPaths.validateAlias(mount.alias) }.getOrNull() != mount.alias) return@mapNotNull null
        val path = ExternalMountPaths.commandAccess(mount).realPath ?: return@mapNotNull null
        LinuxBind(File(path), "/mnt/${mount.alias}")
    } + LinuxBind(skills, "/mnt/skills")
    return LinuxLaunchSpec.shell(command, workspace, binds).copy(environment = mapOf(
        "NEXARA_WORKSPACE" to "/workspace",
        "NEXARA_WORKSPACE_KIND" to kind,
        "NEXARA_BACKEND" to "linux",
        "NEXARA_SKILLS" to "/mnt/skills",
    ))
}

/** 来源检测是尽力记录；命令取消后也完成结束快照，不吞掉执行本身的取消。 */
internal class LinuxExecProvenance(private val provenance: LinuxPackageProvenance?) {
    suspend fun before(): Set<String>? = withContext(Dispatchers.IO) {
        try { provenance?.worldSnapshot() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { logger.warning("Linux 软件包来源起始快照失败"); null }
    }

    suspend fun after(before: Set<String>?, sessionId: String?, title: String?) = withContext(NonCancellable + Dispatchers.IO) {
        if (provenance == null) return@withContext
        try {
            val after = provenance.worldSnapshot()
            if (before != null && after != null && (after - before).isNotEmpty() && sessionId != null) {
                provenance.attributeNewPackages(before, LinuxPackageOrigin.Agent(sessionId, title))
            }
        } catch (_: Exception) { logger.warning("Linux 软件包来源结束记录失败") }
    }

    private companion object { val logger: Logger = Logger.getLogger(LinuxExecProvenance::class.java.name) }
}
