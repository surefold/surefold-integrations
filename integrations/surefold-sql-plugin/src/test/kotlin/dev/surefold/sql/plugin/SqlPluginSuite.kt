package dev.surefold.sql.plugin

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.sql.Frag
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader

object SqlPluginSuite : Suite() {
    init {
        test("the same prepared text under two ids is two questions") {
            val compiled = ok(
                "same-wording",
                snippet(
                    """
                object Questions {
                    fun first(id: UUID) = sql("alpha.find", "select id from bookings where id = ${'$'}id").query<UUID>()
                    fun second(id: UUID) =
                        sql("alpha.same", "select id from bookings where id = ${'$'}id").query<UUID>()
                }
                    """.trimIndent(),
                ),
            )
            val catalog = compiled.catalog.readText()
            val booked = "select id from bookings where bookings.tenant_id = doorlist.current_tenant() and id = ?"
            catalog.contains("alpha.find $booked") shouldBe true
            catalog.contains("alpha.same $booked") shouldBe true
        }

        test("two calls that claim one id fail the build") {
            refused(
                "duplicate-id",
                snippet(
                    """
                object Questions {
                    fun first(id: UUID) = sql("alpha.find", "select id from bookings where id = ${'$'}id").query<UUID>()
                    fun second(id: UUID) =
                        sql("alpha.find", "select name from people where name = ${'$'}id").query<String>()
                }
                    """.trimIndent(),
                ),
                "alpha.find is already declared",
            )
        }

        test("a text the plugin cannot decompose fails and names the call") {
            refused(
                "not-a-template",
                snippet(
                    """
                object Questions {
                    fun pass(text: String) = sql(text)
                }
                    """.trimIndent(),
                ),
                "Questions.pass: the text is not a template the plugin can decompose",
            )
        }

        test("a value with no codec fails and names the call") {
            refused(
                "no-codec",
                snippet(
                    """
                class Weird(val name: String)
                object Questions {
                    fun hold(value: Weird) = sql("select ${'$'}value")
                }
                    """.trimIndent(),
                ),
                "Questions.hold: no codec for sample.Weird",
            )
        }

        test("a data class that does not line up with the select list fails") {
            refused(
                "misaligned",
                snippet(
                    """
                data class Person(val name: String, val age: Int)
                object Questions {
                    fun read(name: String) = sql("select name from people where name = ${'$'}name").query<Person>()
                }
                    """.trimIndent(),
                ),
                "Questions.read: Person does not line up with the select list",
            )
        }

        test("a spliced identifier that is not a constant fails") {
            refused(
                "dynamic-ident",
                snippet(
                    """
                import dev.surefold.sql.ident
                object Questions {
                    fun listed(table: String) = sql("select id from ${'$'}{ident(table)}")
                }
                    """.trimIndent(),
                ),
                "Questions.listed: a spliced identifier is not a constant",
            )
        }

        test("two statements, a raw dollar, a raw placeholder and a create are refused") {
            refused(
                "two-statements",
                snippet("""object Questions { fun both() = sql("select 1; select 2") }"""),
                "Questions.both: the statement is not one select, insert, update, delete or with",
            )
            refused(
                "raw-dollar",
                snippet("""object Questions { fun bare() = sql("select ${"\\$"}id") }"""),
                "Questions.bare: the template has a \$ the plugin does not bind",
            )
            refused(
                "raw-placeholder",
                snippet("""object Questions { fun bare() = sql("select id from bookings where id = ?") }"""),
                "Questions.bare: the template has a placeholder the plugin does not bind",
            )
            refused(
                "create-table",
                snippet("""object Questions { fun make() = sql("create table people (name text)") }"""),
                "Questions.make: the statement is not one select, insert, update, delete or with",
            )
        }

        test("a 301st declaration is over the catalog ceiling") {
            val body = (0 until CATALOG_LIMIT + 1).joinToString("\n") { index ->
                "    fun q$index() = sql(\"select 1\")"
            }
            val over = "the catalog would exceed $CATALOG_LIMIT entries"
            refused("ceiling", snippet("object Questions {\n$body\n}"), over)
        }

        test("trimIndent, a constant identifier, a value class and a data class are rewritten") {
            val trimmed = ok(
                "trim",
                snippet(
                    """
                object Questions {
                    fun find(id: UUID) = sql(${'"'}""
                        select id
                        from bookings
                        where id = ${'$'}id
                    ${'"'}"".trimIndent()).query<UUID>()
                }
                    """.trimIndent(),
                ),
            )
            val where = "where bookings.tenant_id = doorlist.current_tenant() and id = ?"
            trimmed.catalog.readText().contains("select id\\nfrom bookings\\n$where") shouldBe true
            val named = ok(
                "ident",
                snippet(
                    """
                import dev.surefold.sql.ident
                object Questions {
                    fun listed() = sql("select id from ${'$'}{ident("bookings")}")
                }
                    """.trimIndent(),
                ),
            )
            val listed = "Questions.listed select id from bookings where bookings.tenant_id = " +
                "doorlist.current_tenant()"
            named.catalog.readText().contains(listed) shouldBe true
            val slots = ok(
                "value-class",
                snippet(
                    """
                @JvmInline value class SlotId(val value: UUID)
                object Questions {
                    fun find(id: SlotId) = sql("select id from bookings where id = ${'$'}id").query<SlotId>()
                }
                    """.trimIndent(),
                ),
            )
            mentions(slots, "sample/Questions.class", "inlineCodec") shouldBe true
            val people = ok(
                "data-class",
                snippet(
                    """
                data class Person(val name: String, val age: Int)
                object Questions {
                    fun insert(person: Person) = sql("insert into people (name, age) values (${'$'}person)")
                    fun written(person: Person) =
                        sql(
                            "insert into people (name, age) values (${'$'}person) returning name, age",
                        ).returning<Person>()
                }
                    """.trimIndent(),
                ),
            )
            val call = "doorlist.current_tenant()"
            val inserted = "Questions.insert insert into people (tenant_id, name, age) values ($call, ?, ?)"
            people.catalog.readText().contains(inserted) shouldBe true
            mentions(people, "sample/Questions.class", "dataCodec") shouldBe true
            val loader = URLClassLoader(arrayOf(people.classes.toURI().toURL()), Frag::class.java.classLoader)
            val personClass = loader.loadClass("sample.Person")
            val person = personClass.getConstructor(String::class.java, java.lang.Integer.TYPE).newInstance("Ada", 30)
            val questions = loader.loadClass("sample.Questions")
            val instance = questions.getField("INSTANCE").get(null)
            val frag = questions.getMethod("insert", personClass).invoke(instance, person) as Frag
            frag.id.value shouldBe "Questions.insert"
            frag.sql shouldBe "insert into people (tenant_id, name, age) values ($call, ?, ?)"
            loader.close()
        }

        test("a hand-written codec is used, and two codecs for one type are ambiguous") {
            val paid = ok(
                "custom-codec",
                snippet(
                    """
                import dev.surefold.fail.Either
                import dev.surefold.fail.right
                import dev.surefold.sql.DbCodec
                import dev.surefold.sql.SqlFailure
                import java.sql.PreparedStatement
                import java.sql.ResultSet

                data class Money(val pence: Int)
                object MoneyCodec : DbCodec<Money> {
                    override val placeholders: Int = 1
                    override fun write(statement: PreparedStatement, index: Int, value: Money) {
                        statement.setString(index, "money")
                    }
                    override fun read(row: ResultSet, index: Int): Either<SqlFailure, Money> = Money(1).right()
                }
                object Questions {
                    fun pay(money: Money) = sql("insert into payments (pence) values (${'$'}money)")
                }
                    """.trimIndent(),
                ),
            )
            mentions(paid, "sample/Questions.class", "MoneyCodec") shouldBe true
            mentions(paid, "sample/Questions.class", "dataCodec") shouldBe false
            refused(
                "ambiguous-codec",
                snippet(
                    """
                import dev.surefold.fail.Either
                import dev.surefold.fail.right
                import dev.surefold.sql.DbCodec
                import dev.surefold.sql.SqlFailure
                import java.sql.PreparedStatement
                import java.sql.ResultSet

                data class Money(val pence: Int)
                object FirstCodec : DbCodec<Money> {
                    override val placeholders: Int = 1
                    override fun write(statement: PreparedStatement, index: Int, value: Money) = Unit
                    override fun read(row: ResultSet, index: Int): Either<SqlFailure, Money> = Money(1).right()
                }
                object SecondCodec : DbCodec<Money> {
                    override val placeholders: Int = 1
                    override fun write(statement: PreparedStatement, index: Int, value: Money) = Unit
                    override fun read(row: ResultSet, index: Int): Either<SqlFailure, Money> = Money(1).right()
                }
                object Questions {
                    fun pay(money: Money) = sql("insert into payments (pence) values (${'$'}money)")
                }
                    """.trimIndent(),
                ),
                "Questions.pay: ambiguous codec for sample.Money",
            )
        }

        test("a camel case data class, a SqlName and snake case line up") {
            val camel = ok(
                "camel",
                snippet(
                    """
                import dev.surefold.sql.ColumnName
                import dev.surefold.sql.NameCase
                @ColumnName(NameCase.Camel)
                data class Court(val venueId: UUID)
                object Questions {
                    fun read(id: UUID) = sql("select venueId from courts where id = ${'$'}id").query<Court>()
                }
                    """.trimIndent(),
                ),
            )
            camel.catalog.readText().contains("select venueId from courts") shouldBe true
            mentions(camel, "sample/Questions.class", "venue_id") shouldBe false
            val named = ok(
                "sql-name",
                snippet(
                    """
                import dev.surefold.sql.SqlName
                data class Slot(val venueId: UUID, @SqlName("start_time") val start: String)
                object Questions {
                    fun read(id: UUID) =
                        sql("select venue_id, start_time from slots where id = ${'$'}id").query<Slot>()
                }
                    """.trimIndent(),
                ),
            )
            named.catalog.readText().contains("start_time") shouldBe true
            val snake = ok(
                "snake",
                snippet(
                    """
                data class Court(val venueId: UUID)
                object Questions {
                    fun read(id: UUID) = sql("select venue_id from courts where id = ${'$'}id").query<Court>()
                }
                    """.trimIndent(),
                ),
            )
            snake.catalog.readText().contains("select venue_id from courts") shouldBe true
            val module = ok(
                "names-camel",
                snippet(
                    """
                data class Court(val venueId: UUID)
                object Questions {
                    fun read(id: UUID) = sql("select venueId from courts where id = ${'$'}id").query<Court>()
                }
                    """.trimIndent(),
                ),
                names = "camel",
            )
            module.catalog.readText().contains("select venueId from courts") shouldBe true
        }

        test("a tenant-scoped statement splices current_tenant and does not bind a tenant id") {
            val compiled = ok(
                "tenant-splice",
                snippet(
                    """
                object Questions {
                    fun courts(venue: UUID) =
                        sql("select id from resources where venue_id = ${'$'}venue order by sort, id")
                    fun paid(id: UUID) =
                        sql(
                            "select o.id from orders o join payments p on p.order_id = o.id where o.id = ${'$'}id",
                        )
                    fun rename(name: String) = sql("update people set name = ${'$'}name")
                    fun plant() = sql("insert into people (name) values ('Ada'), ('Bea')")
                }
                    """.trimIndent(),
                ),
            )
            val lines = compiled.catalog.readText().lines()
            val courts = lines.first { it.startsWith("Questions.courts ") }
            val call = "doorlist.current_tenant()"
            courts.contains("resources.tenant_id = $call") shouldBe true
            courts.count { it == '?' } shouldBe 1
            val paid = lines.first { it.startsWith("Questions.paid ") }
            paid.contains("o.tenant_id = $call") shouldBe true
            paid.contains("p.tenant_id = $call") shouldBe true
            val rename = lines.first { it.startsWith("Questions.rename ") }
            rename.contains("where people.tenant_id = $call") shouldBe true
            val plant = lines.first { it.startsWith("Questions.plant ") }
            val inserted = "insert into people (tenant_id, name) values ($call, 'Ada'), ($call, 'Bea')"
            plant.contains(inserted) shouldBe true
        }

        test("platform scope leaves the text unchanged") {
            val compiled = ok(
                "platform",
                snippet(
                    """
                import dev.surefold.sql.Scope
                object Questions {
                    fun read(id: UUID) = sql("select id from bookings where id = ${'$'}id", Scope.Platform)
                }
                    """.trimIndent(),
                ),
            )
            val catalog = compiled.catalog.readText()
            catalog.contains("doorlist.current_tenant()") shouldBe false
            catalog.contains("Questions.read select id from bookings where id = ?") shouldBe true
        }

        test("with, union and insert-select splice each real table") {
            val compiled = ok(
                "compound",
                snippet(
                    """
                data class Total(val n: Int)
                data class Kept(val id: java.util.UUID)
                object Questions {
                    fun due(id: java.util.UUID) = sql(
                        "with due as (select id from jobs where id = ${'$'}id) select id from due",
                    )
                    fun both(a: java.util.UUID, b: java.util.UUID) = sql(
                        "select id from holds where id = ${'$'}a union all select hold_id as id from orders where id = ${'$'}b",
                    )
                    fun copy(month: String) = sql("insert into text_usage (month) select ${'$'}month")
                    fun totals() = sql("select count(*) as n from courts").query<Total>()
                    fun ids() = sql("select distinct id from courts").query<Kept>()
                }
                    """.trimIndent(),
                ),
            )
            val lines = compiled.catalog.readText().lines()
            val call = "doorlist.current_tenant()"
            val due = lines.first { it.startsWith("Questions.due ") }
            due.contains("jobs.tenant_id = $call") shouldBe true
            due.contains("due.tenant_id") shouldBe false
            val both = lines.first { it.startsWith("Questions.both ") }
            both.contains("holds.tenant_id = $call") shouldBe true
            both.contains("orders.tenant_id = $call") shouldBe true
            val copy = lines.first { it.startsWith("Questions.copy ") }
            copy.contains("insert into text_usage (tenant_id, month)") shouldBe true
            copy.contains("select $call, ?") shouldBe true
            lines.any { it.startsWith("Questions.totals ") } shouldBe true
            lines.any { it.startsWith("Questions.ids ") } shouldBe true
        }

        test("an unparseable from fails the build and names the call") {
            refused(
                "unparsed-from",
                snippet(
                    """
                object Questions {
                    fun read() = sql("select id from (select id from resources) r")
                }
                    """.trimIndent(),
                ),
                "Questions.read: a from or insert target is not a table the plugin can name",
            )
        }

        test("an empty SqlName and a scope that is not a constant fail") {
            refused(
                "empty-sql-name",
                snippet(
                    """
                import dev.surefold.sql.SqlName
                data class Slot(@SqlName("") val start: String)
                object Questions {
                    fun read() = sql("select start_time from slots").query<Slot>()
                }
                    """.trimIndent(),
                ),
                "Questions.read: @SqlName is empty",
            )
            refused(
                "dynamic-scope",
                snippet(
                    """
                import dev.surefold.sql.Scope
                object Questions {
                    fun read(scope: Scope) = sql("select id from courts", scope)
                }
                    """.trimIndent(),
                ),
                "Questions.read: the scope is not a constant",
            )
        }

        test("the names option must be one of the four cases") {
            refused(
                "bad-names",
                snippet("""object Questions { fun read() = sql("select 1") }"""),
                "the names option is not snake, camel, pascal or upper-snake",
                names = "nope",
            )
        }

        test("the plugin sources do not suppress deprecation") {
            val suppressed = File("src/main/kotlin").walkTopDown()
                .filter { it.extension == "kt" }
                .filter { it.readText().contains("Suppress(\"DEPRECATION\")") }
                .toList()
            suppressed.isEmpty() shouldBe true
        }

        test("a uuid, an instant or an array binds through PostgresCodecs; the standard types never name it") {
            val postgres = ok(
                "postgres-codecs",
                snippet(
                    """
                object Questions {
                    fun byId(id: UUID) = sql("select id from bookings where id = ${'$'}id").query<UUID>()
                    fun since(at: java.time.Instant) = sql("select id from bookings where at > ${'$'}at").query<UUID>()
                    fun tagged(tags: List<String>) =
                        sql("select id from bookings where tags && ${'$'}tags").query<UUID>()
                }
                    """.trimIndent(),
                ),
            )
            mentions(postgres, "sample/Questions.class", "PostgresCodecs") shouldBe true
            val standard = ok(
                "standard-codecs",
                snippet(
                    """
                object Questions {
                    fun named(name: String, age: Int) =
                        sql("select name from people where name = ${'$'}name and age = ${'$'}age").query<String>()
                }
                    """.trimIndent(),
                ),
            )
            mentions(standard, "sample/Questions.class", "PostgresCodecs") shouldBe false
            mentions(standard, "sample/Questions.class", "dev/surefold/sql/Codecs") shouldBe true
        }

        test("the dialect option must name a dialect the plugin knows") {
            refused(
                "dialect-unknown",
                snippet("object Questions { fun one() = sql(\"select 1\", dev.surefold.sql.Scope.Platform) }"),
                "the dialect option is not postgres or standard",
                dialect = "nonsense",
            )
        }

        test("naming postgres compiles exactly what the default does") {
            val source = snippet(
                """
            object Questions {
                fun byId(id: UUID) = sql("select id from bookings where id = ${'$'}id").query<UUID>()
                fun add(name: String) =
                    sql("insert into people (name) values (${'$'}name) returning id").returning<UUID>()
            }
                """.trimIndent(),
            )
            val default = ok("dialect-default", source)
            val named = ok("dialect-postgres", source, dialect = "postgres")
            named.catalog.readText() shouldBe default.catalog.readText()
        }

        test("the standard dialect refuses returning, by call and by text, naming the dialect") {
            refused(
                "standard-returning-call",
                snippet(
                    """
                object Questions {
                    fun add(name: String) =
                        sql("insert into people (name) values (${'$'}name) returning id", dev.surefold.sql.Scope.Platform)
                            .returning<UUID>()
                }
                    """.trimIndent(),
                ),
                "Questions.add: the standard dialect has no returning",
                dialect = "standard",
            )
            refused(
                "standard-returning-text",
                snippet(
                    """
                object Questions {
                    fun add(name: String) =
                        sql("insert into people (name) values (${'$'}name) returning id", dev.surefold.sql.Scope.Platform)
                }
                    """.trimIndent(),
                ),
                "Questions.add: the standard dialect has no returning",
                dialect = "standard",
            )
        }

        test("the standard dialect binds only standard types, and has no tenant scope to splice") {
            refused(
                "standard-uuid",
                snippet(
                    """
                object Questions {
                    fun byId(id: UUID) = sql("select name from people where id = ${'$'}id", dev.surefold.sql.Scope.Platform)
                        .query<String>()
                }
                    """.trimIndent(),
                ),
                "no codec for java.util.UUID in the standard dialect",
                dialect = "standard",
            )
            refused(
                "standard-tenant",
                snippet(
                    """
                object Questions {
                    fun named(name: String) = sql("select name from people where name = ${'$'}name")
                }
                    """.trimIndent(),
                ),
                "Questions.named: the standard dialect has no tenant scope; name Scope.Platform",
                dialect = "standard",
            )
            val plain = ok(
                "standard-plain",
                snippet(
                    """
                object Questions {
                    fun named(name: String, age: Int) =
                        sql(
                            "select name from people where name = ${'$'}name and age = ${'$'}age",
                            dev.surefold.sql.Scope.Platform,
                        ).query<String>()
                }
                    """.trimIndent(),
                ),
                dialect = "standard",
            )
            val named = "Questions.named select name from people where name = ? and age = ?"
            plain.catalog.readText().contains(named) shouldBe true
            mentions(plain, "sample/Questions.class", "PostgresCodecs") shouldBe false
        }

        test("the sql runtime classpath does not carry this plugin, the compiler or a database driver") {
            val runtime = File("build/snippet-classpath.txt").readText()
            runtime.contains("sql-plugin") shouldBe false
            runtime.contains("kotlin-compiler") shouldBe false
            // Spec 0087 rule 7: the driver is the caller's. `store` declares Postgres's; `:sql` names none.
            runtime.contains("org.postgresql") shouldBe false
        }
    }
}

