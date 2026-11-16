# 0001 — Threads and latches a build failure

## Problem

`AGENTS.md` now asks for surefold's primitives over the JDK's, but nothing enforces it. Core enforces part of it:
its spec 0309 made `Thread.ofVirtual`, `Thread.ofPlatform` and `Thread(..)` in main sources a detekt failure, with
every remaining use listed with its reason. This repository's `detekt.yml` has no such rule, and its main sources
use the JDK's primitives in 35 files:

- **22 of them are benchmarks** in `bench/`, which measure threads on purpose.
- **Kafka:** `Commits`, `Consume`, `Keeper` and `Transact`. `Keeper` is one of the two files core's 0309 left to this
  repository (its question 3).
- **SQS:** `Consume`, and `Visibility`, the other file 0309 left here.
- **App modules:** `surefold-app-cluster`'s `Clustered`, the OpenFeature and flagd flags, and typesafe's `Layered`.
- **Test kits:** `surefold-kafka-test`'s `KafkaBroker` and `surefold-otel-test`'s `Spans`.
- **Pekko:** `MapParStage` in `surefold-stream-pekko`.

Tests use them more: 23 files start threads, 27 use latches and 24 sleep.

A new use goes in silently, and an agent copies the nearest one.

## Not doing

- **Benchmarks.** `bench/**` is excluded: measuring threads is their job.
- **A library's own threads.** A Kafka client's poll loop or Pekko's dispatcher isn't started by our code. Only
  code we write is in scope.
- **Atomics and `ConcurrentHashMap`.** Core's 0309 and 0312 both leave them out (hot paths).
- **Test sources, in this spec.** Question 1.

## Shape

A second instance of the rule beside `ForbiddenMethodCall/runCatching`, for main sources, as core's
`ForbiddenMethodCall/threads`:

```yaml
ForbiddenMethodCall/threads:
  active: true
  methods:
    - reason: 'A thread started by hand belongs to no scope: fork with scope.async (AGENTS.md).'
      value: 'java.lang.Thread.ofVirtual'          # and ofPlatform, <init>, startVirtualThread,
                                                    # kotlin.concurrent.thread, Executors.new*
    - reason: 'A latch hands over one value: use a Promise (AGENTS.md).'
      value: 'java.util.concurrent.CountDownLatch.<init>'   # and CyclicBarrier, Phaser
    - reason: 'Shared state is a Shared, changed in a transaction (AGENTS.md).'
      value: 'kotlin.synchronized'                 # and ReentrantLock, ReentrantReadWriteLock
    - reason: 'Values between forks go through a channel (AGENTS.md).'
      value: 'java.util.concurrent.LinkedBlockingQueue.<init>'  # and ArrayBlockingQueue, SynchronousQueue
  excludes:
    - '**/test/**'
    - '**/bench/**'
    - # each remaining main file, with its reason, until the entries below remove it
```

## Why this shape

A rule first, listing every current use, makes the rest a list that only shrinks, as 0309 did in core.
**Alternative:** fix every file first, then add the rule. That's a larger first PR, and new uses keep arriving
while it is reviewed. Recommended: rule first.

## Stack

- [ ] **`spec-0001-rule`**: the rule, with every current main file listed in `excludes` with its reason.
      Done when: a new `CountDownLatch` in a main source fails `./gradlew detekt` until its file is listed.
- [ ] **`spec-0001-kafka-sqs`**: `Keeper` and `Visibility` (0309's question 3), then `Commits`, `Consume` and
      `Transact`, onto `scope`/`async`, `Promise` and `channel`.
      Done when: the rule lists none of them.
- [ ] **`spec-0001-app`**: `Clustered`, `OpenFeature`, `FlagdFlags` and `Layered`.
      Done when: the same, for those files.

## Acceptance

```bash
./gradlew detekt
grep -n -A30 "ForbiddenMethodCall/threads" config/detekt/detekt.yml
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Test sources too?** Agents copy tests most, and 56 test files use these. Recommended: a later spec, after
   core's 0307 settles the test patterns, with this rule's `excludes` then listing test files the same way.
2. **The test kits (`KafkaBroker`, `Spans`)?** They wrap an embedded broker and an in-memory exporter, and may
   need a thread for the library. Recommended: list them with that reason, and don't move them.
3. **`MapParStage`?** It is a Pekko `GraphStage`, which follows Pekko's threading model. Recommended: list it
   with that reason.
4. **`Executors.new*` in one line or each factory?** detekt matches exact names. Recommended: each of
   `newFixedThreadPool`, `newCachedThreadPool`, `newVirtualThreadPerTaskExecutor`, `newThreadPerTaskExecutor`
   and `newWorkStealingPool`. The scheduled ones are core 0132's compiler warning.
