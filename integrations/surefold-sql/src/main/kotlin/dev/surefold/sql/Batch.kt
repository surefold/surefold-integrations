package dev.surefold.sql

import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import dev.surefold.stream.Stream
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement

/** A full chunk is this many rows, so every full chunk has the same text and one plan. */
internal const val BATCH_ROW_CAP = 256

private class ValuesShape(val prefix: String, val tuple: String, val suffix: String, val width: Int)

/** The plugin found the tuple when it compiled the statement; the runtime only cuts the text at those offsets. */
private fun Frag.valuesShape(): ValuesShape? = tuple?.let { range ->
    val text = sql.substring(range.first, range.last + 1)
    ValuesShape(sql.substring(0, range.first), text, sql.substring(range.last + 1), text.count { it == '?' })
}

/** Rows per chunk: at most [BATCH_ROW_CAP], and few enough that the chunk stays under [maxParameters]. */
internal fun chunkSize(placeholders: Int, maxParameters: Int): Int {
    val byDriver = if (placeholders <= 0) BATCH_ROW_CAP else maxParameters / placeholders
    val size = minOf(BATCH_ROW_CAP, byDriver)
    return if (size < 1) 1 else size
}

private fun widened(shape: ValuesShape, copies: Int): String {
    val tuples = List(copies) { shape.tuple }.joinToString(", ")
    return shape.prefix + tuples + shape.suffix
}

private val RETURNING = Regex("""(?is)\breturning\b""")

private fun <A> groups(rows: List<A>, size: Int): List<List<A>> {
    if (rows.isEmpty()) return emptyList()
    val count = (rows.size + size - 1) / size
    return List(count) { index ->
        rows.subList(index * size, minOf(rows.size, (index + 1) * size))
    }
}

fun <A> Frag.batch(connection: Connection, rows: List<A>, dialect: Dialect = Postgres): Either<SqlFailure, Int> =
    measured(this, dialect) {
        if (rows.isEmpty()) 0.right() else rowCodec<A>().flatMap { executeCount(connection, rows, it, dialect) }
    }

fun <E, A : Any> Frag.batch(
    connection: Connection,
    rows: Stream<E, A>,
    dialect: Dialect = Postgres,
): Either<SqlFailure, Int> = measured(this, dialect) {
    rowCodec<A>().flatMap { codec ->
        consume(rows, chunkSize(codec.placeholders, dialect.maxParameters)) { chunk ->
            executeCount(connection, chunk, codec, dialect)
        }.map { counts -> counts.sum() }
    }
}

fun <R, A> Query<R>.batch(
    connection: Connection,
    rows: List<A>,
    dialect: Dialect = Postgres,
): Either<SqlFailure, List<R>> = batchReturning(connection, rows, dialect)

fun <R, E, A : Any> Query<R>.batch(
    connection: Connection,
    rows: Stream<E, A>,
    dialect: Dialect = Postgres,
): Either<SqlFailure, List<R>> = batchReturning(connection, rows, dialect)

fun <R, A> Query<R>.batchReturning(
    connection: Connection,
    rows: List<A>,
    dialect: Dialect = Postgres,
): Either<SqlFailure, List<R>> = measured(frag, dialect) {
    when {
        dialect.returnedRows == ReturnedRows.Unsupported -> noRowsBack(dialect)
        rows.isEmpty() -> emptyList<R>().right()
        else -> frag.rowCodec<A>().flatMap { frag.executeReturning(connection, rows, it, row, dialect) }
    }
}

/** A dialect that returns no rows refuses before the stream is pulled, so nothing upstream runs for nothing. */
fun <R, E, A : Any> Query<R>.batchReturning(
    connection: Connection,
    rows: Stream<E, A>,
    dialect: Dialect = Postgres,
): Either<SqlFailure, List<R>> = measured(frag, dialect) {
    if (dialect.returnedRows == ReturnedRows.Unsupported) {
        noRowsBack(dialect)
    } else {
        frag.rowCodec<A>().flatMap { codec ->
            consume(rows, chunkSize(codec.placeholders, dialect.maxParameters)) { chunk ->
                frag.executeReturning(connection, chunk, codec, row, dialect)
            }.map { lists -> lists.flatten() }
        }
    }
}

