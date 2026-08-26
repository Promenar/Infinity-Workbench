package com.promenar.nexara.ui.theme

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

class ThemeSurfaceContractTest {
    private fun repositoryRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(userDir) { it.parentFile }
            .first { it.resolve("native-ui/app/src/main/java/com/promenar/nexara/ui").isDirectory }
    }

    private val testResourcesPath = "native-ui/app/src/test/resources"
    private val uiSourcePath = "native-ui/app/src/main/java/com/promenar/nexara/ui"
    private val manifestPath = ".agent/plans/20260720-md3-convergence-task13-manifest.txt"
    private val themeFileWhitelist = setOf(
        "native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Color.kt",
        "native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Theme.kt",
        "native-ui/app/src/main/java/com/promenar/nexara/ui/theme/SeededColorScheme.kt",
    )

    private fun manifestFilePaths(): Set<String> {
        val manifest = repositoryRoot().resolve(manifestPath)
            .readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        return manifest
    }

    private fun scanPatterns(): List<Regex> {
        return repositoryRoot().resolve(testResourcesPath).resolve("md3-theme-surface-patterns.txt")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { Regex(it) }
    }

    private data class LineMatch(val line: Int, val text: String, val matches: List<String>)

    private fun matchingLines(file: File, patterns: List<Regex>): List<LineMatch> {
        return file.readLines().flatMapIndexed { index, line ->
            val hits = patterns.flatMap { pattern ->
                pattern.findAll(line).map { it.value }
            }
            if (hits.isEmpty()) emptyList() else listOf(LineMatch(index + 1, line, hits))
        }
    }

    private fun relativePath(file: File): String =
        file.toPath().toAbsolutePath().normalize().toString()
            .replace(File.separatorChar, '/')
            .removePrefix("${repositoryRoot().absolutePath.replace(File.separatorChar, '/')}/")

    @Test
    fun `全树静态 theme surface 形态只允许在主题 token 文件声明`() {
        val patterns = scanPatterns()
        val uiDir = repositoryRoot().resolve(uiSourcePath)

        val violations = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { relativePath(it) !in themeFileWhitelist }
            .mapNotNull { file ->
                val hits = matchingLines(file, patterns)
                if (hits.isEmpty()) {
                    null
                } else {
                    val path = relativePath(file)
                    val messageLines = hits.joinToString("\n") { match ->
                        val matchedText = match.matches.joinToString(", ")
                        "  - $path: line ${match.line} | ${match.text.trim()} | matched: $matchedText"
                    }
                    "[$path]\n$messageLines"
                }
            }.toList()

        assertWithMessage(
            buildString {
                appendLine("以下非主题文件仍命中静态主题 token，需要改造为 MaterialTheme.colorScheme：")
                append(violations.joinToString("\n"))
                if (violations.isNotEmpty()) {
                    appendLine()
                }
            },
        ).that(violations).isEmpty()
    }

    @Test
    fun `全树静态 theme surface 命中不得绕过冻结 manifest`() {
        val patterns = scanPatterns()
        val uiDir = repositoryRoot().resolve(uiSourcePath)
        val manifest = manifestFilePaths()
        val matchingFiles = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                if (matchingLines(file, patterns).isNotEmpty()) relativePath(file) else null
            }
            .toSet()

        val unregistered = matchingFiles - manifest
        assertWithMessage(
            buildString {
                appendLine("检测到以下匹配静态主题 token 的文件未纳入冻结 manifest：")
                append(unregistered.sorted().joinToString("\n") { "  - $it" })
                if (unregistered.isNotEmpty()) appendLine()
            },
        ).that(unregistered).isEmpty()
    }
}
