package dev.surefold.sql.plugin

/** The plugin's tenant options: [column] on every tenant table equals [predicate], the SQL that reads the tenant. */
internal class Tenant(val column: String, val predicate: String)

private const val UNNAMED = "a from or insert target is not a table the plugin can name"

private val CLAUSE = setOf(
    "where", "group", "order", "limit", "offset", "having", "union", "except", "intersect",
    "window", "for", "fetch", "returning", "set", "values", "select", "from", "into",
)

private val JOIN_WORD = setOf("join", "inner", "left", "right", "full", "cross", "outer", "natural")

private val SET_WORD = setOf("union", "except", "intersect")

private val RESERVED = CLAUSE + JOIN_WORD + setOf("on", "using", "as")

/**
 * The one `values (…)` tuple of an insert, as offsets of its parentheses, when a batch can repeat it: it holds only
 * placeholders and the tenant call, and nothing but `on conflict` or `returning` follows. Otherwise null, and a batch
 * of the statement runs as a JDBC batch.
 */
internal fun valuesTuple(sql: String, tenant: Tenant?): IntRange? {
    val tokens = tokenize(sql) ?: return null
    val first = tokens.firstOrNull { it.depth == 0 && it.word } ?: return null
    if (!first.isWord("insert")) return null
    val values = findWord(tokens, 0, "values") ?: return null
    val open = tupleOpens(tokens, values + 1, emptyList())?.singleOrNull() ?: return null
    val close = matchingClose(tokens, open) ?: return null
    val placeholders = onlyPlaceholders(sql.substring(tokens[open].end, tokens[close].start), tenant)
    return if (placeholders) tokens[open].start..tokens[close].start else null
}

/** Whether a values tuple holds nothing but placeholders, once the tenant predicate the splice added is set aside. */
private fun onlyPlaceholders(tuple: String, tenant: Tenant?): Boolean {
    val inside = tenant?.let { tuple.replace(it.predicate, "") } ?: tuple
    return inside.all { it == '?' || it == ',' || it.isWhitespace() }
}

/** Whether the statement has a `returning` list of its own; one inside a subquery or a string is not counted. */
internal fun hasReturning(sql: String): Boolean = tokenize(sql)?.any { it.isWord("returning") } ?: false

internal sealed interface Splice {
    class Ready(val sql: String) : Splice

    class Broken(val message: String) : Splice
}

/**
 * Adds `alias.column = predicate` from [tenant] for each real table. A CTE name is not a table.
 * Unscoped text never comes here.
 */
internal fun spliceTenant(tenant: Tenant, sql: String): Splice = spliceDeep(tenant, sql, emptySet())

/**
 * Splices every subquery (`in (select …)`, `exists (…)`, a scalar subquery in a select list or `set`) and then the
 * statement itself. A CTE body is left to [spliceWith], which knows which names it can see.
 */
private fun spliceDeep(tenant: Tenant, sql: String, ctes: Set<String>): Splice {
    val tokens = tokenize(sql) ?: return Splice.Broken(UNNAMED)
    val visible = ctes + declaredCtes(tokens)
    val edits = subqueries(tokens).map { (open, close) ->
        val inner = sql.substring(tokens[open].end, tokens[close].start)
        when (val spliced = spliceDeep(tenant, inner, visible)) {
            is Splice.Broken -> return spliced
            is Splice.Ready -> Replace(tokens[open].end, tokens[close].start, spliced.sql)
        }
    }
    return spliceStatement(tenant, replaceAll(sql, edits), ctes)
}

/** The names a leading `with` declares, so a subquery in its main statement does not splice a CTE as a table. */
private fun declaredCtes(tokens: List<Tok>): Set<String> {
    val first = tokens.firstOrNull { it.depth == 0 && it.word } ?: return emptySet()
    if (!first.isWord("with")) return emptySet()
    val start = if (tokens.getOrNull(1)?.isWord("recursive") == true) 2 else 1
    return readCtes(tokens, start, emptySet(), emptyList())?.names.orEmpty()
}

/** Each outermost `(select …)` or `(with …)`, as open and close token indexes. CTE bodies are not subqueries. */
private fun subqueries(tokens: List<Tok>): List<Pair<Int, Int>> =
    tokens.indices.fold(emptyList<Pair<Int, Int>>() to emptyList<Pair<Int, Int>>()) { (kept, skipped), index ->
        val token = tokens[index]
        val next = tokens.getOrNull(index + 1)
        val opensQuery = token.text == "(" && !token.word && next != null && next.word &&
            next.text.lowercase() in QUERY_START
        val inside = (kept + skipped).any { (open, close) -> index in open..close }
        val close = if (opensQuery && !inside) matchingClose(tokens, index) else null
        when {
            close == null -> kept to skipped
            isCteBody(tokens, index) -> kept to (skipped + (index to close))
            else -> (kept + (index to close)) to skipped
        }
    }.first

