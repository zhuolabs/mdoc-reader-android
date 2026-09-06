import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.file.DirectoryProperty

abstract class GenerateNative : Exec() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.mdocreader"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "com.example.mdocreader"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    flavorDimensions += "bleBackend"
    productFlavors {
        create("platform") { dimension = "bleBackend" }
        create("btstack") {
            dimension = "bleBackend"
            applicationIdSuffix = ".btstack"
            versionNameSuffix = "-btstack"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

// Same build-time generation pattern as UniFFI's Gradle integration guide,
// using AGP's variant API and library metadata instead of a UDL file.
val rustRoot = rootProject.projectDir.parentFile
val rustInputs = fileTree(rustRoot.resolve("crates")) { include("**/*.rs", "**/*.toml") }
val sdkPath = androidComponents.sdkComponents.sdkDirectory.get().asFile
androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    val release = variant.buildType == "release"
    val btstack = variant.productFlavors.any { it.second == "btstack" }
    val nativeBuild = tasks.register<GenerateNative>("build${capitalized}Rust") {
        workingDir(rustRoot)
        inputs.files(rustInputs, rustRoot.resolve("Cargo.toml"), rustRoot.resolve("Cargo.lock"))
        outputDirectory.set(layout.buildDirectory.dir("generated/rust/${variant.name}/jniLibs"))
        environment("ANDROID_HOME", sdkPath.absolutePath)
        environment("ANDROID_NDK_HOME", sdkPath.resolve("ndk/27.0.12077973").absolutePath)
        // Cargo feature outputs must not race when Gradle builds both flavors.
        environment("CARGO_TARGET_DIR", rustRoot.resolve("target/android/${variant.name}").absolutePath)
        commandLine(listOf("cargo", "ndk", "-t", "arm64-v8a", "-P", "27", "-o",
            outputDirectory.get().asFile.absolutePath, "build", "--locked", "-p", "mdoc-android") +
            (if (btstack) listOf("--features", "btstack") else emptyList()) +
            (if (release) listOf("--release") else emptyList()))
    }
    val bindings = tasks.register<GenerateNative>("generate${capitalized}UniFFIBindings") {
        dependsOn(nativeBuild)
        workingDir(rustRoot)
        inputs.files(rustInputs, rustRoot.resolve("Cargo.toml"), rustRoot.resolve("Cargo.lock"))
        inputs.dir(nativeBuild.flatMap { it.outputDirectory })
        outputDirectory.set(layout.buildDirectory.dir("generated/source/uniffi/${variant.name}/java"))
        commandLine("cargo", "run", "--locked", "-p", "mdoc-uniffi-bindgen", "--", "generate",
            "--library", nativeBuild.get().outputDirectory.file("arm64-v8a/libmdoc_android.so").get().asFile.absolutePath,
            "--language", "kotlin", "--no-format",
            "--out-dir", outputDirectory.get().asFile.absolutePath)
    }
    variant.sources.java?.addGeneratedSourceDirectory(bindings, GenerateNative::outputDirectory)
    variant.sources.jniLibs?.addGeneratedSourceDirectory(nativeBuild, GenerateNative::outputDirectory)
}

dependencies {
  implementation(libs.jna) { artifact { type = "aar" } }
  implementation(libs.kotlinx.coroutines.android)
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

}
