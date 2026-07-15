plugins {
    id("com.android.application")
}

android {
    namespace = "com.promenar.nexara.blackboxfixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.promenar.nexara.blackboxfixture"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
