import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun asBuildConfigString(value: String?): String {
    val escaped = value.orEmpty()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

val firebaseAppId = (findProperty("firebaseAppId") as String?)?.trim()
val firebaseApiKey = (findProperty("firebaseApiKey") as String?)?.trim()
val firebaseProjectId = (findProperty("firebaseProjectId") as String?)?.trim()
val firebaseSenderId = (findProperty("firebaseSenderId") as String?)?.trim()
val forceSseSignaling = (findProperty("forceSseSignaling") as String?)
    ?.trim()
    ?.equals("true", ignoreCase = true)
    ?: false

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "app.serenada.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.serenada.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "0.3.0"
        buildConfigField("String", "FIREBASE_APP_ID", asBuildConfigString(firebaseAppId))
        buildConfigField("String", "FIREBASE_API_KEY", asBuildConfigString(firebaseApiKey))
        buildConfigField("String", "FIREBASE_PROJECT_ID", asBuildConfigString(firebaseProjectId))
        buildConfigField("String", "FIREBASE_SENDER_ID", asBuildConfigString(firebaseSenderId))
        buildConfigField("boolean", "FORCE_SSE_SIGNALING", forceSseSignaling.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                val storeFilePath = keystoreProperties["storeFile"] as String?
                if (!storeFilePath.isNullOrBlank()) {
                    storeFile = file(storeFilePath)
                }
                storePassword = keystoreProperties["storePassword"] as String?
                keyAlias = keystoreProperties["keyAlias"] as String?
                keyPassword = keystoreProperties["keyPassword"] as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val renameDebugApk = tasks.register("renameDebugApk") {
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (apk.exists()) {
            val target = File(apk.parentFile, "serenada-debug.apk")
            apk.copyTo(target, overwrite = true)
        }
    }
}

val renameReleaseApk = tasks.register("renameReleaseApk") {
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (apk.exists()) {
            val target = File(apk.parentFile, "serenada.apk")
            apk.copyTo(target, overwrite = true)
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(renameDebugApk)
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(renameReleaseApk)
}

dependencies {
    implementation(project(":serenada-core"))
    implementation(project(":serenada-call-ui"))

    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation(platform("com.google.firebase:firebase-bom:33.8.0"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // WebRTC AAR is now provided transitively via :serenada-core
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
