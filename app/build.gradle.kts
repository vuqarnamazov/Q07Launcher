plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "az.q07.launcher"
    useLibrary("android.car")
    compileSdk = 34

    defaultConfig {
        applicationId = "az.q07.launcher"
        minSdk = 30          // Android 11 (head unit)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        targetSdk = 30
        versionCode = 52
        versionName = "1.51"
        // GitHub Actions build nömrəsi — tətbiq daxilində yeniləməni yoxlamaq üçün
        buildConfigField("int", "CI_BUILD", System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // Lisenziya açarı GitHub secret-dən (Q07_LIC_SECRET) gəlir; repoda saxlanmır.
        // Hərf-hərf kodlanır ki, APK-da sətir kimi birbaşa görünməsin.
        val lic = (System.getenv("Q07_LIC_SECRET") ?: "").trim()
        buildConfigField("int[]", "LIC_K", "{" + lic.mapIndexed { i, ch -> (ch.code xor (0x5A + i * 7) and 0xFF).toString() }.joinToString(",") + "}")
    }

    // Sabit imza GitHub Secrets-dən gəlir (Q07_KEYSTORE_B64, Q07_KS_PASS) — açar repoda saxlanmır.
    // Secrets yoxdursa, build standart debug açarı ilə imzalanır.
    val ksPath: String? = System.getenv("Q07_KEYSTORE")
    signingConfigs {
        if (ksPath != null) {
            create("q07") {
                storeFile = file(ksPath)
                storePassword = System.getenv("Q07_KS_PASS")
                keyAlias = "q07"
                keyPassword = System.getenv("Q07_KS_PASS")
            }
        }
    }

    buildTypes {
        // Release: R8 ilə optimallaşdırılmış, debug-dan xeyli sürətli işləyir
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (ksPath != null) "q07" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // Daxili göstəricilərin son vəziyyəti şifrələnmiş saxlanır
    implementation("androidx.security:security-crypto:1.0.0")
    // Maşının zavod 3D modelini göstərmək üçün (Google Filament)
    implementation("com.google.android.filament:filament-android:1.54.5")
    implementation("com.google.android.filament:gltfio-android:1.54.5")
    implementation("com.google.android.filament:filament-utils-android:1.54.5")
}
