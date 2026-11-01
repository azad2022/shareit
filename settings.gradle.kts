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

rootProject.name = "PEYVAND"

include(":app")
include(":core:domain")
include(":core:data")
include(":core:connectivity")
include(":core:designsystem")
include(":core:storage")
