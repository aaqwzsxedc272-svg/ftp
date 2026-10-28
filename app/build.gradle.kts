plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phoneftp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phoneftp"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.3"
    }

    // ONE stable signing key for every build (committed at
    // signing/phoneftp-signing.p12). Without this, each CI machine generated
    // a random debug key and Android refused updates with
    // "package conflicts with an existing package".
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("signing/phoneftp-signing.p12")
            storePassword = "phoneftp"
            keyAlias = "phoneftp"
            keyPassword = "phoneftp"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
