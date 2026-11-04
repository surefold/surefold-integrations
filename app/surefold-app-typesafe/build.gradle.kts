// Typesafe Config itself, not a view of it. Everything a HOCON file can say
// stays sayable: substitution, merging, lists, objects, durations, memory sizes.
val surefoldCore: String by rootProject.extra

val typesafeVersion = "1.4.3"

dependencies {
    api("dev.surefold:surefold-app:$surefoldCore")
    api("com.typesafe:config:$typesafeVersion")

    // The checker's copy of Secrets.default's names, for SecretNamesSuite. The checker's own classes reach
    // the compiler's, which a test outside the compiler has to bring.
    testImplementation("dev.surefold:surefold-app-compiler:$surefoldCore")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${rootProject.extra["surefoldKotlinVersion"]}")
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
}

// The page ReportedSuite holds to what the report answers, declared so editing it re-runs the test.
val documentedReport = files(rootProject.layout.projectDirectory.file("docs/configuration.md"))

tasks.test {
    inputs.files(documentedReport).withPropertyName("documentedReport")
    // What LoadedLayersSuite forces on top once -Dconfig.override_with_env_vars asks it to.
    environment("CONFIG_FORCE_surefold_layered_forced", "from-env")
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.typesafe.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dsurefold.app.typesafe.repoRoot=${rootProject.projectDir.absolutePath}",
            )
        },
    )
}
