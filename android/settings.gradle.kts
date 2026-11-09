import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "graft-android"
include(":core")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
