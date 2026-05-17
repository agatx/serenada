import java.io.File
import java.security.MessageDigest
import groovy.util.Node
import org.jetbrains.dokka.gradle.DokkaTask
import org.gradle.api.publish.tasks.GenerateModuleMetadata

plugins {
    id("com.android.library")
    id("org.jetbrains.dokka")
    `maven-publish`
}

fun appendPomDependency(
    dependenciesNode: Node,
    groupId: String,
    artifactId: String,
    version: String? = null,
    type: String? = null,
    scope: String,
) {
    val dependencyNode = dependenciesNode.appendNode("dependency")
    dependencyNode.appendNode("groupId", groupId)
    dependencyNode.appendNode("artifactId", artifactId)
    version?.let { dependencyNode.appendNode("version", it) }
    type?.let { dependencyNode.appendNode("type", it) }
    dependencyNode.appendNode("scope", scope)
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

val sdkVersion = "0.6.13"
val mavenGroupId = "app.serenada"
val webRtcArtifactId = "libwebrtc-7559_173-universal"
val localWebRtcAarPath = "libs/$webRtcArtifactId.aar"
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
        minSdk = 24
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
    api(":$webRtcArtifactId@aar")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

tasks.withType<GenerateModuleMetadata>().matching { it.name == "generateMetadataFileForReleasePublication" }.configureEach {
    enabled = false
}

tasks.withType<DokkaTask>().configureEach {
    dokkaSourceSets.maybeCreate("main").apply {
        sourceRoots.from(file("src/main/java"))
    }
}

afterEvaluate {
    val releaseAarPath = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")

    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = mavenGroupId
                artifactId = "core"
                version = sdkVersion
                artifact(releaseAarPath) {
                    builtBy(tasks.named("bundleReleaseAar"))
                    extension = "aar"
                }
                artifact(tasks.named("sourceReleaseJar")) {
                    classifier = "sources"
                }

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

                    withXml {
                        val root = asNode()
                        val dependenciesNode = root.appendNode("dependencies")
                        appendPomDependency(
                            dependenciesNode = dependenciesNode,
                            groupId = mavenGroupId,
                            artifactId = webRtcArtifactId,
                            version = sdkVersion,
                            type = "aar",
                            scope = "compile",
                        )
                        appendPomDependency(
                            dependenciesNode = dependenciesNode,
                            groupId = "org.jetbrains.kotlinx",
                            artifactId = "kotlinx-coroutines-android",
                            version = "1.7.3",
                            scope = "compile",
                        )
                        appendPomDependency(
                            dependenciesNode = dependenciesNode,
                            groupId = "org.jetbrains.kotlin",
                            artifactId = "kotlin-stdlib",
                            version = "2.3.21",
                            scope = "compile",
                        )
                        appendPomDependency(
                            dependenciesNode = dependenciesNode,
                            groupId = "com.squareup.okhttp3",
                            artifactId = "okhttp",
                            version = "4.12.0",
                            scope = "runtime",
                        )
                    }
                }
            }
        }
    }
}
