package com.promenar.nexara.release

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ReleaseConfigurationTest {
    private val gradle = source("app/build.gradle.kts")
    private val cmake = source("app/src/main/cpp/CMakeLists.txt")

    @Test
    fun `v0_2_1 beta 发行元数据与本地推理能力按构建类型隔离`() {
        assertThat(gradle).contains("versionCode = 3")
        assertThat(gradle).contains("versionName = \"0.2.1-beta\"")
        assertThat(buildTypeBody("debug"))
            .contains("buildConfigField(\"boolean\", \"LOCAL_INFERENCE_AVAILABLE\", \"true\")")
        assertThat(buildTypeBody("release"))
            .contains("buildConfigField(\"boolean\", \"LOCAL_INFERENCE_AVAILABLE\", \"false\")")
    }

    @Test
    fun `发行签名只读取四个环境变量且只在制品任务缺失时失败`() {
        listOf(
            "NEXARA_KEYSTORE_PATH",
            "NEXARA_STORE_PASSWORD",
            "NEXARA_KEY_ALIAS",
            "NEXARA_KEY_PASSWORD",
        ).forEach { name -> assertThat(gradle).contains("environmentVariable(\"$name\")") }

        assertThat(gradle).doesNotContain("secure.properties")
        assertThat(gradle).doesNotContain("FileInputStream")
        assertThat(gradle).doesNotContain("java.util.Properties")
        assertThat(gradle).doesNotContain("/Users/")
        assertThat(gradle).doesNotContain("K:/")
        assertThat(gradle).contains("gradle.taskGraph.whenReady")
        assertThat(gradle).contains("missingReleaseSigningEnvironment")
        assertThat(gradle).contains("releaseArtifactTask")
        val classifier = functionBody("fun releaseArtifactTask")
        assertThat(classifier).contains("finalReleaseArtifactTasks")
        assertThat(classifier).doesNotContain("any(normalized::startsWith)")
    }

    @Test
    fun `release 关闭 native 且 CMake 在拉取 llama 前短路`() {
        assertThat(buildTypeBody("debug"))
            .contains("-DNEXARA_ENABLE_LOCAL_INFERENCE=ON")
        assertThat(buildTypeBody("release"))
            .contains("-DNEXARA_ENABLE_LOCAL_INFERENCE=OFF")

        val option = cmake.indexOf("option(NEXARA_ENABLE_LOCAL_INFERENCE")
        val earlyReturn = cmake.indexOf("return()")
        val fetchContent = cmake.indexOf("FetchContent_Declare")
        val mutableMasterTag = cmake.indexOf("GIT_TAG        master")
        val nativeLibrary = cmake.indexOf("add_library(nexara_llama")
        assertThat(option).isAtLeast(0)
        assertThat(earlyReturn).isGreaterThan(option)
        assertThat(fetchContent).isGreaterThan(earlyReturn)
        assertThat(mutableMasterTag).isGreaterThan(earlyReturn)
        assertThat(nativeLibrary).isGreaterThan(earlyReturn)
        val disabledBranch = cmake.substring(option, earlyReturn)
        listOf(
            "CMAKE_EXPORT_COMPILE_COMMANDS",
            "CMAKE_LIBRARY_OUTPUT_DIRECTORY",
            "CMAKE_RUNTIME_OUTPUT_DIRECTORY",
        ).forEach { injectedVariable ->
            assertThat(disabledBranch).contains(injectedVariable)
        }
    }

    @Test
    fun `arm64 限制只存在于启用 native 的 debug 构建`() {
        assertThat(defaultConfigBody()).doesNotContain("abiFilters")
        assertThat(buildTypeBody("debug")).contains("abiFilters")
        assertThat(buildTypeBody("debug")).contains("arm64-v8a")
        assertThat(buildTypeBody("release")).doesNotContain("abiFilters")
    }

    private fun defaultConfigBody(): String = blockBody("defaultConfig")

    private fun buildTypeBody(name: String): String {
        val buildTypes = blockBody("buildTypes")
        return blockBody(name, buildTypes)
    }

    private fun blockBody(name: String, text: String = gradle): String {
        val marker = Regex("\\b${Regex.escape(name)}\\s*\\{").find(text)
            ?: error("未找到配置块: $name")
        val open = text.indexOf('{', marker.range.first)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, index)
                }
            }
        }
        error("配置块未闭合: $name")
    }

    private fun functionBody(signature: String): String {
        val start = gradle.indexOf(signature).takeIf { it >= 0 }
            ?: error("未找到函数: $signature")
        val open = gradle.indexOf('{', start).takeIf { it >= 0 }
            ?: error("函数无主体: $signature")
        var depth = 0
        for (index in open until gradle.length) {
            when (gradle[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return gradle.substring(open + 1, index)
                }
            }
        }
        error("函数未闭合: $signature")
    }

    private fun source(relative: String): String =
        Files.readAllBytes(Path.of(relative)).toString(Charsets.UTF_8)
}
