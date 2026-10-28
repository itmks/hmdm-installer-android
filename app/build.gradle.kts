plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "co.sat.hmdminstaller"
    compileSdk = 34

    defaultConfig {
        applicationId = "co.sat.hmdminstaller"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // ADB client untuk Android (Wireless debugging: pair + connect)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Pembuatan sertifikat self-signed untuk kunci ADB
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}

// libadb-android sudah membawa BouncyCastle varian jdk15to18; buang varian jdk18on agar tidak duplikat.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk18on")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk18on")
}
