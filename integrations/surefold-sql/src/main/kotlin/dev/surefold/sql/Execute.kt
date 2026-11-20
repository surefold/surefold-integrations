package dev.surefold.sql

import dev.surefold.draining
import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.fail.left
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import dev.surefold.onThrow
import java.sql.Connection
import java.sql.ResultSet

fun <R> Query<R>.list(connection: Connection, dialect: Dialect = Postgres): Either<SqlFailure, List<R>> =
    measured(frag, dialect) { rows(connection, Int.MAX_VALUE) }

/** [SqlFailure.RowCount.found] is 0, or 2 for two or more: the read stops at the second row. */
fun <R> Query<R>.one(connection: Connection, dialect: Dialect = Postgres): Either<SqlFailure, R> =
    measured(frag, dialect) {
        rows(connection, ENOUGH_TO_REFUSE).flatMap { rows ->
            if (rows.size == 1) rows.first().right() else SqlFailure.RowCount(rows.size).left()
        }
    }

/** As [one], with no row answering null. */
fun <R> Query<R>.optional(connection: Connection, dialect: Dialect = Postgres): Either<SqlFailure, R?> =
    measured(frag, dialect) {
        rows(connection, ENOUGH_TO_REFUSE).flatMap { rows ->
            when (rows.size) {
                0 -> null.right()
                1 -> rows.first().right()
                else -> SqlFailure.RowCount(rows.size).left()
            }
        }
    }

/** One row too many is enough to know the answer is not one row. */
private const val ENOUGH_TO_REFUSE = 2

/** Reads at most [limit] rows. Under a limit the driver is told too, so it does not fetch the rest. */
private fun <R> Query<R>.rows(connection: Connection, limit: Int): Either<SqlFailure, List<R>> =
    connection.prepare(frag.sql, { statement ->
        frag.bind(statement)
        if (limit != Int.MAX_VALUE) statement.maxRows = limit
    }) { statement ->
        Either.catch { statement.executeQuery() }.mapLeft { it.asSqlFailure() }.flatMap { rows ->
            val read = onThrow({ draining { rows.close() } }) { readRows(rows, row, limit) }
            read.after(Either.catch { draining { rows.close() } }.mapLeft { it.asSqlFailure() })
        }
    }

fun Frag.update(connection: Connection, dialect: Dialect = Postgres): Either<SqlFailure, Int> =
    measured(this, dialect) {
        connection.prepare(sql, ::bind) { statement ->
            Either.catch { statement.executeUpdate() }.mapLeft { it.asSqlFailure() }
        }
    }

internal fun closeRows(rows: ResultSet): Either<SqlFailure, Unit> =
    Either.catch { draining { rows.close() } }.mapLeft { it.asSqlFailure() }
