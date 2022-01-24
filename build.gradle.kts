plugins {
    kotlin("jvm") version "1.6.0"
}

group = "org.jetbrains.kotlin.spec"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.4")

    testImplementation(kotlin("test"))
}