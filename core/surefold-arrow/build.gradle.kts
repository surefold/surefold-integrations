// Surefold's Either, NonEmptyList and Fail, converted to and from Arrow's, for a codebase already on Arrow.
// It adds arrow-core and nothing else, which NoOtherDependenciesTest asserts.
val surefoldCore: String by rootProject.extra

dependencies {
    api("dev.surefold:surefold:$surefoldCore")
    api("io.arrow-kt:arrow-core:2.1.2")
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.arrow.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
