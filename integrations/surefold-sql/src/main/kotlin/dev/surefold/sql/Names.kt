package dev.surefold.sql

/** How a constructor property is spelled as a column. Snake is the default: `venueId` matches `venue_id`. */
enum class NameCase { Snake, Camel, Pascal, UpperSnake }

/** A statement is tenant-scoped unless it opts into the platform. */
enum class Scope { Tenant, Platform }

/** The column case for one data class, overriding the module option `names`. */
@Target(AnnotationTarget.CLASS)
annotation class ColumnName(val value: NameCase)

/** The column for one property. The literal wins over [NameCase]. */
@Target(AnnotationTarget.PROPERTY)
annotation class SqlName(val value: String)
