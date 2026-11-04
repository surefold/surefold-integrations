// The OpenFeature SDK, with its evaluation context bound where a fork and a message can see it.
// It adds the SDK and the slf4j-api the SDK logs through, which NoOtherDependenciesTest asserts.
val surefoldCore: String by rootProject.extra

dependencies {
    api("dev.surefold:surefold:$surefoldCore")
    api("dev.openfeature:sdk:1.23.0")

    // Actors to send to, for the context carried across a message.
    testImplementation("dev.surefold:surefold-actor:$surefoldCore")
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.openfeature.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
