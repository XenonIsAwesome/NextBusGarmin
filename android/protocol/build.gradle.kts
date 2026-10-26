plugins {
    kotlin("jvm")
    id("com.google.protobuf")
}

val protobufVersion = "4.28.3"

kotlin { jvmToolchain(21) }

sourceSets.main {
    // protocol/ at the repo root is the single source of truth for the schema.
    proto.srcDir("$rootDir/../protocol")
}

dependencies {
    api("com.google.protobuf:protobuf-javalite:$protobufVersion")
    api("com.google.protobuf:protobuf-kotlin-lite:$protobufVersion")
    testImplementation(kotlin("test"))
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                getByName("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

tasks.test { useJUnitPlatform() }

// Regenerates protocol/vectors/ from the Kotlin encoder (see protocol/PROTOCOL.md).
tasks.register<JavaExec>("generateVectors") {
    group = "protocol"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.nextbus.protocol.GenerateVectorsKt")
    args("$rootDir/../protocol")
}
