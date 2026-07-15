plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.promenar.nexara.mainactivitye2e"
    compileSdk = 36
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "com.promenar.nexara.MainActivityE2eRunner"
    }

    buildTypes {
        create("deviceTest") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        if (variantBuilder.buildType != "deviceTest") {
            variantBuilder.enable = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui-test-junit4")
    implementation("androidx.test:core:1.7.0")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.espresso:espresso-core:3.7.0")
    implementation("com.google.truth:truth:1.2.0")
    implementation("io.coil-kt.coil3:coil:3.0.0")
}
