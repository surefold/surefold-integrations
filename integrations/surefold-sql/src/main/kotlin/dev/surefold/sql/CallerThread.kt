@file:OptIn(dev.surefold.stream.StreamSpi::class)

package dev.surefold.sql

import dev.surefold.Shared
import dev.surefold.draining
import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import dev.surefold.stream.BackendKey
import dev.surefold.stream.DeclaredFailure
import dev.surefold.stream.Exit
import dev.surefold.stream.Failing
import dev.surefold.stream.Node
import dev.surefold.stream.Opened
import dev.surefold.stream.Run
import dev.surefold.stream.Running
import dev.surefold.stream.Stream
import dev.surefold.stream.StreamBackend
import dev.surefold.stream.StreamSpi
import dev.surefold.stream.blocking
import dev.surefold.stream.declared
import dev.surefold.stream.mapOrFail
import dev.surefold.stream.operator
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Pulls on the thread that calls [materialise]. A JDBC connection is confined to the thread that created the
 * stream, so this backend does not hand the loop to an executor.
 */
@OptIn(StreamSpi::class)
object CallerThread : StreamBackend {
    override val key: BackendKey = BackendKey("CallerThread")

    override fun runs(node: Node): Boolean = node.supported()

    override fun <E, R : Any> materialise(run: Run<E, R>): Running<E, R> = Finished(drain(run))
}

fun <R : Any> Query<R>.stream(connection: Connection, dialect: Dialect = Postgres): Stream<SqlFailure, R> {
    val owner = Thread.currentThread()
    val query = this
    return Stream.blocking(
        open = { openCursor(query, connection, owner, dialect) },
        next = { cursor -> cursor.next() },
        wake = { cursor -> cursor.cancel() },
        close = { cursor -> cursor.close() },
    ).mapOrFail<SqlFailure, R, R> { row -> row }
}

internal class Cursor<R>(
    private val statement: java.sql.PreparedStatement,
    private val rows: ResultSet,
    private val codec: DbCodec<R>,
    private val started: Long,
    private val frag: Frag,
    private val closed: Shared<Boolean>,
    private val dialect: Dialect,
) {
    fun next(): R? = when (val more = Either.catch { rows.next() }.mapLeft { it.asSqlFailure() }) {
        is Either.Left -> fail(more.value)

        is Either.Right -> if (!more.value) {
            null
        } else {
            when (val read = codec.read(rows, 1)) {
                is Either.Left -> fail(read.value)
                is Either.Right -> read.value
            }
        }
    }

    /** JDBC lets another thread cancel a running statement; the blocked pull then fails as the driver says. */
    fun cancel() {
        Either.catch { statement.cancel() }
    }

    fun close() {
        val first = closed.modify { was -> true to !was }
        if (first) {
            Either.catch { rows.close() }
            Either.catch { statement.close() }
            record(frag, started, null)
        }
    }

    private fun fail(unclassified: SqlFailure): Nothing {
        val failure = unclassified.classifiedBy(dialect)
        val first = closed.modify { was -> true to !was }
        if (first) {
            Either.catch { rows.close() }
            Either.catch { statement.close() }
            record(frag, started, failure)
        }
        Failing<SqlFailure>().fail(failure)
    }
}

@OptIn(StreamSpi::class)
internal fun <R : Any> openCursor(
    query: Query<R>,
    connection: Connection,
    owner: Thread,
    dialect: Dialect = Postgres,
): Cursor<R> {
    if (Thread.currentThread() != owner) {
        Failing<SqlFailure>().fail(
            SqlFailure.Unexpected(IllegalStateException("stream pulled off the thread that created it")),
        )
    }
    val started = System.nanoTime()
    val opened = Either.catch { connection.prepareStatement(query.frag.sql) }
        .mapLeft { it.asSqlFailure() }
        .flatMap { statement -> executed(query, statement).map { rows -> statement to rows } }
        .mapLeft { it.classifiedBy(dialect) }
    return when (opened) {
        is Either.Left -> {
            record(query.frag, started, opened.value)
            Failing<SqlFailure>().fail(opened.value)
        }

        is Either.Right -> Cursor(
            opened.value.first,
            opened.value.second,
            query.row,
            started,
            query.frag,
            Shared(false, "sql.cursor"),
            dialect,
        )
    }
}

