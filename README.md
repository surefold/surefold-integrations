# Surefold integrations

Every Surefold module that wraps one outside library: Pekko, Kafka, SQS and SNS, the AWS SDK, protobuf, Avro and kotlinx wire formats, Kubernetes and AWS discovery, Typesafe Config, Liquibase, Flyway, OpenFeature, SLF4J, Micrometer, OpenTelemetry and Arrow.

This repository is part of Surefold, which is three repositories:

| Repository | Holds |
|---|---|
| [`surefold-core`](https://github.com/surefold/surefold-core) | structured concurrency, typed errors, resources, sagas, streams, actors and the cluster, on the JDK and Kotlin alone |
| [`surefold-integrations`](https://github.com/surefold/surefold-integrations) | one module per outside library |
| [`surefold-platform`](https://github.com/surefold/surefold-platform) | platform features, the example application and `surefold-bom` |

## Status

Empty until its seed lands: the modules still live in `surefold-core` and move here with their history under
spec 0302 in `surefold-core`. Coordinates and packages do not change: every artifact stays `dev.surefold:<name>` and every
package `dev.surefold.*`.

Nothing is on Maven Central yet. Builds use pinned snapshots from GitHub Packages, and the first release of each
repository comes once all three are proven to work together.

## Depending on Surefold

Take one version for everything through the BOM (spec 0306), rather than picking three by hand:

```kotlin
dependencies {
    implementation(platform("dev.surefold:surefold-bom:<version>"))
    implementation("dev.surefold:surefold")
}
```
