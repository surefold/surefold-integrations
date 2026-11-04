// The registry interface and nothing under it. A service brings the registry it
// already has — Prometheus, Datadog, StatsD, OTLP — and this module decides what
// a measurement looks like when it arrives, not where it goes.
//
// Declared at the oldest version this compiles against, for the reason surefold-slf4j
// declares slf4j at 1.7.36: resolution takes the highest asked for, so this can
// only be raised by a consumer and never lowers anyone.
val surefoldCore: String by rootProject.extra

val micrometerVersion = "1.12.0"

dependencies {
    api("dev.surefold:surefold:$surefoldCore")
    api("io.micrometer:micrometer-core:$micrometerVersion")
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.micrometer.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
