plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Headless entry point for batch simulation and calibration reports.
// This is why :engine has no Android dependencies - see docs/SPEC.md 2.2.

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

application {
    mainClass.set("com.nflsim.cli.MainKt")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":data"))
}
