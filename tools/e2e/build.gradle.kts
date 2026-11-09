plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The Android core module is consumed as a jar so this harness can live outside that Gradle
    // build and reuse the exact same protocol and transport code.
    implementation(files("../../android/core/build/libs/core.jar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.16")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
}

application {
    mainClass.set("kr.jjw.graft.e2e.MainKt")
}
