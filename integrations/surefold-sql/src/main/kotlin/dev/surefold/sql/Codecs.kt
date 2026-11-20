package dev.surefold.sql

import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.fail.left
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalTime

/**
 * Spec 0087 rule 5: the codecs any JDBC driver honours, for any [Dialect]. A database's own types (Postgres's `uuid`,
 * `timestamptz` and arrays) live with that database, as [PostgresCodecs]. The plugin selects one; a call site does not.
 */
object Codecs {
    val string: DbCodec<String> = object : ScalarCodec<String>() {
        override fun write(statement: PreparedStatement, index: Int, value: String) {
            statement.setObject(index, value)
        }

        override fun read(row: ResultSet, index: Int): Either<SqlFailure, String> =
            readObject("text") { row.getString(index) }
    }

    val int: DbCodec<Int> = object : ScalarCodec<Int>() {
        override fun write(statement: PreparedStatement, index: Int, value: Int) {
            statement.setObject(index, value)
        }

        override fun read(row: ResultSet, index: Int): Either<SqlFailure, Int> =
            readPrimitive(row, "int4") { row.getInt(index) }
    }

    val long: DbCodec<Long> = object : ScalarCodec<Long>() {
        override fun write(statement: PreparedStatement, index: Int, value: Long) {
            statement.setObject(index, value)
        }

        override fun read(row: ResultSet, index: Int): Either<SqlFailure, Long> =
            readPrimitive(row, "int8") { row.getLong(index) }
    }

    val boolean: DbCodec<Boolean> = object : ScalarCodec<Boolean>() {
        override fun write(statement: PreparedStatement, index: Int, value: Boolean) {
            statement.setObject(index, value)
        }

        override fun read(row: ResultSet, index: Int): Either<SqlFailure, Boolean> =
            readPrimitive(row, "bool") { row.getBoolean(index) }
    }

    val localDate: DbCodec<LocalDate> = object : ScalarCodec<LocalDate>() {
        override fun write(statement: PreparedStatement, index: Int, value: LocalDate) {
            statement.setObject(index, value)
        }

        override fun read(row: ResultSet, index: Int): Either<SqlFailure, LocalDate> =
            readObject("date") { row.getObject(index, LocalDate::class.java) }
    }

    val localTime: DbCodec<LocalTime> = object : ScalarCodec<LocalTime>() {
        override fun write(statement: PreparedStatement, index: Int, value: LocalTime) {
            statement.setObject(index, value)
        }

        override fun read(row: ResultSet, index: Int): Either<SqlFailure, LocalTime> =
            readObject("time") { row.getObject(index, LocalTime::class.java) }
    }

    val bytes: DbCodec<ByteArray> = object : ScalarCodec<ByteArray>() {
        override fun write(statement: PreparedStatement, index: Int, value: ByteArray) {
            statement.setObject(index, value)
        }

        override fun read(row: ResultSet, index: Int): Either<SqlFailure, ByteArray> =
            readObject("bytea") { row.getBytes(index) }
    }
}

internal abstract class ScalarCodec<A : Any> : DbCodec<A> {
    override val placeholders: Int = 1
}

internal fun <A : Any> readObject(column: String, read: () -> A?): Either<SqlFailure, A> =
    Either.catch(read).mapLeft { it.asSqlFailure() }.flatMap { value ->
        if (value == null) nullColumn(column).left() else value.right()
    }

private fun <A : Any> readPrimitive(row: ResultSet, column: String, read: () -> A): Either<SqlFailure, A> =
    Either.catch {
        val value = read()
        if (row.wasNull()) null else value
    }.mapLeft { it.asSqlFailure() }.flatMap { value ->
        if (value == null) nullColumn(column).left() else value.right()
    }
