package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.*
import org.junit.Assert.*
import org.junit.Test

class LinuxPackageParsingTest {
    @Test fun installedParsesMultipleRecordsAndIgnoresIncompleteRecords() {
        val packages = LinuxPackageParsing.installed("""
            P:busybox
            V:1.37.0-r20
            T:Size optimized toolbox
            I:824320

            P:git
            V:2.52.0-r0

            P:broken
            I:abc

            V:1.0-r0
        """.trimIndent(), setOf("git"))
        assertEquals(listOf("busybox", "git"), packages.map { it.name })
        assertEquals(824320L, packages[0].installedSizeBytes)
        assertFalse(packages[0].explicit)
        assertTrue(packages[1].explicit)
        assertNull(packages[1].description)
        assertNull(packages[1].installedSizeBytes)
        assertTrue(packages.all { it.installed })
    }

    @Test fun worldStripsRepositoryTagsAndVersionConstraints() {
        assertEquals(setOf("bash", "git", "python3", ".tools", "so:libc.musl-aarch64.so.1"),
            LinuxPackageParsing.world("bash>=5.2\ngit@edge\npython3~3.14\n.tools\nso:libc.musl-aarch64.so.1\n# comment\n"))
    }

    @Test fun searchKeepsHyphenatedNamesAndDescriptions() {
        // 样本按 apk-tools v3.0.1 的 app_search.c / query.c 输出格式构造，未从真机采集。
        val packages = LinuxPackageParsing.search("py3-pip-25.3-r0 - A tool for installing Python packages\nlibfoo2-1.2-r3 - Library\nWARNING: ignored\n")
        assertEquals(listOf("py3-pip", "libfoo2"), packages.map { it.name })
        assertEquals("25.3-r0", packages[0].version)
        assertEquals("A tool for installing Python packages", packages[0].description)
        assertNull(packages[0].installedSizeBytes)
    }

    @Test fun upgradesReadCandidateVersionFromList() {
        // 样本依据 apk-tools v3.0.1 的 list 输出格式，方括号内是当前版本。
        assertEquals(mapOf("bash" to "5.3.0-r0", "py3-pip" to "25.3-r0"), LinuxPackageParsing.upgrades(
            "bash-5.3.0-r0 aarch64 {bash} (GPL-3.0-or-later) [upgradable from: 5.2.37-r0]\n" +
                "py3-pip-25.3-r0 aarch64 {py3-pip} (MIT) [upgradable from: 25.2-r0]\n"))
    }

    @Test fun packageNamesRejectOptionsPathsConstraintsAndShellSyntax() {
        listOf("bash", "py3-pip", "g++", "7zip", ".tools", "libfoo_bar").forEach { assertTrue(it, LinuxPackageParsing.validName(it)) }
        listOf("", "--help", "-bash", "/tmp/a.apk", "a b", "a;id", "a\nb", "a>=1", "a@edge", "so:libc", "a\u0000b").forEach {
            assertFalse(it, LinuxPackageParsing.validName(it))
        }
    }

    @Test fun progressHandlesChunkBoundariesAndDoesNotMistakeInstalledSizeForDownload() {
        // 样本由 https://github.com/alpinelinux/apk-tools/blob/v3.0.1/src/commit.c
        // 的 print_change / Need to download 格式构造；未取得真实设备输出。
        val progress = LinuxPackageProgress()
        progress.append("( 1/")
        progress.append("12) Installing bash (5.3.0-r0)\n")
        assertEquals(1, progress.step)
        assertEquals(12, progress.totalSteps)
        progress.append("OK: 12 MiB in 20 packages\n")
        assertNull(progress.downloadedBytes)
        progress.append("Need to download 12 KiB of packages.\n")
        assertEquals(12288L, progress.totalBytes)
        // src/print.c 的 progress-fd 数字包含包数量权重，不能当作已下载字节。
        progress.append("1024/4096 download\n")
        assertNull(progress.downloadedBytes)
        progress.append("x".repeat(6000))
        assertTrue(progress.outputTail.toByteArray().size <= 4096)
    }

    @Test fun failureClassifiesStorageNetworkAndMissingPackages() {
        assertEquals(LinuxFailureCode.INSUFFICIENT_STORAGE, LinuxPackageParsing.failure("ERROR: No space left on device").code)
        assertEquals(LinuxFailureCode.NETWORK, LinuxPackageParsing.failure("WARNING: DNS: transient error (try again later)").code)
        assertEquals(LinuxFailureCode.NETWORK, LinuxPackageParsing.failure("TLS: handshake failed").code)
        val missing = "ERROR: unable to select packages: missing (no such package)"
        assertEquals(LinuxFailureCode.PACKAGE_INSTALL_FAILED, LinuxPackageParsing.failure(missing).code)
        assertEquals(missing, LinuxPackageParsing.failure(missing).detail)
        assertEquals(LinuxFailureCode.PACKAGE_INSTALL_FAILED, LinuxPackageParsing.failure("unsatisfiable constraints").code)
    }
}
