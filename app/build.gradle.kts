import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val endpointConfig = Properties()
val privateEndpointConfig = rootProject.file("p2premotefiles.conf")
val exampleEndpointConfig = rootProject.file("p2premotefiles.conf.example")
val endpointConfigFile = when {
    privateEndpointConfig.isFile -> privateEndpointConfig
    exampleEndpointConfig.isFile -> exampleEndpointConfig
    else -> error(
        "Missing p2premotefiles.conf and p2premotefiles.conf.example. " +
            "Create p2premotefiles.conf from the example file before building.",
    )
}
endpointConfigFile.inputStream().use(endpointConfig::load)

fun endpointValue(name: String): String =
    endpointConfig.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("Missing required value '$name' in ${endpointConfigFile.name}")

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.p2premotefiles.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.p2premotefiles.app"
        minSdk = 24
        targetSdk = 37
        buildConfigField("String", "VPS_SIGNALING_URL", buildConfigString(endpointValue("VPS_SIGNALING_URL")))
        buildConfigField("String", "VPS_RELAY_URL", buildConfigString(endpointValue("VPS_RELAY_URL")))
        versionCode = 1
        versionName = "26.10.01"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "DebugProbesKt.bin"
        }
    }

    androidResources {
        localeFilters += listOf("en", "zh-rTW")
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")

    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("com.github.topjohnwu.libsu:service:6.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
