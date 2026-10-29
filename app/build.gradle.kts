plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/** Reads a `beandesk.*` value from gradle.properties, -P or ~/.gradle/gradle.properties. */
fun beanDeskProperty(name: String): String =
    providers.gradleProperty("beandesk.$name").orNull
        ?: error("Missing Gradle property beandesk.$name (see gradle.properties)")

android {
    namespace = "nl.beandesk.app"
    compileSdk = 37

    defaultConfig {
        // The applicationId cannot change after the first Play Store release.
        applicationId = "nl.beandesk.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "CITY_EXPORT_BASE_URL", "\"${beanDeskProperty("cityExportBaseUrl")}\"")
        buildConfigField("String", "MAP_STYLE_URL", "\"${beanDeskProperty("mapStyleUrl")}\"")
        buildConfigField("String", "DEFAULT_CITY", "\"${beanDeskProperty("defaultCity")}\"")
    }

    buildTypes {
        debug {
            // Reads the bundled sample (src/debug/assets/sample) instead of the Supabase export.
            val useSample = beanDeskProperty("useSampleData").toBooleanStrict()
            buildConfigField("boolean", "USE_SAMPLE_DATA", useSample.toString())
        }
        release {
            // Release builds have no sample assets and always use the real export.
            buildConfigField("boolean", "USE_SAMPLE_DATA", "false")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.maplibre.android)

    testImplementation(libs.kotlin.test.junit)
}