private fun <R> noRowsBack(dialect: Dialect): Either<SqlFailure, R> =
    SqlFailure.NotSupported(dialect.name, "rows back from a batch").left()

private fun <A> Frag.executeCount(
    connection: Connection,
    rows: List<A>,
    codec: DbCodec<A>,
    dialect: Dialect,
): Either<SqlFailure, Int> {
    val shape = valuesShape()?.takeIf { dialect.batchInsert == BatchInsert.MultiRowValues }
    val operation = operationName(sql)
    return when {
        operation == "insert" && shape != null && shape.width == codec.placeholders ->
            eachChunk(rows, codec, dialect) { chunk ->
                val text = widened(shape, chunk.size)
                connection.prepare(text, { statement -> writeChunk(statement, chunk, codec) }) { statement ->
                    if (RETURNING.containsMatchIn(shape.suffix)) {
                        Either.catch { statement.executeQuery() }.mapLeft { it.asSqlFailure() }.flatMap { result ->
                            val counted = countRows(result)
                            counted.after(closeRows(result))
                        }
                    } else {
                        Either.catch { statement.executeUpdate() }.mapLeft { it.asSqlFailure() }
                    }
                }
            }

        operation == "insert" && shape != null ->
            SqlFailure.Unexpected(IllegalStateException("row codec does not match the values tuple")).left()

        operation == "update" || operation == "delete" || operation == "insert" ->
            addBatch(connection, rows, codec, dialect)

        else -> SqlFailure.Unexpected(IllegalStateException("this statement cannot be batched")).left()
    }
}

private fun <R, A> Frag.executeReturning(
    connection: Connection,
    rows: List<A>,
    codec: DbCodec<A>,
    row: DbCodec<R>,
    dialect: Dialect,
): Either<SqlFailure, List<R>> = when {
    dialect.returnedRows == ReturnedRows.Unsupported -> noRowsBack(dialect)
    dialect.returnedRows == ReturnedRows.GeneratedKeys -> generatedKeys(connection, rows, codec, row, dialect)
    dialect.batchInsert == BatchInsert.JdbcBatch -> eachReturning(connection, rows, codec, row)
    else -> widenedReturning(connection, rows, codec, row, dialect)
}

/** [BatchInsert.MultiRowValues] with `returning`: one statement per chunk, read as rows. */
private fun <R, A> Frag.widenedReturning(
    connection: Connection,
    rows: List<A>,
    codec: DbCodec<A>,
    row: DbCodec<R>,
    dialect: Dialect,
): Either<SqlFailure, List<R>> {
    val shape = valuesShape() ?: return SqlFailure.Unexpected(
        IllegalStateException("returning batch widens one values tuple"),
    ).left()
    if (shape.width != codec.placeholders) {
        return SqlFailure.Unexpected(IllegalStateException("row codec does not match the values tuple")).left()
    }
    return groups(rows, chunkSize(codec.placeholders, dialect.maxParameters)).fold(ok(emptyList())) { acc, chunk ->
        acc.flatMap { soFar ->
            val text = widened(shape, chunk.size)
            connection.prepare(text, { statement -> writeChunk(statement, chunk, codec) }) { statement ->
                Either.catch { statement.executeQuery() }.mapLeft { it.asSqlFailure() }.flatMap { result ->
                    val read = readRows(result, row)
                    read.after(closeRows(result))
                }
            }.map { soFar + it }
        }
    }
}

/**
 * [BatchInsert.JdbcBatch] with `returning`: JDBC has no batch that answers rows, so each row runs on the one prepared
 * statement in turn and its rows are kept in order.
 */
