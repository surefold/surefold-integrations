# Configuration

A service's configuration as nodes of its application graph, on Typesafe Config: a HOCON section read by the
module that needs it (`surefold-app-typesafe`), or from a `@Serializable` class (`surefold-app-kotlinx`), a report
of where each setting came from, and a cluster joined as a section says (`surefold-app-cluster`). Applications and
their graphs are introduced in surefold-core's
[cookbook](https://github.com/surefold/surefold-core/blob/main/docs/cookbook.md).

## Configuration a module owns

`surefold-app-typesafe` makes a HOCON section a node, read by the module that
needs it. `surefold-app-kotlinx` reads it from a `@Serializable` class, each
property a path named after it.

```kotlin
plugins {
    kotlin("plugin.serialization") version "2.4.10"
}

dependencies {
    implementation("dev.surefold:surefold-app-kotlinx:0.18.0")
}
```

<!-- configuration-module -->
```kotlin
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import dev.surefold.app.Module
import dev.surefold.app.boundTo
import dev.surefold.app.closingOf
import dev.surefold.app.kotlinx.Naming
import dev.surefold.app.kotlinx.Secret
import dev.surefold.app.kotlinx.config
import dev.surefold.app.single
import kotlinx.serialization.Serializable
import kotlin.time.Duration

data class DbConfig(val url: String)

interface Pool

class Hikari(val config: DbConfig) : Pool, AutoCloseable {
    override fun close() = Unit
}

@Serializable
data class PoolSettings(val url: String, val poolSize: Int = 8, val idle: Duration, val password: Secret)

val pool: Module =
    single<Config> { ConfigFactory.load() } +
        config<PoolSettings>("database", Naming.Kebab) +
        closingOf { db: PoolSettings -> Hikari(DbConfig(db.url)) }.boundTo<Pool>()
```

A nested class is a nested section. A default or a nullable makes a key
optional, and an enum names its entries when the file says something else.
`Duration` reads `30s`; `Secret` reads a string that prints as the mask.
`Naming.Kebab` reads `poolSize` from `pool-size`, and `@SerialName` names one
key, itself kebab-cased under `Naming.Kebab` if it has capitals. Every fault is
named at once, as the block form names them.
`Secrets.default.andSecretsOf<PoolSettings>("database", Naming.Kebab)` masks
each `Secret` path in the report below, given the naming `config` was given;
and `config.choosing<Settings>(path) { settings -> … }`
reads the class the same way.

The block form says what a class cannot: a size in bytes, a value computed
from two keys, or anything else read from the `Config` directly. Nothing is
wrapped: `of` hands over the real `com.typesafe.config.Config`, so
substitution, merging, `getDuration` and `getMemorySize` all still work.

```kotlin
dependencies {
    implementation("dev.surefold:surefold-app-typesafe:0.18.0")
}
```

```kotlin
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import dev.surefold.app.typesafe.config

data class DbSettings(val url: String, val poolSize: Int, val idle: kotlin.time.Duration)

val database: Module =
    single<Config> { ConfigFactory.load() } +
    config<DbSettings>("database") { DbSettings(string("url"), int("poolSize"), duration("idle")) } +
    closingOf { db: DbSettings -> Hikari(DbConfig(db.url)) }.boundTo<Pool>()
```

The readers are `string`, `int`, `long`, `boolean`, `duration` (a Kotlin
`Duration`), `bytes`, `strings` and `secret`; `of(default) { … }` reads
anything else from the `Config` directly.

A failed read records its fault and the block carries on, so a bad file
produces one message listing every fault, in Typesafe Config's own words with
the file and line, rather than one deploy per fault. Each module owns its
section, so adding one does not mean editing a root configuration type.

### Where a setting came from

Each section logs what it read as the graph starts, before anything is built
on it, to the `surefold-app` logger at `INFO`. There is nothing to wire: a service
whose `main` is `runApp(Petshop)` prints one block per section, and the
logger's level is how to turn it off. `config<Settings>("petshop") { … }` that
read `port`, `db.pool` and `secret("db.password")` logs:

<!-- configuration-section-report -->
```
surefold-app configuration: petshop

petshop.port         9090          application.conf:2
                     overrides 8080 at reference.conf:2
petshop.db.pool      16            application.conf:4
                     overrides 4 at reference.conf:3
petshop.db.password  ●●●●●●        application.conf:5
```

Only the paths the section read are printed. A path read with `secret(path)`,
or as a `Secret` property, is masked as well as those `Secrets.default` masks
by name. A section that refuses the start logs what it read first, with any
path it could not find marked `not set`. Over `loadedConfig()` the block says
what each setting overrode; over a `Config` written by hand, only where each
came from.

The whole document, rather than one section, is a report of its own.
A hierarchy of files resolves to one document. `origins()` says which file each
value came from and what it overrode:

```kotlin
import dev.surefold.app.typesafe.layeredConfig
import dev.surefold.app.typesafe.origins

layeredConfig().origins().report()
```

<!-- configuration-report -->
```
surefold-app configuration

petshop.arrivalsEvery  30s           reference.conf:4
petshop.db.password    ●●●●●●        application.conf:5
petshop.db.pool        16            application.conf:4
                       overrides 4 at reference.conf:3
petshop.port           9090          application.conf:2
                       overrides 8080 at reference.conf:2
```

`ReportedSuite` holds this output to what the code prints. `layeredConfig()`
reads what `ConfigFactory.load()` reads, layer by layer — `CONFIG_FORCE_`
variables under `-Dconfig.override_with_env_vars`, system properties,
`config.file`/`resource`/`url` or else `application.conf`, `.json` and
`.properties`, then `reference.conf` — and keeps them apart so it can report
what was overridden. `loadedConfig()` and the other sources put a `Layered` in
the graph beside the `Config` it merges to. `config.origins()` on an already merged
`Config` gives the same report without the override lines, because
`withFallback` keeps only the winner.

**Values are redacted by default.** Names such as `password`, `token` and
`key` are masked, but that misses a secret with an ordinary name, so add exact
paths:

```kotlin
import dev.surefold.app.typesafe.Secrets

config.origins(Secrets.default.and("petshop.db.url")).report()   // jdbc:…//user:pass@host
```

For the value itself, `secret(path)` reads a `Secret`, which prints as the mask
through `toString` and gives its value only to `reveal()`, so a log line or an
exception message cannot leak it:

```kotlin
import dev.surefold.app.typesafe.Secret

data class Credentials(val url: Secret, val poolSize: Int)

config<Credentials>("database") { Credentials(secret("url"), int("poolSize")) }
```

Two details of HOCON that the report handles: an environment variable is not
a layer, other than a forced `CONFIG_FORCE_` one, and only takes effect where a
file wrote `${?FOO}`; and
`ConfigFactory.defaultReference()` already includes system properties, so
`layeredConfig` reads `reference.conf` unresolved to avoid attributing a `-D`
value to it.

### Configuration in a test

```kotlin
import dev.surefold.app.typesafe.configFromResource
import dev.surefold.app.typesafe.configOf
import dev.surefold.app.typesafe.loadedConfig
import dev.surefold.app.typesafe.overridingConfig

loadedConfig()                              // what the service reads: application.conf and its reference
configFromResource("test.conf")             // a file beside the test
configOf("database.poolSize = 1")           // a document written in the test
```

Changing one key without restating the file:

```kotlin
testApp(app.overridingConfig("petshop.port = 0")) { server: HttpServer -> server }
```

`overridingConfig` puts the document on top of what the service would read, so
the port changes while everything else still comes from `application.conf`.
It uses `overriding`, so a key the graph does not use fails the test rather
than being silently ignored.

### A setting that picks a module

A setting such as `useCache` does not configure a repository; it chooses
between two. Surefold builds every node in a graph, so the branch not taken must
not be in it.

`choice` is a node that depends on the setting and provides what every branch
provides. Its value is the module Surefold builds into the graph once the setting
is built.

```kotlin
import dev.surefold.app.choice
import dev.surefold.app.typesafe.config
import dev.surefold.app.typesafe.loadedConfig

data class RepoSettings(val useCache: Boolean, val ttl: kotlin.time.Duration)

val choosing: Module =
    config<RepoSettings>("repo") { RepoSettings(boolean("useCache"), duration("ttl")) } +
        choice<RepoSettings, UserRepo> { repo -> if (repo.useCache) cachingRepo else plainRepo }

object Shop : SurefoldApp<UserRepo>() {
    override val module = loadedConfig() + choosing
}
```

The branch not taken contributes no node. Before anything runs, the choice is
one node providing `UserRepo`, so `validate`, `render` and the compiler check
the rest of the graph against it; a chosen branch that does not provide
`UserRepo`, or needs something nothing builds, refuses the start.
`overridingConfig("repo.useCache = false")` flips it in a test, and
`overriding` can replace a node that only the chosen branch brings.

`Config.choosing(path, read) { settings -> module }` makes the same choice
while the graph is assembled, from a `Config` already in hand. Where the
setting is a plain value already, `Module` is a value, so `when` works:

```kotlin
val carts: Module = when (settings.getString("cartStore")) {
    "postgres" -> postgresCarts
    else -> redisCarts
}
```

## A cluster joined from config

An application in `surefold-app` need not assemble its cluster by hand, as surefold-core's
[cluster guide](https://github.com/surefold/surefold-core/blob/main/docs/cluster.md) does. `surefold-app-cluster`
makes the cluster a node of the graph, joined as a HOCON section says. `join` names the
backend, and the section of that name holds what it needs. The backend is
found on the classpath, so add `surefold-cluster-kubernetes` or `surefold-cluster-aws`
to join through it. A name whose module is missing refuses the start, naming
the module. The backend brings its own client and closes it once the node has
left, and it chooses the downing that suits it: a lease on Kubernetes and AWS,
and keep-majority for `static`, `dns` and `srv`.

<!-- configuration-cluster -->
```hocon
shop.cluster {
  node {
    name = "shop-1"
    name = ${?POD_NAME}
    host = "127.0.0.1"
    host = ${?POD_IP}
    port = 25520
  }
  # static, dns or srv from surefold-cluster itself; kubernetes, ecs or cloudmap from their modules.
  join = "static"
  join = ${?CLUSTER_JOIN}
  static.seeds = ["127.0.0.1:25520"]
  # The pods labelled app=shop; the namespace is the pod's own.
  kubernetes { selector { app = "shop" }, lease = "shop-split-brain" }
  downing.stableAfter = 20s
  gossip { probeEvery = 1s, ackWithin = 600ms, formAfter = 5s }
  leaveWithin = 30s
  # exit: a node the others downed ends its process, so its orchestrator starts a new one.
  whenDowned = exit
}
```

```kotlin
val shop = loadedConfig() + actors() + cluster("shop.cluster") + single { cluster: Cluster -> Orders(cluster) }
```

The node joins as `Joining` and is admitted once every node of the graph is
built, so no shard is placed on it before its regions are open.
A node that needs the `Cluster`, such as `Orders` above, is therefore built
while this one is still `Joining`: sharding holds what it tells until there is
an owner, but anything that needs this node `Up` waits for `use`'s block, which
runs only once `cluster.ready()`. The scope leaves the cluster before the
application's actors stop. `cluster(ClusterSettings(...))`
takes the same settings from code, for an application that reads its
configuration its own way.
