import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
}

dependencies {
    implementation(libs.gson)
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin { compilerOptions.jvmTarget.set(JvmTarget.JVM_11) }

tasks.compileTestKotlin { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
tasks.compileTestJava {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
// JUnit 6 requires JVM 17 even though the plugin-facing production API targets JVM 11.
configurations.matching { it.name in setOf("testCompileClasspath", "testRuntimeClasspath") }.configureEach {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
}
tasks.test { useJUnitPlatform() }
