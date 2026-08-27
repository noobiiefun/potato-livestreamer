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
        // Dibutuhkan oleh RootEncoder (pedroSG94), yang dipublikasikan lewat JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PotatoLivestreamerIRL"
include(":app")
