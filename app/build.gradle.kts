import java.time.Instant

plugins {
    id("com.android.application")
}

android {
    namespace = "com.second.risedie.challengeapp"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val ci = System.getenv("CI") == "true"
            if (ci) {
                val keystorePath = System.getenv("CM_KEYSTORE_PATH")
                val keystorePassword = System.getenv("CM_KEYSTORE_PASSWORD")
                val keyAliasEnv = System.getenv("CM_KEY_ALIAS")
                val keyPasswordEnv = System.getenv("CM_KEY_PASSWORD")

                if (
                    !keystorePath.isNullOrBlank() &&
                    !keystorePassword.isNullOrBlank() &&
                    !keyAliasEnv.isNullOrBlank() &&
                    !keyPasswordEnv.isNullOrBlank()
                ) {
                    storeFile = file(keystorePath)
                    storePassword = keystorePassword
                    keyAlias = keyAliasEnv
                    keyPassword = keyPasswordEnv
                }
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
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.health.connect:connect-client:1.1.0-rc03")
}