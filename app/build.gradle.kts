plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versão vem da Action (tag vX.Y.Z); valores locais servem para testes
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.1.2"
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 4

android {
    namespace = "com.dashlive.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dashlive.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO", "\"nabikendo/dashlive-70mai\"")
    }

    // A chave de assinatura NÃO fica no repositório: a Action a recebe pelo segredo
    // ANDROID_KEYSTORE_B64 e aponta para ela por variáveis de ambiente.
    val ksPath = System.getenv("KEYSTORE_FILE")
    val hasKey = ksPath != null && file(ksPath).exists()

    signingConfigs {
        if (hasKey) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "dashlive"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sem a chave (build local), cai na chave de debug só para compilar
            signingConfig = signingConfigs.getByName(if (hasKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
