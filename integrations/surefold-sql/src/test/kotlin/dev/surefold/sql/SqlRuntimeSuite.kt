package dev.surefold.sql

import dev.surefold.Shared
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.alone
import dev.surefold.junit.shouldBe
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.Stream
import dev.surefold.stream.fail
import dev.surefold.stream.from
import dev.surefold.stream.map
import dev.surefold.stream.run
import dev.surefold.stream.runCollect
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val BATCH_ROWS = 500

@JvmInline
value class SlotId(val value: UUID)

@JvmInline
value class Code private constructor(val value: String) {
    companion object {
        private const val RULE = "letters"

        fun of(raw: String): Code = Code(raw.takeIf { RULE.isNotEmpty() } ?: raw)
    }
}

data class Person(val name: String, val age: Int, val born: LocalDate)

data class Labeled(val id: SlotId, val name: String)

data class PersonRow(val name: String, val age: Int)

data class Money(val pence: Int)

object MoneyCodec : DbCodec<Money> {
    override val placeholders: Int = 1

    override fun write(statement: PreparedStatement, index: Int, value: Money) {
        statement.setString(index, "money:${value.pence}")
    }

    override fun read(row: ResultSet, index: Int): Either<SqlFailure, Money> =
        row.getString(index)?.let { text -> Money(text.removePrefix("money:").toInt()).right() }
            ?: SqlFailure.Unexpected(IllegalStateException("money is null")).left()
}

object BookingSql {
    fun findSlot(id: UUID): Query<UUID> = sql("select id from bookings where id = $id").query<UUID>()

    fun both(id: UUID, name: String): Pair<Query<UUID>, Query<String>> =
        sql("select id from bookings where id = $id").query<UUID>() to
            sql("select name from people where name = $name").query<String>()

    fun stable(id: UUID): Query<UUID> = sql("booking.find", "select id from bookings where id = $id").query<UUID>()

    fun sameWording(id: UUID): Query<UUID> = sql("booking.same", "select id from bookings where id = $id").query<UUID>()

    fun twice(id: UUID): Query<UUID> = sql("select id from bookings where id = $id and id = $id").query<UUID>()

    fun indented(id: UUID): Query<UUID> = sql(
        """
        select id
        from bookings
        where id = $id
        """.trimIndent(),
    ).query<UUID>()
}

object SlotSql {
    fun find(id: SlotId): Query<SlotId> = sql("select id from bookings where id = $id").query<SlotId>()
}

object PeopleSql {
    fun insert(person: PersonRow): Frag = sql("insert into people (name, age) values ($person)")

    fun read(name: String): Query<PersonRow> = sql("select name, age from people where name = $name").query<PersonRow>()
}

object PaySql {
    fun insert(money: Money): Frag = sql("insert into payments (pence) values ($money)")
}

