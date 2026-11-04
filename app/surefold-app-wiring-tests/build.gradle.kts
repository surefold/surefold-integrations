// surefold-core's wiring plugin, run by TestKit against apps on this repository's surefold-app modules (surefold-core's
// spec 0302). The checker's reader knows their factories by name, and only a build that compiles a graph using them
// notices its copy of what they need going stale. Unpublished: it has no main code.

val surefoldCore: String by rootProject.extra

/** The modules whose factories the reader knows by name, handed to the project under test as files. */
val surefoldAppUnderTest: Configuration by configurations.creating

/** The plugin, resolved only to learn the version that stands for `surefoldCore`: core's own when it is included. */
val wiringPlugin: Configuration by configurations.creating { isTransitive = false }

/** surefold-app-compiler's sources, where Reader.kt names each factory it knows. */
val readerSources: Configuration by configurations.creating {
    isTransitive = false
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
    }
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
    listOf(
        "surefold-app-pekko", "surefold-app-typesafe", "surefold-app-kotlinx", "surefold-app-openfeature",
        "surefold-app-openfeature-flagd",
    ).forEach { surefoldAppUnderTest(project(":$it")) }
    wiringPlugin("dev.surefold:surefold-app-gradle:$surefoldCore")
    readerSources("dev.surefold:surefold-app-compiler:$surefoldCore")
}

// Where the TestKit build resolves the plugin and its compiler plugin from, as a consumer's build does. With
// ../surefold-core included, its local repository after publishing them there; otherwise GitHub Packages, with the
// `githubPackagesUsername`/`githubPackagesPassword` the build's environment gives, which TestKit inherits.
val core = gradle.includedBuilds.find { it.projectDir.name == "surefold-core" }
val checkerRepo: String =
    core?.projectDir?.resolve("build/repo")?.toURI()?.toString()
        ?: "https://maven.pkg.github.com/surefold/surefold-core"

tasks.test {
    inputs.files(surefoldAppUnderTest).withPropertyName("surefoldAppUnderTest")
    inputs.files(readerSources).withPropertyName("readerSources")
    if (core != null) {
        listOf("surefold-app-compiler", "surefold-compiler", "surefold-app-gradle").forEach {
            dependsOn(core.task(":$it:publishAllPublicationsToLocalRepository"))
        }
    }
    val version = wiringPlugin.incoming.resolutionResult.rootComponent.map { root ->
        root.dependencies.filterIsInstance<ResolvedDependencyResult>().single().selected.moduleVersion!!.version
    }
    inputs.property("checkerVersion", version)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.underTest=" + surefoldAppUnderTest.joinToString(File.pathSeparator) { it.absolutePath },
                "-Dsurefold.checker.repo=$checkerRepo",
                "-Dsurefold.checker.version=" + version.get(),
                "-Dsurefold.checker.readerSources=" + readerSources.singleFile.absolutePath,
            )
        },
    )
    // A TestKit test starts a Gradle build of its own, which on a cold machine is a distribution to download.
    systemProperty("junit.jupiter.execution.timeout.default", "10m")
    systemProperty("surefold.junit.timeout.default", "10m")
}
