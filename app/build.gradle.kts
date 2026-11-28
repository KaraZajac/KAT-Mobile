import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// KAT Mobile's version follows KAT's. The version code is major * 10000 + minor * 100 + patch
// (1.31.0 is 13100; 0.8.0 was 9).
val katMobileVersion = "1.31.0"
// The KAT release the receive core comes from: native/Cargo.toml's package version.
val katCoreVersion = Regex("""(?m)^version\s*=\s*"([^"]+)"""")
    .find(rootProject.file("native/Cargo.toml").readText())?.groupValues?.get(1)
    ?: throw GradleException("native/Cargo.toml has no package version")

android {
    namespace = "app.katmobile"
    compileSdk = 36
    // Links the Rust receive core in native/ (the cargo tasks below).
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "io.karazajac.katmobile"
        minSdk = 26
        targetSdk = 35
        versionName = katMobileVersion
        versionCode = katMobileVersion.split('.').map(String::toInt).let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
        buildConfigField("String", "KAT_CORE", "\"$katCoreVersion\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        // The instrumentation tests reset the saved session, so the debug build is a separate
        // ".qa" app; -PinspectPhone=true builds it under the real application ID instead.
        debug { if (providers.gradleProperty("inspectPhone").orNull != "true") applicationIdSuffix = ".qa" }
        release {
            isMinifyEnabled = false
            // Debug-signed until KAT Mobile has a release key. The release workflow signs with one
            // fixed debug key, so that releases can update each other.
            signingConfig = signingConfigs.getByName("debug")
            ndk { abiFilters += "arm64-v8a" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/katJni"))
    packaging { jniLibs.useLegacyPackaging = false }
}

// The receive core (native/) is built with cargo for each ABI and packaged from
// build/generated/katJni. -PkatAbis=arm64-v8a (comma-separated) builds only those ABIs; the
// release APK packages arm64-v8a only, and x86_64 is for emulators.
val rustTargets = mapOf(
    "arm64-v8a" to ("aarch64-linux-android" to "Arm64"),
    "x86_64" to ("x86_64-linux-android" to "X86_64"),
)
val katAbis = providers.gradleProperty("katAbis").orNull
    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
    ?: rustTargets.keys.toList()
require(katAbis.isNotEmpty() && katAbis.all(rustTargets::containsKey)) {
    "katAbis: $katAbis (known ABIs: ${rustTargets.keys.joinToString()})"
}

val nativeRoot = rootProject.file("native")
val osName = System.getProperty("os.name")
val windows = osName.startsWith("Windows")
val exe = if (windows) ".exe" else ""

// $CARGO (set when Gradle runs under cargo), then rustup's cargo, then cargo on the PATH.
fun cargo(): String = System.getenv("CARGO")
    ?: File(System.getenv("CARGO_HOME") ?: File(System.getProperty("user.home"), ".cargo").path, "bin/cargo$exe")
        .takeIf(File::isFile)?.absolutePath
    ?: "cargo$exe"

// The NDK's clang links the core. The target API level and the 16 KB page alignment are in
// native/.cargo/config.toml.
fun ndkClang(): File {
    val host = when {
        windows -> "windows-x86_64"
        osName.startsWith("Mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
    val ndks = listOfNotNull(
        File(sdk, "ndk/${android.ndkVersion}"),
        System.getenv("ANDROID_NDK_HOME")?.let(::File),
        System.getenv("ANDROID_NDK_ROOT")?.let(::File),
    )
    return ndks.map { File(it, "toolchains/llvm/prebuilt/$host/bin/clang$exe") }.firstOrNull(File::isFile)
        ?: throw GradleException(
            "Android NDK ${android.ndkVersion} not found: install it with " +
                "sdkmanager \"ndk;${android.ndkVersion}\", or set ANDROID_NDK_HOME",
        )
}

val copyKat = tasks.register<Sync>("copyKatLibraries") {
    into(layout.buildDirectory.dir("generated/katJni"))
}
for (abi in katAbis) {
    val (target, name) = rustTargets.getValue(abi)
    val library = nativeRoot.resolve("target/$target/release/libkat_rx.so")
    val build = tasks.register<Exec>("buildKat$name") {
        description = "Builds the KAT receive core for $abi"
        workingDir(nativeRoot)
        commandLine(cargo(), "build", "--locked", "--release", "--target", target, "--lib")
        inputs.dir(nativeRoot.resolve("src"))
        inputs.files(nativeRoot.resolve("Cargo.toml"), nativeRoot.resolve("Cargo.lock"), nativeRoot.resolve(".cargo/config.toml"))
        outputs.file(library)
        doFirst { environment("CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER", ndkClang().absolutePath) }
    }
    copyKat.configure {
        dependsOn(build)
        from(library) { into(abi) }
    }
}
tasks.named("preBuild") { dependsOn(copyKat) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
