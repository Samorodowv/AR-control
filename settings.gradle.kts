pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "AR_Control"
include(":app")
include(":vendor:onexr")
include(":vendor:uvccamera")

project(":vendor:onexr").projectDir = file("vendor/onexr")
project(":vendor:uvccamera").projectDir = file("vendor/uvccamera")
