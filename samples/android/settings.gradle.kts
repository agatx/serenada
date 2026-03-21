pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        flatDir {
            dirs("../../client-android/serenada-core/libs")
        }
    }
}

rootProject.name = "serenada-android-sample"
include(":app")
include(":serenada-core")
include(":serenada-call-ui")

project(":serenada-core").projectDir = file("../../client-android/serenada-core")
project(":serenada-call-ui").projectDir = file("../../client-android/serenada-call-ui")
