package dev.surefold.sql

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.flatMap
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import dev.surefold.fail.traverse
import java.lang.reflect.Modifier
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Writes [A] onto a statement and reads it back. [placeholders] is how many `?` one value occupies, in order.
 */
interface DbCodec<A> {
    val placeholders: Int

    fun write(statement: PreparedStatement, index: Int, value: A)

    fun read(row: ResultSet, index: Int): Either<SqlFailure, A>
}

internal fun <A> DbCodec<A>.readColumn(row: ResultSet, column: String): Either<SqlFailure, A> =
    Either.catch { row.findColumn(column) }.mapLeft { it.asSqlFailure() }.flatMap { index -> read(row, index) }

/** The same codec, erased, so a data class can hold one list of field codecs. */
fun <A> DbCodec<A>.boxed(): DbCodec<Any?> = object : DbCodec<Any?> {
    override val placeholders: Int = this@boxed.placeholders

    override fun write(statement: PreparedStatement, index: Int, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        this@boxed.write(statement, index, value as A)
    }

    override fun read(row: ResultSet, index: Int): Either<SqlFailure, Any?> = this@boxed.read(row, index).map { it }
}

/** A null column stays null. The inner codec still refuses a null when this wrapper is not used. */
fun <A : Any> DbCodec<A>.orNull(): DbCodec<A?> = object : DbCodec<A?> {
    override val placeholders: Int = this@orNull.placeholders

    override fun write(statement: PreparedStatement, index: Int, value: A?) {
        if (value == null) {
            statement.setObject(index, null)
        } else {
            this@orNull.write(statement, index, value)
        }
    }

    override fun read(row: ResultSet, index: Int): Either<SqlFailure, A?> =
        Either.catch { row.getObject(index) }.mapLeft { it.asSqlFailure() }.flatMap { marker ->
            if (marker == null) null.right() else this@orNull.read(row, index)
        }
}

/**
 * A `@JvmInline` value class. The plugin emits this with the underlying codec,
 * so the value class needs none of its own.
 */
fun inlineCodec(inner: DbCodec<*>, type: Class<*>): DbCodec<Any?> = InlineCodec(inner.boxed(), type)

/**
 * A data class row or parameter. [propertyNames] are constructor properties in order; [columns] are the matching
 * snake_case names. The plugin emits this same call.
 */
fun dataCodec(
    type: Class<*>,
    propertyNames: Array<String>,
    columns: Array<String>,
    parts: Array<DbCodec<Any?>>,
): DbCodec<Any?> = DataCodec(type, propertyNames, columns, parts)

private class InlineCodec(private val inner: DbCodec<Any?>, val type: Class<*>) : DbCodec<Any?> {
    private val constructor = type.declaredConstructors.single { it.parameterCount == 1 }
        .also { it.isAccessible = true }
    private val field = type.declaredFields.single { candidate ->
        !candidate.isSynthetic && !Modifier.isStatic(candidate.modifiers)
    }.also { it.isAccessible = true }

    override val placeholders: Int = inner.placeholders

    override fun write(statement: PreparedStatement, index: Int, value: Any?) {
        val raw = if (value != null && field.declaringClass.isInstance(value)) field.get(value) else value
        inner.write(statement, index, raw)
    }

    override fun read(row: ResultSet, index: Int): Either<SqlFailure, Any?> =
        inner.read(row, index).map { constructor.newInstance(it) }

    /** The underlying value, as a data class constructor parameter of the erased type takes it. */
    fun unboxed(value: Any?): Any? = if (value != null && type.isInstance(value)) field.get(value) else value
}

private class DataCodec(
    type: Class<*>,
    propertyNames: Array<String>,
    private val columns: Array<String>,
    private val parts: Array<DbCodec<Any?>>,
) : DbCodec<Any?> {
    private val fields = propertyNames.map { name ->
        type.getDeclaredField(name).also { it.isAccessible = true }
    }
    private val constructor = type.declaredConstructors.single { it.parameterCount == propertyNames.size }
        .also { it.isAccessible = true }

    /** One adapter per parameter, chosen once, so a row is read without searching a class for its unbox method. */
    private val adapters: List<(Any?) -> Any?> = parts.zip(constructor.parameterTypes.toList()) { part, parameter ->
        adapter(part, parameter)
    }

    override val placeholders: Int = parts.sumOf { it.placeholders }

    override fun write(statement: PreparedStatement, index: Int, value: Any?) {
        val pieces = fields.map { it.get(value) }
        parts.zip(pieces).fold(index) { at, (part, piece) ->
            part.write(statement, at, piece)
            at + part.placeholders
        }
    }

    override fun read(row: ResultSet, index: Int): Either<SqlFailure, Any?> = attempt {
        val values = parts.zip(columns.toList()).traverse { (part, column) ->
            part.readColumn(row, column).bind()
        }.bind()
        val args = values.zip(adapters) { read, adapt -> adapt(read) }
        // Reflection takes the arguments as an array; there is no call without the spread.
        @Suppress("SpreadOperator")
        constructor.newInstance(*args.toTypedArray())
    }
}

/**
 * A data class stores a value class as its underlying JVM type. An [InlineCodec] part unboxes through its field; any
 * other part is fitted as it comes.
 */
private fun adapter(part: DbCodec<Any?>, parameter: Class<*>): (Any?) -> Any? = when {
    part is InlineCodec && !parameter.isAssignableFrom(part.type) -> part::unboxed
    else -> { value -> fit(value, parameter) }
}

/** [value] may already be [parameter]'s type, or a value class boxed by a hand-written codec. */
private fun fit(value: Any?, parameter: Class<*>): Any? {
    if (value == null || parameter.isInstance(value)) return value
    val unbox = value.javaClass.methods.firstOrNull { method ->
        method.name == "unbox-impl" && parameter.isAssignableFrom(method.returnType)
    }
    return unbox?.invoke(value) ?: value
}