private val QUERY_START = setOf("select", "with")

private fun isCteBody(tokens: List<Tok>, open: Int): Boolean {
    val before = tokens.getOrNull(open - 1) ?: return false
    return before.word && before.text.lowercase() in setOf("as", "materialized")
}

private fun spliceStatement(tenant: Tenant, sql: String, ctes: Set<String>): Splice {
    val tokens = tokenize(sql) ?: return Splice.Broken(UNNAMED)
    val keyword = tokens.firstOrNull { it.depth == 0 && it.word } ?: return Splice.Broken(UNNAMED)
    return when (keyword.text.lowercase()) {
        "select" -> spliceSelect(tenant, sql, tokens, ctes)
        "update" -> spliceUpdate(tenant, sql, tokens, ctes)
        "delete" -> spliceDelete(tenant, sql, tokens, ctes)
        "insert" -> spliceInsert(tenant, sql, tokens, ctes)
        "with" -> spliceWith(tenant, sql, tokens, ctes)
        else -> Splice.Broken(UNNAMED)
    }
}

private class Tok(val text: String, val start: Int, val end: Int, val depth: Int, val word: Boolean)

private fun tokenize(sql: String): List<Tok>? = readTokens(sql, 0, 0, emptyList())

private class Step(val index: Int, val depth: Int, val tokens: List<Tok>)

private tailrec fun readTokens(sql: String, index: Int, depth: Int, acc: List<Tok>): List<Tok>? {
    if (index >= sql.length) return if (depth == 0) acc else null
    val step = tokenAt(sql, index, depth, acc) ?: return null
    return readTokens(sql, step.index, step.depth, step.tokens)
}

private fun tokenAt(sql: String, index: Int, depth: Int, acc: List<Tok>): Step? {
    val char = sql[index]
    return when {
        char.isWhitespace() -> Step(index + 1, depth, acc)
        isLineComment(sql, index) -> Step(lineCommentEnd(sql, index), depth, acc)
        isBlockComment(sql, index) -> blockComment(sql, index, depth, acc)
        char == '\'' || char == '"' -> quoted(sql, index, char, depth, acc)
        char == '(' -> Step(index + 1, depth + 1, acc + Tok("(", index, index + 1, depth, false))
        char == ')' -> closeParen(index, depth, acc)
        char == ',' || char == '.' -> punct(sql, index, depth, acc)
        char.isLetter() || char == '_' -> word(sql, index, depth, acc)
        else -> Step(index + 1, depth, acc)
    }
}

private fun isLineComment(sql: String, index: Int): Boolean = sql[index] == '-' && sql.getOrNull(index + 1) == '-'

private fun lineCommentEnd(sql: String, index: Int): Int {
    val end = sql.indexOf('\n', index)
    return if (end < 0) sql.length else end
}

private fun isBlockComment(sql: String, index: Int): Boolean = sql[index] == '/' && sql.getOrNull(index + 1) == '*'

private fun blockComment(sql: String, index: Int, depth: Int, acc: List<Tok>): Step? {
    val close = sql.indexOf("*/", index + 2)
    return if (close < 0) null else Step(close + 2, depth, acc)
}

private fun quoted(sql: String, index: Int, quote: Char, depth: Int, acc: List<Tok>): Step? {
    val end = endQuote(sql, index + 1, quote) ?: return null
    val tokens = if (quote == '"') acc + Tok(sql.substring(index, end), index, end, depth, true) else acc
    return Step(end, depth, tokens)
}

private fun closeParen(index: Int, depth: Int, acc: List<Tok>): Step? {
    if (depth == 0) return null
    return Step(index + 1, depth - 1, acc + Tok(")", index, index + 1, depth, false))
}

private fun punct(sql: String, index: Int, depth: Int, acc: List<Tok>): Step =
    Step(index + 1, depth, acc + Tok(sql[index].toString(), index, index + 1, depth, false))

private fun word(sql: String, index: Int, depth: Int, acc: List<Tok>): Step {
    val end = wordEnd(sql, index + 1)
    return Step(end, depth, acc + Tok(sql.substring(index, end), index, end, depth, true))
}

