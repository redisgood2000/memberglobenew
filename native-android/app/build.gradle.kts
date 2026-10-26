import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localFirebase = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun firebaseValue(key: String): String =
    localFirebase.getProperty(key)
        ?: System.getenv(key.uppercase().replace('.', '_'))
        ?: when (key) {
            "firebaseApiKey" -> "AIzaSyBnF6gA8AWe7jYHzumzM5CFY2CGRuzJMeE"
            "firebaseApplicationId" -> "1:622920961765:android:f2787e488d42e6fc2ece0c"
            "firebaseProjectId" -> "member-globe-91998"
            "firebaseWebClientId" -> "622920961765-dtj3vgsree8dir791tss0cturj6bdm8d.apps.googleusercontent.com"
            else -> ""
        }
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.memberglobe.nativeapp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.memberglobe.nativeapp.test"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        buildConfigField("String", "FIREBASE_API_KEY", buildConfigString(firebaseValue("firebaseApiKey")))
        buildConfigField("String", "FIREBASE_APPLICATION_ID", buildConfigString(firebaseValue("firebaseApplicationId")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", buildConfigString(firebaseValue("firebaseProjectId")))
        buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", buildConfigString(firebaseValue("firebaseWebClientId")))
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation(platform("com.google.firebase:firebase-bom:33.12.0"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
