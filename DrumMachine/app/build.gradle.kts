plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.drummachi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.drummachi"
        minSdk = 23
        targetSdk = 34
        versionCode = 11
        versionName = "5.2-beta" // v5.2: curadoria de 7 loops (Pop1, Blues1, Ska, Rock1, Twist1, Funk1, Ballad1)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Extrai as libs nativas para disco antes de carregar (máxima compatibilidade;
    // evita bugs de mmap direto do APK em alguns Samsung/One UI com extractNativeLibs=false)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
}