private tailrec fun endQuote(sql: String, index: Int, quote: Char): Int? {
    if (index >= sql.length) return null
    if (sql[index] != quote) return endQuote(sql, index + 1, quote)
    return if (sql.getOrNull(index + 1) == quote) endQuote(sql, index + 2, quote) else index + 1
}

private tailrec fun wordEnd(sql: String, index: Int): Int = when {
    index >= sql.length -> index
    sql[index].isLetterOrDigit() || sql[index] == '_' -> wordEnd(sql, index + 1)
    else -> index
}

private fun spliceSelect(tenant: Tenant, sql: String, tokens: List<Tok>, ctes: Set<String>): Splice {
    val cut = tokens.indexOfFirst { it.depth == 0 && it.word && it.text.lowercase() in SET_WORD }
    if (cut >= 0) return spliceArms(tenant, sql, tokens, cut, ctes)
    return spliceOneSelect(tenant, sql, tokens, ctes)
}

private fun spliceArms(tenant: Tenant, sql: String, tokens: List<Tok>, cut: Int, ctes: Set<String>): Splice {
    val keyword = tokens[cut]
    val right = afterSetWord(tokens, cut) ?: return Splice.Broken(UNNAMED)
    val leftSql = sql.substring(0, keyword.start)
    val leftTokens = tokenize(leftSql) ?: return Splice.Broken(UNNAMED)
    val left = when (val spliced = spliceOneSelect(tenant, leftSql, leftTokens, ctes)) {
        is Splice.Broken -> return spliced
        is Splice.Ready -> spliced.sql
    }
    val rightSql = sql.substring(tokens[right].start)
    val rest = when (val spliced = spliceStatement(tenant, rightSql, ctes)) {
        is Splice.Broken -> return spliced
        is Splice.Ready -> spliced.sql
    }
    val gap = if (left.isNotEmpty() && !left.last().isWhitespace()) " " else ""
    return Splice.Ready(left + gap + sql.substring(keyword.start, tokens[right].start) + rest)
}

private fun afterSetWord(tokens: List<Tok>, cut: Int): Int? {
    val next = tokens.getOrNull(cut + 1) ?: return null
    val start = if (next.isWord("all")) cut + 2 else cut + 1
    return start.takeIf { tokens.getOrNull(it) != null }
}

private fun spliceOneSelect(tenant: Tenant, sql: String, tokens: List<Tok>, ctes: Set<String>): Splice {
    val from = findWord(tokens, 0, "from") ?: return Splice.Ready(sql)
    return when (val parsed = parseFromList(tokens, from + 1)) {
        is FromParse.Broken -> Splice.Broken(UNNAMED)

        is FromParse.Ready -> {
            val names = parsed.names.stored(ctes)
            if (names.isEmpty()) {
                Splice.Ready(sql)
            } else {
                val edits = scopedEdits(tenant, sql, names) { inner ->
                    predicateEdit(tenant, sql, tokens, parsed.cursor, inner)
                }
                Splice.Ready(apply(sql, edits))
            }
        }
    }
}

private fun spliceUpdate(tenant: Tenant, sql: String, tokens: List<Tok>, ctes: Set<String>): Splice {
    val set = findWord(tokens, 0, "set") ?: return Splice.Broken(UNNAMED)
    val target = parseItem(tokens, 1)
    if (target !is Item.Ready || target.cursor != set) return Splice.Broken(UNNAMED)
    val from = findWord(tokens, set + 1, "from")
    val extra = if (from == null) {
        FromParse.Ready(emptyList(), set + 1)
    } else {
        parseFromList(tokens, from + 1)
    }
    val parsed = extra as? FromParse.Ready ?: return Splice.Broken(UNNAMED)
    if (stopsAtSet(tokens, parsed.cursor)) return Splice.Broken(UNNAMED)
    val names = (listOf(target.name) + parsed.names).stored(ctes)
    if (names.isEmpty()) return Splice.Ready(sql)
    val where = findWord(tokens, set + 1, "where")
    val edits = scopedEdits(tenant, sql, names) { inner ->
        if (where != null) {
            afterWhere(tenant, sql, tokens, where, inner)
        } else {
            listOf(beforeClause(tenant, sql, updateInsertAt(sql, tokens, from, parsed.cursor), inner))
        }
    }
    return Splice.Ready(apply(sql, edits))
}

private fun updateInsertAt(sql: String, tokens: List<Tok>, from: Int?, fromCursor: Int): Int {
    if (from != null) return clauseStart(sql, tokens, fromCursor)
    val returning = findWord(tokens, 0, "returning")
    return if (returning == null) sql.length else tokens[returning].start
}

