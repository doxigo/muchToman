import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    // AGP 9 brings its own Kotlin support; applying kotlin-android on top is an error now.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.doxigo.muchtoman"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.doxigo.muchtoman"
        minSdk = 24
        targetSdk = 37
        // CI passes these from the git tag; local builds don't care.
        versionCode = providers.gradleProperty("muchtoman.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("muchtoman.versionName").orNull ?: "1.0"
    }

    // Release signing. Create keystore.properties yourself (see README) — it is gitignored
    // and never read by anything but this block. Without it the release build is simply
    // left unsigned rather than silently falling back to the debug key.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let {
        Properties().apply { it.inputStream().use(::load) }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // Where the app gets its prices. Override either build with:
    //   ./gradlew installDebug -Pmuchtoman.ratesUrl=https://your-worker.workers.dev/rates
    val ratesOverride = providers.gradleProperty("muchtoman.ratesUrl").orNull

    buildTypes {
        debug {
            // 10.0.2.2 is how the emulator reaches `wrangler dev` on this machine.
            buildConfigField(
                "String",
                "RATES_URL",
                "\"${ratesOverride ?: "http://10.0.2.2:8787/rates"}\"",
            )
        }
        release {
            buildConfigField(
                "String",
                "RATES_URL",
                "\"${ratesOverride ?: "https://muchtoman-rates.milaniz.workers.dev/rates"}\"",
            )
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.biometric)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
