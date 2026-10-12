pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "dannovel-extensions"

include(":core")

// Load all novel extension modules under /src
val srcDir = file("src")
if (srcDir.exists()) {
    srcDir.listFiles()?.filter { it.isDirectory && it.name != ".gradle" && it.name != "build" }?.forEach { extDir ->
        include(":src:${extDir.name}")
        project(":src:${extDir.name}").projectDir = extDir
    }
}
