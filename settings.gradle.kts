// Where a Java toolchain comes from when the machine does not already have it.
// Gradle deprecated auto-provisioning without a declared resolver, so the build
// says which one it uses rather than relying on a default that is going away.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "surefold-integrations"

// Each module lives in its family's folder but keeps a flat project path, as in surefold-core (its spec 0139).
fun module(group: String, name: String) {
    include(name)
    project(":$name").projectDir = file("$group/$name")
}

module("core", "surefold-arrow")

module("stream", "surefold-stream-pekko")
module("stream", "surefold-stream-parity")

module("actor", "surefold-actor-remote-protobuf")
module("actor", "surefold-actor-remote-avro")
module("actor", "surefold-actor-remote-kotlinx")

module("cluster", "surefold-cluster-kubernetes")
module("cluster", "surefold-cluster-aws")

module("app", "surefold-app-pekko")
module("app", "surefold-app-openfeature")
module("app", "surefold-app-openfeature-flagd")
module("app", "surefold-app-liquibase")
module("app", "surefold-app-typesafe")
module("app", "surefold-app-kotlinx")
module("app", "surefold-app-cluster")

module("integrations", "surefold-pekko")
module("integrations", "surefold-actor-pekko")
module("integrations", "surefold-otel")
module("integrations", "surefold-otel-test")
module("integrations", "surefold-jdbc-otel")
module("integrations", "surefold-openfeature")
module("integrations", "surefold-slf4j")
module("integrations", "surefold-micrometer")
module("integrations", "surefold-kafka")
module("integrations", "surefold-kafka-pekko")
module("integrations", "surefold-kafka-test")
module("integrations", "surefold-sqs")
module("integrations", "surefold-sns")
module("integrations", "surefold-sns-test")
module("integrations", "surefold-sqs-test")
module("integrations", "surefold-sqs-pekko")

module("bench", "surefold-stream-benchmarks")
module("bench", "surefold-actor-benchmarks")
module("bench", "surefold-kafka-benchmarks")
module("bench", "surefold-jdbc-otel-bench")

// Upstream source beside this checkout, when it is there (surefold-core's spec 0302): Gradle substitutes its projects
// for the `dev.surefold` artifacts pinned in gradle.properties, so a change to core reaches the tests here without
// publishing. CI never checks it out, so it always builds against the pins; `-Psurefold.upstreamSource=false` does
// the same with a checkout beside this one.
if (providers.gradleProperty("surefold.upstreamSource").orNull != "false") {
    listOf("../surefold-core").filter { file(it).resolve("settings.gradle.kts").exists() }.forEach { includeBuild(it) }
}