private fun spliceDelete(tenant: Tenant, sql: String, tokens: List<Tok>, ctes: Set<String>): Splice {
    val from = findWord(tokens, 0, "from") ?: return Splice.Broken(UNNAMED)
    val target = parseItem(tokens, from + 1)
    if (target !is Item.Ready) return Splice.Broken(UNNAMED)
    val using = tokens.getOrNull(target.cursor)
    val extra = if (using != null && using.isWord("using")) {
        parseFromList(tokens, target.cursor + 1)
    } else {
        FromParse.Ready(emptyList(), target.cursor)
    }
    val parsed = extra as? FromParse.Ready ?: return Splice.Broken(UNNAMED)
    if (stopsAtSet(tokens, parsed.cursor)) return Splice.Broken(UNNAMED)
    val names = (listOf(target.name) + parsed.names).stored(ctes)
    if (names.isEmpty()) return Splice.Ready(sql)
    val edits = scopedEdits(tenant, sql, names) { inner -> predicateEdit(tenant, sql, tokens, parsed.cursor, inner) }
    return Splice.Ready(apply(sql, edits))
}

private fun spliceInsert(tenant: Tenant, sql: String, tokens: List<Tok>, ctes: Set<String>): Splice {
    val into = findWord(tokens, 0, "into") ?: return Splice.Broken(UNNAMED)
    val target = parseItem(tokens, into + 1, columnList = true)
    if (target !is Item.Ready) return Splice.Broken(UNNAMED)
    val open = tokens.getOrNull(target.cursor)
    if (open == null || open.text != "(" || open.depth != 0) return Splice.Broken(UNNAMED)
    val close = matchingClose(tokens, target.cursor) ?: return Splice.Broken(UNNAMED)
    val values = findWord(tokens, close + 1, "values")
    if (values != null) return spliceValues(tenant, sql, tokens, open.end, tokens[close].start, values)
    val select = findWord(tokens, close + 1, "select") ?: return Splice.Broken(UNNAMED)
    return spliceInsertSelect(tenant, sql, tokens, ctes, open.end, tokens[close].start, select)
}

private fun spliceValues(
    tenant: Tenant,
    sql: String,
    tokens: List<Tok>,
    openEnd: Int,
    closeStart: Int,
    values: Int,
): Splice {
    val tuples = tupleOpens(tokens, values + 1, emptyList()) ?: return Splice.Broken(UNNAMED)
    if (tuples.isEmpty()) return Splice.Broken(UNNAMED)
    val columns = insertAt(openEnd, sql, closeStart, tenant.column)
    val rows = tuples.map { index ->
        val end = matchingClose(tokens, index) ?: return Splice.Broken(UNNAMED)
        insertAt(tokens[index].end, sql, tokens[end].start, tenant.predicate)
    }
    return Splice.Ready(apply(sql, listOf(columns) + rows))
}

private fun spliceInsertSelect(
    tenant: Tenant,
    sql: String,
    tokens: List<Tok>,
    ctes: Set<String>,
    openEnd: Int,
    closeStart: Int,
    select: Int,
): Splice {
    val conflict = onConflictAt(tokens, select)
    val selectEnd = if (conflict == null) sql.length else tokens[conflict].start
    val selectSql = sql.substring(tokens[select].start, selectEnd)
    val valued = when (val spliced = spliceStatement(tenant, selectSql, ctes)) {
        is Splice.Broken -> return spliced
        is Splice.Ready -> prependSelectItem(tenant, spliced.sql)
    }
    val column = if (sql.substring(openEnd, closeStart).isBlank()) tenant.column else "${tenant.column}, "
    val withColumn = sql.substring(0, openEnd) + column + sql.substring(openEnd)
    val shift = column.length
    val at = tokens[select].start + shift
    val end = selectEnd + shift
    return Splice.Ready(withColumn.substring(0, at) + valued + withColumn.substring(end))
}

private tailrec fun onConflictAt(tokens: List<Tok>, from: Int): Int? {
    val on = findWord(tokens, from, "on") ?: return null
    val next = tokens.getOrNull(on + 1)
    if (next != null && next.isWord("conflict")) return on
    return onConflictAt(tokens, on + 1)
}

private fun prependSelectItem(tenant: Tenant, sql: String): String {
    val after = "select".length
    val mark = distinctMark(sql.substring(after))
    return sql.substring(0, after + mark) + " ${tenant.predicate}," + sql.substring(after + mark)
}

