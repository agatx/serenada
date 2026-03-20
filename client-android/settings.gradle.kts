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
            dirs("serenada-core/libs")
        }
    }
}

rootProject.name = "serenada-android"
include(":app")
include(":serenada-core")
include(":serenada-call-ui")
