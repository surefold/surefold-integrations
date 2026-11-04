# Working in this repo

## Where this repository sits

Surefold is three repositories, each depending only on the ones above it, through published artifacts:

```
surefold-core          JDK + Kotlin only at runtime
      ▲
surefold-integrations  core + one outside library per module
      ▲
surefold-platform      core + integrations, and surefold-bom
```

This is `surefold-integrations`. It may depend on `surefold-core` only, through its published artifacts, plus the one
outside library each module's name says it integrates. `surefold-platform` builds on this repository.

## Rules on top of core's

The way of working is `surefold-core`'s, set out in the sections below: specs first, one spec section per pull
request, the failing test first, `./gradlew spotlessApply && ./gradlew build` before saying anything is done, and the
same gates (detekt, `NoOtherDependenciesTest`, Kover at 90%). On top of those:

- **Upstream comes from GitHub Packages, pinned.** `gradle.properties` names the exact snapshot versions of each
  upstream repository, read from `https://maven.pkg.github.com/surefold/<repository>`. Moving to a newer upstream is
  a one-line pull request that bumps the pin and is green. No Surefold POM uses a version range.
- **Coordinates never change.** Artifacts stay `dev.surefold:<name>`, packages stay `dev.surefold.*`.
- **One outside library per module**, the one its name says. A second one is a second module.
- **Nothing from `surefold-platform`.** Platform depends on this repository, never the other way round.
- **Working across repositories:** check the repositories out side by side (`../surefold-core`). The build includes
  the upstream ones it finds there, and Gradle uses their source in place of the pinned artifacts, so a change
  upstream reaches this repository's tests without publishing. CI never checks them out: it always builds against
  the pins.
- **Specs** live in `specs/` here, numbered from 0001. Specs about this repository's modules written before the
  split stay in `surefold-core` as the record; link them rather than copying.
- **No Central release** until all three repositories are proven together. Snapshots publish on every push to main.

## Specs come first

Nothing is implemented without a spec file in `specs/`. An agent drafts it, a
human edits it, and only the edited, committed version gets built.

The draft is a proposal, not a plan. Its job is to be **cheap to disagree
with**, so it stays short enough to read in one sitting:

- **One page.** Roughly 80 lines. A draft that runs longer is proposing too
  much at once — split it into two specs rather than writing more.
- **No prose padding.** Fill the template's headings and stop. Where a heading
  has nothing real under it, write "nothing" and move on.
- **Uncertainty goes under Open questions**, not into a hedged paragraph under
  Shape. Three or four questions in a first draft is healthy.
- **Options, not verdicts.** Where a design could go two ways, give each a
  sentence and recommend one. Do not silently pick.

Then stop and hand it over. Do not implement a spec nobody has edited and
committed: an unreviewed draft is still the agent's own opinion, which is the
thing this process exists to stop.

Before a spec exists: ask questions, read code, answer in chat. No code.

`specs/README.md` gives the layout and the lifecycle. Specs here are numbered from 0001.

## One spec section per pull request

Pull requests are stacked. Each branch sits on the one before it and is
reviewable on its own.

- **Soft cap: 200 changed lines** of main sources, build files and docs. Tests,
  generated sources and golden fixtures do not count. Past that, split before
  writing code rather than after.
- **One spec section per PR.** A spec with four stack entries is four branches,
  not one branch with four commits.
- **Announce the split first.** Post the intended stack — branch name and one
  line each — and wait for a yes before the first commit.

Stacking commands (`gh stack init`, `add`, `submit --open`, fixing a lower
entry): surefold-core's [`docs/agents/stacking.md`](https://github.com/surefold/surefold-core/blob/main/docs/agents/stacking.md).

## Layout

Modules keep the family folders they had in surefold-core (`actor/`, `app/`, `bench/`, `cluster/`, `core/`,
`integrations/`, `stream/`) and their flat Gradle paths, `:surefold-kafka` rather than `:integrations:surefold-kafka`;
`settings.gradle.kts` says which folder each is in. A new module goes in its family's folder and gets a `module(...)`
line there.

