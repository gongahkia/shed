import java.io.File
plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.2"
    application
}
repositories { mavenCentral(); google() }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(files("../../target/shed-2.0.0.jar"))
    implementation(compose.desktop.currentOs)
}
application { mainClass.set("shed.LabHost") }
tasks.register("writeClasspath") {
    dependsOn("jar")
    doLast {
        // Shed's fat JAR also contains an older Kotlin stdlib for its terminal dependency.
        // Compose requires the pinned newer runtime first. Keep this override isolated here.
        val runtime = (sourceSets.main.get().runtimeClasspath.files.filter { it.isFile } + tasks.named<Jar>("jar").get().archiveFile.get().asFile).sortedBy {
            if (it.name.startsWith("kotlin-stdlib")) "0" + it.name else "1" + it.name
        }
        layout.buildDirectory.file("runtime-classpath.txt").get().asFile.writeText(runtime.joinToString(File.pathSeparator))
        layout.buildDirectory.file("runtime-artifacts.txt").get().asFile.writeText(runtime.joinToString("\n") { "${it.length()} ${it.name}" })
    }
}
