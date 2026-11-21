package dev.surefold.sql.plugin

internal const val CATALOG_LIMIT = 2000

internal val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal val EXPLICIT_ID = Regex("[a-z0-9.#]+")

private val RETURNING = Regex("""(?i)\breturning\b""")

private val AS = Regex("""(?i)\s+as\s+""")

private const val HOLE_OPEN = '\uE000'

private const val HOLE_CLOSE = '\uE001'

internal sealed interface Piece<out T> {
    class Text(val text: String) : Piece<Nothing>

    class Hole<T>(val payload: T) : Piece<T>
}

internal fun statementKeyword(sql: String): String? {
    val word = sql.trimStart().takeWhile { it.isLetter() }.lowercase()
    return word.takeIf { it in COMMANDS }
}

private val COMMANDS = setOf("select", "insert", "update", "delete", "with")

/** The outer `select`, after a leading `with`, so a CTE is not the row. */
internal fun outerSelect(sql: String): String? {
    val trimmed = sql.trim()
    if (trimmed.startsWith("select", ignoreCase = true)) return trimmed
    if (!trimmed.startsWith("with", ignoreCase = true)) return null
    val at = mainKeyword(trimmed) ?: return null
    val end = wordEnd(trimmed, at)
    return if (trimmed.substring(at, end).equals("select", true)) trimmed.substring(at) else null
}

internal fun oneStatement(sql: String): Boolean = ';' !in sql

internal fun selectColumns(sql: String): List<String>? {
    val body = outerSelect(sql) ?: return null
    val from = wordAtDepth(body, "from")
    if (from != null && from < "select".length) return null
    val list = if (from == null) body.substring("select".length) else body.substring("select".length, from)
    return columnList(list)
}

internal fun returningColumns(sql: String): List<String>? {
    val last = RETURNING.findAll(sql).lastOrNull() ?: return null
    return columnList(sql.substring(last.range.last + 1))
}

internal enum class ColumnCase { Snake, Camel, Pascal, UpperSnake }

internal fun columnCase(raw: String): ColumnCase? = when (raw) {
    "snake" -> ColumnCase.Snake
    "camel" -> ColumnCase.Camel
    "pascal" -> ColumnCase.Pascal
    "upper-snake" -> ColumnCase.UpperSnake
    else -> null
}

internal fun snake(name: String): String = name.fold("") { acc, char ->
    if (char.isUpperCase()) acc + "_" + char.lowercaseChar() else acc + char
}

internal fun columnName(property: String, case: ColumnCase): String = when (case) {
    ColumnCase.Snake -> snake(property)
    ColumnCase.Camel -> property.replaceFirstChar { it.lowercaseChar() }
    ColumnCase.Pascal -> property.replaceFirstChar { it.uppercaseChar() }
    ColumnCase.UpperSnake -> snake(property).uppercase()
}

internal fun columnToken(item: String): String? {
    val trimmed = item.trim()
    if (trimmed.isEmpty()) return null
    val tail = trimmed.split(AS).last().trim()
    if ('*' in tail) return null
    val token = tail.substringAfterLast('.').trim().removeSurrounding("\"")
    return token.takeIf { IDENTIFIER.matches(it) }
}

internal fun columnsMatch(columns: List<String>, names: List<String>): Boolean =
    columns.size == names.size && columns.zip(names).all { (column, name) -> column == name }

internal fun <T> trimPieces(pieces: List<Piece<T>>): List<Piece<T>>? {
    val marked = pieces.mapIndexed { index, piece ->
        when (piece) {
            is Piece.Text -> if (piece.text.indexOf(HOLE_OPEN) >= 0) return null else piece.text
            is Piece.Hole -> "$HOLE_OPEN$index$HOLE_CLOSE"
        }
    }.joinToString("")
    return splitMarked(marked.trimIndent(), pieces, emptyList())
}

