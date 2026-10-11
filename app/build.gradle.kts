import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

val localSecrets = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}

fun secret(name: String): String =
    providers.environmentVariable(name).orNull ?: localSecrets.getProperty(name, "")

fun asBuildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") }\""

android {
    namespace = "com.jarvis.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarvis.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native inference libraries currently publish 64-bit Android builds.
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }

        // Keep this list in sync with ModelProviderCatalog.keyEnvironment in
        // app/src/main/java/com/jarvis/ai/llm/ModelProvider.kt; a unit test guards the names.
        val cloudProviderKeyNames = listOf(
            "MOONSHOT_API_KEY",
            "OPENAI_API_KEY",
            "ANTHROPIC_API_KEY",
            "GEMINI_API_KEY",
            "XAI_API_KEY",
            "DEEPSEEK_API_KEY",
            "GROQ_API_KEY",
            "MISTRAL_API_KEY",
            "OPENROUTER_API_KEY",
            "CUSTOM_LLM_API_KEY",
        )

        // Legacy single-provider fields stay for compatibility with existing builds.
        buildConfigField("String", "OPENAI_API_KEY", asBuildConfigString(secret("OPENAI_API_KEY")))
        buildConfigField("String", "ANTHROPIC_API_KEY", asBuildConfigString(secret("ANTHROPIC_API_KEY")))

        // One compact field carries every build-time key; in-app settings override these.
        buildConfigField(
            "String",
            "CLOUD_PROVIDER_API_KEYS",
            asBuildConfigString(cloudProviderKeyNames.joinToString(";") { name -> "$name=${secret(name)}" }),
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs {
            // Sherpa and the direct ONNX API ship the same pinned ONNX Runtime SONAME.
            pickFirsts += setOf("**/libc++_shared.so", "**/libonnxruntime.so")
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.material)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.onnxruntime.android)
    implementation(libs.sherpa.onnx)
    implementation(libs.llama.cpp.kotlin)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