private fun distinctMark(tail: String): Int {
    val gap = tail.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) tail.length else it }
    val rest = tail.substring(gap)
    if (!rest.startsWith("distinct", true)) return 0
    val afterDistinct = gap + "distinct".length
    val more = tail.substring(afterDistinct)
    val gap2 = more.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
    val word = more.substring(gap2)
    if (!word.startsWith("on", true)) return afterDistinct
    val open = word.indexOf('(')
    if (open < 0) return afterDistinct
    val close = closeWord(word, open) ?: return afterDistinct
    return afterDistinct + gap2 + close + 1
}

private tailrec fun closeWord(text: String, open: Int, index: Int = open + 1, depth: Int = 1): Int? {
    if (index >= text.length || depth == 0) return if (depth == 0) index - 1 else null
    val next = when (text[index]) {
        '(' -> depth + 1
        ')' -> depth - 1
        else -> depth
    }
    return closeWord(text, open, index + 1, next)
}

private fun spliceWith(tenant: Tenant, sql: String, tokens: List<Tok>, ctes: Set<String>): Splice {
    val recursive = tokens.getOrNull(1)?.isWord("recursive") == true
    val start = if (recursive) 2 else 1
    val parsed = readCtes(tokens, start, ctes, emptyList()) ?: return Splice.Broken(UNNAMED)
    val main = tokens.getOrNull(parsed.cursor) ?: return Splice.Broken(UNNAMED)
    val mainSql = when (val spliced = spliceStatement(tenant, sql.substring(main.start), parsed.names)) {
        is Splice.Broken -> return spliced
        is Splice.Ready -> spliced.sql
    }
    val bodies = parsed.bodies.map { body ->
        val inner = sql.substring(body.contentStart, body.contentEnd)
        val text = when (val spliced = spliceDeep(tenant, inner, if (recursive) parsed.names else body.visible)) {
            is Splice.Broken -> return spliced
            is Splice.Ready -> spliced.sql
        }
        Replace(body.contentStart, body.contentEnd, text)
    }
    return Splice.Ready(replaceAll(sql, bodies + Replace(main.start, sql.length, mainSql)))
}

private class CteBody(val contentStart: Int, val contentEnd: Int, val visible: Set<String>)

private class CteList(val bodies: List<CteBody>, val names: Set<String>, val cursor: Int)

private class Replace(val start: Int, val end: Int, val text: String)

private tailrec fun readCtes(tokens: List<Tok>, cursor: Int, names: Set<String>, acc: List<CteBody>): CteList? {
    val name = tokens.getOrNull(cursor)?.takeIf { it.word && it.depth == 0 && !isReserved(it) } ?: return null
    val open = openAfterAs(tokens, skipParen(tokens, cursor + 1)) ?: return null
    val close = matchingClose(tokens, open) ?: return null
    val body = CteBody(tokens[open].end, tokens[close].start, names)
    val next = close + 1
    val comma = tokens.getOrNull(next)
    return if (comma != null && comma.symbol(",")) {
        readCtes(tokens, next + 1, names + name.text, acc + body)
    } else {
        CteList(acc + body, names + name.text, next)
    }
}

/** The index of the `(` after `as` at [at], or null when [at] is not `as (`. */
private fun openAfterAs(tokens: List<Tok>, at: Int): Int? {
    val open = tokens.getOrNull(at + 1)
    val opens = tokens.getOrNull(at)?.isWord("as") == true && open != null && open.text == "(" && open.depth == 0
    return if (opens) at + 1 else null
}

private fun skipParen(tokens: List<Tok>, cursor: Int): Int {
    val token = tokens.getOrNull(cursor) ?: return cursor
    if (token.text != "(" || token.depth != 0) return cursor
    val close = matchingClose(tokens, cursor) ?: return cursor
    return close + 1
}

private fun replaceAll(sql: String, edits: List<Replace>): String =
    edits.sortedByDescending { it.start }.fold(sql) { text, edit ->
        text.substring(0, edit.start) + edit.text + text.substring(edit.end)
    }

private fun insertAt(at: Int, sql: String, close: Int, text: String): Pair<Int, String> {
    val empty = sql.substring(at, close).isBlank()
    return at to if (empty) text else "$text, "
}

private fun predicateEdit(
    tenant: Tenant,
    sql: String,
    tokens: List<Tok>,
    cursor: Int,
    names: List<Named>,
): List<Pair<Int, String>> {
    val token = tokens.getOrNull(cursor)
    return if (token != null && token.isWord("where")) {
        afterWhere(tenant, sql, tokens, cursor, names)
    } else {
        listOf(beforeClause(tenant, sql, clauseStart(sql, tokens, cursor), names))
    }
}

