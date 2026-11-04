# Pekko

Surefold on Pekko: forks on a Pekko dispatcher, a Pekko typed actor as a node of an application, and asking one from
a virtual thread. The modules are `surefold-pekko`, `surefold-actor-pekko` and `surefold-app-pekko`; surefold-core's
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
