// surefold-app on surefold-cluster: a cluster as a node, joined as config says. The backend is found by name on the
// classpath, so this module brings neither fabric8 nor the AWS SDK; the application adds the one it joins through.
// NoOtherDependenciesTest asserts the runtime classpath.
val surefoldCore: String by rootProject.extra

dependencies {
    api("dev.surefold:surefold-app-actor:$surefoldCore")
    api(project(":surefold-app-typesafe"))
    api("dev.surefold:surefold-cluster:$surefoldCore")
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
    // freePort(): a port below the ephemeral range, never handed out twice in a JVM (spec 0197).
    testImplementation(testFixtures("dev.surefold:surefold-actor-remote:$surefoldCore"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.cluster.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
