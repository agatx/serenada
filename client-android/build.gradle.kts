buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.2.0" apply false
    id("com.android.library") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.dokka") version "1.9.20" apply false
}

tasks.register("publishSdkToMavenLocal") {
    dependsOn(
        ":serenada-webrtc:publishReleasePublicationToMavenLocal",
        ":serenada-core:publishReleasePublicationToMavenLocal",
        ":serenada-call-ui:publishReleasePublicationToMavenLocal",
    )
}
