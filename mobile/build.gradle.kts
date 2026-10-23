import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("com.google.android.gms.oss-licenses-plugin")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    kotlin("android")
    id("kotlin-parcelize")
}

setupApp()

android {
    namespace = "com.github.shadowsocks"
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.barcode.scanning)
    implementation(libs.fastscroll)
    implementation(libs.locale.api)
    implementation(libs.preferencex.simplemenu)
    implementation(libs.zxing)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
}

// Build the embedded RelayGuard Rust proxy as a JNI library for the same ABIs as Shadowsocks.
val relayGuardAbis = mapOf("arm" to "armeabi-v7a", "arm64" to "arm64-v8a", "x86" to "x86", "x86_64" to "x86_64")
val requestedAbi = findProperty("TARGET_ABI")?.toString()
val relayGuardRustTargets = if (requestedAbi != null) listOf(relayGuardAbis.getValue(requestedAbi)) else relayGuardAbis.values.toList()
val relayGuardJniDir = layout.projectDirectory.dir("src/main/jniLibs").asFile

tasks.register<Exec>("cargoBuildRelayGuard") {
    group = "build"
    description = "Cross-compile RelayGuard's Rust proxy for Android"
    workingDir = rootProject.projectDir.resolve("relayguard")
    commandLine(buildList<String> {
        add("cargo"); add("ndk")
        relayGuardRustTargets.forEach { add("-t"); add(it) }
        add("-o"); add(relayGuardJniDir.absolutePath)
        add("build"); add("--release"); add("--lib"); add("--locked")
    })
    doLast {
        relayGuardJniDir.listFiles()?.filter { it.isDirectory }?.forEach { abiDir ->
            val hashed = abiDir.listFiles { f -> f.name.matches(Regex("libtun2proxy-[0-9a-f]+\\.so")) } ?: emptyArray()
            val newest = hashed.maxByOrNull { it.lastModified() }
            if (newest != null) newest.copyTo(abiDir.resolve("libtun2proxy.so"), overwrite = true)
            hashed.forEach { it.delete() }
        }
    }
}

tasks.configureEach {
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
        dependsOn("cargoBuildRelayGuard")
    }
}
