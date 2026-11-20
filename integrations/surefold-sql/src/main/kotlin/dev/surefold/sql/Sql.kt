package dev.surefold.sql

/**
 * Rewritten by the compiler plugin into a prepared statement. [scope] is a constant: tenant statements gain
 * `doorlist.current_tenant()`, and [Scope.Platform] leaves the text alone. Reaching this function throws before
 * any prepare.
 */
fun sql(text: String, scope: Scope = Scope.Tenant): Frag = text.run { scope.run { unrewritten() } }

/** [id] is the metric name when it must outlive a rename. It is a constant matching `[a-z0-9.#]+`. */
fun sql(id: String, text: String, scope: Scope = Scope.Tenant): Frag =
    id.run { text.run { scope.run { unrewritten() } } }

/** The plugin supplies the row codec. A call that was not rewritten throws before any prepare. */
fun <R> Frag.query(): Query<R> = unrewritten()

/** The plugin supplies the row codec for the `returning` list. */
fun <R> Frag.returning(): Query<R> = unrewritten()

/**
 * A call the plugin did not rewrite. The throw is that call's contract: it happens before `prepareStatement`.
 */
private fun <A> unrewritten(): A = error("sql() was not rewritten by the surefold-sql compiler plugin")
