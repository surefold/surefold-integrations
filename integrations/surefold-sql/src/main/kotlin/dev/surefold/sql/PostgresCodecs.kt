package dev.surefold.sql

import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.fail.left
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Spec 0087 rule 5: the codecs for Postgres's own types. Another database supplies its own (a `uuid` as `BINARY(16)`,
 * say); these are not portable. The plugin selects one; a call site does not.
 */
object PostgresCodecs {
    val uuid: DbCodec<UUID> = object : ScalarCodec<UUID>() {
        override fun write(statement: PreparedStatement, index: Int, value: UUID) {
            statement.setObject(index, value)
        }

        override fun read(row: ResultSet, index: Int): Either<SqlFailure, UUID> =
            readObject("uuid") { row.getObject(index, UUID::class.java) }
    }

    /** Bound as `OffsetDateTime` in UTC, matching `Tx.bindable`. */
    val instant: DbCodec<Instant> = object : ScalarCodec<Instant>() {
        override fun write(statement: PreparedStatement, index: Int, value: Instant) {
            statement.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
        }

        override fun read(row: ResultSet, index: Int): Either<SqlFailure, Instant> =
            readObject("timestamptz") { row.getObject(index, OffsetDateTime::class.java)?.toInstant() }
    }

    val strings: DbCodec<List<String>> = arrayCodec("text") { item -> item as String }

    val uuids: DbCodec<List<UUID>> = arrayCodec("uuid") { item -> item as UUID }
}

private fun <A : Any> arrayCodec(sqlType: String, cast: (Any) -> A): DbCodec<List<A>> = object : DbCodec<List<A>> {
    override val placeholders: Int = 1

    override fun write(statement: PreparedStatement, index: Int, value: List<A>) {
        val items = arrayOfNulls<Any>(value.size)
        value.forEachIndexed { at, item -> items[at] = item }
        val array = statement.connection.createArrayOf(sqlType, items)
        statement.setArray(index, array)
    }

    override fun read(row: ResultSet, index: Int): Either<SqlFailure, List<A>> =
        Either.catch { row.getArray(index) }.mapLeft { it.asSqlFailure() }.flatMap { array ->
            if (array == null) {
                nullColumn(sqlType).left()
            } else {
                val seed: Either<SqlFailure, List<A>> = emptyList<A>().right()
                (array.array as Array<*>).fold(seed) { acc, item ->
                    acc.flatMap { list ->
                        if (item == null) nullColumn(sqlType).left() else (list + cast(item)).right()
                    }
                }
            }
        }
}
