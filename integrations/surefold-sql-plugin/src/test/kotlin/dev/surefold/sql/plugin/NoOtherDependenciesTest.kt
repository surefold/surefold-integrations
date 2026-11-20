package dev.surefold.sql.plugin

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module may put on a consumer's classpath, stated as a test: the Kotlin standard library alone. The
 * compiler it plugs into is the one already running it, so it is compile-only.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-")

    @Test
    fun `the main runtime classpath is the Kotlin standard library alone, and nothing else`() {
        val raw = System.getProperty("surefold.sql.plugin.runtimeClasspath")
        withClue("the build must pass -Dsurefold.sql.plugin.runtimeClasspath; see this module's build script") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-sql-plugin must stay the Kotlin standard library alone, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
