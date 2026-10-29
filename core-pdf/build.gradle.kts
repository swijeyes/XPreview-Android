// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.xpreview.pdf"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets["main"].jniLibs.srcDirs("build/pdfium-jni")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// The prebuilt libpdfium.so must ship next to libxpreview_pdf.so.
val copyPdfium by tasks.registering(Copy::class) {
    listOf("arm64-v8a", "x86_64").forEach { abi ->
        from("src/main/cpp/third_party/pdfium/$abi/lib/libpdfium.so") { into(abi) }
    }
    into(layout.buildDirectory.dir("pdfium-jni"))
}
tasks.named("preBuild") { dependsOn(copyPdfium) }

dependencies {
    api(project(":core-common"))
    implementation(libs.coroutines.android)
}
