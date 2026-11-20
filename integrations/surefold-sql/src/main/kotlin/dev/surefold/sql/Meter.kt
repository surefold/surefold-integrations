package dev.surefold.sql

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.mapLeft

/** One execution of a declared question. The id is the only attribute that names it. */
class QuerySample(
    val queryId: String,
    val operation: String,
    val durationNanos: Long,
    val errorType: String?,
)

/** The application supplies one. [NoQueryMeter] is the default and records nothing. */
interface QueryMeter {
    fun record(sample: QuerySample)
}

object NoQueryMeter : QueryMeter {
    override fun record(sample: QuerySample) = Unit
}

private val installed = Shared<QueryMeter>(NoQueryMeter, "sql.meter")

/** Replaces the meter used by later executions on this process. */
fun installQueryMeter(meter: QueryMeter) {
    installed.update { meter }
}

internal fun currentMeter(): QueryMeter = installed.peek()

internal fun operationName(sql: String): String {
    val head = sql.trimStart().takeWhile { it.isLetter() }.lowercase()
    return if (head in OPERATIONS) head else "other"
}

private val OPERATIONS = setOf("select", "insert", "update", "delete")

/** Runs [block], classifies a driver refusal through [dialect], and records the outcome under [frag]'s id. */
internal fun <A> measured(frag: Frag, dialect: Dialect, block: () -> Either<SqlFailure, A>): Either<SqlFailure, A> {
    val started = System.nanoTime()
    val answer = block().mapLeft { it.classifiedBy(dialect) }
    record(frag, started, answer.fold({ it }, { null }))
    return answer
}

/** An unclassified refusal typed by [dialect]; anything already typed, or not from the driver, is left alone. */
internal fun SqlFailure.classifiedBy(dialect: Dialect): SqlFailure =
    if (this is SqlFailure.Driver) dialect.classify(cause) else this

internal fun record(frag: Frag, started: Long, failure: SqlFailure?) {
    currentMeter().record(
        QuerySample(
            queryId = frag.id.value,
            operation = operationName(frag.sql),
            durationNanos = System.nanoTime() - started,
            errorType = failure?.let(::errorType),
        ),
    )
}

/** One fixed label per kind, so `error.type` stays a handful of values and never carries the driver's message. */
internal fun errorType(failure: SqlFailure): String = when (failure) {
    is SqlFailure.Driver -> "driver"
    is SqlFailure.UniqueViolation -> "unique_violation"
    is SqlFailure.CheckViolation -> "check_violation"
    is SqlFailure.ForeignKeyViolation -> "foreign_key_violation"
    is SqlFailure.NotNullViolation -> "not_null_violation"
    is SqlFailure.SerializationFailure -> "serialization_failure"
    is SqlFailure.Deadlock -> "deadlock"
    is SqlFailure.PermissionDenied -> "permission_denied"
    is SqlFailure.Unexpected -> "unexpected"
    is SqlFailure.RowCount -> "row_count"
    is SqlFailure.NotSupported -> "not_supported"
}