private class Compiled(val code: ExitCode, val log: String, val catalog: File, val classes: File)

private fun ok(name: String, source: String, names: String? = null, dialect: String? = null): Compiled {
    val compiled = compile(name, source, names, dialect)
    if (compiled.code != ExitCode.OK) compiled.log shouldBe "compilation failed"
    return compiled
}

private fun refused(name: String, source: String, needle: String, names: String? = null, dialect: String? = null) {
    val compiled = compile(name, source, names, dialect)
    compiled.code shouldBe ExitCode.COMPILATION_ERROR
    if (!compiled.log.lowercase().contains(needle.lowercase())) compiled.log shouldBe needle
}

private fun mentions(compiled: Compiled, classFile: String, symbol: String): Boolean {
    val bytes = File(compiled.classes, classFile).readBytes()
    return String(bytes, Charsets.ISO_8859_1).contains(symbol)
}

private fun snippet(body: String): String = """
    package sample

    import dev.surefold.sql.query
    import dev.surefold.sql.returning
    import dev.surefold.sql.sql
    import java.util.UUID

    $body
""".trimIndent()

private fun compile(name: String, source: String, names: String? = null, dialect: String? = null): Compiled {
    val root = File("build/snippets/$name")
    root.deleteRecursively()
    root.mkdirs()
    val file = File(root, "Source.kt")
    file.writeText(source)
    val classes = File(root, "classes")
    classes.mkdirs()
    val catalog = File(root, "catalog.txt")
    val plugin = File("build/plugin-path.txt").readText().trim()
    val classpath = File("build/snippet-classpath.txt").readLines()
        .filter { it.isNotBlank() }
        .joinToString(File.pathSeparator)
    val captured = ByteArrayOutputStream()
    val namesArg = if (names == null) emptyList() else listOf("-P", "plugin:dev.surefold.sql:names=$names")
    val dialectArg = if (dialect == null) emptyList() else listOf("-P", "plugin:dev.surefold.sql:dialect=$dialect")
    val options = listOf(
        "-classpath",
        classpath,
        "-d",
        classes.absolutePath,
        "-jvm-target",
        "21",
        "-no-stdlib",
        "-Xplugin=$plugin",
        "-P",
        "plugin:dev.surefold.sql:catalog=${catalog.absolutePath}",
    ) + namesArg + dialectArg + file.absolutePath
    val code = PrintStream(captured, true, Charsets.UTF_8).use { stream ->
        K2JVMCompiler().exec(stream, *options.toTypedArray())
    }
    return Compiled(code, captured.toString(Charsets.UTF_8), catalog, classes)
}
