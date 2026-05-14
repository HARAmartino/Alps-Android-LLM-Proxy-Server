import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Signing: read credentials from keystore.properties (never committed to git).
// Fall back to no-op so that debug and CI unsigned builds still assemble.
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().also { props ->
    if (keystorePropsFile.exists()) props.load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.llmproxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.llmproxy"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ship only English strings plus any explicitly added locale.
        // Drops all bundled library locales (Ktor, BouncyCastle, etc.) from the APK.
        resourceConfigurations += listOf("en")
    }

    // ---------------------------------------------------------------------------
    // Signing configurations
    // ---------------------------------------------------------------------------
    signingConfigs {
        // debug uses the default SDK debug keystore automatically; no changes needed.
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile     = file(keystoreProps["storeFile"]     as String)
                storePassword =      keystoreProps["storePassword"] as String
                keyAlias      =      keystoreProps["keyAlias"]      as String
                keyPassword   =      keystoreProps["keyPassword"]   as String
            }
            // When keystore.properties is absent (e.g. CI unsigned checks) the
            // release build type still assembles but produces an unsigned APK.
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 full mode: tree-shake + obfuscate + resource shrink.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // ---------------------------------------------------------------------------
    // ABI splits – produce one slim APK per architecture instead of a fat APK.
    // armeabi-v7a  : 32-bit ARM (older phones, ~90 % of Android 8 devices)
    // arm64-v8a    : 64-bit ARM (modern phones, required for Play Store 64-bit rule)
    // ---------------------------------------------------------------------------
    splits {
        abi {
            isEnable = true
            reset()                              // clear defaults (removes x86/x86_64)
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false               // do not also emit a fat APK
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-call-logging:2.3.12")
    implementation("io.ktor:ktor-server-cors:2.3.12")
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
    implementation("org.shredzone.acme4j:acme4j-client:2.16")
    implementation("org.shredzone.acme4j:acme4j-utils:2.16")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.ktor:ktor-client-mock:2.3.12")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
