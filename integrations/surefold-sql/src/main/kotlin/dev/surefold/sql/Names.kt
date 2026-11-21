package dev.surefold.sql

/** How a constructor property is spelled as a column. Snake is the default: `venueId` matches `venue_id`. */
enum class NameCase { Snake, Camel, Pascal, UpperSnake }

/**
 * Whether the plugin adds the tenant predicate to a statement. A statement that names neither takes the module's
 * default: [Tenant] when the plugin has the `tenantColumn` and `tenantPredicate` options, [Unscoped] when it has not.
 */
enum class Scope { Tenant, Unscoped }

/** The column case for one data class, overriding the module option `names`. */
@Target(AnnotationTarget.CLASS)
annotation class ColumnName(val value: NameCase)

/** The column for one property. The literal wins over [NameCase]. */
@Target(AnnotationTarget.PROPERTY)
annotation class SqlName(val value: String)
