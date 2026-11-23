plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versão vem da Action (tag vX.Y.Z); valores locais servem para testes
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.9.1"
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 21

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
        // Só ARM (tablets/celulares): o Filament e o TensorFlow trazem bibliotecas por processador
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
        debug {
            // Instala ao lado do app oficial (assinaturas diferentes não deixam substituir)
            applicationIdSuffix = if (project.findProperty("isolatedRoadTest") == "true") ".estrada" else ".debug"
            versionNameSuffix = "-debug"
            // No CI, a mesma chave em cada build permite atualizar a variante de teste.
            signingConfig = signingConfigs.getByName(if (hasKey) "release" else "debug")
        }
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
    // O modelo de detecção precisa ficar sem compressão dentro do APK
    androidResources { noCompress += "tflite" }
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
    // Detecção de carros, pessoas etc. no próprio tablet (simulação da estrada)
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    // Interpreter puro para o modelo de faixa do ADAS (a Task Library só traz a interface, sem o motor).
    // Mesma versão da tensorflow-lite-api que a Task Library puxa. Não é LiteRT (ver CLAUDE.md, pendência #5).
    debugImplementation("org.tensorflow:tensorflow-lite:2.13.0")
    // Estrada 3D noturna (motor 3D do Google + modelos .glb da Kenney, CC0, em assets/road3d)
    // 1.74.0: a última que compila com compileSdk 34 / Kotlin 1.9 (a 1.76+ exige Kotlin 2.4 e Android 37)
    val filament = "1.74.0"
    implementation("com.google.android.filament:filament-android:$filament")
    implementation("com.google.android.filament:gltfio-android:$filament")

    testImplementation("junit:junit:4.13.2")
    // o org.json do Android não funciona nos testes do PC (OsmMapTest lê JSON do OpenStreetMap)
    testImplementation("org.json:json:20240303")
}