/**
 * The predicate goes first, and `and` binds tighter than `or`: a condition with a top-level `or` is parenthesised,
 * or its second arm would match rows of any tenant. No other operator binds looser than `and`, so the rest stay bare.
 */
private fun afterWhere(
    tenant: Tenant,
    sql: String,
    tokens: List<Tok>,
    where: Int,
    names: List<Named>,
): List<Pair<Int, String>> {
    val end = tokens.indices.firstOrNull { it > where && endsWhere(tokens[it]) }
    val alternatives = (where + 1 until (end ?: tokens.size)).any { tokens[it].isWord("or") }
    if (!alternatives) return listOf(tokens[where].end to " ${predicate(tenant, names)} and")
    val close = when {
        end != null -> tokens[end].start to ") "

        sql.substring(tokens[where].end).contains("--") -> sql.length to "\n)"

        // Before any trailing space: an insert's select is spliced apart from the `on conflict` that follows it.
        else -> sql.trimEnd().length to ")"
    }
    return listOf(tokens[where].end to " ${predicate(tenant, names)} and (", close)
}

/** A where's condition runs to the next of these at its own depth; `from` is not one (`is distinct from`). */
private val AFTER_WHERE = setOf(
    "group", "having", "window", "order", "limit", "offset", "fetch", "for", "returning", "union", "except",
    "intersect",
)

private fun endsWhere(token: Tok): Boolean = token.depth == 0 && token.word && token.text.lowercase() in AFTER_WHERE

private fun beforeClause(tenant: Tenant, sql: String, at: Int, names: List<Named>): Pair<Int, String> {
    val lead = if (at > 0 && !sql[at - 1].isWhitespace()) " " else ""
    val trail = if (at < sql.length && !sql[at].isWhitespace()) " " else ""
    return at to "${lead}where ${predicate(tenant, names)}$trail"
}

private fun clauseStart(sql: String, tokens: List<Tok>, cursor: Int): Int =
    tokens.getOrNull(cursor)?.start ?: sql.length

private fun predicate(tenant: Tenant, names: List<Named>): String =
    names.joinToString(" and ") { "${it.qualifier}.${tenant.column} = ${tenant.predicate}" }

private fun List<Named>.stored(ctes: Set<String>): List<Named> = filter { name ->
    !name.function && ctes.none { it.equals(name.table.substringAfterLast('.'), ignoreCase = true) }
}

private fun stopsAtSet(tokens: List<Tok>, cursor: Int): Boolean {
    val token = tokens.getOrNull(cursor) ?: return false
    return token.word && token.depth == 0 && token.text.lowercase() in SET_WORD
}

/**
 * [function] marks a set-returning function such as `unnest(?)`: it has no `tenant_id`, so nothing is spliced for it.
 * [on] is set for the table a left join may leave null: its predicate goes in that join's `on`, not `where`. */
private class Named(val table: String, val qualifier: String, val on: OnSpan? = null, val function: Boolean = false)

/** [onEnd] is where the `on` keyword ends; [next] is where the next clause starts, or null at the end of the text. */
private class OnSpan(val onEnd: Int, val next: Int?)

/**
 * The predicate for the inner tables through [inner], and for each outer-joined table inside its own `on`, so a
 * left join keeps its unmatched rows.
 */
private fun scopedEdits(
    tenant: Tenant,
    sql: String,
    names: List<Named>,
    inner: (List<Named>) -> List<Pair<Int, String>>,
): List<Pair<Int, String>> {
    val (outer, plain) = names.partition { it.on != null }
    val wherePart = if (plain.isEmpty()) emptyList() else inner(plain)
    return wherePart + outer.flatMap { name -> name.on?.let { onEdits(tenant, sql, name, it) }.orEmpty() }
}

private fun onEdits(tenant: Tenant, sql: String, name: Named, on: OnSpan): List<Pair<Int, String>> {
    val close = when {
        on.next != null -> on.next to ") "
        sql.substring(on.onEnd).contains("--") -> sql.length to "\n)"
        else -> sql.length to ")"
    }
    return listOf(on.onEnd to " ${predicate(tenant, listOf(name))} and (", close)
}

private sealed interface FromParse {
    class Ready(val names: List<Named>, val cursor: Int) : FromParse

    object Broken : FromParse
}

