plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionCode = providers.gradleProperty("VERSION_CODE").orNull?.toIntOrNull() ?: 1
val appVersionName = providers.gradleProperty("VERSION_NAME").orNull ?: "0.4.0-dev"

val devStoreFile = providers.environmentVariable("ANDROID_DEV_KEYSTORE_FILE").orNull
val devStorePassword = providers.environmentVariable("ANDROID_DEV_KEYSTORE_PASSWORD").orNull
val devKeyAlias = providers.environmentVariable("ANDROID_DEV_KEY_ALIAS").orNull
val devKeyPassword = providers.environmentVariable("ANDROID_DEV_KEY_PASSWORD").orNull
val devStoreType = providers.environmentVariable("ANDROID_DEV_KEYSTORE_TYPE").orNull ?: "PKCS12"
val devSigningReady = listOf(
    devStoreFile,
    devStorePassword,
    devKeyAlias,
    devKeyPassword,
).all { !it.isNullOrBlank() }

val releaseStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseStoreType = providers.environmentVariable("ANDROID_KEYSTORE_TYPE").orNull ?: "PKCS12"
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.rteats.mpeineo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rteats.mpeineo"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (devSigningReady) {
            create("ciDev") {
                storeFile = rootProject.file(devStoreFile!!)
                storePassword = devStorePassword
                keyAlias = devKeyAlias
                keyPassword = devKeyPassword
                storeType = devStoreType
            }
        }

        if (releaseSigningReady) {
            create("ciRelease") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeType = releaseStoreType
            }
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "MPEI Neo Dev"
            buildConfigField("String", "UPDATE_CHANNEL", "\"dev\"")
            if (devSigningReady) {
                signingConfig = signingConfigs.getByName("ciDev")
            }
        }

        create("stable") {
            dimension = "channel"
            manifestPlaceholders["appLabel"] = "MPEI Neo"
            buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Material 3 Expressive APIs (floating toolbar, loading indicator, animated toggles)
    // are newer than the version selected by the April 2026 Compose BOM.
    implementation("androidx.compose.material3:material3:1.5.0-alpha29")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.leakcanary:leakcanary-android-instrumentation:2.14")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
