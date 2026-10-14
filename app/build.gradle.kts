plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Firebase só com o arquivo do projeto (baixado do console do Firebase); sem ele o app funciona igual, sem Crashlytics.
val hasFirebase = file("google-services.json").exists()
if (hasFirebase) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// IDs do AdMob: em gradle.properties do usuário (~/.gradle) ou nos secrets do CI. Sem eles, IDs de TESTE do Google.
fun prop(name: String, def: String): String =
    (System.getenv(name) ?: (project.findProperty(name) as String?))?.takeIf { it.isNotBlank() } ?: def

val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("ANDROID_SIGNING_PASSWORD")
val hasReleaseKey = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()

android {
    namespace = "com.leandrozzy.managercoach"
    compileSdk = 36

    defaultConfig {
        // Identificador na Play Store: NÃO pode mudar depois da primeira publicação.
        applicationId = "com.leandrozzy.managercoach"
        minSdk = 30
        targetSdk = 36
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        // A Play Store exige versionCode sempre maior: número da execução do CI
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        manifestPlaceholders["admobAppId"] = prop("ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")
        buildConfigField("String", "ADMOB_BANNER_ID", "\"" + prop("ADMOB_BANNER_ID", "ca-app-pub-3940256099942544/6300978111") + "\"")
        buildConfigField("String", "ADMOB_REWARDED_ID", "\"" + prop("ADMOB_REWARDED_ID", "ca-app-pub-3940256099942544/5224354917") + "\"")
        buildConfigField("Boolean", "HAS_FIREBASE", hasFirebase.toString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "upload"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            isMinifyEnabled = false
            // símbolos nativos para os relatórios de travamento do Play Console
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Premium (compra única) pelo Google Play Billing
    implementation("com.android.billingclient:billing-ktx:7.1.1")
    // anúncios discretos (banner e anúncio com recompensa) e consentimento LGPD/GDPR
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
    // monitoramento (só funciona com google-services.json)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
