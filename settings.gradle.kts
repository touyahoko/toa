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
        maven { url = uri("https://jitpack.io") }
        // AUSBC 古い推移依存のフォールバック
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
rootProject.name = "MhxxRngTool"
include(":app")
