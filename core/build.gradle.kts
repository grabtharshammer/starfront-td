import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-neutral game: rules, sound synthesis, rendering and input logic. The Android app
// uses the JVM build; the :ios module links the iOS builds into the app framework.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
        }
    }
}