internal fun encodeCatalog(rows: List<Pair<String, String>>): String = buildString {
    append("v1\n")
    rows.forEach { (id, text) ->
        append(id)
        append(' ')
        append(text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r"))
        append('\n')
    }
}

private fun columnList(raw: String): List<String>? {
    val body = withoutDistinct(raw.trim()) ?: return null
    if (body.isEmpty()) return null
    val parts = splitAtDepthZero(body) ?: return null
    return parts.fold(emptyList<String>() as List<String>?) { acc, part ->
        val token = columnToken(part)
        if (acc == null || token == null) null else acc + token
    }
}

private fun withoutDistinct(body: String): String? {
    if (!body.startsWith("distinct", ignoreCase = true)) return body
    val rest = body.drop("distinct".length).trimStart()
    if (!rest.startsWith("on", ignoreCase = true)) return rest
    val open = rest.indexOf('(')
    if (open < 0) return null
    val close = matching(rest, open) ?: return null
    return rest.substring(close + 1).trimStart()
}

private tailrec fun matching(text: String, open: Int, index: Int = open + 1, depth: Int = 1): Int? {
    if (index >= text.length || depth == 0) return if (depth == 0) index - 1 else null
    val next = when (text[index]) {
        '(' -> depth + 1
        ')' -> depth - 1
        else -> depth
    }
    return matching(text, open, index + 1, next)
}

private fun splitAtDepthZero(raw: String): List<String>? = parts(raw, 0, 0, 0, emptyList())

private tailrec fun parts(raw: String, index: Int, depth: Int, start: Int, acc: List<String>): List<String>? {
    if (index >= raw.length) return acc + raw.substring(start)
    return when (raw[index]) {
        '\'' -> {
            val end = finishQuote(raw, index + 1) ?: return null
            parts(raw, end, depth, start, acc)
        }

        '(' -> parts(raw, index + 1, depth + 1, start, acc)

        ')' -> if (depth == 0) null else parts(raw, index + 1, depth - 1, start, acc)

        ',' -> if (depth == 0) {
            parts(raw, index + 1, depth, index + 1, acc + raw.substring(start, index))
        } else {
            parts(raw, index + 1, depth, start, acc)
        }

        else -> parts(raw, index + 1, depth, start, acc)
    }
}

private val MAIN = setOf("select", "insert", "update", "delete")

private tailrec fun mainKeyword(sql: String, index: Int = 0, depth: Int = 0, seenCte: Boolean = false): Int? {
    if (index >= sql.length) return null
    return when (val char = sql[index]) {
        '-', '/' -> commented(sql, index, depth, seenCte, char)
        '\'' -> quotedStep(sql, index, depth, seenCte)
        '(' -> mainKeyword(sql, index + 1, depth + 1, seenCte)
        ')' -> mainKeyword(sql, index + 1, depth - 1, seenCte || depth == 1)
        else -> wordStep(sql, index, depth, seenCte, char)
    }
}

private fun commented(sql: String, index: Int, depth: Int, seenCte: Boolean, char: Char): Int? {
    val line = char == '-' && sql.getOrNull(index + 1) == '-'
    val block = char == '/' && sql.getOrNull(index + 1) == '*'
    val next = when {
        line -> lineEnd(sql, index)
        block -> sql.indexOf("*/", index + 2).let { close -> if (close < 0) return null else close + 2 }
        else -> index + 1
    }
    return mainKeyword(sql, next, depth, seenCte)
}

private fun quotedStep(sql: String, index: Int, depth: Int, seenCte: Boolean): Int? {
    val end = finishQuote(sql, index + 1) ?: return null
    return mainKeyword(sql, end, depth, seenCte)
}

private fun wordStep(sql: String, index: Int, depth: Int, seenCte: Boolean, char: Char): Int? {
    val wordChar = char.isLetter() || char == '_'
    if (char.isWhitespace() || depth != 0 || !wordChar) {
        return mainKeyword(sql, index + 1, depth, seenCte)
    }
    val end = wordEnd(sql, index)
    val word = sql.substring(index, end).lowercase()
    return if (seenCte && word in MAIN) index else mainKeyword(sql, end, depth, seenCte)
}

private tailrec fun wordAtDepth(sql: String, word: String, index: Int = 0, depth: Int = 0): Int? {
    if (index >= sql.length) return null
    return when (val char = sql[index]) {
        '\'' -> {
            val end = finishQuote(sql, index + 1) ?: return null
            wordAtDepth(sql, word, end, depth)
        }

        '(' -> wordAtDepth(sql, word, index + 1, depth + 1)

        ')' -> if (depth == 0) null else wordAtDepth(sql, word, index + 1, depth - 1)

        else -> if (depth == 0 && (char.isLetter() || char == '_')) {
            val end = wordEnd(sql, index)
            if (sql.substring(index, end).equals(word, true)) index else wordAtDepth(sql, word, end, depth)
        } else {
            wordAtDepth(sql, word, index + 1, depth)
        }
    }
}

private fun lineEnd(sql: String, index: Int): Int {
    val end = sql.indexOf('\n', index)
    return if (end < 0) sql.length else end
}

private tailrec fun finishQuote(sql: String, index: Int): Int? {
    if (index >= sql.length) return null
    if (sql[index] != '\'') return finishQuote(sql, index + 1)
    return if (sql.getOrNull(index + 1) == '\'') finishQuote(sql, index + 2) else index + 1
}

private tailrec fun wordEnd(sql: String, index: Int): Int = when {
    index >= sql.length -> index
    sql[index].isLetterOrDigit() || sql[index] == '_' -> wordEnd(sql, index + 1)
    else -> index
}

private fun <T> splitMarked(text: String, pieces: List<Piece<T>>, acc: List<Piece<T>>): List<Piece<T>>? {
    if (text.isEmpty()) return acc
    val at = text.indexOf(HOLE_OPEN)
    if (at < 0) return acc + Piece.Text(text)
    val end = text.indexOf(HOLE_CLOSE, at)
    if (end < 0) return null
    val index = text.substring(at + 1, end).toIntOrNull() ?: return null
    val hole = pieces.getOrNull(index) as? Piece.Hole ?: return null
    val prefix = if (at == 0) acc else acc + Piece.Text(text.substring(0, at))
    return splitMarked(text.substring(end + 1), pieces, prefix + hole)
}