/** Binds and runs [statement]. A failure closes it here, because no cursor will exist to close it. */
private fun executed(query: Query<*>, statement: java.sql.PreparedStatement): Either<SqlFailure, ResultSet> {
    val rows = Either.catch {
        query.frag.bind(statement)
        statement.executeQuery()
    }.mapLeft { it.asSqlFailure() }
    return when (rows) {
        is Either.Left -> rows.after(Either.catch { draining { statement.close() } }.mapLeft { it.asSqlFailure() })
        is Either.Right -> rows
    }
}

@OptIn(StreamSpi::class)
private class Finished<E, R>(private val result: Exit<E, R>) : Running<E, R> {
    // The drain already finished on this thread. Running.exit is a CompletionStage, so this one is completed
    // and nothing is scheduled. The owner, 7 November 2026, excepted `:sql` as library code.
    override val exit: CompletionStage<Exit<E, R>> = CompletableFuture.completedFuture(result)

    override fun stop() = Unit

    override fun close() = Unit
}

@OptIn(StreamSpi::class)
private fun <E, R : Any> drain(run: Run<E, R>): Exit<E, R> {
    val pulled = Either.catch { runToEnd(run) }
    return when (pulled) {
        is Either.Right -> pulled.value

        is Either.Left -> when (val thrown = pulled.value) {
            is DeclaredFailure -> Exit.Failed(thrown.declared())
            else -> Exit.Died(thrown)
        }
    }
}

@OptIn(StreamSpi::class)
private fun <E, R : Any> runToEnd(run: Run<E, R>): Exit<E, R> {
    val next = pullOf(run.node)
    return when (val end = run.end) {
        is dev.surefold.stream.End.Collect -> {
            @Suppress("UNCHECKED_CAST")
            Exit.Done(collect(next, ArrayList()) as R)
        }

        is dev.surefold.stream.End.Fold -> {
            @Suppress("UNCHECKED_CAST")
            Exit.Done(fold(next, end, end.zero) as R)
        }

        else -> Exit.Died(IllegalStateException("CallerThread does not run this sink"))
    }
}

private tailrec fun collect(next: () -> Any?, acc: ArrayList<Any>): ArrayList<Any> {
    val step = next() ?: return acc
    acc.add(step)
    return collect(next, acc)
}

@OptIn(StreamSpi::class)
private tailrec fun fold(next: () -> Any?, end: dev.surefold.stream.End.Fold, acc: Any): Any {
    val step = next() ?: return acc
    return fold(next, end, end.f(acc, step))
}

/**
 * Groups [rows] on the calling thread. [use] sees each chunk; an empty stream never calls it.
 * The stream's failure is the [Either]'s failure.
 */
internal fun <E, A : Any, B> consume(
    rows: Stream<E, A>,
    size: Int,
    use: (List<A>) -> Either<SqlFailure, B>,
): Either<SqlFailure, List<B>> {
    val session = Either.catchOrThrow<DeclaredFailure, Pull> { Pull(rows) }
    return when (session) {
        is Either.Left -> session.value.declared<Any?>().asStreamFailure().left()

        is Either.Right -> session.value.chunks(size) { chunk ->
            @Suppress("UNCHECKED_CAST")
            use(chunk as List<A>)
        }
    }
}

private class Pull(stream: Stream<*, *>) {
    @OptIn(StreamSpi::class)
    private val next = pullOf(stream.node)

    fun <B> chunks(size: Int, use: (List<Any>) -> Either<SqlFailure, B>): Either<SqlFailure, List<B>> =
        chunksFrom(size, use, emptyList())

