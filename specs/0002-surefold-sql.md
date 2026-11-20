# 0002 — surefold-sql: typed SQL from Doorlist

## Problem

Doorlist wrote a typed SQL library for itself: `sql("...")` calls that a Kotlin compiler plugin rewrites at compile
time into prepared statements, with row codecs, a query catalog and SQLSTATE mapping (Doorlist specs 0086, 0087).
About 3,750 lines across two modules, `:sql` (runtime) and `:sql-plugin` (compiler plugin). Neither imports anything
else from Doorlist, but both are named for it and hard-code its tenancy: every tenant-scoped statement gains
`tenant_id = doorlist.current_tenant()`. Any other Surefold user wanting typed SQL has to copy the code.

## Not doing

- No new SQL features, dialects or codecs. The code moves as it is, apart from the renames and the one option below.
- No Gradle plugin. Users wire the compiler plugin through `kotlinCompilerPluginClasspath`, as Doorlist does today.
- No Doorlist change in this repository. Doorlist switching to the artifact is a Doorlist spec of its own.
- No Central release (AGENTS.md); snapshots only.

## Shape

Two modules under `integrations/`:

- `surefold-sql`: runtime. Depends on `surefold` and `surefold-stream` only; JDBC is the JDK's.
- `surefold-sql-plugin`: compiler plugin. Its one outside library is `kotlin-compiler-embeddable` (compileOnly).

Renames: package `doorlist.sql` → `dev.surefold.sql`, plugin id `doorlist.sql` → `dev.surefold.sql`, catalog resource
`META-INF/doorlist/sql.catalog` → `META-INF/surefold/sql.catalog`, the "not rewritten" message names surefold.

Tenant splicing becomes two plugin options, set together or not at all. Without them, no statement is spliced and an
omitted scope means `Scope.Unscoped`:

```kotlin
// build.gradle.kts of a user that wants Doorlist's behaviour
dependencies {
    implementation("dev.surefold:surefold-sql:$surefoldIntegrations")
    add("kotlinCompilerPluginClasspath", "dev.surefold:surefold-sql-plugin:$surefoldIntegrations")
}
kotlin.compilerOptions.freeCompilerArgs.addAll(
    "-P", "plugin:dev.surefold.sql:tenantColumn=tenant_id",
    "-P", "plugin:dev.surefold.sql:tenantPredicate=doorlist.current_tenant()",
)
```

```kotlin
// main code, unchanged apart from the import
import dev.surefold.sql.*
val rows = sql("select id, name from venue where id = ?").query<Venue>()
```

## Why this shape

Options rather than an interface: the splice happens at compile time, so the column and predicate must be known to the
compiler, and plugin options are the plugin's existing way to take settings (`catalog`, `names`, `dialect`). The
alternative, dropping tenant splicing and leaving isolation to callers, was rejected by the owner (20 November 2026):
it is the library's main safety property for Doorlist. A dialect without tenant support (`standard`) still refuses
tenant scope, as today.

## Stack

Below the stack: core and this repository build with Kotlin 2.4.21, Doorlist's version, since a compiler plugin
loads only into the compiler it was built against (surefold/surefold-core#1119, surefold/surefold-integrations#14).

- [ ] **`sql/move`** — `surefold-sql` and `surefold-sql-plugin` with their tests, renamed, catalog path changed,
      still splicing the fixed Doorlist predicate. One entry, not two: the runtime's tests need the plugin to
      rewrite their `sql()` calls (owner, 20 November 2026). Functions over this repository's detekt limits are
      split rather than suppressed.
      Done when: `./gradlew :surefold-sql:build :surefold-sql-plugin:build` is green with the moved suites.
- [ ] **`sql/tenant-options`** — `tenantColumn` and `tenantPredicate` options replace the hard-coded strings;
      `Scope.Platform` becomes `Scope.Unscoped`, the default when no tenant options are set.
      Done when: plugin stories cover both options set, neither set, one set (error), and `Scope.Tenant` with neither
      set (error), each checking the compiled text or diagnostic.

`sql/move` runs far past ~200 lines because it is a move; the reviewable part is the diff against Doorlist's copy.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

Then, in Doorlist (its own spec), the full build green against the published snapshot with the two options set.

## Open questions

All answered by the owner, 20 November 2026, taking the drafted recommendations:

1. **Default scope with no tenant options.** An omitted scope means unscoped; an explicit `Scope.Tenant` is a compile
   error.
2. **Rename `Scope.Platform`.** It becomes `Scope.Unscoped`, in `sql/tenant-options`. Doorlist renames at switch-over.
3. **Both options or one.** Both are required together: a column without a predicate, or the reverse, is an error.
4. **History.** A plain copy, with a note linking Doorlist specs 0086 and 0087. No `git filter-repo`.
5. **Module folder.** `integrations/`, beside `surefold-jdbc-otel`.
