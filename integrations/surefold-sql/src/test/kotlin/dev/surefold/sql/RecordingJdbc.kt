package dev.surefold.sql

import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Array as SqlArray

/**
 * A connection that records the statement text, the bound values and the calling thread. The JDBC interfaces
 * throw, and the recording changes as the driver calls in. The owner, 7 November 2026, excepted this library's
 * test double.
 */
object RecordingJdbc {
    class Call(val sql: String, val thread: Thread) {
        val parameters = mutableListOf<Any?>()
        val current = mutableListOf<Any?>()
        var batches: Int = 0
        var closed: Int = 0

        /** Driver calls that are not values: `cancel`, `maxRows=2`, `batch=3` for an `executeBatch` of three rows. */
        val events = mutableListOf<String>()
    }

    class Holder<A> {
        var value: A? = null
    }

    /** [batchAnswer] is what `executeBatch` answers for each row; null answers [updateCount]. */
    class Memory(
        val failOnBind: Boolean = false,
        val failOnExecute: Boolean = false,
        val batchAnswer: Int? = null,
        /** The SQLSTATE a refused execute carries, as Postgres sends one; null for a refusal without a state. */
        val failState: String? = null,
    ) {
        val calls = mutableListOf<Call>()
        val rows = mutableListOf<List<Any?>>()
        val columns = mutableListOf<String>()
        var prepares: Int = 0
        var reads: Int = 0
        var updateCount: Int = 1
        var failOnRead: Boolean = false

        fun row(vararg values: Any?) {
            rows += values.toList()
        }

        fun column(name: String) {
            columns += name
        }
    }

    fun connection(memory: Memory): Connection =
        proxy(Connection::class.java) { method, args -> connectionCall(memory, method, args) } as Connection

    fun replay(memory: Memory, columns: List<String>): ResultSet {
        val call = memory.calls.last()
        val values = if (call.parameters.isEmpty()) call.current else call.parameters
        return rows(listOf(values), columns, memory)
    }

    private fun connectionCall(memory: Memory, method: Method, args: kotlin.Array<Any?>): Any? = when (method.name) {
        "prepareStatement" -> {
            memory.prepares++
            val call = Call(args[0] as String, Thread.currentThread())
            if (args.size > 1) call.events += "generatedKeys"
            memory.calls += call
            statement(memory, call)
        }

        "createArrayOf" -> array(args[1] as kotlin.Array<*>)

        "close" -> null

        else -> unsupported(method.name)
    }

    private fun statement(memory: Memory, call: Call): PreparedStatement {
        val connection = connection(memory)
        return proxy(PreparedStatement::class.java) { method, args ->
            statementCall(memory, call, connection, method, args)
        } as PreparedStatement
    }

    private fun statementCall(
        memory: Memory,
        call: Call,
        connection: Connection,
        method: Method,
        args: kotlin.Array<Any?>,
    ): Any? {
        val name = method.name
        if (name.startsWith("set")) return setting(memory, call, name, args)
        if (name.startsWith("execute") && memory.failOnExecute) refuse("execute failed", memory.failState)
        return when (name) {
            "addBatch" -> {
                call.parameters.addAll(call.current)
                call.current.clear()
                call.batches++
                null
            }

            "clearParameters" -> {
                call.current.clear()
                null
            }

            "executeUpdate" -> {
                flush(call)
                memory.updateCount
            }

            "executeQuery" -> {
                flush(call)
                rows(memory.rows, memory.columns, memory)
            }

            "executeBatch" -> executeBatch(memory, call)

            "getConnection" -> connection

            "getGeneratedKeys" -> rows(memory.rows, memory.columns, memory)

            "cancel" -> {
                call.events += "cancel"
                null
            }

            "close" -> {
                call.closed++
                null
            }

            else -> unsupported(name)
        }
    }

    private fun setting(memory: Memory, call: Call, name: String, args: kotlin.Array<Any?>): Any? {
        if (name == "setMaxRows") {
            call.events += "maxRows=${args[0]}"
            return null
        }
        val index = args.firstOrNull() as? Int ?: unsupported(name)
        if (memory.failOnBind) refuse("bind failed")
        put(call, index, if (name == "setNull") null else args[1])
        return null
    }

    private fun executeBatch(memory: Memory, call: Call): IntArray {
        val sent = call.events.filter { it.startsWith("batch=") }.sumOf { it.removePrefix("batch=").toInt() }
        val pending = call.batches - sent
        call.events += "batch=$pending"
        return IntArray(pending) { memory.batchAnswer ?: memory.updateCount }
    }

    private fun rows(data: List<List<Any?>>, columns: List<String>, memory: Memory): ResultSet {
        val cursor = Cursor()
        return proxy(ResultSet::class.java) { method, args ->
            rowCall(data, columns, memory, cursor, method, args)
        } as ResultSet
    }

    private fun rowCall(
        data: List<List<Any?>>,
        columns: List<String>,
        memory: Memory,
        cursor: Cursor,
        method: Method,
        args: kotlin.Array<Any?>,
    ): Any? {
        val name = method.name
        val index = args.firstOrNull() as? Int
        return when {
            name == "next" -> {
                cursor.at++
                memory.reads++
                cursor.at < data.size
            }

            name == "wasNull" -> cursor.wasNull

            name == "findColumn" -> column(columns, args[0] as String)

            name == "close" -> null

            name.startsWith("get") && index != null -> cell(data, memory, cursor, name, index)

            else -> unsupported(name)
        }
    }

    private fun cell(data: List<List<Any?>>, memory: Memory, cursor: Cursor, name: String, index: Int): Any? {
        if (memory.failOnRead) refuse("read failed")
        val value = at(data, cursor.at, index)
        cursor.wasNull = value == null
        return when (name) {
            "getInt" -> (value as? Number)?.toInt() ?: 0
            "getLong" -> (value as? Number)?.toLong() ?: 0L
            "getBoolean" -> value != null && value as Boolean
            else -> value
        }
    }

    private fun array(elements: kotlin.Array<*>): SqlArray = proxy(SqlArray::class.java) { method, _ ->
        when (method.name) {
            "getArray" -> elements
            "free" -> null
            else -> unsupported(method.name)
        }
    } as SqlArray

    private fun put(call: Call, index: Int, value: Any?) {
        while (call.current.size < index) call.current += null
        call.current[index - 1] = value
    }

    private fun flush(call: Call) {
        call.parameters.addAll(call.current)
        call.current.clear()
    }

    private fun at(data: List<List<Any?>>, row: Int, index: Int): Any? = data.getOrNull(row)?.getOrNull(index - 1)

    private fun column(columns: List<String>, name: String): Int {
        val found = columns.indexOfFirst { it.equals(name, ignoreCase = true) }
        if (found < 0) refuse("no column $name")
        return found + 1
    }

    /** The driver's refusal, which the JDBC interfaces can only give by throwing. */
    private fun refuse(message: String, state: String? = null): Nothing = throw SQLException(message, state)

    private fun unsupported(name: String): Nothing = throw UnsupportedOperationException(name)

    private fun proxy(type: Class<*>, handler: (Method, kotlin.Array<Any?>) -> Any?): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            if (method.declaringClass == Any::class.java) {
                when (method.name) {
                    "toString" -> type.simpleName
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.getOrNull(0)
                    else -> null
                }
            } else {
                handler(method, args ?: emptyArray())
            }
        }

    private class Cursor {
        var at: Int = -1
        var wasNull: Boolean = false
    }
}
