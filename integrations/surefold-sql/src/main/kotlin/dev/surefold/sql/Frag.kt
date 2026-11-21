package dev.surefold.sql

import dev.surefold.draining
import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.fail.left
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import dev.surefold.onThrow
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/** One bound value: [write] starts at a 1-based index and covers [placeholders] slots. */
class Bind(val placeholders: Int, val write: (PreparedStatement, Int) -> Unit)

/**
 * A rewritten statement. [sql] is the single-row prepared text the catalog stores. The plugin builds this;
 * [sql] itself throws when a call was not rewritten. [tuple] bounds the `values (…)` a batch repeats, from the
 * opening to the closing parenthesis; without it a batch is a JDBC batch of the one statement.
 */
class Frag internal constructor(
    val id: QueryId,
    val sql: String,
    private val binds: List<Bind>,
    private val batchCodec: DbCodec<Any?>?,
    internal val tuple: IntRange? = null,
) {
    val slots: Int = binds.sumOf { it.placeholders }

    internal fun bind(statement: PreparedStatement) {
        binds.fold(1) { index, bind ->
            bind.write(statement, index)
            index + bind.placeholders
        }
    }

    internal fun <A> rowCodec(): Either<SqlFailure, DbCodec<A>> {
        val codec = batchCodec ?: return SqlFailure.Unexpected(
            IllegalStateException("batch needs one row codec"),
        ).left()
        @Suppress("UNCHECKED_CAST")
        return (codec as DbCodec<A>).right()
    }
}

/** What the compiler plugin emits. A hand-written call site uses [sql] instead. */
fun prepared(id: String, sql: String, binds: Array<Bind>, batch: DbCodec<*>?): Frag = Frag(
    QueryId(id),
    sql,
    binds.toList(),
    batch?.boxed(),
)

/** What the plugin emits for an insert whose one `values` tuple a batch repeats, from [tupleStart] to [tupleEnd]. */
fun prepared(id: String, sql: String, binds: Array<Bind>, batch: DbCodec<*>?, tupleStart: Int, tupleEnd: Int): Frag =
    Frag(QueryId(id), sql, binds.toList(), batch?.boxed(), tupleStart..tupleEnd)

/** One interpolated value, bound with [codec]. */
fun <A> bind(codec: DbCodec<A>, value: A): Bind = Bind(codec.placeholders) { statement, index ->
    codec.write(statement, index, value)
}

class Query<R> internal constructor(internal val frag: Frag, internal val row: DbCodec<R>)

fun <R> Frag.query(row: DbCodec<R>): Query<R> = Query(this, row)

fun <R> Frag.returning(row: DbCodec<R>): Query<R> = Query(this, row)

/** [generatedKeys] asks the driver to keep what the statement generated, for [ReturnedRows.GeneratedKeys]. */
internal fun <A> Connection.prepare(
    text: String,
    bind: (PreparedStatement) -> Unit,
    generatedKeys: Boolean = false,
    use: (PreparedStatement) -> Either<SqlFailure, A>,
): Either<SqlFailure, A> = Either.catch {
    if (generatedKeys) prepareStatement(text, Statement.RETURN_GENERATED_KEYS) else prepareStatement(text)
}.mapLeft { it.asSqlFailure() }.flatMap { statement ->
    val answer = onThrow({ draining { statement.close() } }) {
        Either.catch { bind(statement) }.mapLeft { it.asSqlFailure() }.flatMap { use(statement) }
    }
    answer.after(Either.catch { draining { statement.close() } }.mapLeft { it.asSqlFailure() })
}

internal fun <R> readRows(
    rows: ResultSet,
    codec: DbCodec<R>,
    limit: Int = Int.MAX_VALUE,
): Either<SqlFailure, List<R>> = readFrom(rows, codec, limit, ArrayList())

private tailrec fun <R> readFrom(
    rows: ResultSet,
    codec: DbCodec<R>,
    limit: Int,
    acc: ArrayList<R>,
): Either<SqlFailure, List<R>> {
    if (acc.size >= limit) return acc.right()
    val more = Either.catch { rows.next() }.mapLeft { it.asSqlFailure() }
    return when (more) {
        is Either.Left -> more

        is Either.Right -> if (!more.value) {
            acc.right()
        } else {
            when (val read = codec.read(rows, 1)) {
                is Either.Left -> read

                is Either.Right -> {
                    acc.add(read.value)
                    readFrom(rows, codec, limit, acc)
                }
            }
        }
    }
}
