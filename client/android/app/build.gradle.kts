import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Standalone modes a PM5 firmware accepts (armsrc/Standalone/Makefile.hal), offered by the
// "build firmware" form of the firmware screen. The PM5 has neither the BT add-on nor a
// smartcard slot, so the modes that need them are left out.
val standaloneModes: String = rootDir.resolve("../../armsrc/Standalone/Makefile.hal").let { hal ->
    if (!hal.exists()) return@let ""
    val lines = hal.readLines()
    fun list(name: String) = lines
        .filter { Regex("^$name\\s*[:+]?=").containsMatchIn(it) }
        .flatMap { it.substringAfter("=").trim().split(Regex("\\s+")) }
        .filter { it.isNotBlank() }
    (list("STANDALONE_MODES") - list("STANDALONE_MODES_REQ_BT").toSet() -
        list("STANDALONE_MODES_REQ_SMARTCARD").toSet()).joinToString(",")
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
        buildConfigField("String", "STANDALONE_MODES", "\"$standaloneModes\"")
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

    // client resources (dictionaries, lua, json) staged into assets, see stageClientResources,
    // and the bundled firmware, see stageFirmware
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
        // Ship the hardnested bitflip tables (~9 MB, already lz4-compressed): `hf mf
        // hardnested` and `hf mf autopwn` on a hardened card need them, and the client
        // calls exit() if a table is missing — which would kill the whole app. Skip only
        // the benchmark data, which real cracking never reads.
        exclude("hardnested_bf_bench_data.bin")
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

// PM5 firmware built from this same tree (CI passes -Ppm3.firmwareDir=<dir>), shipped as
// assets/firmware so the app can always flash a firmware that matches its client, even
// offline. Without the property the APK has no bundled firmware and the app hides it.
val firmwareDir: String? = providers.gradleProperty("pm3.firmwareDir").orNull
val stageFirmware by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("pm3-assets/firmware"))
    if (firmwareDir != null) {
        from(file(firmwareDir)) {
            include("bootrom.elf", "fullimage.elf", "recovery.bin", "firmware.json")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(stageClientResources, stageFirmware)
}
