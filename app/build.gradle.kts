plugins {
    id("com.android.application")
}

android {
    namespace = "com.tvplay"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tvplay"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
}