/** The runtime, the catalog and the plugin, against a stub driver. Postgres is not required. */
object SqlRuntimeSuite : Suite() {
    init {
        test("an insert batch of 500 rows is two plans, and the values stay out of the text") {
            val codec = personCodec()
            val people = List(BATCH_ROWS) { index -> Person("person-$index", index, LocalDate.of(2020, 1, 1)) }
            val memory = RecordingJdbc.Memory()
            val connection = RecordingJdbc.connection(memory)
            val frag = insert("people.insert", "insert into people (name, age, born) values (?, ?, ?)", codec)
            frag.batch(connection, people).shouldBeRight() shouldBe 2
            memory.prepares shouldBe 2
            memory.calls[0].sql.let { sql ->
                tuples(sql) shouldBe BATCH_ROW_CAP
                sql.contains("person-") shouldBe false
            }
            memory.calls[1].sql.let { sql ->
                tuples(sql) shouldBe BATCH_ROWS - BATCH_ROW_CAP
                sql.contains("person-") shouldBe false
            }
            memory.calls[0].parameters shouldBe people.take(BATCH_ROW_CAP).flatMap { listOf(it.name, it.age, it.born) }
            memory.calls[1].parameters shouldBe people.drop(BATCH_ROW_CAP).flatMap { listOf(it.name, it.age, it.born) }
            memory.calls[0].closed shouldBe 1
            memory.calls[1].closed shouldBe 1
        }

        test("a tenant function inside one values tuple is repeated, and the author's values stay bound") {
            val codec = personCodec()
            val people = List(BATCH_ROWS) { index -> Person("person-$index", index, LocalDate.of(2020, 1, 1)) }
            val memory = RecordingJdbc.Memory()
            val call = "doorlist.current_tenant()"
            val text = "insert into people (tenant_id, name, age, born) values ($call, ?, ?, ?)"
            val frag = insert("people.tenant", text, codec)
            frag.batch(RecordingJdbc.connection(memory), people).shouldBeRight() shouldBe 2
            memory.prepares shouldBe 2
            val tuple = Regex("""\(${Regex.escape(call)}, \?, \?, \?\)""")
            tuple.findAll(memory.calls[0].sql).count() shouldBe BATCH_ROW_CAP
            tuple.findAll(memory.calls[1].sql).count() shouldBe BATCH_ROWS - BATCH_ROW_CAP
            memory.calls[0].sql.contains("person-") shouldBe false
            memory.calls[0].parameters shouldBe people.take(BATCH_ROW_CAP).flatMap { listOf(it.name, it.age, it.born) }
            val rest = people.drop(BATCH_ROW_CAP).flatMap { listOf(it.name, it.age, it.born) }
            memory.calls[1].parameters shouldBe rest
        }

        test("an empty batch prepares nothing") {
            val memory = RecordingJdbc.Memory()
            val frag = prepared("people.insert", "insert into people (name) values (?)", emptyArray(), Codecs.string)
            frag.batch(RecordingJdbc.connection(memory), emptyList<String>()).shouldBeRight() shouldBe 0
            memory.prepares shouldBe 0
        }

        test("on conflict do nothing stays on the statement and the count is the driver's") {
            val memory = RecordingJdbc.Memory()
            memory.updateCount = 0
            val text = "insert into people (name) values (?) on conflict do nothing"
            val frag = insert("people.ignore", text, Codecs.string)
            frag.batch(RecordingJdbc.connection(memory), listOf("Ada", "Bea")).shouldBeRight() shouldBe 0
            memory.calls.single().sql.contains("on conflict do nothing") shouldBe true
            memory.calls.single().sql.contains("Ada") shouldBe false
        }

        test("an insert the plugin found no tuple for is one JDBC batch, not a rewritten statement") {
            val memory = RecordingJdbc.Memory()
            val text = "insert into people (name) select ? where true"
            val frag = prepared("people.select", text, emptyArray(), Codecs.string)
            frag.batch(RecordingJdbc.connection(memory), listOf("Ada", "Bea", "Cy")).shouldBeRight() shouldBe 3
            memory.prepares shouldBe 1
            memory.calls.single().sql shouldBe text
            memory.calls.single().batches shouldBe 3
        }

        test("an update batch is one prepared statement") {
            val codec = dataCodec(
                Rename::class.java,
                arrayOf("name", "id"),
                arrayOf("name", "id"),
                arrayOf(Codecs.string.boxed(), PostgresCodecs.uuid.boxed()),
            )
            val memory = RecordingJdbc.Memory()
            val rows = listOf(Rename("Ada", UUID.fromString(ID)), Rename("Bea", UUID.fromString(OTHER)))
            val frag = prepared("people.rename", "update people set name = ? where id = ?", emptyArray(), codec)
            frag.batch(RecordingJdbc.connection(memory), rows).shouldBeRight() shouldBe rows.size
            memory.prepares shouldBe 1
            memory.calls.single().batches shouldBe rows.size
            memory.calls.single().sql shouldBe "update people set name = ? where id = ?"
        }

        test("a stream of rows is batched on the caller, and a failing stream prepares nothing") {
            val codec = personCodec()
            val people = List(BATCH_ROWS) { index -> Person("person-$index", index, LocalDate.of(2020, 1, 1)) }
            val memory = RecordingJdbc.Memory()
            val frag = insert("people.stream", "insert into people (name, age, born) values (?, ?, ?)", codec)
            frag.batch(RecordingJdbc.connection(memory), Stream.from(people)).shouldBeRight() shouldBe 2
            memory.prepares shouldBe 2
            val failed = RecordingJdbc.Memory()
            frag.batch(RecordingJdbc.connection(failed), Stream.fail("broken")).shouldBeLeft()
            failed.prepares shouldBe 0
        }

        test("list, one, optional and update run on the calling virtual thread") {
            val id = UUID.fromString(ID)
            val listed = onCaller {
                val memory = scripted(listOf(listOf(id)), listOf("id"))
                val query = prepared("people.one", "select id from people where id = ?", emptyArray(), null)
                    .query(PostgresCodecs.uuid)
                Seen(query.list(RecordingJdbc.connection(memory)), memory.calls.single().thread, Thread.currentThread())
            }
            listed.value.shouldBeRight() shouldBe listOf(id)
            listed.ran shouldBe listed.caller
            listed.caller.isVirtual shouldBe true
            val named = onCaller {
                val memory = scripted(listOf(listOf("Ada")), listOf("name"))
                val query = prepared("people.name", "select name from people", emptyArray(), null).query(Codecs.string)
                Seen(query.one(RecordingJdbc.connection(memory)), memory.calls.single().thread, Thread.currentThread())
            }
            named.value.shouldBeRight() shouldBe "Ada"
            named.ran shouldBe named.caller
            val missing = onCaller {
                val memory = scripted(emptyList(), listOf("name"))
                val query = prepared(
                    "people.missing",
                    "select name from people",
                    emptyArray(),
                    null,
                ).query(Codecs.string)
                Seen(
                    query.optional(RecordingJdbc.connection(memory)),
                    memory.calls.single().thread,
                    Thread.currentThread(),
                )
            }
            missing.value.shouldBeRight() shouldBe null
            missing.ran shouldBe missing.caller
            val touched = onCaller {
                val memory = RecordingJdbc.Memory()
                val frag = prepared(
                    "people.touch",
                    "update people set name = ?",
                    arrayOf(bind(Codecs.string, "Ada")),
                    null,
                )
                val updated = frag.update(RecordingJdbc.connection(memory))
                Seen(updated to memory.calls.single().parameters, memory.calls.single().thread, Thread.currentThread())
            }
            touched.value.first.shouldBeRight() shouldBe 1
            touched.value.second shouldBe listOf("Ada")
            touched.ran shouldBe touched.caller
        }

        test("one of none or many is a row count, and a driver failure still closes the statement") {
            val query = prepared("people.names", "select name from people", emptyArray(), null).query(Codecs.string)
            val none = scripted(emptyList(), listOf("name"))
            (query.one(RecordingJdbc.connection(none)).shouldBeLeft() as SqlFailure.RowCount).found shouldBe 0
            val many = scripted(listOf(listOf("Ada"), listOf("Bea")), listOf("name"))
            (query.one(RecordingJdbc.connection(many)).shouldBeLeft() as SqlFailure.RowCount).found shouldBe 2
            (query.optional(RecordingJdbc.connection(many)).shouldBeLeft() as SqlFailure.RowCount).found shouldBe 2
            val broken = scripted(listOf(listOf("Ada")), listOf("name"))
            broken.failOnRead = true
            (query.list(RecordingJdbc.connection(broken)).shouldBeLeft() is SqlFailure.Driver) shouldBe true
            broken.calls.single().closed shouldBe 1
        }

        test("a stream opens on first pull, on the thread that created it") {
            val memory = scripted(listOf(listOf("Ada")), listOf("name"))
            val query = prepared("people.streamed", "select name from people", emptyArray(), null).query(Codecs.string)
            val connection = RecordingJdbc.connection(memory)
            val opened = onCaller {
                val stream = query.stream(connection)
                val before = memory.prepares
                val exit = stream.runCollect().run(CallerThread).toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
                Seen(before to exit, memory.calls.single().thread, Thread.currentThread())
            }
            opened.value.first shouldBe 0
            val done = opened.value.second
            (done is Exit.Done) shouldBe true
            (done as Exit.Done).value shouldBe listOf("Ada")
            opened.ran shouldBe opened.caller
        }

        test("a pull on another thread fails before a row is read") {
            val memory = scripted(listOf(listOf("Ada")), listOf("name"))
            val query = prepared("people.owned", "select name from people", emptyArray(), null).query(Codecs.string)
            val stream = query.stream(RecordingJdbc.connection(memory))
            val exit = onCaller {
                stream.runCollect().run(CallerThread).toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
            }
            (exit is Exit.Failed) shouldBe true
            memory.prepares shouldBe 0
            memory.reads shouldBe 0
            val forks = scripted(listOf(listOf("Ada")), listOf("name"))
            val again = query.stream(RecordingJdbc.connection(forks))
            val refused = again.runCollect().run(Forks()).toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
            (refused is Exit.Failed) shouldBe true
            forks.prepares shouldBe 0
            forks.reads shouldBe 0
        }

        test("a batch the driver answers without counts counts each row it sent") {
            val codec = dataCodec(
                Rename::class.java,
                arrayOf("name", "id"),
                arrayOf("name", "id"),
                arrayOf(Codecs.string.boxed(), PostgresCodecs.uuid.boxed()),
            )
            val memory = RecordingJdbc.Memory(batchAnswer = Statement.SUCCESS_NO_INFO)
            val rows = List(BATCH_ROWS) { index -> Rename("person-$index", UUID.fromString(ID)) }
            val frag = prepared("people.rename.unknown", "update people set name = ? where id = ?", emptyArray(), codec)
            frag.batch(RecordingJdbc.connection(memory), rows).shouldBeRight() shouldBe BATCH_ROWS
        }

        test("waking a stream cancels its statement, and closing it twice closes once") {
            val memory = scripted(listOf(listOf("Ada")), listOf("name"))
            val query = prepared("people.wake", "select name from people", emptyArray(), null).query(Codecs.string)
            val cursor = openCursor(query, RecordingJdbc.connection(memory), Thread.currentThread())
            cursor.cancel()
            cursor.close()
            cursor.close()
            memory.calls.single().events.count { it == "cancel" } shouldBe 1
            memory.calls.single().closed shouldBe 1
        }

        // The meter is one for the process, so each test that reads it runs alone.
        test("a stream whose bind or execute fails closes its statement and records the failure", alone) {
            val meter = RecordingMeter()
            installQueryMeter(meter)
            val query = prepared(
                "people.stream.broken",
                "select name from people where name = ?",
                arrayOf(bind(Codecs.string, "Ada")),
                null,
            ).query(Codecs.string)
            val unbound = scripted(listOf(listOf("Ada")), listOf("name"), RecordingJdbc.Memory(failOnBind = true))
            val unrun = scripted(listOf(listOf("Ada")), listOf("name"), RecordingJdbc.Memory(failOnExecute = true))
            val exits = onCaller {
                listOf(unbound, unrun).map { memory ->
                    query.stream(RecordingJdbc.connection(memory)).runCollect().run(CallerThread)
                        .toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
                }
            }
            installQueryMeter(NoQueryMeter)
            exits.all { it is Exit.Failed } shouldBe true
            unbound.calls.single().closed shouldBe 1
            unrun.calls.single().closed shouldBe 1
            meter.samples.peek().filter { it.queryId == "people.stream.broken" }.map { it.errorType } shouldBe
                listOf("driver", "driver")
        }

        test("one and optional stop after two rows and record the row count", alone) {
            val meter = RecordingMeter()
            installQueryMeter(meter)
            val query = prepared("people.counted", "select name from people", emptyArray(), null).query(Codecs.string)
            val five = (1..5).map { listOf("person-$it") }
            val many = scripted(five, listOf("name"))
            val one = query.one(RecordingJdbc.connection(many))
            val again = scripted(five, listOf("name"))
            val optional = query.optional(RecordingJdbc.connection(again))
            installQueryMeter(NoQueryMeter)
            (one.shouldBeLeft() as SqlFailure.RowCount).found shouldBe 2
            (optional.shouldBeLeft() as SqlFailure.RowCount).found shouldBe 2
            many.reads shouldBe 2
            many.calls.single().events shouldBe listOf("maxRows=2")
            again.reads shouldBe 2
            meter.samples.peek().filter { it.queryId == "people.counted" }.map { it.errorType } shouldBe
                listOf("row_count", "row_count")
        }

        test("Postgres's SQLSTATEs are typed refusals, each with its cause; any other state stays Driver") {
            val typed = mapOf(
                "23505" to SqlFailure.UniqueViolation::class,
                "23514" to SqlFailure.CheckViolation::class,
                "23503" to SqlFailure.ForeignKeyViolation::class,
                "23502" to SqlFailure.NotNullViolation::class,
                "40001" to SqlFailure.SerializationFailure::class,
                "40P01" to SqlFailure.Deadlock::class,
                "42501" to SqlFailure.PermissionDenied::class,
            )
            typed.forEach { (state, kind) ->
                val error = SQLException("refused", state)
                val failure = Postgres.classify(error)
                (failure::class to failure.cause) shouldBe (kind to error)
            }
            listOf("22023", "23P01", null).forEach { state ->
                (Postgres.classify(SQLException("refused", state)) is SqlFailure.Driver) shouldBe true
            }
        }

        test("a wrapped refusal is typed by the first exception in the chain that carries a SQLSTATE") {
            val outer = SQLException("batch refused")
            outer.setNextException(SQLException("duplicate", "23505"))
            val failure = Postgres.classify(outer)
            (failure is SqlFailure.UniqueViolation) shouldBe true
            failure.cause shouldBe outer
            val wrapped = SQLException("wrapped", null, SQLException("locked", "40P01"))
            (Postgres.classify(wrapped) is SqlFailure.Deadlock) shouldBe true
        }

        test("a refused statement answers its typed failure and the meter names the kind", alone) {
            val meter = RecordingMeter()
            installQueryMeter(meter)
            val memory = RecordingJdbc.Memory(failOnExecute = true, failState = "23505")
            val binds = arrayOf(bind(Codecs.string, "Ada"))
            val frag = prepared("people.duplicate", "update people set name = ?", binds, null)
            val answer = frag.update(RecordingJdbc.connection(memory))
            installQueryMeter(NoQueryMeter)
            (answer.shouldBeLeft() is SqlFailure.UniqueViolation) shouldBe true
            memory.calls.single().closed shouldBe 1
            meter.samples.peek().single { it.queryId == "people.duplicate" }.errorType shouldBe "unique_violation"
        }

        test("a dialect's parameter limit sizes the chunks: 1000 rows of three are 256, 256, 256 and 232") {
            val memory = RecordingJdbc.Memory()
            val dialect = TestDialect(maxParameters = 999)
            val people = List(1000) { index -> Person("person-$index", index, LocalDate.of(2020, 1, 1)) }
            val frag = insert("people.limited", "insert into people (name, age, born) values (?, ?, ?)", personCodec())
            frag.batch(RecordingJdbc.connection(memory), people, dialect).shouldBeRight() shouldBe 4
            memory.calls.map { tuples(it.sql) } shouldBe listOf(256, 256, 256, 232)
            memory.calls.all { it.parameters.size <= 999 } shouldBe true
            val tight = RecordingJdbc.Memory()
            frag.batch(RecordingJdbc.connection(tight), people.take(10), TestDialect(maxParameters = 20))
                .shouldBeRight()
            tight.calls.map { tuples(it.sql) } shouldBe listOf(6, 4)
            val tiny = RecordingJdbc.Memory()
            frag.batch(RecordingJdbc.connection(tiny), people.take(2), TestDialect(maxParameters = 2)).shouldBeRight()
            tiny.calls.map { tuples(it.sql) } shouldBe listOf(1, 1)
        }

        test("a JDBC batch dialect sends 300 rows as one statement and two executeBatch calls, on the caller") {
            val memory = RecordingJdbc.Memory()
            val dialect = TestDialect(batchInsert = BatchInsert.JdbcBatch)
            val text = "insert into people (name, age, born) values (?, ?, ?)"
            val people = List(300) { index -> Person("person-$index", index, LocalDate.of(2020, 1, 1)) }
            val seen = onCaller {
                val count = insert("people.jdbc", text, personCodec())
                    .batch(RecordingJdbc.connection(memory), people, dialect)
                Seen(count, memory.calls.single().thread, Thread.currentThread())
            }
            seen.value.shouldBeRight() shouldBe 300
            seen.ran shouldBe seen.caller
            memory.prepares shouldBe 1
            memory.calls.single().sql shouldBe text
            memory.calls.single().batches shouldBe 300
            memory.calls.single().events shouldBe listOf("batch=256", "batch=44")
            memory.calls.single().parameters shouldBe people.flatMap { listOf(it.name, it.age, it.born) }
        }

        test("a dialect that returns no rows from a batch refuses before anything is prepared or read") {
            val memory = RecordingJdbc.Memory()
            val dialect = TestDialect(returnedRows = ReturnedRows.Unsupported)
            val query = insert("people.back", "insert into people (name) values (?) returning id", Codecs.string)
                .returning(PostgresCodecs.uuid)
            val listed = query.batchReturning(RecordingJdbc.connection(memory), listOf("Ada"), dialect)
            (listed.shouldBeLeft() as SqlFailure.NotSupported).dialect shouldBe "test"
            val pulled = Shared(0, "test.pulled")
            val rows = Stream.from(listOf("Ada", "Bea")).map { name -> name.also { pulled.update { n -> n + 1 } } }
            query.batchReturning(RecordingJdbc.connection(memory), rows, dialect).shouldBeLeft()
            memory.prepares shouldBe 0
            pulled.peek() shouldBe 0
        }

        test("a generated-keys dialect batches the rows and reads what the driver generated") {
            val id = UUID.fromString(ID)
            val other = UUID.fromString(OTHER)
            val memory = scripted(listOf(listOf(id), listOf(other)), listOf("id"))
            val dialect = TestDialect(returnedRows = ReturnedRows.GeneratedKeys, batchInsert = BatchInsert.JdbcBatch)
            val text = "insert into people (name) values (?)"
            val keys = insert("people.keys", text, Codecs.string).returning(PostgresCodecs.uuid)
                .batchReturning(RecordingJdbc.connection(memory), listOf("Ada", "Bea"), dialect)
            keys.shouldBeRight() shouldBe listOf(id, other)
            memory.calls.single().sql shouldBe text
            memory.calls.single().events shouldBe listOf("generatedKeys", "batch=2")
        }

        test("a returning dialect without multi-row inserts runs each row and keeps every answer") {
            val id = UUID.fromString(ID)
            val memory = scripted(listOf(listOf(id)), listOf("id"))
            val dialect = TestDialect(batchInsert = BatchInsert.JdbcBatch)
            val text = "insert into people (name) values (?) returning id"
            val ids = insert("people.each", text, Codecs.string).returning(PostgresCodecs.uuid)
                .batchReturning(RecordingJdbc.connection(memory), listOf("Ada", "Bea", "Cy"), dialect)
            ids.shouldBeRight() shouldBe listOf(id, id, id)
            memory.prepares shouldBe 1
            memory.calls.single().parameters shouldBe listOf("Ada", "Bea", "Cy")
        }

        test("Postgres widens a returning batch to one statement per chunk and reads every row back, list or stream") {
            val id = UUID.fromString(ID)
            val other = UUID.fromString(OTHER)
            val text = "insert into people (name) values (?) returning id"
            val query = insert("people.widened", text, Codecs.string).returning(PostgresCodecs.uuid)
            val listed = scripted(listOf(listOf(id), listOf(other)), listOf("id"))
            query.batch(RecordingJdbc.connection(listed), listOf("Ada", "Bea")).shouldBeRight() shouldBe
                listOf(id, other)
            listed.calls.single().sql shouldBe "insert into people (name) values (?), (?) returning id"
            listed.calls.single().parameters shouldBe listOf("Ada", "Bea")
            val streamed = scripted(listOf(listOf(id), listOf(other)), listOf("id"))
            val rows = Stream.from(listOf("Ada", "Bea"))
            query.batch(RecordingJdbc.connection(streamed), rows).shouldBeRight() shouldBe listOf(id, other)
            streamed.calls.single().parameters shouldBe listOf("Ada", "Bea")
        }

        test("an insert batch that returns rows counts the rows it read back") {
            val memory = scripted(listOf(listOf(UUID.fromString(ID)), listOf(UUID.fromString(OTHER))), listOf("id"))
            val frag = insert("people.counted", "insert into people (name) values (?) returning id", Codecs.string)
            frag.batch(RecordingJdbc.connection(memory), listOf("Ada", "Bea")).shouldBeRight() shouldBe 2
            memory.calls.single().sql shouldBe "insert into people (name) values (?), (?) returning id"
        }

        test("a batch whose codec does not fit the values tuple, or that is not a write, prepares nothing") {
            val memory = RecordingJdbc.Memory()
            val connection = RecordingJdbc.connection(memory)
            val person = Person("Ada", 30, LocalDate.of(2020, 1, 1))
            val narrow = insert("people.narrow", "insert into people (name) values (?)", personCodec())
            narrow.batch(connection, listOf(person)).shouldBeLeft()
            narrow.returning(PostgresCodecs.uuid).batch(connection, listOf(person)).shouldBeLeft()
            val untupled =
                prepared("people.untupled", "insert into people (name) values (?)", emptyArray(), Codecs.string)
            untupled.returning(PostgresCodecs.uuid).batch(connection, listOf("Ada")).shouldBeLeft()
            val read = prepared("people.read", "select name from people where name = ?", emptyArray(), Codecs.string)
            read.batch(connection, listOf("Ada")).shouldBeLeft()
            memory.prepares shouldBe 0
        }

        test("installed catalogs load from the class path with their escapes, and a line with no SQL is refused") {
            val dir = Files.createTempDirectory("sql-catalog")
            val file = dir.resolve(CATALOG)
            Files.createDirectories(file.parent)
            val text = "select 'a\\b'\nfrom people\r"
            Files.writeString(file, encodeCatalog(listOf("catalog.installed" to text)))
            URLClassLoader(arrayOf(dir.toUri().toURL()), null).use { loader ->
                QueryRegistry.loadInstalled(loader).shouldBeRight()
            }
            QueryRegistry.all()[QueryId("catalog.installed")] shouldBe text
            decodeCatalog("v1\nlonely\n").shouldBeLeft()
            dir.toFile().deleteRecursively() shouldBe true
        }

        test("a refusal is classified by the dialect in use, not by Postgres") {
            val dialect = TestDialect(classify = { error ->
                if (error.sqlState == "X1") SqlFailure.Deadlock(error) else SqlFailure.Driver(error)
            })
            val binds = arrayOf(bind(Codecs.string, "Ada"))
            val frag = prepared("people.classified", "update people set name = ?", binds, null)
            val own = RecordingJdbc.Memory(failOnExecute = true, failState = "X1")
            (frag.update(RecordingJdbc.connection(own), dialect).shouldBeLeft() is SqlFailure.Deadlock) shouldBe true
            val postgres = RecordingJdbc.Memory(failOnExecute = true, failState = "23505")
            (frag.update(RecordingJdbc.connection(postgres), dialect).shouldBeLeft() is SqlFailure.Driver) shouldBe true
            val streamed = RecordingJdbc.Memory(failOnExecute = true, failState = "X1")
            val query = prepared("people.classified.stream", "select name from people", emptyArray(), null)
                .query(Codecs.string)
            val exit = onCaller {
                query.stream(RecordingJdbc.connection(streamed), dialect).runCollect().run(CallerThread)
                    .toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
            }
            ((exit as Exit.Failed).error is SqlFailure.Deadlock) shouldBe true
        }

        test("Codecs holds the standard JDBC types; PostgresCodecs holds uuid, timestamptz and the arrays") {
            codecNames(Codecs::class.java) shouldBe
                setOf("string", "int", "long", "boolean", "localDate", "localTime", "bytes")
            codecNames(PostgresCodecs::class.java) shouldBe setOf("uuid", "instant", "strings", "uuids")
        }

        test("a call the plugin did not rewrite throws before prepare") {
            val memory = RecordingJdbc.Memory()
            val connection = RecordingJdbc.connection(memory)
            // The plugin rewrites every Kotlin sql() call, so this reaches the function body by reflection.
            when (val reached = unrewritten("select 1")) {
                is Either.Left -> (reached.value is IllegalStateException) shouldBe true
                is Either.Right -> reached.value.update(connection).shouldBeLeft()
            }
            memory.prepares shouldBe 0
        }

        test("the registry loads a catalog twice and refuses a changed statement") {
            val text = File("build/generated/sql/test-catalog.txt").readText()
            val catalog = decodeCatalog(text).shouldBeRight()
            val booking = "select id from bookings ${whereTenant("bookings", "id = ?")}"
            catalog[QueryId("BookingSql.findSlot")] shouldBe booking
            catalog[QueryId("BookingSql.both")] shouldBe booking
            val people = "select name from people ${whereTenant("people", "name = ?")}"
            catalog[QueryId("BookingSql.both#2")] shouldBe people
            catalog[QueryId("booking.find")] shouldBe booking
            catalog[QueryId("booking.same")] shouldBe catalog[QueryId("booking.find")]
            val twice = "select id from bookings ${whereTenant("bookings", "id = ? and id = ?")}"
            catalog[QueryId("BookingSql.twice")] shouldBe twice
            val indented = "select id\nfrom bookings\n" + whereTenant("bookings", "id = ?")
            catalog[QueryId("BookingSql.indented")] shouldBe indented
            QueryRegistry.load(catalog).shouldBeRight()
            QueryRegistry.load(catalog).shouldBeRight()
            QueryRegistry.load(mapOf(QueryId("BookingSql.findSlot") to "select 2")).shouldBeLeft()
            QueryRegistry.all()[QueryId("BookingSql.findSlot")] shouldBe booking
            val shared = "select 'registry.shared'"
            QueryRegistry.load(mapOf(QueryId("shared.first") to shared, QueryId("shared.second") to shared))
                .shouldBeRight()
            QueryRegistry.load(mapOf(QueryId("shared.third") to shared)).shouldBeRight()
            QueryRegistry.idFor(shared) shouldBe QueryId("shared.first")
            QueryRegistry.idFor("select 'never registered'") shouldBe null
        }

        test("codecs round-trip the built-ins, a value class and a data class") {
            val id = UUID.fromString(ID)
            val whenAt = Instant.parse("2026-11-06T12:00:00Z")
            roundTrip(PostgresCodecs.uuid, id)
            roundTrip(Codecs.string, "Ada")
            roundTrip(Codecs.int, 7)
            roundTrip(Codecs.long, 9L)
            roundTrip(Codecs.boolean, true)
            roundTrip(PostgresCodecs.instant, whenAt)
            roundTrip(Codecs.localDate, LocalDate.of(2026, 11, 6))
            roundTrip(Codecs.localTime, LocalTime.of(12, 30))
            val bytes = byteArrayOf(1, 2, 3)
            roundTrip(Codecs.bytes, bytes).contentEquals(bytes) shouldBe true
            roundTrip(PostgresCodecs.strings, listOf("a", "b"))
            roundTrip(PostgresCodecs.uuids, listOf(id))
            roundTrip(Codecs.string.orNull(), null) shouldBe null
            roundTrip(inlineCodec(PostgresCodecs.uuid, SlotId::class.java), SlotId(id))
            roundTrip(inlineCodec(Codecs.string, Code::class.java), Code.of("ada"))
            val labeled = Labeled(SlotId(id), "Ada")
            roundTrip(labeledCodec(), labeled, listOf("id", "name")) shouldBe labeled
            val person = PersonRow("Ada", 30)
            roundTrip(personCodecRow(), person, listOf("name", "age")) shouldBe person
            val memory = scripted(listOf(listOf<Any?>(null)), listOf("name"))
            val missing = prepared("people.null", "select name from people", emptyArray(), null).query(Codecs.string)
            (missing.list(RecordingJdbc.connection(memory)).shouldBeLeft() is SqlFailure.Unexpected) shouldBe true
        }

        test("the plugin binds a value class, a data class and a hand-written codec") {
            val id = UUID.fromString(ID)
            val memory = scripted(listOf(listOf(id)), listOf("id"))
            val connection = RecordingJdbc.connection(memory)
            SlotSql.find(SlotId(id)).list(connection).shouldBeRight() shouldBe listOf(SlotId(id))
            memory.calls.single().parameters shouldBe listOf(id)
            val people = RecordingJdbc.Memory()
            val rows = listOf(PersonRow("Ada", 30), PersonRow("Bea", 31))
            PeopleSql.insert(rows[0]).batch(RecordingJdbc.connection(people), rows).shouldBeRight() shouldBe 1
            val call = "doorlist.current_tenant()"
            val widened = "insert into people (tenant_id, name, age) values ($call, ?, ?), ($call, ?, ?)"
            people.calls.single().sql shouldBe widened
            people.calls.single().parameters shouldBe listOf("Ada", 30, "Bea", 31)
            people.calls.single().sql.contains("Ada") shouldBe false
            val pay = RecordingJdbc.Memory()
            PaySql.insert(Money(5)).update(RecordingJdbc.connection(pay)).shouldBeRight()
            pay.calls.single().parameters shouldBe listOf("money:5")
            val read = scripted(listOf(listOf("Ada", 30)), listOf("name", "age"))
            PeopleSql.read("Ada").list(RecordingJdbc.connection(read)).shouldBeRight() shouldBe
                listOf(PersonRow("Ada", 30))
            val twice = scripted(listOf(listOf(id)), listOf("id"))
            BookingSql.twice(id).list(RecordingJdbc.connection(twice)).shouldBeRight()
            twice.calls.single().parameters shouldBe listOf(id, id)
            twice.calls.single().sql.contains(ID) shouldBe false
        }

        // The meter is one for the process. The other tests here run beside this one and would record into it.
        test("the meter records the query id and nothing that was bound", alone) {
            val meter = RecordingMeter()
            installQueryMeter(meter)
            val memory = RecordingJdbc.Memory()
            val frag = prepared("meter.touch", "update people set name = ?", arrayOf(bind(Codecs.string, "Ada")), null)
            val updated = frag.update(RecordingJdbc.connection(memory))
            installQueryMeter(NoQueryMeter)
            updated.shouldBeRight()
            val sample = meter.samples.peek().single { it.queryId == "meter.touch" }
            sample.operation shouldBe "update"
            sample.errorType shouldBe null
            (sample.durationNanos >= 0L) shouldBe true
            val fields = QuerySample::class.java.declaredFields.map { it.name }.toSet()
            fields shouldBe setOf("queryId", "operation", "durationNanos", "errorType")
            val kept = meter.samples.peek().size
            prepared("meter.after", "update people set name = ?", emptyArray(), null)
                .update(RecordingJdbc.connection(RecordingJdbc.Memory()))
            meter.samples.peek().size shouldBe kept
            NoQueryMeter.record(sample)
        }
    }
}

