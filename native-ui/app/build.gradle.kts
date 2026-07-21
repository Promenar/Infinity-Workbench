import com.android.compose.screenshot.tasks.PreviewScreenshotUpdateTask
import com.android.compose.screenshot.tasks.PreviewScreenshotValidationTask

val realLlmEnvironmentNames = listOf(
    "NEXARA_TEST_LLM_BASE_URL",
    "NEXARA_TEST_LLM_API_KEY",
    "NEXARA_TEST_LLM_MULTIMODAL_MODEL",
    "NEXARA_TEST_LLM_FAST_TEXT_MODEL",
    "NEXARA_TEST_LLM_REASONING_MODEL",
    "NEXARA_TEST_LLM_BALANCED_MULTIMODAL_MODEL",
)
val releaseSigningEnvironment = mapOf(
    "NEXARA_KEYSTORE_PATH" to providers.environmentVariable("NEXARA_KEYSTORE_PATH").orNull?.trim(),
    "NEXARA_STORE_PASSWORD" to providers.environmentVariable("NEXARA_STORE_PASSWORD").orNull,
    "NEXARA_KEY_ALIAS" to providers.environmentVariable("NEXARA_KEY_ALIAS").orNull?.trim(),
    "NEXARA_KEY_PASSWORD" to providers.environmentVariable("NEXARA_KEY_PASSWORD").orNull,
)
val missingReleaseSigningEnvironment = releaseSigningEnvironment
    .filterValues { it.isNullOrBlank() }
    .keys
    .sorted()
val deviceE2eEnabled = providers.gradleProperty("nexara.deviceE2e")
    .map(String::toBoolean)
    .getOrElse(false)
val deviceE2eAbi = providers.gradleProperty("nexara.deviceE2eAbi")
    .getOrElse("x86_64")
    .trim()
val allowedE2eBuildTypes = setOf("debug", "deviceTest")
val selectedDeviceE2eBuildType = providers.gradleProperty("nexara.deviceE2eTestBuildType")
    .map(String::trim)
    .orElse(if (deviceE2eEnabled) "deviceTest" else "debug")
    .get()
require(deviceE2eAbi in setOf("x86_64", "arm64-v8a")) {
    "nexara.deviceE2eAbi 仅支持 x86_64 或 arm64-v8a，当前值：$deviceE2eAbi"
}
require(selectedDeviceE2eBuildType in allowedE2eBuildTypes) {
    "nexara.deviceE2eTestBuildType 仅支持 debug 或 deviceTest，当前值：$selectedDeviceE2eBuildType"
}
require(!deviceE2eEnabled || selectedDeviceE2eBuildType == "deviceTest") {
    "启用 nexara.deviceE2e 时，nexara.deviceE2eTestBuildType 必须为 deviceTest，当前值：$selectedDeviceE2eBuildType"
}
val finalReleaseArtifactTasks = setOf(
    "assemblerelease",
    "bundlerelease",
    "packagerelease",
    "packagereleasebundle",
    "packagereleaseuniversalapk",
    "signreleasebundle",
    "installrelease",
)

fun releaseArtifactTask(taskName: String): Boolean {
    val normalized = taskName.lowercase()
    return normalized in finalReleaseArtifactTasks || normalized.startsWith("publishrelease")
}

