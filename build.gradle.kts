plugins {
    // Share the Kotlin plugin classloader across modules; no plugin is applied to the root.
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    tasks.withType<Jar>().configureEach {
        manifest.attributes("Implementation-Version" to project.version.toString())
    }
}

// One release directory shared by local verification and CI; module outputs remain internal.
tasks.register<Sync>("releaseArtifacts") {
    dependsOn(":app:shadowJar", ":gui-plugin:shadowJar")
    from(project(":app").layout.buildDirectory.file("libs/jadx-mcp.jar"))
    from(project(":gui-plugin").layout.buildDirectory.file("libs/jadx-mcp-gui-plugin.jar"))
    into(layout.buildDirectory.dir("release"))
}