private const val ID = "11111111-1111-1111-1111-111111111111"

private const val OTHER = "22222222-2222-2222-2222-222222222222"

private const val WAIT = 5L

private data class Rename(val name: String, val id: UUID)

/** A dialect for tests: Postgres's answers unless a test names another. */
private class TestDialect(
    override val maxParameters: Int = Postgres.maxParameters,
    override val batchInsert: BatchInsert = BatchInsert.MultiRowValues,
    override val returnedRows: ReturnedRows = ReturnedRows.Returning,
    private val classify: (SQLException) -> SqlFailure.FromDriver = Postgres::classify,
) : Dialect {
    override val name: String = "test"

    override fun classify(error: SQLException): SqlFailure.FromDriver = classify.invoke(error)
}

private class RecordingMeter : QueryMeter {
    val samples = Shared(emptyList<QuerySample>(), "sql.test.meter")

    override fun record(sample: QuerySample) {
        samples.update { it + sample }
    }
}

private fun labeledCodec(): DbCodec<Any?> = dataCodec(
    Labeled::class.java,
    arrayOf("id", "name"),
    arrayOf("id", "name"),
    arrayOf(inlineCodec(PostgresCodecs.uuid, SlotId::class.java), Codecs.string.boxed()),
)

private fun personCodec(): DbCodec<Any?> = dataCodec(
    Person::class.java,
    arrayOf("name", "age", "born"),
    arrayOf("name", "age", "born"),
    arrayOf(Codecs.string.boxed(), Codecs.int.boxed(), Codecs.localDate.boxed()),
)

