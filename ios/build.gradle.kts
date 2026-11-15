// The iOS app's Kotlin side, built as the StarfrontKit framework that iosApp/ links: a Compose
// host that draws the shared GameScreen through Skia, plus iOS sound, haptics and saves.
// Xcode builds it through the embedAndSignAppleFrameworkForXcode task (see iosApp/).
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

val composeVersion = "1.9.3"

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "StarfrontKit"
            isStatic = true
        }
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":core"))
            implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
            implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
            implementation("org.jetbrains.compose.ui:ui:$composeVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
    }
}
