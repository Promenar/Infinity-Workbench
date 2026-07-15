package com.promenar.nexara

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class ReleaseLoggingSourceContractTest {
    private val projectRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/promenar/nexara").isDirectory }
    private val sourceRoot = File(projectRoot, "app/src/main/java/com/promenar/nexara")

    @Test
    fun `release 可达源码只允许统一日志器直接访问平台日志`() {
        // 唯一白名单是平台日志适配边界；其公开输出入口由下一项测试锁定 DEBUG 门禁。
        val violations = sourceRoot.walkTopDown()
            .filter {
                it.isFile &&
                    it.extension in setOf("kt", "java") &&
                    it.relativeTo(sourceRoot).invariantSeparatorsPath != "utils/NexaraLogger.kt"
            }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    val directLog = Regex("(?:android\\.util\\.)?Log\\.[vdiewtf]\\s*\\(").containsMatchIn(line)
                    val directPrint = line.contains("printStackTrace(") ||
                        line.contains("System.err.print") ||
                        line.contains("System.out.print") ||
                        Regex("(?<![A-Za-z])println\\s*\\(").containsMatchIn(line)
                    if (directLog || directPrint) "${file.relativeTo(sourceRoot)}:${index + 1}" else null
                }
            }
            .toList()

        assertThat(violations).isEmpty()
    }

    @Test
    fun `统一日志器全部输出入口均由 DEBUG 编译期门禁保护`() {
        val logger = File(sourceRoot, "utils/NexaraLogger.kt").readText()

        assertThat(logger).contains("fun log(message: String) {\n        if (!com.promenar.nexara.BuildConfig.DEBUG) return")
        assertThat(logger).contains("fun logError(tag: String, throwable: Throwable) {\n        if (!com.promenar.nexara.BuildConfig.DEBUG) return")
        assertThat(logger).contains("fun metro(event: String, payload: String) {\n        if (!com.promenar.nexara.BuildConfig.DEBUG) return")
    }

    @Test
    fun `R8 使用 Kotlin object 的实例方法签名并精确剥离平台输出`() {
        val rules = File(projectRoot, "app/proguard-rules.pro").readText()

        assertThat(rules).contains("public void log(java.lang.String);")
        assertThat(rules).contains("public void logError(java.lang.String, java.lang.Throwable);")
        assertThat(rules).contains("public void metro(java.lang.String, java.lang.String);")
        assertThat(rules).doesNotContain("public static void log(java.lang.String);")
        assertThat(rules).contains("public static int d(java.lang.String, java.lang.String);")
        assertThat(rules).contains("public static int e(java.lang.String, java.lang.String);")
        assertThat(rules).contains("public static int e(java.lang.String, java.lang.String, java.lang.Throwable);")
        assertThat(rules).contains("public void printStackTrace();")
    }
}
