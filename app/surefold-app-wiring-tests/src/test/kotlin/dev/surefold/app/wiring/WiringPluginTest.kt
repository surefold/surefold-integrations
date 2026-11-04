package dev.surefold.app.wiring

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.shouldNotContain
import dev.surefold.assert.withClue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * surefold-core's wiring plugin, applied by a build that runs, to graphs on this repository's surefold-app modules.
 * Those modules are handed over as files; the plugin and its compiler plugin are resolved from a repository by
 * coordinates, as a consumer's build resolves them (see this module's build file).
 */
class WiringPluginTest {

    private fun project(dir: File, wiring: String, graph: String) {
        val underTest = System.getProperty("surefold.app.underTest")
        val checker = System.getProperty("surefold.checker.repo")
        val version = System.getProperty("surefold.checker.version")
        withClue("the build must pass -Dsurefold.app.underTest; see surefold-app-wiring-tests/build.gradle.kts") {
            underTest.shouldNotBeNull()
        }
        withClue("the build must pass -Dsurefold.checker.repo; see surefold-app-wiring-tests/build.gradle.kts") {
            checker.shouldNotBeNull()
        }
        val jars = underTest!!.split(File.pathSeparator).joinToString(", ") { "\"$it\"" }
        // GitHub Packages asks for a token even to read: the one in the environment, which TestKit inherits.
        val credentials = if (checker!!.startsWith("https:")) "credentials(PasswordCredentials::class)" else ""
        val repository = """maven { name = "githubPackages"; url = uri("$checker"); $credentials }"""

        File(dir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    $repository
                    gradlePluginPortal()
                }
                // GitHub Packages holds dev.surefold's artifacts, not the plugin's marker: named by its module instead.
                resolutionStrategy.eachPlugin {
                    if (requested.id.id == "dev.surefold.wiring") useModule("dev.surefold:surefold-app-gradle:$version")
                }
            }

            rootProject.name = "under-test"
            """.trimIndent(),
        )
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.4.10"
                id("dev.surefold.wiring") version "$version"
            }

            // Where the compiler plugin comes from; the Kotlin plugin resolves it by coordinates.
            repositories {
                $repository
                mavenCentral()
            }

            dependencies { implementation(files($jars)) }

            $wiring
            """.trimIndent(),
        )
        source(dir, "main", "Under.kt", graph)
    }

    private fun source(dir: File, set: String, name: String, text: String) {
        val file = File(dir, "src/$set/kotlin/$name")
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun compile(dir: File) =
        GradleRunner.create().withProjectDir(dir).withArguments("compileKotlin", "--stacktrace").build()

    @Test
    fun `the reader's copy of what surefold's own factories need has not gone stale`(@TempDir dir: File) {
        project(dir, "surefoldWiring { verbose = true }", LIBRARY)

        val result = compile(dir)

        withClue("actor takes an ActorSystem, config a Config, and loadedConfig gives its Layered too") {
            result.output shouldContain
                "provides [ActorRef<Ping>, ActorSystem, Config, Layered, Settings], and is short of []"
        }
    }

    @Test
    fun `surefold-app-kotlinx's config is read from the reader's list too`(@TempDir dir: File) {
        project(dir, "surefoldWiring { verbose = true }", LIBRARY_KOTLINX)

        val result = compile(dir)

        withClue("config with no block takes a Config, as the block form does") {
            result.output shouldContain "provides [Config, Layered, Settings], and is short of []"
        }
        withClue("the Config a source provides is its Layered's merge, so reaching one reaches both") {
            result.output shouldNotContain "nothing reaches Layered"
        }
    }

    @Test
    fun `a secret read as a string in main code is named, with secret beside it`(@TempDir dir: File) {
        project(dir, "", SOUND)
        source(dir, "main", "Waits.kt", SECRETS)

        val result = compile(dir)

        result.output shouldContain "string(\"db.password\") reads a secret as a String that prints itself"
        result.output shouldContain "read it with secret(\"db.password\")"
        withClue("passwordPolicy is not a password, and a path the compiler cannot read is left alone") {
            result.output.split("reads a secret").size shouldBe 2
        }
    }
}

private const val SOUND = """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.single

class Pump

object Under : SurefoldApp<Pump>() {
    override val module: Module = single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/**
 * Every factory here the reader knows by name, compiled against the real modules.
 *
 * The reader holds its own copy of what each of these takes — `actor` an `ActorSystem`, `config` a
 * `Config`, `loadedConfig` nothing — and this is the only build that would notice that copy going
 * stale. Change one of those in the library and this test says so.
 */
private const val LIBRARY = """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.single
import dev.surefold.app.pekko.actor
import dev.surefold.app.typesafe.config
import dev.surefold.app.typesafe.loadedConfig
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors

class Ping
class Settings(val port: Int)

object Under : SurefoldApp<Settings>() {
    override val module: Module =
        single<ActorSystem> { ActorSystem.create("under") } +
            loadedConfig() +
            config<Settings>("under") { Settings(int("port")) } +
            actor<Ping>("ping") { Behaviors.empty<Ping>() }
    override fun AppScope.run(root: Settings) = Unit
}
"""

/**
 * surefold-app-kotlinx's config, compiled against the real module; only the reader runs, so no serialization plugin.
 */
private const val LIBRARY_KOTLINX = """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.kotlinx.config
import dev.surefold.app.typesafe.loadedConfig

class Settings(val port: Int)

object Under : SurefoldApp<Settings>() {
    override val module: Module = loadedConfig() + config<Settings>("under")
    override fun AppScope.run(root: Settings) = Unit
}
"""

/** One secret by its last segment, one name that only starts like one, and a path that is not a literal. */
private const val SECRETS = """
import dev.surefold.app.typesafe.config

data class Db(val password: String, val policy: String, val other: String)

val path = "db.password"

val db = config<Db>("app") { Db(string("db.password"), string("passwordPolicy"), string(path)) }
"""
