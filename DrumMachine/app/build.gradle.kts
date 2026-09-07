import java.util.Properties

// Build para Google Play? Ativado no CI com `-PplayRelease=1`.
// Localmente (dev, no seu aarch64) fica em API 34 e compila com o aapt2 arm64.
val playRelease = providers.gradleProperty("playRelease").orNull == "1"
// v5.5: rebuild nativo do libdrummachi.so a partir do engine.cpp. Só no CI x86_64
// (onde o NDK/CMake do Google funcionam). Local (aarch64) fica OFF e usa o .so precompilado.
val buildNative = providers.gradleProperty("buildNative").orNull == "1"
val api = if (playRelease) 36 else 34
val vc = if (playRelease) 1 else 14
val vn = if (playRelease) "1.0.0" else "5.5"  // release: 1ª pública; dev: 5.5

// Credenciais de assinatura (release). Arquivo gitignorado: keystore.properties
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.drummachi"
    compileSdk = api
    buildFeatures { prefab = buildNative } // v5.5: só no CI (resolve oboe::oboe)

    defaultConfig {
        applicationId = "com.drummachi"
        minSdk = 23
        targetSdk = api
        versionCode = vc
        versionName = vn

        // v5.5: build nativo (engine.cpp) apenas no CI x86_64
        if (buildNative) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments += listOf("-DANDROID_STL=c++_shared")
                }
                ndk { abiFilters += "arm64-v8a" }
            }
        }
    }

    // v5.5: aponta o CMake só quando buildNative (evita tentar rodar CMake no aarch64)
    if (buildNative) {
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt") }
        }
    }

    // Assinatura de release (upload key). Só ativa se o keystore.properties existir.
    if (keystorePropsFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Extrai as libs nativas para disco antes de carregar (máxima compatibilidade;
    // evita bugs de mmap direto do APK em alguns Samsung/One UI com extractNativeLibs=false)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // v5.5: com buildNative o .so vem do CMake (senão colidiria com o precompilado);
    // local (aarch64) usa o precompilado de jniLibs normalmente.
    sourceSets {
            getByName("main").jniLibs.setSrcDirs(
                if (buildNative) emptyList() else listOf("src/main/jniLibs")
            )
        }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // v5.5: oboe (prefab) só entra no build nativo do CI (x86_64). Local (aarch64)
    // fica com o mesmo conjunto de deps de antes e usa o .so precompilado.
    if (buildNative) {
        implementation("com.google.oboe:oboe:1.10.0")
    }
}