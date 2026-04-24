import java.io.File
import java.security.MessageDigest

plugins {
    base
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

val sdkVersion = "0.5.0"
val webRtcArtifactId = "libwebrtc-7559_173-universal"
val localWebRtcAarPath = "../serenada-core/libs/$webRtcArtifactId.aar"
val localWebRtcAarFile = file(localWebRtcAarPath)
val localWebRtcAarSha256File = file("$localWebRtcAarPath.sha256")
val expectedLocalWebRtcAarSha256 = readSha256FromFile(localWebRtcAarSha256File)

if (!localWebRtcAarFile.exists()) {
    throw GradleException("Missing local WebRTC AAR at $localWebRtcAarPath")
}
if (expectedLocalWebRtcAarSha256.isNullOrBlank()) {
    throw GradleException("Missing local WebRTC SHA-256 file at ${localWebRtcAarSha256File.path}")
}

val verifyLocalWebRtcAar = tasks.register("verifyLocalWebRtcAar") {
    doLast {
        val expectedHash = expectedLocalWebRtcAarSha256
            ?: throw GradleException("Missing expected SHA-256 for $localWebRtcAarPath")
        val actualHash = sha256Of(localWebRtcAarFile)
        if (actualHash != expectedHash) {
            throw GradleException(
                "Local WebRTC AAR checksum mismatch for $localWebRtcAarPath. " +
                    "Expected $expectedHash but found $actualHash",
            )
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "app.serenada"
            artifactId = webRtcArtifactId
            version = sdkVersion
            artifact(localWebRtcAarFile)

            pom {
                name.set("Serenada WebRTC")
                description.set("Pinned prebuilt WebRTC AAR used by Serenada Android SDK artifacts")
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
}

tasks.matching { it.name == "publishReleasePublicationToMavenLocal" }.configureEach {
    dependsOn(verifyLocalWebRtcAar)
}
