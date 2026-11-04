package dev.surefold.app.pekko

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The complete examples in `docs/observability.md`, `docs/arrow.md`, `docs/pekko.md` and `docs/configuration.md`, each
 * compiled on its own: this module's test classpath holds the core, surefold-pekko, surefold-actor-pekko, this module,
 * surefold-arrow and surefold-app-kotlinx, which is all they use, and the serialization plugin is handed to the
 * compiler for the `@Serializable` ones. surefold-core's own guides are compiled there.
 */
class GuideTest {

    private val pages =
        listOf(
            "docs/observability.md" to "observability",
            "docs/arrow.md" to "arrow",
            "docs/pekko.md" to "pekko",
            "docs/configuration.md" to "configuration",
        )

    @TempDir
    lateinit var workspace: File

    private fun page(path: String): String {
        val root = System.getProperty("surefold.app.pekko.repoRoot")
        withClue("the build must pass -Dsurefold.app.pekko.repoRoot; see surefold-app-pekko/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, path).readText()
    }

    /** Every fence marked `<!-- prefix-name -->` on the page, by its marker. */
    private fun fences(path: String, prefix: String): Map<String, String> =
        Regex("""<!-- ($prefix-[a-z-]+) -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(page(path)).associate { it.groupValues[1] to it.groupValues[2] }

    private fun errors(source: String): List<String> {
        val (exit, errors) = EmbeddedKotlin(File(workspace, "${source.hashCode()}").apply { mkdirs() }).compile(source)
        return errors + listOfNotNull(if (exit == ExitCode.OK) null else "exit $exit")
    }

    @Test
    fun `every marked example in the guides compiles against the library`() {
        pages.forEach { (path, prefix) ->
            val marked = fences(path, prefix)
            withClue("$path must carry examples marked <!-- $prefix-… -->, or this test asserts nothing") {
                marked.keys.shouldNotBeEmpty()
            }
            marked.forEach { (marker, source) -> withClue("$path $marker") { errors(source).shouldBeEmpty() } }
        }
    }

    @Test
    fun `an example that no longer matches the library fails to compile`() {
        val (marker, source) = fences("docs/arrow.md", "arrow").entries.first()
        withClue(marker) { errors("$source\nval broken: Int = \"not an Int\"").shouldNotBeEmpty() }
    }

    @Test
    fun `every marker on a page is unique`() {
        pages.forEach { (path, prefix) ->
            val markers = Regex("""<!-- ($prefix-[a-z-]+) -->""").findAll(page(path)).map { it.groupValues[1] }.toList()
            withClue(path) { markers.size shouldBe markers.toSet().size }
        }
    }
}
