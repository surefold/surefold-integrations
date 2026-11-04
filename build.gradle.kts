plugins {
    kotlin("jvm") version "2.4.10" apply false
    // @Serializable classes: surefold-actor-remote-kotlinx's tests, and the services that use it (spec 0093).
    kotlin("plugin.serialization") version "2.4.10" apply false
    // A failed `expect` in surefold-junit's suites draws its values (spec 0175).
    kotlin("plugin.power-assert") version "2.4.10" apply false
    id("com.diffplug.spotless") version "8.10.0"
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    // The version comes from the nearest `v` tag rather than a property, so
    // cutting a release is `git tag v0.1.0 && git push --tags` and nothing
    // else. An untagged commit is a -SNAPSHOT of the next one.
    id("pl.allegro.tech.build.axion-release") version "1.21.3"
    // So the root project has `check`/`build`, and the scripts formatted here
    // are covered by a plain `./gradlew build` like everything else.
    base
    // Publishing to the Central Portal, which is the only way in since OSSRH
    // closed. It wraps `maven-publish` and `signing`, so neither is applied
    // here directly.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    // Renders the KDoc into the javadoc jar the published module ships.
    id("org.jetbrains.dokka") version "2.1.0" apply false
}

scmVersion {
    tag {
        prefix.set("v")
        // Before this repository's first tag: these modules were released from surefold-core up to 0.18.0 under the
        // same coordinates, so a snapshot here has to sort above that rather than start again at 0.1.0.
        initialVersion { _, _ -> "0.18.1" }
    }
    // Plain `0.1.0-SNAPSHOT` off a tag rather than axion's default, which
    // decorates it with the branch name. The README tells a contributor to
    // install locally and depend on the version; that version should not
    // change with the branch they happen to be on.
    versionCreator("simple")
}

// Read once, at configuration time: `scmVersion.version` shells out to git.
val scmVer: String = scmVersion.version

/**
 * ktlint rather than ktfmt: ktfmt reflows, and this codebase is hand-laid-out
 * on purpose — banner comments, aligned trailing comments, KDoc written as
 * prose. The rules that make ktlint disagree with any of that are turned off
 * in `.editorconfig`, which is also where the line length lives.
 */
val ktlintVersion = "1.8.0"

/**
 * The Kotlin this build compiles with, for the two modules that compile against the compiler itself.
 *
 * Written once: a plugin built against one compiler and loaded into another fails at a consumer's
 * compile, or quietly in their editor, and a version number repeated in three build scripts is three
 * chances for that to happen on a bump.
 */
val kotlinVersion: String = "2.4.10"

// Spotless does not pick these up from .editorconfig for every source set, so
// they are handed to the ktlint step directly. The reasoning for each lives in
// .editorconfig beside the rest, which is also what the IDE reads.
val ktlintOverrides = mapOf("ktlint_standard_kdoc" to "disabled")

// The root project builds nothing, but Spotless resolves ktlint here.
repositories { mavenCentral() }

