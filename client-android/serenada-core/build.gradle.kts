import java.io.File
import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.dokka")
    `maven-publish`
}

fun readSha256FromFile(file: File): String? {
    if (!file.exists()) {
        return null
    }
    val raw = file.readText()
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: return null
    return raw.split(Regex("\\s+")).firstOrNull()?.lowercase()
}

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val localWebRtcAarPath = "libs/libwebrtc-7559_173-arm64.aar"
val localWebRtcAarFile = file(localWebRtcAarPath)
val localWebRtcAarSha256Path = "$localWebRtcAarPath.sha256"
val localWebRtcAarSha256File = file(localWebRtcAarSha256Path)
val expectedLocalWebRtcAarSha256 = readSha256FromFile(localWebRtcAarSha256File)
if (!localWebRtcAarFile.exists()) {
    throw GradleException("Missing local WebRTC AAR at serenada-core/$localWebRtcAarPath")
}
if (expectedLocalWebRtcAarSha256.isNullOrBlank()) {
    throw GradleException("Missing local WebRTC SHA-256 file at serenada-core/$localWebRtcAarSha256Path")
}

android {
    namespace = "app.serenada.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

val verifyLocalWebRtcAar = tasks.register("verifyLocalWebRtcAar") {
    doLast {
        val expectedHash = expectedLocalWebRtcAarSha256
            ?: throw GradleException("Missing expected SHA-256 for serenada-core/$localWebRtcAarPath")
        val actualHash = sha256Of(localWebRtcAarFile)
        if (actualHash != expectedHash) {
            throw GradleException(
                "Local WebRTC AAR checksum mismatch for serenada-core/$localWebRtcAarPath. " +
                    "Expected $expectedHash but found $actualHash",
            )
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyLocalWebRtcAar)
}

dependencies {
    api("", name = "libwebrtc-7559_173-arm64", ext = "aar")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "app.serenada"
                artifactId = "core"
                version = "0.1.0"

                pom {
                    name.set("Serenada Core")
                    description.set("Headless WebRTC call engine for 1:1 video calls")
                    url.set("https://github.com/agatx/serenada")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/agatx/serenada")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: ""
                    password = System.getenv("GITHUB_TOKEN") ?: ""
                }
            }
        }
    }
}