private fun personCodecRow(): DbCodec<Any?> = dataCodec(
    PersonRow::class.java,
    arrayOf("name", "age"),
    arrayOf("name", "age"),
    arrayOf(Codecs.string.boxed(), Codecs.int.boxed()),
)

private fun scripted(
    rows: List<List<Any?>>,
    columns: List<String>,
    memory: RecordingJdbc.Memory = RecordingJdbc.Memory(),
): RecordingJdbc.Memory {
    rows.forEach { row -> memory.row(*row.toTypedArray()) }
    columns.forEach { memory.column(it) }
    return memory
}

private fun <A> roundTrip(codec: DbCodec<A>, value: A, columns: List<String> = listOf("c")): A {
    val memory = RecordingJdbc.Memory()
    val statement = RecordingJdbc.connection(memory).prepareStatement("select ?")
    codec.write(statement, 1, value)
    val row = RecordingJdbc.replay(memory, columns)
    row.next()
    return codec.read(row, 1).shouldBeRight()
}

/** A hand-written insert with the tuple bounds the plugin would emit: the parentheses after `values`. */
private fun insert(id: String, text: String, codec: DbCodec<*>): Frag {
    val open = text.indexOf('(', text.indexOf("values"))
    val close = (open until text.length).fold(0 to -1) { (depth, found), at ->
        when {
            found >= 0 -> depth to found
            text[at] == '(' -> depth + 1 to found
            text[at] == ')' && depth == 1 -> 0 to at
            text[at] == ')' -> depth - 1 to found
            else -> depth to found
        }
    }.second
    return prepared(id, text, emptyArray(), codec, open, close)
}

