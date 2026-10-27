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
    }
}

rootProject.name = "Proxmark5"

include(":parsers")
// The app needs the Android SDK/NDK. Pass -Pparsers.only to build just the
// pure-JVM parsers module where no Android SDK is installed (e.g. this check runs
// on a plain JVM; CI omits the flag and builds the APK).
if (!providers.gradleProperty("parsers.only").isPresent) {
    include(":app")
}
