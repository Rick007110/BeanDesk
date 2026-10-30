plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
        // The release workflow passes the run number, so every build installs as an update.
        versionCode = providers.gradleProperty("beandesk.versionCode").orNull?.toInt() ?: 1
        versionName = beanDeskProperty("versionName")

        // Empty until there is a Supabase project; the anon key is public by design.
        buildConfigField("String", "SUPABASE_URL", "\"${beanDeskProperty("supabaseUrl").trimEnd('/')}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${beanDeskProperty("supabaseAnonKey")}\"")
        buildConfigField("String", "MAP_STYLE_URL", "\"${beanDeskProperty("mapStyleUrl")}\"")
        buildConfigField("String", "DEFAULT_CITY", "\"${beanDeskProperty("defaultCity")}\"")

        // UI tests on an emulator (src/androidTest), run in CI.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // A shared debug key (not a secret, like Android's own), so builds from GitHub Releases
            // and Android Studio install over each other instead of needing an uninstall first.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)

    implementation(libs.maplibre.android)
    implementation(libs.play.services.location)

    testImplementation(libs.kotlin.test.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
