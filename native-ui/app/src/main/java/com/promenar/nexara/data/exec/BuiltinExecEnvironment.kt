package com.promenar.nexara.data.exec

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.promenar.nexara.data.workspace.SessionWorkspaceResolver
import com.promenar.nexara.infra.util.Sha256Utils
import java.io.File

/**
 * 内置后端的进程环境：白名单启动，不继承主进程环境变量。
 * 打包 Python 的 `LD_LIBRARY_PATH` 只在调用解释器时设置，避免影响 shell 中的系统命令。
 */
class BuiltinExecEnvironment(
    private val python: PackagedPython,
    private val homeDirectory: File,
    private val tempDirectory: File,
    private val caBundle: () -> File?,
    /** 为 null 时（Play 渠道）不开放 pip 与用户站点目录。 */
    private val userPackages: PythonUserPackages? = null,
    private val inherited: Map<String, String> = System.getenv(),
) {
    val pythonAvailable: Boolean get() = python.isAvailable

    /** 侧载渠道开放 pip；Play 渠道 `pip` 返回 PIP_UNSUPPORTED。 */
    val pipAvailable: Boolean get() = userPackages != null

    /** [kind] 写入 `NEXARA_WORKSPACE_KIND`（private、project、shared），供内置 git 判断提示与兼容配置。 */
    fun forWorkspace(workspace: File, kind: String = SessionWorkspaceResolver.KIND_PRIVATE): Map<String, String> {
        homeDirectory.mkdirs()
        tempDirectory.mkdirs()
        val environment = linkedMapOf<String, String>()
        INHERITED_KEYS.forEach { key -> inherited[key]?.let { environment[key] = it } }
        environment.putIfAbsent("PATH", DEFAULT_PATH)
        environment["HOME"] = homeDirectory.path
        environment["TMPDIR"] = tempDirectory.path
        environment["NEXARA_WORKSPACE"] = workspace.path
        environment["NEXARA_WORKSPACE_KIND"] = kind
        environment["NEXARA_BACKEND"] = "builtin"
        environment["LANG"] = "C.UTF-8"
        if (python.isAvailable) {
            environment.putAll(python.environment() - "LD_LIBRARY_PATH")
            environment["PYTHONUTF8"] = "1"
            environment["NEXARA_PYTHON"] = python.launcher.path
            caBundle()?.let { environment["SSL_CERT_FILE"] = it.path }
            userPackages?.let { packages ->
                environment.remove("PYTHONNOUSERSITE")
                environment["PYTHONUSERBASE"] = packages.userBase.path
                environment["NEXARA_PIP"] = "1"
                environment["NEXARA_PIP_DIR"] = packages.pipDirectory.path
                environment["PIP_CACHE_DIR"] = packages.cacheDirectory.path
                environment["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
                environment["PIP_NO_INPUT"] = "1"
                environment["PIP_PROGRESS_BAR"] = "off"
            }
        }
        return environment
    }

    /** 直接运行解释器时补上库搜索路径。 */
    fun forPython(workspace: File, kind: String = SessionWorkspaceResolver.KIND_PRIVATE): Map<String, String> =
        forWorkspace(workspace, kind) + python.environment().filterKeys { it == "LD_LIBRARY_PATH" }

    val pythonLauncher: File get() = python.launcher

    /**
     * sh 不能 execve 数据目录中的包装脚本，`python3`/`python`、`git`（dulwich）与 `pip` 以 shell 函数提供；
     * 函数只在本次命令的 shell 中有效，子 shell 与 Python 的 subprocess 找不到它们。
     */
    fun shellPrelude(): String = if (python.isAvailable) {
        "python3() { LD_LIBRARY_PATH=\"\$NEXARA_PYEXT_DIR\" \"\$NEXARA_PYTHON\" \"\$@\"; }; python() { python3 \"\$@\"; }; " +
            "git() { python3 -m nexara_git \"\$@\"; }; pip() { python3 -m nexara_pip \"\$@\"; }; pip3() { pip \"\$@\"; }"
    } else {
        listOf("python3", "git", "pip").joinToString("; ") { name ->
            "$name() { echo \"$name: $PYTHON_UNAVAILABLE_SHORT\" >&2; return 127; }"
        } + "; python() { python3 \"\$@\"; }; pip3() { pip \"\$@\"; }"
    }

    companion object {
        const val PYTHON_UNAVAILABLE_SHORT = "当前设备架构没有内置 Python（只随 arm64-v8a 发行），请改用 termux_exec 或 ssh_exec"
        private const val DEFAULT_PATH = "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:" +
            "/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
        private val INHERITED_KEYS = listOf(
            "PATH", "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
        )

        fun create(context: Context, pipEnabled: Boolean): BuiltinExecEnvironment {
            val bundle = SystemCaBundle(File(context.noBackupFilesDir, "exec-ca"))
            return BuiltinExecEnvironment(
                python = PackagedPython.from(context),
                homeDirectory = File(context.filesDir, "exec-home"),
                tempDirectory = File(context.cacheDir, "exec-tmp"),
                caBundle = bundle::ensure,
                userPackages = if (pipEnabled) {
                    PythonUserPackages(
                        userBase = File(context.filesDir, "python-user"),
                        pipDirectory = File(context.noBackupFilesDir, "python-pip"),
                        cacheDirectory = File(context.cacheDir, "pip"),
                    )
                } else null,
            )
        }
    }
}

/** pip 安装位置：[userBase] 为用户站点根目录，[pipDirectory] 存放从标准库取出的 pip wheel。 */
data class PythonUserPackages(val userBase: File, val pipDirectory: File, val cacheDirectory: File)

/**
 * Python ssl 无法读取 Android 以哈希命名的系统证书目录；把系统证书拼成 PEM bundle。
 * 证书目录的文件名、大小与修改时间变化（系统证书更新）时重建。
 */
class SystemCaBundle(
    private val directory: File,
    private val sources: List<File> = listOf(File("/apex/com.android.conscrypt/cacerts"), File("/system/etc/security/cacerts")),
) {
    @Synchronized
    fun ensure(): File? = runCatching {
        val source = sources.firstOrNull { it.isDirectory && !it.listFiles().isNullOrEmpty() } ?: return@runCatching null
        val certificates = source.listFiles { file -> file.isFile }.orEmpty().sortedBy { it.name }
        val fingerprint = Sha256Utils.hash(
            source.path + "\n" + certificates.joinToString("\n") { "${it.name}:${it.length()}:${it.lastModified()}" },
        ).take(16)
        val bundle = File(directory, "ca-bundle-$fingerprint.pem")
        if (bundle.isFile && bundle.length() > 0) return@runCatching bundle
        directory.mkdirs()
        val pem = buildString {
            certificates.forEach { file ->
                PEM_BLOCK.findAll(runCatching { file.readText() }.getOrDefault("")).forEach { append(it.value).append('\n') }
            }
        }
        if (pem.isEmpty()) return@runCatching null
        val temporary = File(directory, "ca-bundle-$fingerprint.tmp")
        temporary.writeText(pem)
        if (!temporary.renameTo(bundle)) {
            temporary.delete()
            return@runCatching null
        }
        directory.listFiles { file -> file.name.startsWith("ca-bundle-") && file != bundle }?.forEach { it.delete() }
        bundle
    }.getOrNull()

    private companion object {
        val PEM_BLOCK = Regex("-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----")
    }
}

object AndroidProcessGroupSignaller : ProcessGroupSignaller {
    override fun signal(processGroupId: Int, signal: Int) {
        try {
            Os.kill(-processGroupId, signal)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESRCH) throw error
        }
    }
}
