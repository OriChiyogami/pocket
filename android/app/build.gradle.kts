import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 生图 Key 不进仓库：放在 android/image-secrets.properties（已 .gitignore），构建时注入 BuildConfig
val imageSecrets = Properties().apply {
    val file = rootProject.file("image-secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val imageApiKey = imageSecrets.getProperty("image.apiKey", "").trim()

android {
    namespace = "io.pocket.host"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.pocket.host"
        minSdk = 24
        targetSdk = 34
        versionCode = 13
        versionName = "1.3.1"

        buildConfigField("String", "IMAGE_API_KEY", "\"$imageApiKey\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../pocket-release.jks")
            storePassword = "pocket2024"
            keyAlias = "pocket"
            keyPassword = "pocket2024"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.webkit:webkit:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
