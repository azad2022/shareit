plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.peyvand.core.connectivity"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        named("main") {
            kotlin.directories.add("src/main/kotlin")
        }
        named("test") {
            kotlin.directories.add("src/test/kotlin")
        }
        named("androidTest") {
            kotlin.directories.add("src/androidTest/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:transfer"))
    implementation(libs.androidx.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
