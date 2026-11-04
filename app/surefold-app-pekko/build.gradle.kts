val surefoldCore: String by rootProject.extra

val pekkoVersion = "1.2.1"
val scalaBinary = "2.13"

dependencies {
    api("dev.surefold:surefold-app:$surefoldCore")
    // `ask`, and with it pekko-actor-typed, lives in surefold-actor-pekko (spec 0058).
    api(project(":surefold-actor-pekko"))

    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_$scalaBinary:$pekkoVersion")
    // The Kotlin compiler in the test JVM, so the guides' examples are compiled
    // rather than believed.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    // For the configuration guide's examples, which read a section from a @Serializable class.
    testImplementation(project(":surefold-app-kotlinx"))
    // For the flags guide, which declares and reads flags over the OpenFeature SDK.
    testImplementation(project(":surefold-openfeature"))
    // For the migration page's examples, which convert to and from Arrow.
    testImplementation(project(":surefold-arrow"))
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
    // CacheCallerCompatibilityTest compiles a 0.16 caller of surefold-cache against today's.
    testImplementation("dev.surefold:surefold-cache:$surefoldCore")
}

// The serialization plugin, handed to the embedded compiler as a reader's build applies it, so a
// @Serializable example is checked rather than passed through as a bare annotation.
val serializationPlugin: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    serializationPlugin("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.10")
}

val repoRoot = rootProject.projectDir.absolutePath

// The pages GuideTest compiles, declared so that editing one re-runs the tests rather than leaving a stale answer behind.
val documentedRecipes = files(
    rootProject.layout.projectDirectory.file("docs/observability.md"),
    rootProject.layout.projectDirectory.file("docs/arrow.md"),
    rootProject.layout.projectDirectory.file("docs/pekko.md"),
    rootProject.layout.projectDirectory.file("docs/configuration.md"),
    rootProject.layout.projectDirectory.file("docs/flags.md"),
)

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    inputs.files(documentedRecipes).withPropertyName("documentedRecipes")
    inputs.files(serializationPlugin).withPropertyName("serializationPlugin")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.pekko.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dsurefold.app.pekko.repoRoot=$repoRoot",
                "-Dsurefold.app.pekko.serializationPlugin=" + serializationPlugin.singleFile.absolutePath,
            )
        },
    )
}
