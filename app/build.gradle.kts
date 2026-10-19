import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val xuperLocalProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { this.load(it) }
}
val tmdbApiKey = xuperLocalProperties.getProperty("TMDB_API_KEY", "")
val escapedTmdbApiKey = tmdbApiKey.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.quilmes.xuper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.quilmes.xuper"
        buildConfigField("String", "TMDB_API_KEY", "\"$escapedTmdbApiKey\"")
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Clave fija (app/xuper.keystore): así cada APK nuevo se instala POR ENCIMA del anterior
    // sin desinstalar. Es una app personal; si algún día se publica, generar una clave privada propia.
    signingConfigs {
        create("xuper") {
            storeFile = file("xuper.keystore")
            storePassword = "xuper123"
            keyAlias = "xuper"
            keyPassword = "xuper123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("xuper")
        }
        release {
            // Release (no debuggable) corre mucho más rápido que debug con Compose
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("xuper")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    // Compose base
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")

    // Compose for TV (componentes con foco/D-pad: Carousel, Card, etc.)
    implementation("androidx.tv:tv-foundation:1.0.0-alpha11")
    implementation("androidx.tv:tv-material:1.0.0")

    // Navegación
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Motor nativo adblock-rust de Brave, envuelto para Android/JNI y WebView.
    implementation("io.github.mlm-games:adblock:0.1.2")

    // Parseo HTML de páginas públicas; no ejecuta JavaScript ni resuelve players ofuscados.
    implementation("org.jsoup:jsoup:1.18.3")

    // Carga de imágenes (pósters, backdrops) desde URL
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Reproductor de video (Media3 / ExoPlayer) — soporta HLS/DASH, útil para IPTV
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