private fun parseFromList(tokens: List<Tok>, start: Int): FromParse {
    val first = parseItem(tokens, start)
    if (first !is Item.Ready) return FromParse.Broken
    return moreItems(tokens, first.cursor, listOf(first.name))
}

private tailrec fun moreItems(tokens: List<Tok>, cursor: Int, acc: List<Named>): FromParse {
    val token = tokens.getOrNull(cursor) ?: return FromParse.Ready(acc, cursor)
    if (token.depth != 0) return FromParse.Broken
    return when {
        token.text == "," -> {
            val item = parseItem(tokens, cursor + 1)
            if (item !is Item.Ready) FromParse.Broken else moreItems(tokens, item.cursor, acc + item.name)
        }

        token.isWord("on") && tokens.getOrNull(cursor + 1)?.isWord("conflict") == true ->
            FromParse.Ready(acc, cursor)

        isJoinStart(token) -> when (val joined = joinedItem(tokens, cursor)) {
            null -> FromParse.Broken
            else -> moreItems(tokens, joined.second, acc + joined.first)
        }

        isClause(token) -> FromParse.Ready(acc, cursor)

        else -> FromParse.Broken
    }
}

/** The table a join starting at [cursor] adds, and where the join ends. Right and full joins are refused. */
private fun joinedItem(tokens: List<Tok>, cursor: Int): Pair<Named, Int>? {
    val afterJoin = skipUntilJoin(tokens, cursor) ?: return null
    val kind = (cursor until afterJoin).map { tokens[it].text.lowercase() }
    if ("right" in kind || "full" in kind) return null
    val item = parseItem(tokens, afterJoin) as? Item.Ready ?: return null
    val after = skipOn(tokens, item.cursor) ?: return null
    val named = if ("left" in kind) leftJoined(tokens, item, after) else item.name
    return named?.let { it to after }
}

/** A left join's table with its `on`. Without an `on` (`using`, `natural`) there is nowhere safe to put it. */
private fun leftJoined(tokens: List<Tok>, item: Item.Ready, after: Int): Named? {
    val on = tokens.getOrNull(item.cursor)
    if (on == null || !on.isWord("on")) return null
    val span = OnSpan(on.end, tokens.getOrNull(after)?.start)
    return Named(item.name.table, item.name.qualifier, span, item.name.function)
}

private tailrec fun skipUntilJoin(tokens: List<Tok>, cursor: Int): Int? {
    val token = tokens.getOrNull(cursor) ?: return null
    if (token.depth != 0 || !token.word) return null
    val lower = token.text.lowercase()
    return when {
        lower == "join" -> cursor + 1
        lower in JOIN_WORD -> skipUntilJoin(tokens, cursor + 1)
        else -> null
    }
}

private tailrec fun skipOn(tokens: List<Tok>, cursor: Int): Int? {
    val token = tokens.getOrNull(cursor) ?: return cursor
    if (token.text == "," && token.depth == 0) return cursor
    if (token.depth != 0 || !token.word) return skipOn(tokens, cursor + 1)
    val lower = token.text.lowercase()
    return when {
        lower == "on" || lower == "using" -> skipCondition(tokens, cursor + 1)
        else -> cursor
    }
}

private tailrec fun skipCondition(tokens: List<Tok>, cursor: Int): Int? {
    val token = tokens.getOrNull(cursor) ?: return cursor
    if (token.depth != 0) return skipCondition(tokens, cursor + 1)
    if (isClause(token) || isJoinStart(token) || token.symbol(",")) return cursor
    return skipCondition(tokens, cursor + 1)
}

private sealed interface Item {
    class Ready(val name: Named, val cursor: Int) : Item

    object Broken : Item
}

private fun parseItem(tokens: List<Tok>, start: Int, columnList: Boolean = false): Item {
    val token = tokens.getOrNull(start) ?: return Item.Broken
    if (token.depth != 0 || !token.word || isReserved(token)) return Item.Broken
    val dotted = readDotted(tokens, token.text, start + 1)
    val next = tokens.getOrNull(dotted.cursor)
    if (next != null && next.text == "(" && next.depth == 0) {
        if (!columnList) return functionItem(tokens, dotted)
        return Item.Ready(Named(dotted.text, dotted.text), dotted.cursor)
    }
    return when (val alias = readAlias(tokens, dotted.cursor)) {
        is Alias.Broken -> Item.Broken
        is Alias.Ready -> Item.Ready(Named(dotted.text, alias.name ?: dotted.text), alias.cursor)
    }
}

