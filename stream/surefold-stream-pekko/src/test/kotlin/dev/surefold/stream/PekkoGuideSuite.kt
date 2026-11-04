package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.right
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import dev.surefold.junit.withTempDirectory
import org.apache.pekko.Done
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.stream.javadsl.Sink
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The stream examples in `docs/pekko.md`, compiled out of the page and the pipeline run beside it. They came from
 * surefold-core's `docs/stream.md` with surefold-core's spec 0302; their markers start `stream-pekko-` so that
 * surefold-app-pekko's `GuideTest`, which compiles each `pekko-` fence on its own, leaves these to this suite.
 */
object PekkoGuideSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-pekko-guide-test")

    /** One description run on Pekko Streams. */
    private const val BACKEND = "<!-- stream-pekko-backend -->"

    /** The three fences of the before-and-after: the fixtures, and the pipeline written each way. */
    private const val FIXTURES = "<!-- stream-pekko-fixtures -->"
    private const val BEFORE = "<!-- stream-pekko-before -->"
    private const val AFTER = "<!-- stream-pekko-after -->"

    /** The complete pipeline, run below line for line. */
    private const val PIPELINE = "<!-- stream-pekko-pipeline -->"

    private val onThePage = listOf(BACKEND, FIXTURES, BEFORE, AFTER, PIPELINE)

    private fun marked(marker: String) = Regex("""$marker\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    /** The build hands the root over, so the page read here is the one the repository ships. */
    private fun guide(then: (File) -> Unit) {
        val root: String? = System.getProperty("surefold.stream.repoRoot")
        return withClue("the build must pass -Dsurefold.stream.repoRoot; see surefold-stream-pekko/build.gradle.kts") {
            root.shouldNotBeNull { path -> then(File(path, "docs/pekko.md")) }
        }
    }

    private fun examples(page: File, marker: String): List<String> =
        marked(marker).findAll(page.readText()).map { match -> match.groupValues[1] }.toList()

    private fun marksOne(page: File, marker: String) =
        withClue("$page must hold one kotlin fence marked $marker") {
            examples(page, marker).size shouldBe 1
        }

    /** The one fence [page] marks with [marker], checked by [then] only if there is exactly one. */
    private fun only(page: File, marker: String, then: (String) -> Unit) {
        marksOne(page, marker)
        examples(page, marker).singleOrNull()?.let(then)
    }

    /** Compiled in a workspace of its own, and removed after. */
    private fun compiles(source: String) {
        val (exit, errors) = withTempDirectory("surefold-stream-pekko-guide") { workspace ->
            EmbeddedKotlin(workspace.toFile()).compile(source)
        }

        return withClue(errors.joinToString("\n")) {
            errors.shouldBeEmpty()
            exit shouldBe ExitCode.OK
        }
    }

    private data class Row(val id: Int, val customer: String?)

    private data class Customer(val id: Int, val name: String)

    private data class Receipt(val id: Int)

    private sealed interface IngestError

    private data class NoCustomer(val id: Int) : IngestError

    private data class Declined(val id: Int) : IngestError

    /** The document's ledger, declining the even ids so that the twin runs both branches. */
    private class Ledger {
        fun settle(customer: Customer): CompletionStage<Either<Declined, Receipt>> =
            if (customer.id % 2 == 0) {
                CompletableFuture.completedFuture(Either.Left(Declined(customer.id)))
            } else {
                CompletableFuture.completedFuture(Either.Right(Receipt(customer.id)))
            }
    }

    private val ledger = Ledger()

    private val rows = listOf(
        Row(1, "ada"),
        Row(2, "grace"),
        Row(3, "alan"),
        Row(4, "edsger"),
        Row(5, "barbara"),
    )
    init {
        test("the guide marks one fence for each stream example it compiles") { _ ->
            guide { page -> onThePage.forAll { marker -> marksOne(page, marker) } }
        }

        test("a description run on Pekko Streams compiles") { _ ->
            guide { page -> only(page, BACKEND) { compiles(it) } }
        }

        test("the pipeline written each way compiles against what it is written on") { _ ->
            guide { page ->
                only(page, FIXTURES) { fixtures ->
                    only(page, BEFORE) { before -> compiles("$fixtures\n\n$before") }
                    only(page, AFTER) { after -> compiles("$fixtures\n\n$after") }
                }
            }
        }

        test("the complete pipeline compiles against the library") { _ ->
            guide { page -> only(page, PIPELINE) { compiles(it) } }
        }

        // The same pipeline, line for line, run: what the guide shows is compiled and also true.
        test("the pipeline the guide shows sends the receipts and diverts the declines") { pekko ->
            val system: ClassicActorSystemProvider = pekko.system
            val declines = ConcurrentLinkedQueue<Declined>()
            val bothDeclines = CountDownLatch(2)
            val declinedSink = Sink.foreach<Declined> { declined ->
                declines.add(declined)
                bothDeclines.countDown()
            }
            val receipts = ConcurrentLinkedQueue<Receipt>()
            val receiptSink = Sink.foreach<Receipt> { receipt -> receipts.add(receipt) }

            val settled: Either<IngestError, Done> = attempt {
                awaitExit(
                    Stream.from(rows)
                        .mapOrFail { row -> Customer(row.id, row.customer ?: fail(NoCustomer(row.id))) }
                        .mapAsync(4) { customer -> ledger.settle(customer) }
                        .divertLefts(to = declinedSink)
                        .runWith(receiptSink)
                        .run(system),
                )
            }

            settled shouldBe Done.getInstance().right()
            withClue("the run ends when its own sink does, so every receipt has been sent by now") {
                receipts.toList() shouldBe listOf(Receipt(1), Receipt(3), Receipt(5))
            }
            withClue("the diverted branch is a branch of its own and can outlive the run") {
                bothDeclines.await(30, TimeUnit.SECONDS) shouldBe true
            }
            declines.toList() shouldBe listOf(Declined(2), Declined(4))
        }
    }
}
