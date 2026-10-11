plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit)
    // o MESMO jar que o app usa - o teste de interoperabilidade e com ele, nao com um mock
    testImplementation(libs.socketio.client)
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
