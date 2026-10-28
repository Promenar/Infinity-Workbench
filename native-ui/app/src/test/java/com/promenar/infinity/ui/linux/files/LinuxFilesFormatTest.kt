package com.promenar.infinity.ui.linux.files

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.linux.LinuxFileEntry
import com.promenar.infinity.domain.linux.LinuxFileErrorCode
import com.promenar.infinity.domain.linux.LinuxFileException
import com.promenar.infinity.domain.linux.LinuxFileType
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxUnavailableException
import com.promenar.infinity.domain.linux.LinuxUnavailableReason
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class LinuxFilesFormatTest {
    private val utc = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun `permission string covers types and special bits`() {
        assertThat(formatLinuxPermissions(LinuxFileType.DIRECTORY, 0b111_101_101)).isEqualTo("drwxr-xr-x")
        assertThat(formatLinuxPermissions(LinuxFileType.FILE, 0b110_100_100)).isEqualTo("-rw-r--r--")
        assertThat(formatLinuxPermissions(LinuxFileType.SYMLINK, 0b111_111_111)).isEqualTo("lrwxrwxrwx")
        assertThat(formatLinuxPermissions(LinuxFileType.FILE, "4755".toInt(8))).isEqualTo("-rwsr-xr-x")
        assertThat(formatLinuxPermissions(LinuxFileType.FILE, "2644".toInt(8))).isEqualTo("-rw-r-Sr--")
        assertThat(formatLinuxPermissions(LinuxFileType.DIRECTORY, "1777".toInt(8))).isEqualTo("drwxrwxrwt")
        assertThat(formatLinuxPermissions(LinuxFileType.DIRECTORY, "1776".toInt(8))).isEqualTo("drwxrwxrwT")
        assertThat(formatLinuxPermissions(LinuxFileType.OTHER, 0)).isEqualTo("?---------")
    }

    @Test
    fun `octal mode shows four digits only with special bits`() {
        assertThat(formatLinuxOctalMode("755".toInt(8))).isEqualTo("755")
        assertThat(formatLinuxOctalMode("644".toInt(8))).isEqualTo("644")
        assertThat(formatLinuxOctalMode(0)).isEqualTo("000")
        assertThat(formatLinuxOctalMode("4755".toInt(8))).isEqualTo("4755")
        // 高于 12 位的类型位被忽略。
        assertThat(formatLinuxOctalMode("100644".toInt(8))).isEqualTo("644")
    }

    @Test
    fun `toggling permission bits keeps the rest`() {
        val mode = "4644".toInt(8)
        assertThat(toggleLinuxPermission(mode, group = 0, permission = 2)).isEqualTo("4744".toInt(8))
        assertThat(toggleLinuxPermission(mode, group = 2, permission = 0)).isEqualTo("4640".toInt(8))
        assertThat(toggleLinuxPermission(toggleLinuxPermission(mode, 1, 1), 1, 1)).isEqualTo(mode)
    }

    @Test
    fun `executable means a regular file with any execute bit`() {
        assertThat(entry("/root/run.sh", mode = "744".toInt(8)).isExecutableFile()).isTrue()
        assertThat(entry("/root/a.txt", mode = "644".toInt(8)).isExecutableFile()).isFalse()
        assertThat(entry("/root/bin", type = LinuxFileType.DIRECTORY, mode = "755".toInt(8)).isExecutableFile()).isFalse()
    }

    @Test
    fun `sizes use binary units with one decimal below one hundred`() {
        assertThat(formatLinuxSize(0)).isEqualTo("0 B")
        assertThat(formatLinuxSize(214)).isEqualTo("214 B")
        assertThat(formatLinuxSize(1024)).isEqualTo("1 KB")
        assertThat(formatLinuxSize(1229)).isEqualTo("1.2 KB")
        assertThat(formatLinuxSize(150L * 1024)).isEqualTo("150 KB")
        assertThat(formatLinuxSize(412L * 1024 * 1024)).isEqualTo("412 MB")
        assertThat(formatLinuxSize(3L * 1024 * 1024 * 1024 / 2)).isEqualTo("1.5 GB")
    }

    @Test
    fun `relative time steps from minutes to dates`() {
        assertThat(linuxRelativeTime(now - 30_000, now, utc)).isEqualTo(LinuxRelativeTime.JustNow)
        assertThat(linuxRelativeTime(now + 60_000, now, utc)).isEqualTo(LinuxRelativeTime.JustNow)
        assertThat(linuxRelativeTime(now - 3 * 60_000, now, utc)).isEqualTo(LinuxRelativeTime.MinutesAgo(3))
        assertThat(linuxRelativeTime(at(2026, 10, 25, 7), now, utc)).isEqualTo(LinuxRelativeTime.HoursAgo(5))
        assertThat(linuxRelativeTime(at(2026, 10, 24, 20), now, utc)).isEqualTo(LinuxRelativeTime.Yesterday)
        assertThat(linuxRelativeTime(at(2026, 10, 21, 9), now, utc)).isEqualTo(LinuxRelativeTime.DaysAgo(4))
        assertThat(linuxRelativeTime(at(2026, 3, 2, 9), now, utc)).isEqualTo(LinuxRelativeTime.Date("03-02"))
        assertThat(linuxRelativeTime(at(2025, 12, 31, 9), now, utc)).isEqualTo(LinuxRelativeTime.Date("2025-12-31"))
    }

    @Test
    fun `relative time just after midnight stays in minutes`() {
        val justAfterMidnight = at(2026, 10, 25, 0, 10)
        assertThat(linuxRelativeTime(at(2026, 10, 24, 23, 30), justAfterMidnight, utc)).isEqualTo(LinuxRelativeTime.MinutesAgo(40))
        assertThat(linuxRelativeTime(at(2026, 10, 24, 22, 0), justAfterMidnight, utc)).isEqualTo(LinuxRelativeTime.Yesterday)
    }

    @Test
    fun `timestamp marks today and yesterday`() {
        assertThat(linuxTimestamp(at(2026, 10, 25, 9, 58), now, utc)).isEqualTo(LinuxTimestamp(LinuxTimestampDay.TODAY, "2026-10-25", "09:58"))
        assertThat(linuxTimestamp(at(2026, 10, 24, 22, 5), now, utc).day).isEqualTo(LinuxTimestampDay.YESTERDAY)
        assertThat(linuxTimestamp(at(2026, 1, 3, 8, 0), now, utc)).isEqualTo(LinuxTimestamp(LinuxTimestampDay.OTHER, "2026-01-03", "08:00"))
    }

    @Test
    fun `name sort puts folders and folder links first`() {
        val entries = listOf(
            entry("/root/b.txt"),
            entry("/root/Zeta", type = LinuxFileType.DIRECTORY),
            entry("/root/alpha", type = LinuxFileType.DIRECTORY),
            entry("/root/data", type = LinuxFileType.SYMLINK, targetIsDirectory = true),
            entry("/root/A.txt"),
        )
        assertThat(sortLinuxEntries(entries, LinuxFileSort.NAME, showHidden = true).map { it.name })
            .containsExactly("alpha", "data", "Zeta", "A.txt", "b.txt").inOrder()
    }

    @Test
    fun `modified sort is newest first regardless of type`() {
        val entries = listOf(
            entry("/root/old.txt", modifiedMs = 1),
            entry("/root/dir", type = LinuxFileType.DIRECTORY, modifiedMs = 2),
            entry("/root/new.txt", modifiedMs = 3),
        )
        assertThat(sortLinuxEntries(entries, LinuxFileSort.MODIFIED, true).map { it.name })
            .containsExactly("new.txt", "dir", "old.txt").inOrder()
    }

    @Test
    fun `size sort keeps folders first then largest files`() {
        val entries = listOf(
            entry("/root/small", size = 10),
            entry("/root/big", size = 1_000),
            entry("/root/dir", type = LinuxFileType.DIRECTORY, size = 4096),
        )
        assertThat(sortLinuxEntries(entries, LinuxFileSort.SIZE, true).map { it.name })
            .containsExactly("dir", "big", "small").inOrder()
    }

    @Test
    fun `hidden entries are filtered when not shown`() {
        val entries = listOf(entry("/root/.venv", type = LinuxFileType.DIRECTORY), entry("/root/run.sh"))
        assertThat(sortLinuxEntries(entries, LinuxFileSort.NAME, showHidden = false).map { it.name }).containsExactly("run.sh")
    }

    @Test
    fun `paths normalize and resolve link targets`() {
        assertThat(normalizeLinuxPath("//root/./projects/../x/")).isEqualTo("/root/x")
        assertThat(normalizeLinuxPath("/..")).isEqualTo("/")
        assertThat(linuxParentPath("/root/projects")).isEqualTo("/root")
        assertThat(linuxParentPath("/root")).isEqualTo("/")
        assertThat(linuxParentPath("/")).isNull()
        assertThat(linuxChildPath("/", "etc")).isEqualTo("/etc")
        assertThat(resolveLinuxLinkTarget("/root/projects/data", "../shared")).isEqualTo("/root/shared")
        assertThat(resolveLinuxLinkTarget("/root/projects/data", "/workspace/data")).isEqualTo("/workspace/data")
        assertThat(resolveLinuxLinkTarget("/root/link", "sub/dir")).isEqualTo("/root/sub/dir")
    }

    @Test
    fun `runtime bindings are workspace and mnt subpaths`() {
        assertThat(isLinuxRuntimeBinding("/workspace")).isTrue()
        assertThat(isLinuxRuntimeBinding("/workspace/data")).isTrue()
        assertThat(isLinuxRuntimeBinding("/mnt/sdcard")).isTrue()
        assertThat(isLinuxRuntimeBinding("/workspaces")).isFalse()
        assertThat(isLinuxRuntimeBinding("/mnt")).isFalse()
        assertThat(isLinuxRuntimeBinding("/root/workspace")).isFalse()
    }

    @Test
    fun `path segments start at root`() {
        assertThat(linuxPathSegments("/root/projects")).containsExactly(
            LinuxPathSegment("/", "/"),
            LinuxPathSegment("root", "/root"),
            LinuxPathSegment("projects", "/root/projects"),
        ).inOrder()
        assertThat(linuxPathSegments("/")).containsExactly(LinuxPathSegment("/", "/"))
    }

    @Test
    fun `crumbs are relative to home inside it and to root elsewhere`() {
        assertThat(linuxCrumbs("/root")).isEqualTo(LinuxCrumbs(underHome = true, trail = emptyList()))
        assertThat(linuxCrumbs("/root/projects/cli")).isEqualTo(
            LinuxCrumbs(
                underHome = true,
                trail = listOf(LinuxPathSegment("projects", "/root/projects"), LinuxPathSegment("cli", "/root/projects/cli")),
            ),
        )
        assertThat(linuxCrumbs("/")).isEqualTo(LinuxCrumbs(underHome = false, trail = emptyList()))
        assertThat(linuxCrumbs("/etc/apk")).isEqualTo(
            LinuxCrumbs(underHome = false, trail = listOf(LinuxPathSegment("etc", "/etc"), LinuxPathSegment("apk", "/etc/apk"))),
        )
        // 名字以 root 开头的同级目录不算主目录。
        assertThat(linuxCrumbs("/rootfs").underHome).isFalse()
    }

    @Test
    fun `name filter ignores case and keeps everything for a blank query`() {
        val entries = listOf(entry("/root/Notes.md"), entry("/root/install.sh"), entry("/root/notebook", type = LinuxFileType.DIRECTORY))
        assertThat(filterLinuxEntriesByName(entries, "note").map { it.name }).containsExactly("Notes.md", "notebook").inOrder()
        assertThat(filterLinuxEntriesByName(entries, "  ")).isEqualTo(entries)
        assertThat(filterLinuxEntriesByName(entries, " SH ").map { it.name }).containsExactly("install.sh")
    }

    @Test
    fun `shell quoting leaves safe names and escapes the rest`() {
        assertThat(shellQuote("run.sh")).isEqualTo("run.sh")
        assertThat(shellQuote("my file.sh")).isEqualTo("'my file.sh'")
        assertThat(shellQuote("it's")).isEqualTo("'it'\\''s'")
        assertThat(shellQuote("")).isEqualTo("''")
    }

    @Test
    fun `file kind uses binary flag, extension, then shebang`() {
        assertThat(linuxFileKind("run.sh", false, "#!/bin/bash")).isEqualTo(LinuxFileKind.SHELL)
        assertThat(linuxFileKind("tool", false, "#!/usr/bin/env python3")).isEqualTo(LinuxFileKind.PYTHON)
        assertThat(linuxFileKind("tool", false, "#!/bin/sh -e")).isEqualTo(LinuxFileKind.SHELL)
        assertThat(linuxFileKind("notes", false, "hello")).isEqualTo(LinuxFileKind.TEXT)
        assertThat(linuxFileKind("model.bin", true, null)).isEqualTo(LinuxFileKind.BINARY)
        assertThat(linuxFileKind("out.tar.gz", true, null)).isEqualTo(LinuxFileKind.ARCHIVE)
        assertThat(linuxFileKind("README.md", false, "# Title")).isEqualTo(LinuxFileKind.MARKDOWN)
        assertThat(linuxFileKind(".bashrc", false, "alias ll='ls -l'")).isEqualTo(LinuxFileKind.SHELL)
        // 扩展名说是二进制但内容是文本时按 shebang 或文本处理。
        assertThat(linuxFileKind("data.bin", false, "plain")).isEqualTo(LinuxFileKind.TEXT)
        assertThat(linuxLooksBinaryByName("weights.gguf")).isTrue()
        assertThat(linuxLooksBinaryByName("main.py")).isFalse()
    }

    @Test
    fun `hex rows pad short lines and mask control bytes`() {
        val rows = linuxHexRows("#!/bin/sh\n".toByteArray(), startOffset = 4096, bytesPerRow = 8)
        assertThat(rows).hasSize(2)
        assertThat(rows[0]).isEqualTo(LinuxHexRow("00001000", "23 21 2f 62 69 6e 2f 73", "#!/bin/s"))
        assertThat(rows[1].offset).isEqualTo("00001008")
        assertThat(rows[1].hex).isEqualTo("68 0a".padEnd(23))
        assertThat(rows[1].ascii).isEqualTo("h.")
    }

    @Test
    fun `hex row width adapts to available characters`() {
        assertThat(linuxHexBytesPerRow(80)).isEqualTo(16)
        assertThat(linuxHexBytesPerRow(75)).isEqualTo(16)
        assertThat(linuxHexBytesPerRow(74)).isEqualTo(8)
        assertThat(linuxHexBytesPerRow(43)).isEqualTo(8)
        assertThat(linuxHexBytesPerRow(42)).isEqualTo(4)
        assertThat(linuxHexBytesPerRow(10)).isEqualTo(4)
    }

    @Test
    fun `archive names default to the item or folder and keep the suffix once`() {
        assertThat(defaultLinuxArchiveName(listOf("/root/projects/scraper"), "/root/projects")).isEqualTo("scraper.tar.gz")
        assertThat(defaultLinuxArchiveName(listOf("/root/projects/a", "/root/projects/b"), "/root/projects")).isEqualTo("projects.tar.gz")
        assertThat(defaultLinuxArchiveName(listOf("/a", "/b"), "/")).isEqualTo("archive.tar.gz")
        assertThat(ensureTarGzSuffix(" backup ")).isEqualTo("backup.tar.gz")
        assertThat(ensureTarGzSuffix("backup.tar.gz")).isEqualTo("backup.tar.gz")
        assertThat(ensureTarGzSuffix("backup.tgz")).isEqualTo("backup.tgz")
    }

    @Test
    fun `every error code has its own message`() {
        val messages = LinuxFileErrorCode.entries.map(::linuxFileErrorText)
        assertThat(messages.toSet()).hasSize(LinuxFileErrorCode.entries.size)
    }

    @Test
    fun `exceptions map to error codes`() {
        assertThat(LinuxFileException(LinuxFileErrorCode.NAME_EXISTS).toLinuxFileErrorCode()).isEqualTo(LinuxFileErrorCode.NAME_EXISTS)
        val unavailable = LinuxUnavailableException(LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION))
        assertThat(unavailable.toLinuxFileErrorCode()).isEqualTo(LinuxFileErrorCode.NOT_READY)
        assertThat(IllegalStateException("boom").toLinuxFileErrorCode()).isEqualTo(LinuxFileErrorCode.IO)
    }

    @Test
    fun `truncated text drops a split multibyte character`() {
        val bytes = "中文".toByteArray(Charsets.UTF_8).copyOf(4)
        assertThat(decodeLinuxText(bytes, "UTF-8", truncated = true)).isEqualTo("中")
        assertThat(decodeLinuxText("abc".toByteArray(), "no-such-charset", truncated = false)).isEqualTo("abc")
    }

    @Test
    fun `line count counts an unterminated last line`() {
        assertThat(countLines("")).isEqualTo(0)
        assertThat(countLines("a")).isEqualTo(1)
        assertThat(countLines("a\n")).isEqualTo(1)
        assertThat(countLines("a\nb")).isEqualTo(2)
    }

    private fun entry(
        path: String,
        type: LinuxFileType = LinuxFileType.FILE,
        size: Long = 0,
        modifiedMs: Long = 0,
        mode: Int = "644".toInt(8),
        targetIsDirectory: Boolean = false,
    ) = LinuxFileEntry(
        path = path,
        name = path.substringAfterLast('/'),
        type = type,
        size = size,
        modifiedMs = modifiedMs,
        mode = mode,
        owner = "root",
        group = "root",
        symlinkTarget = if (type == LinuxFileType.SYMLINK) "/target" else null,
        targetIsDirectory = targetIsDirectory,
    )
}
