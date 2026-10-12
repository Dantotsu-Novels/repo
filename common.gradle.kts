import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val extName = project.extra["extName"] as String
val extVersionName = project.extra["extVersionName"] as String
val extVersionCode = (project.extra["extVersionCode"] as Number).toInt()
val extSuffix = project.extra["extSuffix"] as String
val fullPkgName = "some.random.$extSuffix"

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

val signingKeyFile = rootProject.file("signingkey.jks")

android {
    namespace = "some.random"
    compileSdk = 35

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories.add("src/main/java")
            res.directories.add("res")
            assets.directories.add("assets")
        }
    }

    defaultConfig {
        applicationId = fullPkgName
        minSdk = 21
        targetSdk = 35
        versionCode = extVersionCode
        versionName = extVersionName

        manifestPlaceholders["appName"] = extName
    }

    signingConfigs {
        create("release") {
            if (signingKeyFile.exists()) {
                storeFile = signingKeyFile
                storePassword = System.getenv("KEY_STORE_PASSWORD") ?: System.getenv("SIGNING_KEY_PASSWORD") ?: ""
                keyAlias = System.getenv("ALIAS") ?: "key0"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("SIGNING_KEY_PASSWORD") ?: ""
            } else if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile") ?: "keystore.jks")
                storePassword = keystoreProperties.getProperty("storePassword") ?: ""
                keyAlias = keystoreProperties.getProperty("keyAlias") ?: ""
                keyPassword = keystoreProperties.getProperty("keyPassword") ?: ""
            }
        }
    }

    buildTypes {
        release {
            val hasSigningKey = signingKeyFile.exists() || (keystoreProperties.isNotEmpty() && keystorePropertiesFile.exists())
            signingConfig = if (hasSigningKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), rootProject.file("common/proguard-rules.pro"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf("-Xcontext-receivers")
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as? BaseVariantOutputImpl
            output?.outputFileName = "$fullPkgName.apk"
        }
    }
}

dependencies {
    implementation(project(":core"))
}
