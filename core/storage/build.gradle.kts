plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.peyvand.core.storage"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    sourceSets {
        named("main") {
            kotlin.directories.add("src/main/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:domain"))
}