spotless {
    kotlinGradle {
        // settings.gradle.kts and this file. The module formats its own build
        // script in the `subprojects` block below.
        target("*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
}

/** One line per module, so a Maven search result says what the artifact is. */
val moduleDescriptions = mapOf(
    "surefold-pekko" to
        "surefold on Pekko: a dispatcher as the executor, and Pekko's stages awaited from a virtual thread.",
    "surefold-stream-pekko" to
        "surefold-stream on Pekko Streams: the backend, and the operators that take Pekko's types.",
    "surefold-arrow" to
        "Surefold's Either, NonEmptyList and Fail to and from Arrow's, for a codebase already on Arrow.",
    "surefold-actor-pekko" to
        "A Pekko typed actor from Kotlin: ask one on a virtual thread, without the wiring graph.",
    "surefold-app-pekko" to "surefold-app on Pekko: an actor is a node, spawned in order and stopped in reverse.",
    "surefold-otel" to "surefold on OpenTelemetry: a Context that crosses a fork, so a trace survives a parMap.",
    "surefold-jdbc-otel" to "JDBC tracing with automatic query names and protective OpenTelemetry metadata defaults.",
    "surefold-jdbc-otel-bench" to "The reproducible JDBC tracing workload and telemetry pipeline for spec 0266.",
    "surefold-otel-test" to
        "For tests of a traced service: a tracer that keeps the spans it ends, beside surefold-otel's storage.",
    "surefold-slf4j" to "surefold's log lines through SLF4J, with a fork's annotations in the MDC.",
    "surefold-micrometer" to "surefold's metrics into a Micrometer registry.",
    "surefold-app-liquibase" to
        "surefold-app on Liquibase: a migration is a node, and reading the database depends on it.",
    "surefold-app-typesafe" to
        "surefold-app on Typesafe Config: a section is a node, and a bad file says every fault at once.",
    "surefold-app-kotlinx" to
        "surefold-app-typesafe from a @Serializable class: the section read from the class itself.",
    "surefold-kafka" to
        "Kafka as a surefold Stream on any backend: a record is committed only after the work on it is done.",
    "surefold-kafka-pekko" to
        "surefold-kafka through Pekko's own Kafka connector: prefetch, batched commits, a draining stop.",
    "surefold-kafka-test" to
        "A Kafka broker in the test JVM for a service's own tests: no Docker, and helpers to read what it did.",
    "surefold-sqs" to
        "SQS as a surefold Stream on any backend: a message is deleted only after the work on it is done.",
    "surefold-sns" to
        "SNS publishing from a surefold Stream on any backend: an element passes on once SNS has its message.",
    "surefold-sqs-test" to
        "An SQS queue in the test JVM on ElasticMQ for a service's own tests: no Docker, and helpers to read it.",
    "surefold-sns-test" to
        "SNS topics in the test JVM, delivering to ElasticMQ queues, for a service's own tests: no Docker.",
    "surefold-sqs-pekko" to
        "surefold-sqs through Pekko's own SQS connector: parallel receives, batched asynchronous deletes, a drain.",
    "surefold-actor-remote-avro" to
        "surefold-actor-remote's messages in Avro: specific records, resolved across versions, and asks.",
    "surefold-actor-remote-protobuf" to
        "surefold-actor-remote's messages in Protobuf: generated messages, protocols of them, and asks.",
    "surefold-actor-remote-kotlinx" to
        "surefold-actor-remote's messages, events and snapshots from @Serializable data classes, " +
        "in ProtoBuf by default.",
    "surefold-cluster-aws" to
        "surefold-cluster on AWS: seeds from Cloud Map or ECS, and a DynamoDB lease to break an even split.",
    "surefold-cluster-kubernetes" to
        "surefold-cluster on Kubernetes: seeds from the pods API, and a Lease to break an even split.",
    "surefold-app-cluster" to "surefold-app on surefold-cluster: a cluster is a node, joined as a HOCON section says.",
    "surefold-openfeature" to
        "surefold on OpenFeature: who a flag is evaluated for, bound once and read in every fork and actor.",
    "surefold-app-openfeature" to
        "surefold-app on OpenFeature: the provider is a node, set before any flag is read and shut down on release.",
    "surefold-app-openfeature-flagd" to
        "surefold-app-openfeature on flagd: flags in flagd's schema as a section of the service's own HOCON.",
)

// Measured, not tested: a benchmark has no assertions for a coverage floor to count.
val benchmarkModules =
    setOf(
        "surefold-stream-benchmarks", "surefold-actor-benchmarks", "surefold-kafka-benchmarks",
        "surefold-jdbc-otel-bench",
    )

// Built and tested, never published: surefold-stream-parity runs every backend's tests against each other.
val unpublished = setOf("surefold-stream-parity") + benchmarkModules

// The modules a snapshot or a release publishes, one per line: snapshot.yml checks each is on GitHub Packages after.
tasks.register("publishedModules") {
    val names = subprojects.map { it.name }.filterNot { it in unpublished }.sorted()
    doLast { names.forEach(::println) }
}

/** The surefold-core snapshot this build is pinned to (gradle.properties), or its source beside this checkout. */
val surefoldCore: String = providers.gradleProperty("surefoldCore").get()

/** Whether settings.gradle.kts found ../surefold-core and included it, so its projects replace the pinned artifacts. */
val coreFromSource: Boolean = gradle.includedBuilds.any { it.projectDir.name == "surefold-core" }

// The floor is a ratchet against regression, not a target to code towards — a
// test written to move a percentage is worth less than no test at all.
kover {
    reports {
        total {
            verify {
                rule {
                    minBound(90)
                }
            }
        }
    }
}

dependencies {
    subprojects.filterNot { it.name in benchmarkModules }.forEach { kover(project(it.path)) }
}

// A floor nobody runs is not a floor: `./gradlew build` checks it.
tasks.named("check") {
    dependsOn("koverVerify")
}

extra["surefoldKotlinVersion"] = kotlinVersion
extra["surefoldCore"] = surefoldCore

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories {
        mavenCentral()
        // surefold-core's snapshots (its spec 0281). GitHub Packages asks for a token even to read a public package:
        // a classic personal access token with read:packages, as `githubPackagesUsername`/`githubPackagesPassword`.
        // Not with ../surefold-core beside this checkout, whose source stands in for every one of them.
        if (!coreFromSource) {
            maven {
                name = "githubPackages"
                url = uri("https://maven.pkg.github.com/surefold/surefold-core")
                credentials(PasswordCredentials::class)
                content { includeGroup("dev.surefold") }
            }
        }
    }

    // The namespace verified on the Central Portal, against the GitHub
    // account it names.
    group = "dev.surefold"
    version = scmVer

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        // 25: the first LTS where a virtual thread blocked in `synchronized` no longer pins its carrier, which
        // every library here runs on.
        jvmToolchain(25)

        compilerOptions {
            // A warning nobody reads is a defect nobody fixed; the build says so.
            allWarningsAsErrors.set(true)
            // Without this, every interface with a method body also gets a
            // `DefaultImpls` class holding a copy of it, and both are published
            // surface the BCV gate then has to keep. The interfaces here
            // already emit real JVM default methods, so the copies are the ABI
            // of a compatibility mode with nothing behind it: Kotlin callers
            // never reach them, and the Java caller they exist for would have
            // had to write `JsonValue.DefaultImpls.x(this)` by hand.
            jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        }
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // A module's tests are surefold-junit suites (surefold-core's spec 0197), and a failed `expect` draws its values.
    if (name !in benchmarkModules) {
        apply(plugin = "org.jetbrains.kotlin.plugin.power-assert")
        dependencies { "testImplementation"("dev.surefold:surefold-junit:$surefoldCore") }
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        extensions.configure<org.jetbrains.kotlin.powerassert.gradle.PowerAssertGradleExtension> {
            functions.add("dev.surefold.junit.expect")
            includedSourceSets.add("test")
        }
        // The checker reports the standard library's dropped must-use values, and only under it does `val _ =` compile.
        tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
            compilerOptions.freeCompilerArgs.add("-Xreturn-value-checker=check")
        }
    }

    tasks.withType<Test>().configureEach {
        // `-PincludeTags=io,db` runs only the tests tagged so: Jupiter's @Tag and surefold-junit's tag() alike.
        val included = providers.gradleProperty("includeTags").orNull?.split(',')?.filter { it.isNotBlank() }
        useJUnitPlatform { included?.let { includeTags(*it.toTypedArray()) } }

        systemProperty("junit.jupiter.execution.timeout.default", "60s")
        // The same default for surefold-junit suites, which Jupiter's property does not reach (spec 0193).
        systemProperty("surefold.junit.timeout.default", "60s")
    }

    if (name !in benchmarkModules) {
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }

    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        // The deviations live in one file for the whole build; see its header.
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }
    // The plain `detekt` task cannot see types, so the rules that need them —
    // ForbiddenMethodCall, for one — are silently skipped there. `check`
    // depends on the type-resolving pair instead.
    tasks.named("check") { dependsOn("detektMain", "detektTest") }
    // A source detekt cannot compile is analysed without its types, and detekt
    // only prints a count of the errors and passes: it has no setting to fail
    // instead, so each task watches its own output. Detekt runs on the task's
    // thread, and other tasks print to the same listeners in parallel, so only
    // that thread's lines count. The usual cause is a call to code a compiler
    // plugin generates, such as `Paid.serializer()`: detekt runs no compiler
    // plugins, and `serializer<Paid>()` resolves without one.
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        val running = java.util.concurrent.atomic.AtomicReference<Thread>()
        val compilerErrors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        doFirst { running.set(Thread.currentThread()) }
        logging.addStandardOutputListener {
            if (Thread.currentThread() == running.get() && "compiler errors found during analysis" in it) {
                compilerErrors += it.toString().trim()
            }
        }
        doLast {
            if (compilerErrors.isNotEmpty()) {
                throw GradleException(
                    "$path: ${compilerErrors.joinToString()} Set `debug = true` on the task to see them.",
                )
            }
        }
    }

    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
    }

    if (name in unpublished) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")
    apply(plugin = "org.jetbrains.dokka")

    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        // Sources are not an optional extra for a library someone else has to
        // debug, and Maven Central will not accept a release without a javadoc
        // jar. Dokka fills it: an empty jar leaves javadoc.io blank, which puts
        // the KDoc out of reach of anyone who has not cloned the repository.
        run {
            configure(
                com.vanniktech.maven.publish.KotlinJvm(
                    javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                    sourcesJar = true,
                ),
            )
        }

        pom {
            name.set(this@subprojects.name)
            description.set(moduleDescriptions[this@subprojects.name] ?: "Part of Surefold.")
            url.set("https://github.com/surefold/surefold-integrations")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("matthewjones372")
                    name.set("Matt Jones")
                }
            }
            scm {
                url.set("https://github.com/surefold/surefold-integrations")
                connection.set("scm:git:https://github.com/surefold/surefold-integrations.git")
                developerConnection.set("scm:git:ssh://git@github.com/surefold/surefold-integrations.git")
            }
        }
    }

    // A tag gives axion a version with no `-SNAPSHOT`, which makes Gradle's signing
    // required — and `build` signs the publications, so the gate starts depending on
    // a key. The release job has one; the gate build and every pull request do not,
    // and handing them the signing key to run a task nobody publishes from would be
    // the wrong way round. Signed where there is something to sign with, skipped
    // where there is not, and `publishToMavenCentral` still fails loudly on an
    // unsigned artifact.
    tasks.withType<Sign>().configureEach {
        onlyIf { !(project.findProperty("signingInMemoryKey") as? String).isNullOrBlank() }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            // `./gradlew publishToMavenLocal` for a local try-out, and
            // `publishAllPublicationsToLocalRepository` for something to
            // inspect without installing it.
            maven {
                name = "local"
                url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI()
            }
            // Snapshots (spec 0281): snapshot.yml runs `publishAllPublicationsToGithubPackagesRepository` with the
            // workflow's token as `githubPackagesUsername`/`githubPackagesPassword`. Releases stay on Central.
            maven {
                name = "githubPackages"
                url = uri("https://maven.pkg.github.com/surefold/surefold-integrations")
                credentials(PasswordCredentials::class)
            }
        }
        relocatedFromOldGroup(this@subprojects.name, version.toString())
    }
    // The relocation POMs are for Central: GitHub Packages, which keeps snapshots, takes `dev.surefold` only.
    tasks.withType<PublishToMavenRepository>().configureEach {
        onlyIf("only dev.surefold goes to GitHub Packages") { task ->
            val publish = task as PublishToMavenRepository
            publish.repository.name != "githubPackages" || publish.publication.groupId == "dev.surefold"
        }
    }
    // The Central Portal refuses a deployment with files for two namespaces, and a Gradle run uploads one
    // deployment, so release.yml publishes to Central once per namespace: `dev.surefold` by default, then the
    // relocation POMs with `-PcentralNamespace=io.github.matthewjones372`.
    val centralNamespace = providers.gradleProperty("centralNamespace").getOrElse("dev.surefold")
    tasks.withType<PublishToMavenRepository>().configureEach {
        onlyIf("only $centralNamespace goes to Central in this run") { task ->
            val publish = task as PublishToMavenRepository
            val group = publish.publication.groupId
            val inNamespace = group == centralNamespace || group.startsWith("$centralNamespace.")
            publish.repository.name != "mavenCentral" || inNamespace
        }
    }
}

/**
 * A POM-only publication at the coordinates surefold had up to 0.17, relocating to the same module under
 * `dev.surefold` (spec 0277). Maven and Gradle follow it, so a build still on `io.github.matthewjones372` resolves
 * the new module.
 */
fun PublishingExtension.relocatedFromOldGroup(module: String, version: String) {
    publications.register<MavenPublication>("relocation") {
        groupId = "io.github.matthewjones372"
        artifactId = module
        this.version = version
        pom {
            packaging = "pom"
            distributionManagement {
                relocation {
                    groupId.set("dev.surefold")
                    message.set("surefold moved to dev.surefold in 0.18.0; packages and plugin IDs moved with it.")
                }
            }
        }
    }
}
