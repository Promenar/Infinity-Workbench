package com.promenar.nexara.data.exec

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test

class PackagedPythonTest {
    @Test fun `缺少启动器或标准库时报告不可用`() {
        val dir = Files.createTempDirectory("packaged-python").toFile()
        try {
            assertThat(PackagedPython(dir).isAvailable).isFalse()
            dir.resolve(PackagedPython.LAUNCHER).writeText("")
            assertThat(PackagedPython(dir).isAvailable).isFalse()
            dir.resolve(PackagedPython.STDLIB).writeText("")
            assertThat(PackagedPython(dir).isAvailable).isTrue()
        } finally { dir.deleteRecursively() }
    }

    @Test fun `环境变量全部指向打包库目录且不使用用户站点与字节码缓存`() {
        val dir = Files.createTempDirectory("packaged-python").toFile()
        try {
            val env = PackagedPython(dir).environment()
            assertThat(env["PYTHONPATH"]).isEqualTo(dir.resolve(PackagedPython.STDLIB).path)
            assertThat(listOf("PYTHONHOME", "NEXARA_PYEXT_DIR", "LD_LIBRARY_PATH").map(env::get)).containsExactly(dir.path, dir.path, dir.path)
            assertThat(env["PYTHONNOUSERSITE"]).isEqualTo("1")
            assertThat(env["PYTHONDONTWRITEBYTECODE"]).isEqualTo("1")
        } finally { dir.deleteRecursively() }
    }
}
