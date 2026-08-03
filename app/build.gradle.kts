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

    // Where the app gets its prices — the deployed Worker for both builds, so a debug install
    // is the shipped app with the debugger attached rather than a second environment to keep
    // in your head. A local Worker is the thing you opt into:
    //   ./gradlew installDebug -Pmuchtoman.ratesUrl=http://10.0.2.2:8787/rates   # emulator
    //   ./gradlew installDebug -Pmuchtoman.ratesUrl=http://<this-mac-on-the-lan>:8787/rates
    // Defaulting debug to 10.0.2.2 was the older way round, and it failed silently on a real
    // phone: prices kept showing (they are cached) while every wallet lookup timed out.
    // Not the workers.dev address the Worker also answers on: that whole domain is
    // DNS-filtered inside Iran — resolved to the 10.10.34.36 sinkhole — which is where most
    // of the people this app is for are. It failed in the least legible way possible: the
    // fixed assets still listed themselves because their catalogue is compiled in, while
    // رمزارز lost its prices *and* its entire section, since the coin list is the one part
    // of the catalogue that arrives over the network. It read as "this app has no crypto".
    val ratesUrl = providers.gradleProperty("muchtoman.ratesUrl")
        .getOrElse("https://rates.muchtoman.com/rates")

    buildTypes {
        debug {
            // Same key as release so a debug install replaces the released app in place —
            // data, backups and all — instead of demanding an uninstall. CI has no
            // keystore.properties, so its debug builds keep the default debug key.
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "RATES_URL", "\"$ratesUrl\"")
        }
        // A second app on the same phone — its own icon, its own data — so new features are
        // tried out beside the app she actually uses, never on top of it.
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            // src/dev/res overrides the app name so the two icons can be told apart.
            buildConfigField("String", "RATES_URL", "\"$ratesUrl\"")
        }
        release {
            buildConfigField("String", "RATES_URL", "\"$ratesUrl\"")
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
    implementation(libs.androidx.work.runtime.ktx)
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
