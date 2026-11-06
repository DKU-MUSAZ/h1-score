pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://devrepo.kakao.com/nexus/repository/kakaomap-releases/") {
            content { includeGroup("com.kakao.maps.open") }
        }
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content { includeGroupByRegex("ai\\.pleos.*") }
        }
    }
}
rootProject.name = "H1-score"
include(":app", ":core")
