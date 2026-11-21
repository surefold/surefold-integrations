package dev.surefold.sql

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: surefold and the Kotlin standard library. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold")

    @Test
    fun `the main runtime classpath is surefold and the Kotlin standard library, and nothing else`() {
        val raw = System.getProperty("surefold.sql.runtimeClasspath")
        withClue("the build must pass -Dsurefold.sql.runtimeClasspath; see surefold-sql/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-sql must stay surefold and the Kotlin standard library, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
