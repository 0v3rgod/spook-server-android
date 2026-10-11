plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.taylor.spook.server"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.taylor.spook.server"
        // 26 = java.util.Base64 (usado no sid) + icone adaptativo
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-proto"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// O :core e um modulo JVM. Duas dependencias dele NAO funcionam no Android:
//  - org.xerial:sqlite-jdbc  -> o driver JDBC nao existe no Android (o app usa
//    AndroidStore, sobre android.database.sqlite). Fora do classpath para o
//    D8 nunca tentar dexar as classes de java.sql.
//  - ch.qos.logback-classic  -> runtimeOnly no :core; nao e binding valido no
//    Android e so inflaria o APK. O app loga pelo Logcat.
configurations.configureEach {
    exclude(group = "org.xerial", module = "sqlite-jdbc")
    exclude(group = "ch.qos.logback", module = "logback-classic")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    // o :core declara isso como `implementation`, que nao propaga no classpath
    // de compilacao -- mas Mensagem/Task/Relatorio expõem JsonObject na API
    // publica, entao o :app precisa dele para compilar e para o D8 dexar.
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
