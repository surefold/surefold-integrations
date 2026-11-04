// The API alone. A service brings its own SDK, exporter and agent; this module
// decides where a Context lives, not what is done with one.
val surefoldCore: String by rootProject.extra

val otelVersion = "1.67.0"

dependencies {
    api("dev.surefold:surefold:$surefoldCore")
    api("io.opentelemetry:opentelemetry-api:$otelVersion")

    // Spans to read, from surefold-otel-test's collector, and not opentelemetry-sdk-testing's: that artifact
    // registers a ContextStorageProvider of its own, and two on the classpath leave this module's storage untested.
    testImplementation(project(":surefold-otel-test"))
    // Actors to send to, for the trace carried across a message (spec 0122).
    testImplementation("dev.surefold:surefold-actor:$surefoldCore")
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.otel.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
