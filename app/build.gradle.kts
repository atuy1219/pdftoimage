import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun getGitCommitHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
        process.inputStream.bufferedReader().use { it.readText().trim() }
    } catch (e: Exception) {
        "unknown"
    }
}

fun getGitCommandOutput(command: String): String {
    return try {
        val byteOut = ByteArrayOutputStream()
        project.exec {
            commandLine = command.split(" ")
            standardOutput = byteOut
        }
        byteOut.toString().trim()
    } catch (e: Exception) {
        "1.0.0-dev"
    }
}

val gitVersionName = getGitCommandOutput("git describe --tags --always")

val gitCommitCount = try {
    getGitCommandOutput("git rev-list --count HEAD").toInt()
} catch (e: Exception) {
    1
}


android {
    namespace = "com.atuy.pdftoimg"
    compileSdk {
        version = release(36)
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("local.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }

            val keyStorePath = System.getenv("KEYSTORE_PATH")
                ?: keystoreProperties.getProperty("key.store")

            val keyStorePwd = System.getenv("KEY_STORE_PASSWORD")
                ?: keystoreProperties.getProperty("key.store.password")

            val keyAliasVal = System.getenv("ALIAS")
                ?: keystoreProperties.getProperty("key.alias")

            val keyPwd = System.getenv("KEY_PASSWORD")
                ?: keystoreProperties.getProperty("key.password")

            if (keyStorePath != null && keyStorePwd != null && keyAliasVal != null && keyPwd != null) {
                storeFile = file(keyStorePath)
                storePassword = keyStorePwd
                keyAlias = keyAliasVal
                keyPassword = keyPwd
            }
        }
    }

    defaultConfig {
        applicationId = "com.atuy.pdftoimg"
        minSdk = 34
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = gitVersionName
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}