/*
 * JADX Headless Server Gradle build.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    id("com.gradleup.shadow") version "8.3.6"
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":analysis"))
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation(libs.jadx.core)
    implementation(libs.jadx.dex.input)
    implementation(libs.jadx.java.input)
    implementation(libs.jadx.smali.input)
    implementation(libs.gson)
    implementation(libs.logback.classic)
    implementation(libs.mcp.kotlin.sdk)

    // WHY: OpenCode type=remote requires MCP Streamable HTTP; kotlin-sdk's mcpStreamableHttp depends on Ktor.
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    // The master owns HTTP serving and isolated analysis workers.
    mainClass.set("com.yyang.jadxmcp.bootstrap.MasterMain")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    maxHeapSize = "4G"
}

tasks.processResources {
    val productVersion = project.version.toString()
    inputs.property("productVersion", productVersion)
    from("src/main/version") {
        expand("productVersion" to productVersion)
    }
}

tasks.named<Jar>("jar") {
    // Keep the thin JAR for internal consumers, separate from the release artifact.
    archiveBaseName.set("jadx-mcp")
    archiveClassifier.set("plain")
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("jadx-mcp")
    archiveClassifier.set("")
    archiveFileName.set("jadx-mcp.jar")
    mergeServiceFiles()
}
