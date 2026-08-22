plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

val offlineApkBuildRoot = providers.gradleProperty("offlineApkBuildRoot").orNull
if (!offlineApkBuildRoot.isNullOrBlank()) {
    val baseBuildDir = file(offlineApkBuildRoot)
    layout.buildDirectory.set(baseBuildDir.resolve("root"))
    subprojects {
        val projectDirName = path.trim(':').replace(':', '-').ifBlank { name }
        layout.buildDirectory.set(baseBuildDir.resolve(projectDirName))
    }
}
