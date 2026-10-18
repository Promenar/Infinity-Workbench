plugins { id("com.android.application") }

android {
    namespace = "com.promenar.nexara.execprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.promenar.nexara.execprobe"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "poc-1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // 关键假设：legacy packaging 让系统把 lib*.so 解压到 nativeLibraryDir，才能 execve。
    packaging { jniLibs.useLegacyPackaging = true }
    // AAPT 默认忽略以下划线开头的目录（<dir>_*），会漏掉 zipfile/_path 等标准库包。
    androidResources { ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