    private tailrec fun <B> chunksFrom(
        size: Int,
        use: (List<Any>) -> Either<SqlFailure, B>,
        acc: List<B>,
    ): Either<SqlFailure, List<B>> = when (val chunk = take(size)) {
        is Either.Left -> chunk

        is Either.Right -> if (chunk.value.isEmpty()) {
            acc.right()
        } else {
            when (val used = use(chunk.value)) {
                is Either.Left -> used
                is Either.Right -> chunksFrom(size, use, acc + used.value)
            }
        }
    }

    @OptIn(StreamSpi::class)
    private fun take(size: Int): Either<SqlFailure, List<Any>> {
        val taken = Either.catchOrThrow<DeclaredFailure, List<Any>> { takeSome(size, emptyList()) }
        return when (taken) {
            is Either.Right -> taken.value.right()
            is Either.Left -> taken.value.declared<Any?>().asStreamFailure().left()
        }
    }

    private fun takeSome(size: Int, acc: List<Any>): List<Any> {
        if (acc.size == size) return acc
        val item = next() ?: return acc
        return takeSome(size, acc + item)
    }
}

@OptIn(StreamSpi::class)
private fun pullOf(node: Node): () -> Any? = when (node) {
    is Node.Elements -> iteratorPull(node.elements.iterator())

    is Node.Single -> {
        val once = Shared(false, "sql.stream.single")
        (
            {
                val first = once.modify { seen -> true to !seen }
                if (first) node.element else null
            }
            )
    }

    Node.Empty -> ({ null })

    is Node.Fail -> ({ Failing<Any?>().fail(node.error) })

    is Node.Blocking -> blockingPull(node)

    is Node.Map -> {
        val upstream = pullOf(node.upstream)
        ({ upstream()?.let(node.f) })
    }

    is Node.MapOrFail -> {
        val upstream = pullOf(node.upstream)
        val scope = Failing<Any?>()
        ({ upstream()?.let { item -> node.f(scope, item) } })
    }

    is Node.Filter -> {
        val upstream = pullOf(node.upstream)
        ({ kept(upstream, node.predicate, keep = true) })
    }

    is Node.FilterNot -> {
        val upstream = pullOf(node.upstream)
        ({ kept(upstream, node.predicate, keep = false) })
    }

    else -> (
        {
            val unsupported = SqlFailure.Unexpected(
                IllegalStateException("CallerThread cannot pull ${node.operator}"),
            )
            Failing<Any?>().fail(unsupported)
        }
        )
}

private tailrec fun kept(upstream: () -> Any?, predicate: (Any) -> Boolean, keep: Boolean): Any? {
    val item = upstream() ?: return null
    val matches = predicate(item) == keep
    return if (matches) item else kept(upstream, predicate, keep)
}

@OptIn(StreamSpi::class)
private fun blockingPull(node: Node.Blocking): () -> Any? {
    val opened = Shared<Opened?>(null, "sql.stream.opened")
    return {
        val existing = opened.peek()
        val resource = if (existing != null) {
            existing
        } else {
            val created = Opened(node)
            opened.update { created }
            created
        }
        val value = resource.next()
        if (value == null) resource.close()
        value
    }
}

private fun iteratorPull(iterator: Iterator<Any>): () -> Any? = {
    if (iterator.hasNext()) iterator.next() else null
}

@OptIn(StreamSpi::class)
private fun Node.supported(): Boolean = when (this) {
    is Node.Elements, is Node.Single, is Node.Fail, is Node.Blocking -> true
    Node.Empty -> true
    is Node.Map, is Node.MapOrFail, is Node.Filter, is Node.FilterNot -> upstream.supported()
    else -> false
}

private fun Any?.asStreamFailure(): SqlFailure = when (this) {
    is SqlFailure -> this
    is Throwable -> asSqlFailure()
    else -> SqlFailure.Unexpected(IllegalStateException("stream failed"))
}