private fun <R, A> Frag.eachReturning(
    connection: Connection,
    rows: List<A>,
    codec: DbCodec<A>,
    row: DbCodec<R>,
): Either<SqlFailure, List<R>> = connection.prepare(sql, {}) { statement ->
    rows.fold(ok(emptyList<R>())) { acc, value ->
        acc.flatMap { soFar ->
            Either.catch {
                codec.write(statement, 1, value)
                statement.executeQuery()
            }.mapLeft { it.asSqlFailure() }.flatMap { result ->
                readRows(result, row).after(closeRows(result))
            }.map { soFar + it }
        }
    }
}

/** [ReturnedRows.GeneratedKeys]: a JDBC batch per chunk, then the keys the driver kept, read with [row]. */
private fun <R, A> Frag.generatedKeys(
    connection: Connection,
    rows: List<A>,
    codec: DbCodec<A>,
    row: DbCodec<R>,
    dialect: Dialect,
): Either<SqlFailure, List<R>> = connection.prepare(sql, {}, generatedKeys = true) { statement ->
    groups(rows, chunkSize(codec.placeholders, dialect.maxParameters)).fold(ok(emptyList<R>())) { acc, chunk ->
        acc.flatMap { soFar ->
            sendChunk(statement, chunk, codec).flatMap {
                Either.catch { statement.generatedKeys }.mapLeft { thrown -> thrown.asSqlFailure() }.flatMap { keys ->
                    readRows(keys, row).after(closeRows(keys))
                }
            }.map { soFar + it }
        }
    }
}

private fun <A> eachChunk(
    rows: List<A>,
    codec: DbCodec<A>,
    dialect: Dialect,
    use: (List<A>) -> Either<SqlFailure, Int>,
): Either<SqlFailure, Int> =
    groups(rows, chunkSize(codec.placeholders, dialect.maxParameters)).fold(ok(0)) { acc, chunk ->
        acc.flatMap { total -> use(chunk).map { total + it } }
    }

private fun <A> Frag.addBatch(
    connection: Connection,
    rows: List<A>,
    codec: DbCodec<A>,
    dialect: Dialect,
): Either<SqlFailure, Int> = connection.prepare(sql, {}) { statement ->
    groups(rows, chunkSize(codec.placeholders, dialect.maxParameters)).fold(ok(0)) { acc, chunk ->
        acc.flatMap { total ->
            sendChunk(statement, chunk, codec).map { counts -> total + counts.sumOf(::rowsCounted) }
        }
    }
}

/** `addBatch` for each row of [chunk], then one `executeBatch`; the driver's counts, one per row. */
private fun <A> sendChunk(
    statement: PreparedStatement,
    chunk: List<A>,
    codec: DbCodec<A>,
): Either<SqlFailure, IntArray> {
    val bound = chunk.fold(ok(Unit)) { step, row ->
        step.flatMap {
            Either.catch {
                codec.write(statement, 1, row)
                statement.addBatch()
            }.mapLeft { thrown -> thrown.asSqlFailure() }
        }
    }
    return bound.flatMap { Either.catch { statement.executeBatch() }.mapLeft { thrown -> thrown.asSqlFailure() } }
}

/**
 * A driver that ran a batched statement without counting its rows answers `SUCCESS_NO_INFO`. The statement ran, so
 * it counts as the one row it sent, not as none.
 */
private fun rowsCounted(count: Int): Int = if (count == Statement.SUCCESS_NO_INFO) 1 else count

private fun countRows(rows: java.sql.ResultSet): Either<SqlFailure, Int> = readRows(
    rows,
    object : DbCodec<Unit> {
        override val placeholders: Int = 0
        override fun write(statement: PreparedStatement, index: Int, value: Unit) = Unit
        override fun read(row: java.sql.ResultSet, index: Int): Either<SqlFailure, Unit> = Unit.right()
    },
).map { it.size }

private fun <A> writeChunk(statement: PreparedStatement, rows: List<A>, codec: DbCodec<A>) {
    rows.fold(1) { index, row ->
        codec.write(statement, index, row)
        index + codec.placeholders
    }
}
