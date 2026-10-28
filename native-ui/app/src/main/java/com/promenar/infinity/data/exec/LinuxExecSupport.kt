package com.promenar.infinity.data.exec

import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.isUserSkills
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.storage.ExternalMount
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
    sessionHome: File = workspace,
    launchBinding: com.promenar.infinity.data.branch.BranchLinuxCommandWorkspace? = null,
    workspaceDirectory: String? = null,
): LinuxLaunchSpec {
    val binds = mounts.filterNot { it.isUserSkills }.mapNotNull { mount ->
        if (runCatching { ExternalMountPaths.validateAlias(mount.alias) }.getOrNull() != mount.alias) return@mapNotNull null
        val path = ExternalMountPaths.commandAccess(mount).realPath ?: return@mapNotNull null
        LinuxBind(File(path), "/mnt/${mount.alias}")
    } + LinuxBind(skills, "/mnt/skills")
    val cwd = launchBinding?.workingDirectory ?: workspaceDirectory
    return LinuxLaunchSpec.shell(command, launchBinding?.baseRoot ?: workspace, binds).copy(
        sessionHome = sessionHome, workingDirectory = cwd, environment = mapOf(
        "INFINITY_WORKSPACE" to (cwd ?: "/workspace"),
        "INFINITY_WORKSPACE_KIND" to kind,
        "INFINITY_BACKEND" to "linux",
        "INFINITY_SKILLS" to "/mnt/skills",
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
