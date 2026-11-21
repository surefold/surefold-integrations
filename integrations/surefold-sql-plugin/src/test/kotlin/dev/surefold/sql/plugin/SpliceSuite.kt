package dev.surefold.sql.plugin

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe

/** The tenant splice on its own, without compiling a snippet. Each case is the exact text a statement becomes. */
object SpliceSuite : Suite() {
    init {
        test("a subquery in where, exists, a select list, set or array is scoped, at any depth") {
            spliced("select id from a where x = ? and y in (select id from b)") shouldBe
                "select id from a where ${t("a")} and x = ? and y in (select id from b where ${t("b")})"
            spliced("select id from a where exists (select 1 from b where b.a_id = a.id)") shouldBe
                "select id from a where ${t("a")} and exists (select 1 from b where ${t("b")} and b.a_id = a.id)"
            spliced("select id, (select count(*) from b where b.a = a.id) as n from a") shouldBe
                "select id, (select count(*) from b where ${t("b")} and b.a = a.id) as n from a where ${t("a")}"
            spliced("update a set n = (select n from b where b.id = a.id)") shouldBe
                "update a set n = (select n from b where ${t("b")} and b.id = a.id) where ${t("a")}"
            spliced("delete from a where id in (select id from b)") shouldBe
                "delete from a where ${t("a")} and id in (select id from b where ${t("b")})"
            spliced("select id from a where x = any(array(select id from b))") shouldBe
                "select id from a where ${t("a")} and x = any(array(select id from b where ${t("b")}))"
            spliced("select id from a where id in (select id from b where k in (select k from c))") shouldBe
                "select id from a where ${t("a")} and id in (select id from b where ${t("b")} and " +
                "k in (select k from c where ${t("c")}))"
            spliced("insert into a (id) select id from b where id not in (select id from c)") shouldBe
                "insert into a (tenant_id, id) select $CALL, id from b where ${t("b")} and " +
                "id not in (select id from c where ${t("c")})"
        }

        test("a left join is scoped in its on, so its unmatched rows stay") {
            val mixed = "select id from a left join b on b.id = a.id or b.x = 1 join c on c.id = a.id where a.n = ?"
            spliced(mixed) shouldBe
                "select id from a left join b on ${t("b")} and ( b.id = a.id or b.x = 1 ) join c on c.id = a.id " +
                "where ${t("a")} and ${t("c")} and a.n = ?"
            spliced("select id from a left outer join b bb on bb.id = a.id order by id") shouldBe
                "select id from a left outer join b bb on ${t("bb")} and ( bb.id = a.id ) where ${t("a")} order by id"
            spliced("select id from a left join b on b.id = a.id -- note") shouldBe
                "select id from a left join b on ${t("b")} and ( b.id = a.id -- note\n) where ${t("a")}"
            spliced("update a set n = 1 from b left join c on c.id = b.id where b.id = a.id") shouldBe
                "update a set n = 1 from b left join c on ${t("c")} and ( c.id = b.id ) " +
                "where ${t("a")} and ${t("b")} and b.id = a.id"
        }

        test("a top-level or in where stays inside the tenant predicate") {
            spliced("select id from a where x = ? or y = ?") shouldBe
                "select id from a where ${t("a")} and ( x = ? or y = ?)"
            spliced("select id from a join b on b.id = a.id where a.x = ? OR b.y = ?") shouldBe
                "select id from a join b on b.id = a.id where ${t("a")} and ${t("b")} and ( a.x = ? OR b.y = ?)"
            spliced("select id from a where x = ? or y = ? -- note") shouldBe
                "select id from a where ${t("a")} and ( x = ? or y = ? -- note\n)"
            spliced("update a set n = 1 where x = ? or y = ? returning id") shouldBe
                "update a set n = 1 where ${t("a")} and ( x = ? or y = ? ) returning id"
            spliced("update a set n = 1 from b where b.id = a.id or a.x = ?") shouldBe
                "update a set n = 1 from b where ${t("a")} and ${t("b")} and ( b.id = a.id or a.x = ?)"
            spliced("delete from a where x = ? or y = ? returning id") shouldBe
                "delete from a where ${t("a")} and ( x = ? or y = ? ) returning id"
            spliced("insert into a (id) select id from b where x = ? or y = ? on conflict do nothing") shouldBe
                "insert into a (tenant_id, id) select $CALL, id from b where ${t("b")} and ( x = ? or y = ?) " +
                "on conflict do nothing"
        }

        test("the or's parentheses close before each clause that can follow a where") {
            listOf(
                "group by id", "having count(*) > 1", "order by id", "limit 5", "offset 5", "fetch first 5 rows only",
                "for update", "window w as (partition by id)",
            ).forEach { clause ->
                spliced("select id from a where x = ? or y = ? $clause") shouldBe
                    "select id from a where ${t("a")} and ( x = ? or y = ? ) $clause"
            }
            spliced("select id from a where x = ? or y = ? union all select id from b where p = ? or q = ?") shouldBe
                "select id from a where ${t("a")} and ( x = ? or y = ?) union all " +
                "select id from b where ${t("b")} and ( p = ? or q = ?)"
            spliced("select id from a where x is distinct from ? or y = ? order by id") shouldBe
                "select id from a where ${t("a")} and ( x is distinct from ? or y = ? ) order by id"
        }

        test("an or in a subquery or a CTE is closed inside it, and one in parentheses needs no more") {
            val nested = "select id from a where x = ? or id in (select a_id from b where p = ? or q = ?) order by id"
            spliced(nested) shouldBe
                "select id from a where ${t("a")} and ( x = ? or id in (select a_id from b where ${t("b")} and " +
                "( p = ? or q = ?)) ) order by id"
            spliced("select id from a where id in (select a_id from b where p = ? or q = ? limit 1) and x = ?") shouldBe
                "select id from a where ${t("a")} and id in (select a_id from b where ${t("b")} and " +
                "( p = ? or q = ? ) limit 1) and x = ?"
            spliced("with x as (select id from a where p = ? or q = ?) select id from x") shouldBe
                "with x as (select id from a where ${t("a")} and ( p = ? or q = ?)) select id from x"
            spliced("select id from a where (x = ? or y = ?) and z = ?") shouldBe
                "select id from a where ${t("a")} and (x = ? or y = ?) and z = ?"
            spliced("select id from a where x = 'this or that' and z = ?") shouldBe
                "select id from a where ${t("a")} and x = 'this or that' and z = ?"
        }

        test("a right or full join, or a left join without on, is refused rather than guessed") {
            refused("select id from a right join b on b.id = a.id")
            refused("select id from a full outer join b on b.id = a.id")
            refused("select id from a left join b using (id)")
            refused("select id from a natural left join b")
        }

        test("a CTE is never scoped as a table, in its main statement, a subquery or its own recursion") {
            spliced("with x as (select id from a) select id from b where id in (select id from x)") shouldBe
                "with x as (select id from a where ${t("a")}) select id from b where ${t("b")} and " +
                "id in (select id from x)"
            spliced("with x as (select id from a where id in (select id from b)) select id from x") shouldBe
                "with x as (select id from a where ${t("a")} and id in (select id from b where ${t("b")})) " +
                "select id from x"
            spliced("with recursive x as (select id from a union all select id from x) select id from x") shouldBe
                "with recursive x as (select id from a where ${t("a")} union all select id from x) select id from x"
        }

        test("a set-returning function has no tenant, and its alias and column list are read") {
            val bare = "select 1 from unnest(?) as tag where tag = ?"
            spliced(bare) shouldBe bare
            spliced("select a.id from a where not exists(select 1 from unnest(a.tags) as tag where tag = ?)") shouldBe
                "select a.id from a where ${t("a")} and not exists(select 1 from unnest(a.tags) as tag where tag = ?)"
            spliced("update a set f = (select jsonb_agg(f) from unnest(?::text[]) as x(f)) where id = ?") shouldBe
                "update a set f = (select jsonb_agg(f) from unnest(?::text[]) as x(f)) where ${t("a")} and id = ?"
            spliced("select a.id from a join unnest(?::uuid[]) as w(id) on w.id = a.id") shouldBe
                "select a.id from a join unnest(?::uuid[]) as w(id) on w.id = a.id where ${t("a")}"
        }

        test("text in comments, string literals and filter clauses is not read as SQL") {
            spliced("select id from a -- from b\nwhere x = ?") shouldBe
                "select id from a -- from b\nwhere ${t("a")} and x = ?"
            spliced("select id from a /* from b */ where x = ?") shouldBe
                "select id from a /* from b */ where ${t("a")} and x = ?"
            spliced("select 'from b' as t from a") shouldBe "select 'from b' as t from a where ${t("a")}"
            spliced("select count(*) filter (where n > 1) from a") shouldBe
                "select count(*) filter (where n > 1) from a where ${t("a")}"
        }

        test("a derived table, lateral or an escaped string is refused, not left unscoped") {
            refused("select id from (select id from a) r")
            refused("select id from a cross join lateral (select id from b where b.a = a.id) l")
            refused("select E'it\\'s' as t from a")
        }

        test("the values tuple a batch repeats is found once, and anything else is left to a JDBC batch") {
            val tenant = "insert into p (tenant_id, a, b) values ($CALL, ?, ?)"
            valuesTuple(tenant) shouldBe tenant.indexOf("($CALL")..tenant.lastIndexOf(')')
            val conflict = "insert into p (a) values (?) on conflict (a) do update set n = excluded.n returning id"
            valuesTuple(conflict) shouldBe conflict.indexOf("(?)")..conflict.indexOf("(?)") + 2
            valuesTuple("insert into p (a) values (?), (?)") shouldBe null
            valuesTuple("insert into p (a) values ('x')") shouldBe null
            valuesTuple("insert into p (a) values (now())") shouldBe null
            valuesTuple("insert into p (a) select ?") shouldBe null
            valuesTuple("update p set a = ?") shouldBe null
        }

        test("union arms are each scoped and keep the space before the set word") {
            spliced("select id from a where x = ? union select id from b") shouldBe
                "select id from a where ${t("a")} and x = ? union select id from b where ${t("b")}"
            spliced("select id from a union all select id from b") shouldBe
                "select id from a where ${t("a")} union all select id from b where ${t("b")}"
        }
    }
}

private const val CALL = "doorlist.current_tenant()"

private fun t(alias: String): String = "$alias.tenant_id = $CALL"

private fun spliced(sql: String): String = when (val result = spliceTenant(sql)) {
    is Splice.Ready -> result.sql
    is Splice.Broken -> "refused: ${result.message}"
}

private fun refused(sql: String) {
    (spliceTenant(sql) is Splice.Broken) shouldBe true
}
