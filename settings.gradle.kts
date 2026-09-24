pluginManagement {
    repositories {
        // 官方仓库优先，避免镜像只同步了元数据、尚未同步完整构件时解析失败。
        google()
        mavenCentral()
        gradlePluginPortal()

        // 国内镜像作为官方仓库不可用时的兜底。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}

dependencyResolutionManagement {
    // 依赖仓库统一在 settings 声明，模块内不得再声明 repositories
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // 国内镜像作为官方仓库不可用时的兜底。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}

rootProject.name = "PlantIdentifyLibrary"
include(":app")
