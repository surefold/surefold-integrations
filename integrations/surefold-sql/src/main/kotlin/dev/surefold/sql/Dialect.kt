package dev.surefold.sql

import java.sql.SQLException

/**
 * Spec 0087: what the library assumes about the database, in one value. Every entry point that takes a connection
 * takes one too, defaulting to [Postgres]; the library never guesses it from the connection.
 */
interface Dialect {
    /** For a [SqlFailure.NotSupported] and nothing else: never a metric label. */
    val name: String

    /** The most bound parameters one statement may carry. A batch chunk stays under it. */
    val maxParameters: Int

    val batchInsert: BatchInsert

    val returnedRows: ReturnedRows

    /** The kind of refusal [error] is, kept with [error] as its cause. */
    fun classify(error: SQLException): SqlFailure.FromDriver
}

/** How an insert batch is sent. */
enum class BatchInsert {
    /** One statement per chunk, its `values` tuple repeated, where the plugin found one tuple to repeat. */
    MultiRowValues,

    /** `addBatch` per row and `executeBatch` per chunk, of the one statement as written. */
    JdbcBatch,
}

/** How a batch gets rows back. */
enum class ReturnedRows {
    /** The statement's own `returning` list. */
    Returning,

    /** The driver's generated keys (`RETURN_GENERATED_KEYS`), read with the query's row codec. */
    GeneratedKeys,

    /** None: a returning batch answers [SqlFailure.NotSupported] before anything is prepared. */
    Unsupported,
}

/** The default dialect, and the database Doorlist runs on (its ADR 0002). Its SQLSTATEs are read here alone. */
object Postgres : Dialect {
    override val name: String = "postgres"

    /** The wire protocol counts parameters in 16 bits. */
    override val maxParameters: Int = 65535

    override val batchInsert: BatchInsert = BatchInsert.MultiRowValues

    override val returnedRows: ReturnedRows = ReturnedRows.Returning

    private val kinds: Map<String, (SQLException) -> SqlFailure.FromDriver> = mapOf(
        "23505" to SqlFailure::UniqueViolation,
        "23514" to SqlFailure::CheckViolation,
        "23503" to SqlFailure::ForeignKeyViolation,
        "23502" to SqlFailure::NotNullViolation,
        "40001" to SqlFailure::SerializationFailure,
        "40P01" to SqlFailure::Deadlock,
        "42501" to SqlFailure::PermissionDenied,
    )

    /**
     * The kind of [error], from the first exception in its chain that carries a SQLSTATE: a batch refusal wraps the
     * row's own in `nextException`, and a pool may wrap it as a cause. The answer keeps [error] itself as its cause.
     */
    override fun classify(error: SQLException): SqlFailure.FromDriver {
        val state = generateSequence<Throwable>(error) { link -> (link as? SQLException)?.nextException ?: link.cause }
            .take(CHAIN_LIMIT)
            .filterIsInstance<SQLException>()
            .firstNotNullOfOrNull { it.sqlState }
        val kind = state?.let(kinds::get) ?: SqlFailure::Driver
        return kind(error)
    }

    /** A chain that loops back on itself is read this far and no further. */
    private const val CHAIN_LIMIT = 16
}