plugins {
    id("com.android.application")
    id("com.android.compose.screenshot")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.promenar.nexara"
    compileSdk = 36
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        applicationId = "com.promenar.nexara.native"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.2-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        androidResources {
            localeFilters += listOf("en", "zh-rCN")
        }

    }

    signingConfigs {
        create("release") {
            if (missingReleaseSigningEnvironment.isEmpty()) {
                storeFile = file(requireNotNull(releaseSigningEnvironment["NEXARA_KEYSTORE_PATH"]))
                storePassword = requireNotNull(releaseSigningEnvironment["NEXARA_STORE_PASSWORD"])
                keyAlias = requireNotNull(releaseSigningEnvironment["NEXARA_KEY_ALIAS"])
                keyPassword = requireNotNull(releaseSigningEnvironment["NEXARA_KEY_PASSWORD"])
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Nexara Native (Dev)")
            buildConfigField("boolean", "LOCAL_INFERENCE_AVAILABLE", "true")
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            externalNativeBuild {
                cmake {
                    arguments += "-DNEXARA_ENABLE_LOCAL_INFERENCE=ON"
                }
            }
        }
        create("deviceTest") {
            applicationIdSuffix = ".deviceTest"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Nexara Native (Device Test)")
            buildConfigField("boolean", "LOCAL_INFERENCE_AVAILABLE", "false")
            ndk {
                abiFilters += listOf(deviceE2eAbi)
            }
            externalNativeBuild {
                cmake {
                    arguments += "-DNEXARA_ENABLE_LOCAL_INFERENCE=OFF"
                }
            }
        }
        create("minifiedTest") {
            applicationIdSuffix = ".minifiedTest"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Nexara Native (Minified Test)")
            buildConfigField("boolean", "LOCAL_INFERENCE_AVAILABLE", "false")
            ndk {
                abiFilters += listOf(deviceE2eAbi)
            }
            externalNativeBuild {
                cmake {
                    arguments += "-DNEXARA_ENABLE_LOCAL_INFERENCE=OFF"
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            resValue("string", "app_name", "Nexara Native")
            buildConfigField("boolean", "LOCAL_INFERENCE_AVAILABLE", "false")
            externalNativeBuild {
                cmake {
                    arguments += "-DNEXARA_ENABLE_LOCAL_INFERENCE=OFF"
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testBuildType = selectedDeviceE2eBuildType

    sourceSets {
        getByName("deviceTest") {
            manifest.srcFile("src/debug/AndroidManifest.xml")
            java.directories.add("src/debug/java")
            kotlin.directories.add("src/debug/java")
            res.directories.add("src/debug/res")
        }
        getByName("androidTest") {
            assets.directories.add("$projectDir/schemas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // jniLibs 默认目录即 src/main/jniLibs，无需显式配置

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        screenshotTests {
            imageDifferenceThreshold = 0.0001f
        }
    }

}

tasks.withType<PreviewScreenshotValidationTask>().configureEach {
    systemProperty("user.timezone", "Asia/Shanghai")
}

tasks.withType<PreviewScreenshotUpdateTask>().configureEach {
    systemProperty("user.timezone", "Asia/Shanghai")
}

gradle.taskGraph.whenReady {
    val needsReleaseSigning = allTasks.any { task ->
        task.project.path == project.path && releaseArtifactTask(task.name)
    }
    if (needsReleaseSigning && missingReleaseSigningEnvironment.isNotEmpty()) {
        throw GradleException(
            "Release signing environment is incomplete. Missing: " +
                missingReleaseSigningEnvironment.joinToString(", ")
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ─── Jetpack Compose BOM ───
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha15")
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")

    // ─── AndroidX ───
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ─── OkHttp (SSE Client 临时依赖，后期迁移到 Ktor) ───
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // ─── 网络 (Multiplatform-Ready) ───
    implementation("io.ktor:ktor-client-android:2.3.7")
    implementation("io.ktor:ktor-client-okhttp:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // ─── 序列化 (Multiplatform-Ready) ───
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // ─── 协程 ───
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ─── 图片 (Multiplatform-Ready) ───
    implementation("io.coil-kt.coil3:coil:3.0.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.0")
    implementation("io.coil-kt.coil3:coil-video:3.0.0")

    // ─── 数据存储 (Multiplatform-Ready) ───
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ─── Room (SQLite ORM) ───
    val roomVersion = "2.7.0-alpha11"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    testImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")

    // ─── Markdown 渲染 ───
    val markdownRendererVersion = "0.41.0"
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:$markdownRendererVersion")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:$markdownRendererVersion")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:$markdownRendererVersion")

    // ─── 图片裁剪 (UCrop) ───
    implementation("com.github.yalantis:ucrop:2.2.8")

    // ─── HTML 解析 ───
    implementation("org.jsoup:jsoup:1.17.2")

    // ─── 文档解析 ───
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // ─── 测试 ───
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.junit.vintage:junit-vintage-engine:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.ktor:ktor-client-mock:2.3.7")
    testImplementation("com.google.truth:truth:1.2.0")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.google.truth:truth:1.2.0")
    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "deviceTestImplementation"(composeBom)
    "deviceTestImplementation"("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test> {
    useJUnitPlatform()
    workingDir = rootProject.projectDir
}

afterEvaluate {
    if (deviceE2eEnabled) return@afterEvaluate
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest")
    tasks.register<Test>("realLlmIntegrationTest") {
        group = "verification"
        description = "显式运行真实 LLM 集成测试；凭证仅从允许的进程环境变量读取。"
        val configured = realLlmEnvironmentNames.all {
            providers.environmentVariable(it).orNull?.isNotBlank() == true
        }
        testClassesDirs = debugUnitTest.get().testClassesDirs
        classpath = debugUnitTest.get().classpath
        filter {
            includeTestsMatching("*RealLlmProviderIntegrationTest")
            isFailOnNoMatchingTests = true
        }
        failOnNoDiscoveredTests.set(true)
        systemProperty("nexara.realLlmIntegration", "true")
        failFast = true
        // 显式真实网络门禁每次调用都必须重跑，禁止复用旧模型、旧凭据或旧服务状态的结果。
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        reports.html.required.set(false)
        reports.junitXml.required.set(false)
        testLogging {
            showExceptions = false
            showCauses = false
            showStackTraces = false
            showStandardStreams = false
        }
        if (configured) {
            dependsOn("compileDebugUnitTestSources", "processDebugUnitTestJavaRes")
        }
        onlyIf {
            if (!configured) logger.lifecycle("integration credentials unavailable")
            configured
        }
    }
}
