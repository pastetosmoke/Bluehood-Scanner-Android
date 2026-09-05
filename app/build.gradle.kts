import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")   // Kotlin 2.0+ はComposeコンパイラをプラグイン化
    id("com.google.devtools.ksp")
}

// 署名情報はリポジトリ外の keystore.properties に置く(鍵とパスワードをコードに埋めない)。
// 無い場合は署名設定を作らず、release も debug 鍵で署名される(手元検証用)。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.faker.bluehood"
    compileSdk = 36            // SDKにandroid-36.1のみインストール済みのため

    defaultConfig {
        applicationId = "com.faker.bluehood"
        minSdk = 33            // ScanRecord.getBytes() がAPI33。GrapheneOS/Pixel8Proは34+なので実害なし
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
    }
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 配布物には debuggable を絶対に付けない。
            // debug APK は android:debuggable=true になり、端末を一時的に触られただけで
            // アプリ内部データ(位置ログを含む)を ADB 経由で吸い出せてしまう。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        // 難読化済みビルドを本番と別IDで並べてインストールし、実機で壊れていないか確かめるための型。
        // R8 の出力は applicationId に影響されないので、これで検証すれば本番APKの検証になる。
        create("releaseTest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".rt"
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val room = "2.6.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    implementation("org.osmdroid:osmdroid-android:6.1.20")   // OpenStreetMap地図(Play Services不要)
    testImplementation("junit:junit:4.13.2")
}
