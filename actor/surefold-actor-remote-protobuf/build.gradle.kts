// Messages of surefold-actor-remote in Protobuf (spec 0071): a codec for a generated message, for a protocol of
// several, and for an ask whose request is one. NoOtherDependenciesTest asserts the runtime classpath.
val surefoldCore: String by rootProject.extra

dependencies {
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
    // freePort(): a port below the ephemeral range, never handed out twice in a JVM (spec 0197).
    testImplementation(testFixtures("dev.surefold:surefold-actor-remote:$surefoldCore"))
    api("dev.surefold:surefold-actor-remote:$surefoldCore")
    api("com.google.protobuf:protobuf-java:4.36.2")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.actor.remote.protobuf.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
