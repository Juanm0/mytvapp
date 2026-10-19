import java.time.LocalDate
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val xuperLocalProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { this.load(it) }
}
val tmdbApiKey = xuperLocalProperties.getProperty("TMDB_API_KEY", "")
val escapedTmdbApiKey = tmdbApiKey.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.quilmes.xuper"
    compileSdk = 34
    // La lista de cuentas filtrada se genera desde config/ antes de empaquetar el APK.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/iptv-assets"))

    defaultConfig {
        applicationId = "com.quilmes.xuper"
        buildConfigField("String", "TMDB_API_KEY", "\"$escapedTmdbApiKey\"")
        minSdk = 24
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Elimina del archivo empaquetado las cuentas con Expires anterior al día de compilación.
// El TXT original de config/ queda como archivo editable, y la copia generada es la que va al APK.
val generateActiveIptvAccounts by tasks.registering {
    val sourceFile = rootProject.file("config/iptv-accounts-raw.txt")
    val generatedFile = layout.buildDirectory.file("generated/iptv-assets/iptv-accounts-raw.txt")
    inputs.file(sourceFile)
    inputs.property("buildDate", LocalDate.now().toString())
    outputs.file(generatedFile)

    doLast {
        val output = generatedFile.get().asFile
        output.parentFile.mkdirs()
        if (!sourceFile.isFile) {
            output.writeText("", Charsets.UTF_8)
            return@doLast
        }
        val separator = Regex("(?m)^\\s*-{10,}\\s*$")
        val expiryPattern = Regex("(?im)^\\s*Expires\\s*:\\s*(\\d{4}-\\d{2}-\\d{2})\\s*$")
        val today = LocalDate.now()
        val blocks = sourceFile.readText(Charsets.UTF_8)
            .split(separator)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val kept = blocks.filter { block ->
            val expiry = expiryPattern.find(block)?.groupValues?.getOrNull(1)
            val expiryDate = expiry?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            // Una fecha ausente o mal formada no se borra automáticamente.
            expiryDate == null || !expiryDate.isBefore(today)
        }
        val cleanedText = kept.joinToString("\n--------------------------------------------------------------------------------------------------------------\n") + if (kept.isEmpty()) "" else "\n"
        // El TXT de VS Code también se limpia para que las cuentas vencidas desaparezcan del archivo.
        sourceFile.writeText(cleanedText, Charsets.UTF_8)
        output.writeText(cleanedText, Charsets.UTF_8)
        val removed = blocks.size - kept.size
        if (removed > 0) logger.lifecycle("Xuper IPTV: se excluyeron $removed cuenta(s) vencida(s) del APK.")
        logger.lifecycle("Xuper IPTV: ${kept.size} cuenta(s) vigente(s) preparadas para el APK.")
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateActiveIptvAccounts)
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

    // Parser HTML para páginas web usadas por fuentes IPTV heredadas.
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
