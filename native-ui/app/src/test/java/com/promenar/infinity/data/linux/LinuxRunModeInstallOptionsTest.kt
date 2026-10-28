package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LinuxRunModeInstallOptionsTest {
    @Test fun `默认全选和旧布尔选项均保留基础包`() {
        assertEquals(LinuxOptionalPackage.entries.toSet(), LinuxInstallOptions().optionalPackages)
        assertEquals(LinuxPackagePresets.COMMON, LinuxInstallOptions(true).packages)
        assertEquals(LinuxPackagePresets.BASE, LinuxInstallOptions(false).packages)
    }

    @Test fun `可选python包含pip且显式选择覆盖兼容布尔值`() {
        val options = LinuxInstallOptions(installCommonTools = false, optionalPackages = setOf(LinuxOptionalPackage.PYTHON3))
        assertEquals(LinuxPackagePresets.BASE + listOf("python3", "py3-pip"), options.packages)
        assertFalse("git" in options.packages)
    }

    @Test fun `补装附加包沿用包服务且空选择不执行apk`() = runTest {
        val calls = mutableListOf<List<String>>()
        val manager = object : LinuxPackageManager {
            override val operation = MutableStateFlow<LinuxPackageOperation?>(null)
            override suspend fun installed() = emptyList<LinuxPackage>()
            override suspend fun search(query: String, limit: Int) = emptyList<LinuxPackage>()
            override suspend fun refreshIndex() = error("未使用")
            override suspend fun remove(names: List<String>) = error("未使用")
            override suspend fun upgrade(names: List<String>) = error("未使用")
            override suspend fun install(names: List<String>, origin: LinuxPackageOrigin): LinuxPackageResult {
                calls += names
                assertEquals(LinuxPackageOrigin.Preset, origin)
                return LinuxPackageResult(true, 0, "")
            }
        }
        assertTrue(manager.installOptionalPackages(emptySet()).success)
        assertTrue(calls.isEmpty())
        assertTrue(manager.installOptionalPackages(setOf(LinuxOptionalPackage.PYTHON3)).success)
        assertEquals(listOf(listOf("python3", "py3-pip")), calls)
    }
}
