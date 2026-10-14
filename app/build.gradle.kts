import java.util.Properties

plugins {
    // AGP 9 provides built-in Kotlin support (KGP 2.2.10+), so the
    // org.jetbrains.kotlin.android plugin is intentionally not applied.
    alias(libs.plugins.android.application)
}

// Release signing key, kept out of git: a `keystore.properties` at the repo root
// (see CONTRIBUTING.md, "Publishing a release"). Without it, release builds are
// left unsigned so CI and fresh checkouts still build.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
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

    signingConfigs {
        keystoreProps?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Every published release must be signed with this same key, and
            // carry a higher versionCode, or devices refuse the in-app update.
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
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
        // With AGP 9's built-in Kotlin support, this alone also pins Kotlin's
        // own jvmTarget to 17 (no separate `kotlin { jvmToolchain(17) }` or
        // kotlinOptions.jvmTarget needed) - compilation still runs on
        // whichever JDK launched Gradle, it just emits 17-level bytecode.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
