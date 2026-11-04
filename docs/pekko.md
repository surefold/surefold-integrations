# Pekko

Surefold on Pekko: forks on a Pekko dispatcher, a Pekko typed actor as a node of an application, asking one from a
virtual thread, and streams on Pekko Streams. The modules are `surefold-pekko`, `surefold-actor-pekko`,
`surefold-app-pekko` and `surefold-stream-pekko`; surefold-core's
[core guide](https://github.com/surefold/surefold-core/blob/main/docs/core.md) and
[cookbook](https://github.com/surefold/surefold-core/blob/main/docs/cookbook.md) are where forks and applications are
introduced.

## Running forks on Pekko

A Pekko application already has one place that names, sizes and instruments
its threads: the dispatcher configuration. `surefold-pekko` makes a dispatcher
configured there the executor Surefold forks on, and lets a fork wait for Pekko's
`CompletionStage`s and Scala `Future`s.

```kotlin
dependencies {
    // brings surefold and pekko-actor
    implementation("dev.surefold:surefold-pekko:0.18.0")
}
```

The dispatcher is configured beside Pekko's own, and named by the part after
`pekko.actor.`:

```hocon
pekko.actor.surefold {
  executor = "virtual-thread-executor"
}
```

<!-- pekko-forks -->
```kotlin
import dev.surefold.await
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.parZip
import dev.surefold.pekko.surefoldDispatcher
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class Missing(val id: Int)

fun price(id: Int): CompletionStage<Long> = CompletableFuture.completedFuture(100L * id)

fun stock(id: Int): Either<Missing, Int> = Either.Right(3)

fun offer(system: ActorSystem, id: Int): Either<Missing, Pair<Long, Int>> = attempt {
    val surefold = system.surefoldDispatcher()   // pekko.actor.surefold; surefoldDispatcher("other") for another
    parZip(on = surefold, { price(id).await() }, { stock(id).bind() }) { p, s -> p to s }
}
```

- `surefoldDispatcher` accepts a dispatcher whose executor is
  `virtual-thread-executor`, or one with `type = PinnedDispatcher`, whose thread
  is not shared. It rejects any other executor, including Pekko's default
  fork-join pool, with an `IllegalArgumentException` naming the setting: a
  Surefold fork blocks, and blocking the pool Pekko sizes for its actors starves
  them. Both the classic and the typed `ActorSystem` can call it.
- `await()` parks the calling fork until the stage completes. A failed stage
  rethrows the original cause, not the `CompletionException` around it. An
  interrupt cancels the stage with `cancel(true)` and rethrows the
  `InterruptedException`, so a value that arrives later is dropped.
- `await()` is in `surefold` and takes any `CompletionStage` or `Future`, Pekko's
  or not; Pekko's Scala futures have one of their own in `surefold-pekko`.
- The other way round, `surefoldFuture { }` hands surefold to a caller that wants a
  future: the block runs on a virtual thread of its own, with the caller's
  bindings as a fork has them, and the future's `cancel` interrupts it, so its
  forks stop. `CompletableFuture.supplyAsync` would ignore the cancel. A
  coroutine awaiting it with kotlinx's own `await()` cancels it when the
  coroutine is cancelled.
- `await()` does not change what a stage holds. A stream run into `Sink.seq`
  still collects every element before it completes.

## Spawn a Pekko actor in an application

`surefold-app-pekko` makes a Pekko typed actor a node keyed by the `ActorRef<T>`
of its protocol. It needs a classic `ActorSystem` node:

<!-- pekko-actor -->
```kotlin
import dev.surefold.app.Module
import dev.surefold.app.pekko.actor
import dev.surefold.app.single
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.javadsl.Behaviors

interface Pool
interface UserRepo
class PgUserRepo(private val pool: Pool) : UserRepo

data class Ingest(val line: String)

fun ingesting(users: UserRepo): Behavior<Ingest> =
    Behaviors.receive(Ingest::class.java)
        .onMessage(Ingest::class.java) { Behaviors.same() }
        .build()

val actors: Module =
    single<ActorSystem> { install({ ActorSystem.create("app") }) { system, _ -> system.terminate() } } +
    single { pool: Pool -> PgUserRepo(pool) as UserRepo } +
    actor("ingest") { users: UserRepo -> ingesting(users) }

val sending: Module = single { ingest: ActorRef<Ingest> -> Routes2(ingest) }

class Routes2(private val ingest: ActorRef<Ingest>) {
    fun accept(line: String) = ingest.tell(Ingest(line))
}
```

The protocol is inferred from the behaviour's type. Write it out,
`actor<Ingest>(…)`, only where the actor takes no dependencies: with one type
argument given, the overloads that take dependencies cannot apply.

Two protocols are two keys, and a dependent declares the protocol it sends.
The actor is stopped through `gracefulStop` and awaited, so it has given back
what it held before the nodes it depends on are released.

Asking an actor, and why the reply type is bound to `Any`:

```kotlin
import dev.surefold.actor.pekko.ask

fun find(ref: ActorRef<Shop>, system: ActorSystem, id: PetId): Option<Pet> =
    ref.ask(system, 3.seconds) { replyTo -> Find(id, replyTo) }
```

Pekko refuses a `null` message, so an actor that answers "no such pet" with
`null` throws instead of answering, and `ActorRef<Pet?>` compiles, so neither
Kotlin nor the typed protocol stops it. Binding the reply to `Any` does:
absence has to be modelled as an `Option`, a sealed reply or an empty list.

## Streams on Pekko

`surefold-stream-pekko` is both a backend and a typed layer over Pekko's own
API. `Stream.from(source)` and `toSource()` convert in and out, `runWith(sink)`
runs into a Pekko `Sink`, `divertLefts(to = sink)` splits a stream of `Either`
into two sinks, and `run(system)` is short for `run(PekkoStreams(system))`.
`toSource()` exists only on `Stream<Nothing, A>`, so a stream that can still
fail has to say what a failure means, with `catchAll`, `either` or `orElse`,
before it can become a plain Pekko `Source`.

A `surefold-stream` description names no backend, and the same one runs on Pekko Streams by handing `run` a
`PekkoStreams`, as on surefold-core's own forks or test backend
([streams guide](https://github.com/surefold/surefold-core/blob/main/docs/stream.md)):

```kotlin
dependencies {
    // brings surefold-stream, surefold-pekko and pekko-stream
    implementation("dev.surefold:surefold-stream-pekko:0.18.0")
}
```

<!-- stream-pekko-backend -->
```kotlin
import dev.surefold.stream.Exit
import dev.surefold.stream.PekkoStreams
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.from
import dev.surefold.stream.run
import dev.surefold.stream.runFold
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.CompletionStage

val takings: Run<Nothing, Long> =
    Stream.from(listOf(1200L, 800L, 4500L)).runFold(0L) { total, pence -> total + pence }

val system: ActorSystem = ActorSystem.create("shop")

val onPekko: CompletionStage<Exit<Nothing, Long>> = takings.run(PekkoStreams(system))
```

### What it changes

A Kotlin service written directly against Pekko's `javadsl` has one error
channel typed `Throwable` for the whole graph, and a `Supervision.Decider` set
as an attribute some distance from the operator it governs. A team that keeps
its failures in an `Either` puts one in every element, splits the stream
with `divertTo(sink, predicate)` and casts the survivors back. Two things can
then drop an element without a trace, and Pekko documents both: a `mapAsync`
whose `CompletionStage` completes with `null` is not passed downstream, and a
`Resume` decider drops the element and continues. The first is easy to write
in Kotlin, as a nullable lookup lifted into a future, and nothing in the types
warns about it.

The same ingest written both ways, from shared fixtures:

<!-- stream-pekko-fixtures -->
```kotlin
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.fold
import dev.surefold.fail.mapLeft
import dev.surefold.stream.Stream
import dev.surefold.stream.divertLefts
import dev.surefold.stream.from
import dev.surefold.stream.mapAsync
import dev.surefold.stream.mapOrFail
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class Row(val id: Int, val customer: String?)

data class Customer(val id: Int, val name: String)

data class Receipt(val id: Int)

sealed interface IngestError

data class NoCustomer(val id: Int) : IngestError

data class Declined(val id: Int) : IngestError

class Ledger {
    fun settle(customer: Customer): CompletionStage<Either<Declined, Receipt>> =
        CompletableFuture.completedFuture(Either.Right(Receipt(customer.id)))
}

val ledger = Ledger()
val rows = listOf(Row(1, "ada"), Row(2, "grace"), Row(3, null))
val declinedSink = Sink.foreach<Declined> { declined -> println("declined ${declined.id}") }
```

Raw Pekko `Source`:

<!-- stream-pekko-before -->
```kotlin
val receipts: Source<Either<IngestError, Receipt>, NotUsed> =
    Source.from(rows)
        .map { row ->                                        // Source<Either<IngestError, Customer>, NotUsed>
            attempt<IngestError, Customer> { Customer(row.id, row.customer ?: fail(NoCustomer(row.id))) }
        }
        .mapAsync(4) { customer ->
            customer.fold(
                { e -> CompletableFuture.completedFuture<Either<IngestError, Receipt>>(Either.Left(e)) },
                { c -> ledger.settle(c).thenApply { it.mapLeft { d -> d as IngestError } } },
            )                                                // the Left carried past a stage that never wanted it,
        }                                                    // and Declined widened to IngestError by hand
        .divertTo(
            declinedSink.contramap<Either<IngestError, Receipt>> { (it as Either.Left).value as Declined },
            { it is Either.Left && it.value is Declined },    // a cast for the sink, a predicate on the subtype
        )                                                    // and a NoCustomer Left is still in the stream
```

With `surefold-stream`:

<!-- stream-pekko-after -->
```kotlin
val receipts: Stream<IngestError, Receipt> =
    Stream.from(rows)
        .mapOrFail { row -> Customer(row.id, row.customer ?: fail(NoCustomer(row.id))) }   // a nullable body does not compile
        .mapAsync(4) { customer -> ledger.settle(customer) }                                // the same mapAsync, E already on the stream
        .divertLefts(to = declinedSink)                                                     // no predicate, no cast
```

Raw Pekko carries the failure in the element, so every stage after the first
unwraps and rewraps one: the `mapAsync` that only wanted a customer carries a
`Left` past itself, and `Declined` is widened to `IngestError` by hand. A
diverted element and a failed pipeline then share one channel, told apart by a
predicate on the error's subtype, so a `NoCustomer` that should end the run
keeps flowing to the consumer, and the element type at the end is still
`Either<IngestError, Receipt>`.

With `surefold-stream` the failure is on the stream, not in the element:
`mapAsync` sees a `Customer` and passes on the ledger's own `Either`,
`divertLefts` splits on that without a predicate, and what reaches the end is
a `Receipt`. Pekko runs the same three stages either way; what changes is
what the types say and what they will not let you write.

### A complete pipeline

Rows in, receipts to one sink, declines to another, and the run's `Exit`
turned back into an `Either` in the handler that started it:

<!-- stream-pekko-pipeline -->
```kotlin
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.stream.Stream
import dev.surefold.stream.awaitExit
import dev.surefold.stream.divertLefts
import dev.surefold.stream.from
import dev.surefold.stream.mapAsync
import dev.surefold.stream.mapOrFail
import dev.surefold.stream.run
import dev.surefold.stream.runWith
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class Row(val id: Int, val customer: String?)

data class Customer(val id: Int, val name: String)

data class Receipt(val id: Int)

sealed interface IngestError

data class NoCustomer(val id: Int) : IngestError

data class Declined(val id: Int) : IngestError

class Ledger {
    fun settle(customer: Customer): CompletionStage<Either<Declined, Receipt>> =
        CompletableFuture.completedFuture(Either.Right(Receipt(customer.id)))
}

val system: ActorSystem = ActorSystem.create("ingest")
val ledger = Ledger()
val rows = listOf(Row(1, "ada"), Row(2, "grace"), Row(3, null))

// Where a decline goes and where a receipt goes: two sinks with names, rather than a decider that drops one.
val declinedSink = Sink.foreach<Declined> { declined -> println("declined ${declined.id}") }
val receiptSink = Sink.foreach<Receipt> { receipt -> println("receipt ${receipt.id}") }

val settled: Either<IngestError, Done> = attempt {
    awaitExit(
        Stream.from(rows)                                              // Stream<Nothing, Row>
            .mapOrFail { row ->                                        // Stream<IngestError, Customer>
                Customer(row.id, row.customer ?: fail(NoCustomer(row.id)))
            }
            .mapAsync(4) { customer -> ledger.settle(customer) }        // up to four stages at once, in order
            .divertLefts(to = declinedSink)                            // Stream<IngestError, Receipt>
            .runWith(receiptSink)                                      // Run<IngestError, Done>
            .run(system),                                              // CompletionStage<Exit<IngestError, Done>>
    )                                                                  // Done → the value, Failed → fail, Died → throw
}
```

`awaitExit` waits for the run inside a `Fail`: `Done` is the value, `Failed`
fails, and `Died` throws. It waits through `surefold-pekko`'s
[`await`](#running-forks-on-pekko), so an interrupt cancels the run
rather than abandoning it.
