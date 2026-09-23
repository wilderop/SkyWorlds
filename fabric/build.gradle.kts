plugins {
    id("fabric-loom") version "1.17.12"
}

base {
    archivesName.set("skyworlds-fabric")
}

version = "3.0.40"
group = "com.example"

loom {
    enableModProvidedJavadoc.set(false)
    decompilers { clear() }
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    targetNamespace.set("named")
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    mappings("net.fabricmc:yarn:1.21.11+build.6:v2")
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.159.0+26.2")
    implementation("redis.clients:jedis:4.4.6")
    include("redis.clients:jedis:4.4.6")
    include("org.apache.commons:commons-pool2:2.11.1")
    include("org.json:json:20231013")
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
