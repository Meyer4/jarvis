plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Platform-neutral Jarvis brain: the cloud provider catalog, settings, and chat client.
 *
 * Shared by the Android app (`:app`) and the laptop app (`:desktop`), so adding a provider or
 * fixing a request shape benefits both. Nothing here may touch Android or Compose APIs.
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    // OkHttp is a plain JVM library and is also the HTTP stack the Android app already ships.
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
