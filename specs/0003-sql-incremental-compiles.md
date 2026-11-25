# 0003 — surefold-sql-plugin: incremental compiles see the whole module

## Problem

The plugin only sees the files the compiler is compiling in the current run. On a clean build that is every file. On
an incremental round it is only the changed files (surefold/surefold-integrations#18):

- **Codecs.** `codecFor()` looks for `DbCodec` objects among this round's files only. A codec declared in a file that
  was not recompiled is not found, so every `sql()` using it fails with `no codec for X`. Reproduced by the story
  "a codec compiled in an earlier round is still found" in `SqlPluginSuite` (two compiler runs, the codec in the
  first). The placeholder count is a second blocker: `placeholderCount()` reads the codec's source code, which a codec
  already compiled to class files does not have, so finding the object is not enough.
- **Catalog.** `writeCatalog()` overwrites the catalog with this round's statements only. After an incremental round
  the catalog in the jar is probably missing every statement from the other files (read from the code, not yet
  reproduced).

Users turn incremental compilation off for modules that use the plugin.

## Not doing

- Codecs from dependency modules. The index below makes them possible later; reading them is its own spec.
- Recompiling a file when a codec it uses changes. Incremental compilation does not know a file using a codec depends
  on that codec, because the plugin adds the reference. See open question 3.
- A Gradle plugin, or any change users must make to their build.

## Shape

The plugin keeps an index of what earlier rounds found, and every round merges with it:

```
v1
codec   <object class id>  <placeholders>
query   <source path>  <query id>  <escaped sql>
```

Each round:

1. Reads the index from the previous round, if there is one.
2. Keeps a `codec` entry only if its class id still names a compiled class outside this round's files. Objects in
   this round's files are read from their source, as today, so a changed or deleted codec is picked up. An object
   whose file was deleted no longer resolves, and its entry is dropped.
3. Looks codecs up among this round's objects and the kept entries, loaded by class id through the plugin context,
   with the placeholder count from the index.
4. Writes the merged index. `sql/catalog-merge` adds the `query` entries and writes the catalog from all of them.

Nothing changes for users: the same `sql()` calls compile, with or without incremental compilation.

## Why this shape

The compiler has no way to list every class that implements `DbCodec` across the classpath, and cannot read a
compiled codec's `placeholders`. So the plugin has to remember what it saw in earlier rounds. Alternatives: forcing
codec files to recompile every round (Gradle cannot express that for Kotlin incremental compilation), or reading
`placeholders` at run time (changes the generated code and the catalog text, which holds the placeholders).
Recommended: the index. One file covers codecs and the catalog, since both break for the same reason.

## Stack

- [ ] **`sql/codec-index`** — the index file, with `codec` entries; codec lookup reads the merged index.
      Done when: the "earlier round" story passes, plus stories for a codec whose file is deleted between rounds
      (`no codec`) and a second codec for the same type added in a later round (`ambiguous codec`).
- [ ] **`sql/catalog-merge`** — `query` entries in the index; the catalog is written from the merged set.
      Done when: a two-round story shows the catalog holds statements from both rounds, and a statement removed from
      a recompiled file is gone from it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

Then, in a module using the plugin with incremental compilation on: clean build, append a comment to a file with
`sql()` calls using a custom codec, build again. It compiles, and the jar's `META-INF/surefold/sql.catalog` matches
the one from a clean build.

## Open questions

Answered by the owner, 25 November 2026, taking the drafted recommendations, except question 2, changed while
building:

1. **Where the index lives.** In the compiler's output directory, as `META-INF/surefold/sql.index`: no new option,
   cleared on a clean build, and ships in the jar, which later allows dependency codecs. Checked while building that
   Kotlin incremental compilation keeps it between rounds.
2. **Key.** Codec entries are keyed by class id, not source path. The compiler does not know the module root, so a
   source path would be absolute and break on a build cache hit from another machine; a class id is stable and is
   enough to tell an object from this round's files from one compiled earlier. `query` entries still need their
   source path, decided in `sql/catalog-merge`.
3. **Changed codec, stale users.** Deferred. If a codec's `placeholders` changes, files using it keep the old count
   until recompiled. A later spec can have the runtime fail fast on a mismatch.
4. **Index from a newer or older plugin.** A `v1` header; any other version is a compile error that says to run a
   clean build.
