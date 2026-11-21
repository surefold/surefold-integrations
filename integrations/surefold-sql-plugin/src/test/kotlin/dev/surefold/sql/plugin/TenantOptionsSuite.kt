package dev.surefold.sql.plugin

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe

/** The tenantColumn and tenantPredicate options: what a statement is spliced with, and the scope it defaults to. */
object TenantOptionsSuite : Suite() {
    init {
        test("the tenant options name the column and the predicate a tenant statement is spliced with") {
            val source = snippet(
                """
            object Questions {
                fun byId(id: UUID) = sql("select id from bookings where id = ${'$'}id").query<UUID>()
            }
                """.trimIndent(),
            )
            val own = ok("tenant-own", source, tenant = tenantOptions("org_id", "auth.org()"))
            val spliced = "Questions.byId select id from bookings where bookings.org_id = auth.org() and id = ?"
            own.catalog.readText().contains(spliced) shouldBe true
        }

        test("without the tenant options an omitted scope is unscoped, and Scope.Tenant is refused") {
            val plain = ok(
                "tenant-none",
                snippet(
                    """
                object Questions {
                    fun byId(id: UUID) = sql("select id from bookings where id = ${'$'}id").query<UUID>()
                }
                    """.trimIndent(),
                ),
                tenant = emptyList(),
            )
            plain.catalog.readText().contains("Questions.byId select id from bookings where id = ?") shouldBe true
            refused(
                "tenant-none-explicit",
                snippet(
                    """
                object Questions {
                    fun byId(id: UUID) =
                        sql("select id from bookings where id = ${'$'}id", dev.surefold.sql.Scope.Tenant).query<UUID>()
                }
                    """.trimIndent(),
                ),
                "Questions.byId: Scope.Tenant needs the tenantColumn and tenantPredicate options",
                tenant = emptyList(),
            )
        }

        test("one tenant option without the other is refused") {
            val source = snippet("object Questions { fun one() = sql(\"select 1\", dev.surefold.sql.Scope.Unscoped) }")
            val needle = "tenantColumn and tenantPredicate are set together or not at all"
            refused("tenant-column-only", source, needle, tenant = tenantOptions("tenant_id", null))
            refused("tenant-predicate-only", source, needle, tenant = tenantOptions(null, "app.current_tenant()"))
        }
    }
}