Every module depends on the surefold-core modules it builds on, by coordinates at the pinned `surefoldCore`
(`"dev.surefold:surefold-actor:$surefoldCore"`), on other modules here by `project(":…")`, and on at most the one
outside library or SDK its name says it integrates: `surefold-pekko` adds `pekko-actor`, `surefold-kafka` adds
`kafka-clients`, and so on.

Each claim is a test. `NoOtherDependenciesTest` asserts the module's main runtime classpath, in every module alike.

A dependency added to any module is a build failure, not a judgement call.

## Coding standards

Comments, imports, errors, effects and read-only API:
[`CODING_STANDARDS.md`](CODING_STANDARDS.md). Review enforces it.

## Testing

Write the failing test first. Most claims here are claims about agreement
between the route, the document, the test client and the generated client, and
a test written afterwards asserts what the implementation does rather than what
the description promised.

Test names are sentences in backticks. `surefold-assert` matchers, JUnit 5, `withClue`
where a bare boolean would not explain itself.

Work out which of these a change can break:

- **Thread claims.** A test that says the body ran on a virtual thread reads
  `Thread.currentThread().isVirtual` inside the body and asserts on it
  afterwards; it does not count threads or time sleeps.

### Examples in the docs are compiled

The guides' Kotlin fences are compiled by tests that read the Markdown: `GuideTest` in surefold-app-pekko
(`docs/observability.md`, `docs/arrow.md`, `docs/pekko.md`, `docs/configuration.md`), `PekkoGuideSuite` in
surefold-stream-pekko (`docs/pekko.md`'s stream examples), `ReportedSuite` in surefold-app-typesafe and
`GuideConfigSuite` in surefold-app-cluster (`docs/configuration.md`'s report and HOCON), `SqsGuideSuite`,
`SqsPekkoGuideSuite`, `SnsGuideSuite` and `JdbcGuideSuite`. A fence is found by the HTML comment above it, such as
`<!-- arrow-either -->`, so keep the marker when editing an example, and add one to a new complete example. Each
module's `build.gradle.kts` declares the pages it reads as test inputs.

### An actor system belongs to the test class

`surefold-pekko` and `surefold-stream-pekko` tests need an `ActorSystem`. It is created in a
`companion object` from a config string and stopped in `@AfterAll`, never by
hand inside a test and never shared across classes — a system left running
holds threads that make the next suite's thread assertions lie.

Kover has a floor of 90% on `check`.

## Verifying

`./gradlew build` runs tests, detekt and spotless. Run it before saying
anything is done, and quote the result rather than predicting it.

**Finish with `./gradlew spotlessApply`.** Formatting is a gate like any other,
and a change that is correct but unformatted fails the build for whoever runs
it next. Run it last, after the final edit, so nothing lands unformatted:

```bash
./gradlew spotlessApply && ./gradlew build
```

CI builds the whole repository on every pull request and push, against the pinned core snapshot.

One Gradle build at a time per checkout. Two at once fail with timeouts and
missing test results that look like real failures.

Three gates sit beyond the tests. Each exists because a claim in the README
would otherwise be unverified.

| Gate | Fails when | Not the fix |
|---|---|---|
| detekt | any finding, or a source it cannot compile (`X.serializer()`: write `serializer<X>()`) | a suppression with no reason |
| `NoOtherDependenciesTest` | anything but what the module is allowed on the runtime classpath | widening the allow-list |
| Kover | line coverage under 90% | lowering the floor |

Before saying it is done:

- The failing test came first, and fails without the change.
- `./gradlew build` is green, gates included.
- No outside library beyond the one a module's name says.
- The README reflects the change, or the change is invisible from outside.

## Issue tracker

Specs are the tickets: a spec's Stack entries are its work items, in blocking order. Other issues live in GitHub
Issues for `surefold/surefold-integrations`.
