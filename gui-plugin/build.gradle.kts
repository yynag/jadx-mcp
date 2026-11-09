import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("com.gradleup.shadow") version "8.3.6"
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":analysis"))
    compileOnly("io.github.skylot:jadx-core:1.5.6")
    compileOnly("io.github.skylot:jadx-gui:1.5.6")
    compileOnly("com.google.code.gson:gson:2.10.1")
    testImplementation("io.github.skylot:jadx-core:1.5.6")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation(libs.jadx.java.input)
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

tasks.withType<KotlinCompile>().configureEach {
    if (name.contains("Test")) compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.withType<JavaCompile>().configureEach {
    if (name == "compileTestJava") {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("jadx-mcp-gui-plugin")
    enabled = false
}

// JADX/Gson remain host-provided compileOnly APIs; project protocol/analysis code must travel
// inside the installable plugin or a fresh GUI cannot resolve the rewritten runtime classes.
tasks.shadowJar {
    archiveBaseName.set("jadx-mcp-gui-plugin")
    archiveClassifier.set("")
    mergeServiceFiles()
}

// A release distribution supplies the complete GUI runtime without downloading GUI test deps.
// Keep this optional smoke separate from the ordinary headless unit suite.
val compileGuiMenuSmoke = tasks.register<JavaCompile>("compileGuiMenuSmoke") {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
    val distribution = providers.gradleProperty("jadxGuiDistribution")
    source("src/guiSmoke/java")
    classpath = files(distribution.orNull)
    destinationDirectory.set(layout.buildDirectory.dir("classes/guiSmoke"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    onlyIf { distribution.isPresent }
}
tasks.register<JavaExec>("guiMenuSmoke") {
    dependsOn(tasks.shadowJar, compileGuiMenuSmoke)
    val distribution = providers.gradleProperty("jadxGuiDistribution")
    onlyIf { distribution.isPresent }
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    classpath = files(distribution.orNull, compileGuiMenuSmoke.flatMap { it.destinationDirectory })
    mainClass.set("MainWindowPluginsMenuSmoke")
    args(layout.buildDirectory.dir("gui-menu-smoke").get().asFile.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,
        rootProject.file("app/src/test/resources/sample.jar").absolutePath)
}
