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
    }
}

rootProject.name = "REV"

include(":app")
include(":core")
include(":domain")
include(":data")
include(":playback")
include(":wearprotocol")
include(":wear")
include(":feature:auth")
include(":feature:home")
include(":feature:library")
include(":feature:player")
include(":feature:lyrics")
include(":feature:search")
include(":feature:downloads")
include(":feature:settings")
include(":feature:stats")
