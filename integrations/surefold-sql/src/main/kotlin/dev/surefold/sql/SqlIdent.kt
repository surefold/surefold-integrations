package dev.surefold.sql

/**
 * A constant identifier the plugin splices into the statement. Request data is never spliced: a non-constant
 * argument fails compilation.
 */
@JvmInline
value class SqlIdent(val text: String)

fun ident(name: String): SqlIdent = SqlIdent(name)
