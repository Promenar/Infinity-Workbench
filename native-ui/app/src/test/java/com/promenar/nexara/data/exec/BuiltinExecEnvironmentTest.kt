package com.promenar.nexara.data.exec

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test

class BuiltinExecEnvironmentTest {
    private val work: File = Files.createTempDirectory("builtin-exec-env").toFile()
    private val pythonDir = File(work, "lib").apply {
        mkdirs()
        resolve(PackagedPython.LAUNCHER).writeText("")
        resolve(PackagedPython.STDLIB).writeText("")
        resolve(PackagedPython.SITE).writeText("")
    }

    @After fun tearDown() { work.deleteRecursively() }

    private fun environment(userPackages: PythonUserPackages?) = BuiltinExecEnvironment(
        python = PackagedPython(pythonDir),
        homeDirectory = File(work, "home"),
        tempDirectory = File(work, "tmp"),
        caBundle = { null },
        userPackages = userPackages,
        inherited = emptyMap(),
    )

    @Test fun `站点 zip 排在标准库之后进入 PYTHONPATH`() {
        val env = environment(null).forWorkspace(File(work, "ws"))
        assertThat(env["PYTHONPATH"]).isEqualTo("${pythonDir.resolve(PackagedPython.STDLIB).path}:${pythonDir.resolve(PackagedPython.SITE).path}")
        pythonDir.resolve(PackagedPython.SITE).delete()
        assertThat(environment(null).forWorkspace(File(work, "ws"))["PYTHONPATH"]).isEqualTo(pythonDir.resolve(PackagedPython.STDLIB).path)
    }

    @Test fun `未开放 pip 时保持禁用用户站点且不设置 pip 变量`() {
        val env = environment(null).forWorkspace(File(work, "ws"))
        assertThat(env["PYTHONNOUSERSITE"]).isEqualTo("1")
        assertThat(env.keys.filter { it.startsWith("PIP_") || it.startsWith("NEXARA_PIP") || it == "PYTHONUSERBASE" }).isEmpty()
    }

    @Test fun `开放 pip 时启用用户站点并指向应用目录`() {
        val packages = PythonUserPackages(File(work, "python-user"), File(work, "python-pip"), File(work, "pip-cache"))
        val env = environment(packages).forPython(File(work, "ws"))
        assertThat(env).doesNotContainKey("PYTHONNOUSERSITE")
        assertThat(env["PYTHONUSERBASE"]).isEqualTo(packages.userBase.path)
        assertThat(env["NEXARA_PIP"]).isEqualTo("1")
        assertThat(env["NEXARA_PIP_DIR"]).isEqualTo(packages.pipDirectory.path)
        assertThat(env["PIP_CACHE_DIR"]).isEqualTo(packages.cacheDirectory.path)
        assertThat(env["PIP_DISABLE_PIP_VERSION_CHECK"]).isEqualTo("1")
        assertThat(env["PIP_NO_INPUT"]).isEqualTo("1")
    }

    @Test fun `shell 前导以函数提供 python3、git 与 pip`() {
        val prelude = environment(null).shellPrelude()
        listOf("python3()", "python()", "git()", "pip()", "pip3()").forEach { assertThat(prelude).contains(it) }
        assertThat(prelude).contains("-m nexara_git")
        assertThat(prelude).contains("-m nexara_pip")
    }
}
