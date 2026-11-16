plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rocom.nearby"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rocom.nearby"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.material)
    implementation(libs.easyfloat)

    testImplementation(libs.junit)
}