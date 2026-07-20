pluginManagement {
    val isCiBuild = System.getenv("CI").equals("true", ignoreCase = true)
    repositories {
        if (isCiBuild) {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
    }
}
dependencyResolutionManagement {
    val isCiBuild = System.getenv("CI").equals("true", ignoreCase = true)
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (isCiBuild) {
            google()
            mavenCentral()
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
    }
}

rootProject.name = "ConcertoSMSForwarder"
include(":app")
