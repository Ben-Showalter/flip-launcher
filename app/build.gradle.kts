plugins {
    // AGP 9 provides built-in Kotlin support (KGP 2.2.10+), so the
    // org.jetbrains.kotlin.android plugin is intentionally not applied.
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.flipos.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flipos.launcher"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    // Pins the compile JDK to 17. Gradle resolves it from a locally-installed
    // JDK it can auto-detect (JAVA_HOME, ~/.jdks, Android Studio's bundled
    // JBR, etc.) - see org.gradle.java.installations.auto-download=false in
    // gradle.properties, which keeps this from ever requiring network access.
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.google.material)
    implementation(libs.androidx.palette.ktx)

    testImplementation(libs.junit)
}
