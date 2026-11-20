package dev.surefold.sql.plugin

/** A built-in codec: the property [field] of the runtime object [holder] in `dev.surefold.sql`. */
internal data class CodecField(val holder: String, val field: String)

/** The built-in codecs one dialect binds with: by Kotlin type, and by element type for a `List`. */
internal class CodecSet(val scalars: Map<String, CodecField>, val lists: Map<String, CodecField>)

private fun standard(field: String) = CodecField("Codecs", field)

private fun postgres(field: String) = CodecField("PostgresCodecs", field)

/** Spec 0087 rule 5: the codecs any JDBC driver honours. Every dialect binds with these. */
private val STANDARD_SCALARS = mapOf(
    "kotlin.String" to standard("string"),
    "kotlin.Int" to standard("int"),
    "kotlin.Long" to standard("long"),
    "kotlin.Boolean" to standard("boolean"),
    "java.time.LocalDate" to standard("localDate"),
    "java.time.LocalTime" to standard("localTime"),
    "kotlin.ByteArray" to standard("bytes"),
)

/**
 * Spec 0087 rule 6: what the target database accepts, chosen by the plugin option `dialect`.
 *
 * [tenantScope] is whether a tenant-scoped statement can be spliced. The predicate is Postgres's
 * `doorlist.current_tenant()`, read under row-level security (ADR 0003); a dialect without that function refuses
 * tenant scope, so a statement must name `Scope.Platform`, rather than rest isolation on a rewrite with nothing behind
 * it. [returning] is whether a statement may end in, or read, a `returning` list.
 */
internal enum class PluginDialect(
    val tenantScope: Boolean,
    val returning: Boolean,
    val codecs: CodecSet,
) {
    /** Doorlist's database (its ADR 0002), and the default: what every statement compiled to before the option. */
    Postgres(
        tenantScope = true,
        returning = true,
        codecs = CodecSet(
            scalars = STANDARD_SCALARS + mapOf(
                "java.util.UUID" to postgres("uuid"),
                "java.time.Instant" to postgres("instant"),
            ),
            lists = mapOf(
                "kotlin.String" to postgres("strings"),
                "java.util.UUID" to postgres("uuids"),
            ),
        ),
    ),

    /** What any JDBC database runs: no `returning`, no tenant function and only the standard codecs. */
    Standard(tenantScope = false, returning = false, codecs = CodecSet(STANDARD_SCALARS, emptyMap())),
    ;

    /** As the option spells it. */
    val optionName: String get() = name.lowercase()
}

/** The dialect the option [value] names, or null for one the plugin does not know. */
internal fun pluginDialect(value: String): PluginDialect? = PluginDialect.entries.firstOrNull { it.optionName == value }
