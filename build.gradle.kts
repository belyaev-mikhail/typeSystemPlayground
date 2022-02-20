import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.20-M1"
}

group = "org.jetbrains.kotlin.spec"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += listOf("-Xcontext-receivers")
    }
}

val execute by tasks.creating(JavaExec::class) {
    val main by sourceSets
    classpath = main.runtimeClasspath
    var mainClass by mainClass
    mainClass = "org.jetbrains.kotlin.types.play.MainKt"
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.4")

    testImplementation(kotlin("test"))
}