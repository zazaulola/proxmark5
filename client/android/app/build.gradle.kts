import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.zazaulola.proxmark5"
    compileSdk = 35
    ndkVersion = project.findProperty("pm3.ndkVersion") as String? ?: "27.1.12297006"

    defaultConfig {
        applicationId = "io.github.zazaulola.proxmark5"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // arm64 covers every modern phone; add armeabi-v7a for older ones
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                // the client is C; keep the build to the one shared lib we need
                targets += "pm3jni"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // client resources (dictionaries, lua, json) staged into assets, see stageClientResources
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("pm3-assets"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":parsers"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Copy the subset of client/{resources,dictionaries,...} we ship into the APK.
// $HOME/.proxmark3/<subdir> is the first place the client looks (fileutils.c),
// so ClientApp unpacks assets/pm3/<subdir> there on first run.
val stageClientResources by tasks.registering(Sync::class) {
    val clientRoot = rootDir.resolve("../..") // .../client
    into(layout.buildDirectory.dir("pm3-assets/pm3"))
    from(clientRoot.resolve("resources")) {
        into("resources")
        // the 9 MB hardnested tables and other large crack data are a later feature
        exclude("hardnested_tables/**", "hardnested_bf_bench_data.bin")
    }
    from(clientRoot.resolve("dictionaries")) {
        into("dictionaries")
        // ship the common key lists, not the 28 MB extras bundle
        exclude("extras/**")
    }
    from(clientRoot.resolve("luascripts")) { into("luascripts") }
    from(clientRoot.resolve("lualibs")) { into("lualibs") }
    from(clientRoot.resolve("cmdscripts")) { into("cmdscripts") }
}

tasks.named("preBuild") {
    dependsOn(stageClientResources)
}
