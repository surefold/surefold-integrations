# Surefold integrations

Every Surefold module that wraps one outside library: Pekko, Kafka, SQS and SNS, the AWS SDK, protobuf, Avro and kotlinx wire formats, Kubernetes and AWS discovery, Typesafe Config, Liquibase, OpenFeature, SLF4J, Micrometer, OpenTelemetry and Arrow.

This repository is part of Surefold, which is three repositories:

| Repository | Holds |
|---|---|
| [`surefold-core`](https://github.com/surefold/surefold-core) | structured concurrency, typed errors, resources, sagas, streams, actors and the cluster, on the JDK and Kotlin alone |
| [`surefold-integrations`](https://github.com/surefold/surefold-integrations) | one module per outside library |
| [`surefold-platform`](https://github.com/surefold/surefold-platform) | platform features, the example application and `surefold-bom` |

## Status

Seeded from `surefold-core` with each module's history (surefold-core's spec 0302). Coordinates and packages did not
change: every artifact stays `dev.surefold:<name>` and every package `dev.surefold.*`.

Nothing is on Maven Central yet. This repository builds against a pinned `surefold-core` snapshot from GitHub Packages
(`surefoldCore` in `gradle.properties`), and the first release of each repository comes once all three are proven
to work together.

## Building

```bash
./gradlew build
```

GitHub Packages asks for a token even to read a public package: put a classic personal access token with only
`read:packages` in `~/.gradle/gradle.properties` as `githubPackagesUsername` and `githubPackagesPassword`. With
`surefold-core` checked out beside this repository (`../surefold-core`), the build uses its source instead, and needs
no token for it.

## Guides

[Pekko](docs/pekko.md), [configuration](docs/configuration.md), [feature flags](docs/flags.md), [Kafka](docs/kafka.md),
[SQS](docs/sqs.md), [SNS](docs/sns.md),
[observability](docs/observability.md),
[JDBC tracing](docs/jdbc-tracing.md) and [Arrow](docs/arrow.md). The core's guides are in
[surefold-core](https://github.com/surefold/surefold-core).

## Depending on Surefold

Take one version for everything through the BOM (spec 0306), rather than picking three by hand:

```kotlin
dependencies {
    implementation(platform("dev.surefold:surefold-bom:<version>"))
    implementation("dev.surefold:surefold")
}
```