/** `unnest(…) as x(a, b)`: the call, its alias and the alias's column list. */
private fun functionItem(tokens: List<Tok>, dotted: Dotted): Item {
    // `lateral (select …)` is a derived table, refused as one, not a function named lateral.
    if (dotted.text.equals("lateral", ignoreCase = true)) return Item.Broken
    val close = matchingClose(tokens, dotted.cursor) ?: return Item.Broken
    return when (val alias = readAlias(tokens, close + 1)) {
        is Alias.Broken -> Item.Broken

        is Alias.Ready -> {
            val columns = tokens.getOrNull(alias.cursor)
            val end = if (alias.name != null && columns != null && columns.symbol("(")) {
                matchingClose(tokens, alias.cursor)?.plus(1) ?: return Item.Broken
            } else {
                alias.cursor
            }
            Item.Ready(Named(dotted.text, alias.name ?: dotted.text, function = true), end)
        }
    }
}

private data class Dotted(val text: String, val cursor: Int)

private tailrec fun readDotted(tokens: List<Tok>, text: String, cursor: Int): Dotted {
    val part = dottedPart(tokens, cursor) ?: return Dotted(text, cursor)
    return readDotted(tokens, "$text.${part.text}", cursor + 2)
}

private fun dottedPart(tokens: List<Tok>, cursor: Int): Tok? {
    val dot = tokens.getOrNull(cursor)
    val part = tokens.getOrNull(cursor + 1)
    if (dot == null || part == null) return null
    if (!dot.symbol(".") || !part.word || part.depth != 0) return null
    return part
}

private sealed interface Alias {
    class Ready(val name: String?, val cursor: Int) : Alias

    object Broken : Alias
}

private fun readAlias(tokens: List<Tok>, cursor: Int): Alias {
    val token = tokens.getOrNull(cursor) ?: return Alias.Ready(null, cursor)
    if (token.depth != 0 || !token.word) return Alias.Ready(null, cursor)
    if (token.text.equals("as", true)) {
        val name = tokens.getOrNull(cursor + 1)
        if (name == null || !usableAlias(name)) return Alias.Broken
        return Alias.Ready(name.text, cursor + 2)
    }
    if (isReserved(token)) return Alias.Ready(null, cursor)
    return Alias.Ready(token.text, cursor + 1)
}

private fun isReserved(token: Tok): Boolean = token.text.lowercase() in RESERVED

private fun usableAlias(token: Tok): Boolean = token.word && token.depth == 0 && !isReserved(token)

private fun Tok.isWord(text: String): Boolean = depth == 0 && word && this.text.equals(text, true)

private fun Tok.symbol(text: String): Boolean = this.text == text && depth == 0

private fun isClause(token: Tok): Boolean = token.word && token.text.lowercase() in CLAUSE

private fun isJoinStart(token: Tok): Boolean = token.word && token.depth == 0 && token.text.lowercase() in JOIN_WORD

private fun findWord(tokens: List<Tok>, from: Int, word: String): Int? =
    tokens.indices.firstOrNull { index -> index >= from && tokens[index].isWord(word) }

private fun matchingClose(tokens: List<Tok>, open: Int): Int? = scanClose(tokens, open + 1, 1)

private tailrec fun scanClose(tokens: List<Tok>, index: Int, depth: Int): Int? {
    val token = tokens.getOrNull(index) ?: return null
    val next = when (token.text) {
        "(" -> depth + 1
        ")" -> depth - 1
        else -> depth
    }
    if (token.text == ")" && next == 0) return index
    if (next < 0) return null
    return scanClose(tokens, index + 1, next)
}

private tailrec fun tupleOpens(tokens: List<Tok>, cursor: Int, acc: List<Int>): List<Int>? {
    val token = tokens.getOrNull(cursor) ?: return acc
    if (token.depth == 0 && token.word && token.text.lowercase() in setOf("on", "returning")) return acc
    return when {
        token.text == "," && token.depth == 0 -> tupleOpens(tokens, cursor + 1, acc)

        token.text == "(" && token.depth == 0 -> {
            val close = matchingClose(tokens, cursor) ?: return null
            tupleOpens(tokens, close + 1, acc + cursor)
        }

        else -> null
    }
}

private val closingLast = compareByDescending<Pair<Int, String>> { it.first }.thenBy { it.second.startsWith(")") }

/** At one position, a closing paren is applied last, so it lands before a clause inserted at the same place. */
private fun apply(sql: String, edits: List<Pair<Int, String>>): String =
    edits.sortedWith(closingLast).fold(sql) { text, (at, insert) ->
        text.substring(0, at) + insert + text.substring(at)
    }
