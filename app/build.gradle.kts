import java.time.Instant

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.second.risedie.challengeapp"
    compileSdk = 36

    // Signing material must live outside the repository. CI environment variables
    // take precedence; local/release automation may use external Gradle properties.
    val releaseKeystorePath = System.getenv("CM_KEYSTORE_PATH")
        ?: providers.gradleProperty("GRAFIT_KEYSTORE_PATH").orNull
    val releaseKeystorePassword = System.getenv("CM_KEYSTORE_PASSWORD")
        ?: providers.gradleProperty("GRAFIT_KEYSTORE_PASSWORD").orNull
    val releaseKeyAlias = System.getenv("CM_KEY_ALIAS")
        ?: providers.gradleProperty("GRAFIT_KEY_ALIAS").orNull
    val releaseKeyPassword = System.getenv("CM_KEY_PASSWORD")
        ?: providers.gradleProperty("GRAFIT_KEY_PASSWORD").orNull
    val releaseSigningConfigured = listOf(
        releaseKeystorePath,
        releaseKeystorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    defaultConfig {
        applicationId = "com.second.risedie.challengeapp"
        minSdk = 28
        targetSdk = 36

        val dynamicVersionCode = ((Instant.now().epochSecond / 60L).toInt()).coerceAtLeast(1)
        val dynamicVersionName = "1.2.dev.${dynamicVersionCode}"
        versionCode = dynamicVersionCode
        versionName = dynamicVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "APP_WEB_URL", "\"https://second.risedie.ru/web\"")
        buildConfigField(
            "String",
            "APP_ALLOWED_HOSTS_JSON",
            "\"[\\\"second.risedie.ru\\\",\\\"www.second.risedie.ru\\\"]\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}


androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variantBuilder ->
        variantBuilder.enable = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.health.connect:connect-client:1.1.0-rc03")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
}