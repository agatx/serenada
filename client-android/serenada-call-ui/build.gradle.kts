import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.dokka")
    `maven-publish`
}

android {
    namespace = "app.serenada.callui"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
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

dependencies {
    api(project(":serenada-core"))

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.zxing:core:3.5.3")
}

tasks.withType<DokkaTask>().configureEach {
    dokkaSourceSets.maybeCreate("main").apply {
        sourceRoots.from(file("src/main/java"))
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "app.serenada"
                artifactId = "call-ui"
                version = "0.8.3"

                pom {
                    name.set("Serenada Call UI")
                    description.set("Pre-built Jetpack Compose call UI for Serenada video calls")
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
}
