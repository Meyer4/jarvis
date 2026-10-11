import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * JarvisAI for a laptop. Same shared brain as the Android app: the same provider catalog, the same
 * request layer, the same ordering rules — plus a Compose desktop window.
 *
 * Speech input/output on the desktop is not wired up yet; this app is a text chat for now.
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Gives Compose a main dispatcher on the desktop JVM.
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.jarvis.ai.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "JarvisAI"
            packageVersion = "1.0.0"
            description = "JarvisAI personal assistant"
        }
    }
}
