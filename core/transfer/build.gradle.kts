plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.peyvand.core.transfer"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    sourceSets {
        named("main") {
            kotlin.directories.add("src/main/kotlin")
        }
        named("test") {
            kotlin.directories.add("src/test/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnit()
}