/** The codecs an object declares, by property name. */
private fun codecNames(holder: Class<*>): Set<String> =
    holder.declaredFields.filter { DbCodec::class.java.isAssignableFrom(it.type) }.map { it.name }.toSet()

private fun tuples(sql: String): Int = Regex("""\(\?, \?, \?\)""").findAll(sql).count()

private fun whereTenant(table: String, predicate: String): String =
    "where $table.tenant_id = doorlist.current_tenant() and $predicate"

private data class Seen<A>(val value: A, val ran: Thread, val caller: Thread)

/** The runtime `sql` the plugin did not rewrite. A Kotlin call site would be rewritten. */
private fun unrewritten(text: String): Either<Throwable, Frag> {
    val method = Class.forName("dev.surefold.sql.SqlKt").getMethod("sql", String::class.java, Scope::class.java)
    return when (val called = Either.catch { method.invoke(null, text, Scope.Tenant) as Frag }) {
        is Either.Left -> ((called.value as? InvocationTargetException)?.targetException ?: called.value).left()
        is Either.Right -> called.value.right()
    }
}

private fun unfinished(): Nothing = Either.Left(
    IllegalStateException("the virtual thread did not finish"),
).shouldBeRight()

private fun <A> onCaller(body: () -> A): A {
    val box = RecordingJdbc.Holder<Either<Throwable, A>>()
    val worker = Thread.ofVirtual().unstarted { box.value = Either.catch { body() } }
    worker.start()
    worker.join()
    val finished = box.value ?: unfinished()
    return finished.shouldBeRight()
}
