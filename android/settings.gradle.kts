pluginManagement { repositories { maven("https://maven.aliyun.com/repository/google"); maven("https://maven.aliyun.com/repository/gradle-plugin"); google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { maven("https://maven.aliyun.com/repository/google"); maven("https://maven.aliyun.com/repository/public"); google(); mavenCentral() } }
rootProject.name = "leoagent"
include(":app")
