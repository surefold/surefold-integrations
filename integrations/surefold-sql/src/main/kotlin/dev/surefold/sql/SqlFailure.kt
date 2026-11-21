package dev.surefold.sql

import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.right
import java.sql.SQLException

/** A query the caller can handle. The statement is already closed. */
sealed interface SqlFailure {
    /**
     * The database refused the statement. Each kind is classified once, by the [Dialect] the statement ran under, so
     * a caller matches a type rather than reading a vendor's SQLSTATE. [cause] is the exception the driver threw.
     */
    sealed interface FromDriver : SqlFailure {
        val cause: SQLException
    }

    /** A refusal no other kind names. */
    class Driver(override val cause: SQLException) : FromDriver

    class UniqueViolation(override val cause: SQLException) : FromDriver

    class CheckViolation(override val cause: SQLException) : FromDriver

    class ForeignKeyViolation(override val cause: SQLException) : FromDriver

    class NotNullViolation(override val cause: SQLException) : FromDriver

    /** The transaction could not be serialised with another; retrying it may succeed. */
    class SerializationFailure(override val cause: SQLException) : FromDriver

    /** The transaction was chosen to break a deadlock; retrying it may succeed. */
    class Deadlock(override val cause: SQLException) : FromDriver

    /** The login, or row-level security, does not allow the statement. */
    class PermissionDenied(override val cause: SQLException) : FromDriver

    /** The driver, or this library, failed in a way the statement did not declare. */
    class Unexpected(val cause: Throwable) : SqlFailure

    /** `one` saw a count other than one, or `optional` saw more than one. */
    class RowCount(val found: Int) : SqlFailure

    /** The call needs something [dialect] cannot do, such as rows back from a batch. Nothing was prepared or read. */
    class NotSupported(val dialect: String, val what: String) : SqlFailure
}

internal fun Throwable.asSqlFailure(): SqlFailure = when (this) {
    // Unclassified here: the entry point that ran the statement knows its dialect and classifies once.
    is SQLException -> SqlFailure.Driver(this)

    is SqlFailure -> this

    else -> SqlFailure.Unexpected(this)
}

internal fun nullColumn(column: String): SqlFailure = SqlFailure.Unexpected(IllegalStateException("$column is null"))

internal fun <A> ok(value: A): Either<SqlFailure, A> = value.right()

internal fun <A> Either<SqlFailure, A>.after(cleanup: Either<SqlFailure, Unit>): Either<SqlFailure, A> =
    when (cleanup) {
        is Either.Right -> this

        is Either.Left -> when (this) {
            is Either.Right -> cleanup.map { value }
            is Either.Left -> this
        }
    }

internal fun <A> Either<SqlFailure, A?>.orColumn(column: String): Either<SqlFailure, A> = flatMap { value ->
    if (value == null) nullColumn(column).left() else value.right()
}
