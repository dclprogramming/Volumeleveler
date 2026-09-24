plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.volumeleveler"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.volumeleveler"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "VolumeLeveler-${variant.versionName}-${variant.name}.apk"
        }
    }
}
