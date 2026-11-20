package dev.surefold.sql

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import java.net.URL

/** The catalog of declared questions. Same SQL under two ids is two questions. A changed id is refused. */
object QueryRegistry {
    private val entries = Shared(Catalog(emptyMap(), emptyMap()), "sql.query-registry")

    fun load(catalog: Map<QueryId, String>): Either<SqlFailure, Unit> = entries.modify { current ->
        val conflict = catalog.entries.firstOrNull { (id, text) ->
            val existing = current.byId[id]
            existing != null && existing != text
        }
        if (conflict == null) {
            current.plus(catalog) to Unit.right()
        } else {
            current to SqlFailure.Unexpected(
                IllegalStateException("query id ${conflict.key.value} already has different SQL"),
            ).left()
        }
    }

    /** The first id registered for [preparedSql], when one question's text is shared. One map lookup. */
    fun idFor(preparedSql: String): QueryId? = entries.peek().byText[preparedSql]

    fun all(): Map<QueryId, String> = entries.peek().byId

    /** Loads every `META-INF/surefold/sql.catalog` visible to [loader]. Loading the same bytes twice is harmless. */
    fun loadInstalled(loader: ClassLoader = Thread.currentThread().contextClassLoader): Either<SqlFailure, Unit> =
        Either.catch { java.util.Collections.list(loader.getResources(CATALOG)) }
            .mapLeft { it.asSqlFailure() }
            .flatMap { urls ->
                urls.fold(ok(emptyMap<QueryId, String>())) { acc, url ->
                    acc.flatMap { soFar -> read(url).map { soFar + it } }
                }.flatMap { load(it) }
            }
}

/** [byText] keeps the first id registered for a text, as [QueryRegistry.idFor] answers. */
private class Catalog(val byId: Map<QueryId, String>, val byText: Map<String, QueryId>) {
    fun plus(more: Map<QueryId, String>): Catalog = Catalog(
        byId + more,
        more.entries.fold(byText) { index, (id, text) -> if (text in index) index else index + (text to id) },
    )
}

internal const val CATALOG = "META-INF/surefold/sql.catalog"

internal fun encodeCatalog(rows: List<Pair<String, String>>): String = buildString {
    append("v1\n")
    rows.forEach { (id, text) ->
        append(id)
        append(' ')
        append(escapeCatalog(text))
        append('\n')
    }
}

internal fun decodeCatalog(text: String): Either<SqlFailure, Map<QueryId, String>> {
    val lines = text.lines().filter { it.isNotEmpty() }
    val body = if (lines.firstOrNull() == "v1") lines.drop(1) else lines
    return body.fold(ok(emptyMap<QueryId, String>())) { acc, line ->
        acc.flatMap { soFar ->
            val id = line.substringBefore(' ')
            val sql = unescapeCatalog(line.substringAfter(' ', ""))
            if (id.isEmpty() || !line.contains(' ')) {
                SqlFailure.Unexpected(IllegalStateException("catalog line has no SQL")).left()
            } else {
                (soFar + (QueryId(id) to sql)).right()
            }
        }
    }
}

private fun read(url: URL): Either<SqlFailure, Map<QueryId, String>> =
    Either.catch { url.readText() }.mapLeft { it.asSqlFailure() }.flatMap { decodeCatalog(it) }

private fun escapeCatalog(text: String): String = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

private fun unescapeCatalog(text: String): String = unescapeFrom(text, 0, StringBuilder())

private tailrec fun unescapeFrom(text: String, index: Int, out: StringBuilder): String = when {
    index >= text.length -> out.toString()

    text[index] == '\\' && index + 1 < text.length -> {
        out.append(
            when (text[index + 1]) {
                'n' -> '\n'
                'r' -> '\r'
                '\\' -> '\\'
                else -> text[index + 1]
            },
        )
        unescapeFrom(text, index + 2, out)
    }

    else -> {
        out.append(text[index])
        unescapeFrom(text, index + 1, out)
    }
}
