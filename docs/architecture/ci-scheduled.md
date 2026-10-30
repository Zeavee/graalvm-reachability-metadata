# Scheduled CI workflows

These run on `cron` (and usually `workflow_dispatch`) and keep coverage current
and releases flowing without a human in the loop.

## AR-layered-tests: Shared and dedicated Native Image layer tests

Every Sunday at 00:30 UTC (`30 0 * * 0`) and on manual dispatch. The scheduled
run checks every supported library on the default branch with GraalVM `latest-ea`
and the `current-defaults` Native Image mode, using the cached shared base layer
(§AR-test-harness.3). An `all` selection uses 32 independent shared-layer
shards and 128 independent dedicated-layer shards to reduce the work each job
must finish within the six-hour hosted-runner limit (§FS-repository-functional-spec.6).
The first batch releases every shared shard and dedicated shards 1 through 64;
the second releases dedicated shards 65 through 128 after the first batch
finishes. This bounds the number of jobs waiting for runners, with one barrier
between batches. Test failures do not skip the second batch, but cancellation
or failed matrix preparation does. Every dedicated shard keeps the denominator
128, so splitting submission does not change coordinate assignment or coverage.
Each lane and batch calls the same reusable shard workflow with its own matrix,
keeping each matrix within GitHub Actions' 256-job limit. No matrix has a
`max-parallel` cap; available organization runner capacity controls concurrency.
Manual dispatch defaults to both lanes, `master`, `all`, and `latest-ea`;
it may instead run only the
shared or dedicated lane and select a different ref, coordinate, JDK, or mode.
A concrete coordinate or explicit shard creates one job per selected lane in
the first batch only. Selecting only dedicated tests retains both batches;
selecting only shared tests needs only the first. The scheduled workflow runs
both matrix lanes: the original shared JDK-layer
lane and a library-layer lane that creates one base layer per coordinate with
the tested library code included (§AR-test-harness.3).
Dedicated layers are deleted after each coordinate to bound runner disk use;
failures are collected independently so the two layer layouts remain directly
comparable.

The matrix preparation resolves the requested ref to one immutable commit.
Inside the shared reusable workflow, a single job restores or builds the common
base with §AR-setup-native-image-base-layer and uploads its complete directory
as a tar artifact (§FS-tests.3.6). Shared shards depend on this job, download its
artifact, verify repository, JDK release/version, mode, and runner identity, and
require the existing base manifest to match their build policy. The artifact
name is passed through job outputs so failed-job reruns can reuse the successful
producer's artifact. Dedicated shards skip that dependency's work and proceed
independently; custom shared bases still rebuild per coordinate.
Serves §FS-repository-functional-spec.5.4 — the Native Image mode this lane pins.

## AR-test-all-metadata: Test all metadata

Every Sunday (`0 2 * * 0`) and on manual dispatch. Uses
`generateMatrixBatchedCoordinates` with 85 batches to build a JDK/OS matrix, runs
the full `test` lane, pulls only allowed images, then disables Docker networking.
Failed batches are isolated down to concrete library versions, publish result
and failure-log artifacts, and fail in the matrix so the Actions UI points at
the failing batch. The aggregate job publishes a failure report when failures are
found (§FS-repository-functional-spec.5.3); it surfaces sweep regressions but does
not gate the scheduled release (§AR-create-scheduled-release).

## AR-verify-new-library-version-compatibility: Verify new library version compatibility

Every day (`0 0 * * *`) and on manual dispatch. Owns the upstream-version
tracking loop fully specified in §FS-repository-functional-spec.9
(§GOAL-broad-version-coverage, §GOAL-fresh-metadata): it discovers newer versions
only for libraries whose latest index entry sets `auto-update: true`, using
`fetchExistingLibrariesWithNewerVersions`, and builds a matrix with
`generateNewLibraryVersionCompatibilityMatrix` (capped per
§FS-repository-functional-spec.5.3), tests every candidate across the matrix
using `run-consecutive-tests.sh` (§AR-shared-scripts), records versions that pass
everywhere via `addTestedVersion` in one
`library-bulk-update` PR (refreshing the root coverage table), and files one
aggregated `fails-*` failure issue per failing version. A failure for an artifact
whose latest index entry sets `high-priority: true` also receives the
`high-priority` label. Those failure issues are
the entry point of the Forge repair queue (§forge/FS-forge-functional-spec).

## AR-publish-scheduled-coverage: Publish scheduled coverage

Every two hours (`15 */2 * * *`) and on manual dispatch. Regenerates the coverage
badges and dashboard from committed `stats/` and `metadata/**/index.json`
(§FS-repository-functional-spec.4.5), then force-pushes the published artifacts
to the `stats/coverage` branch. The published branch keeps only `COVERAGE.md`,
`latest/badges.json`,
`latest/metrics-over-time.svg`, `latest/metrics-over-time-dark.svg`, and
`history/history.json`.

## AR-create-scheduled-release: Create scheduled release

Every Monday (`0 3 * * 1`) and on manual dispatch. Packages metadata only if it
changed; runs `spotlessCheck` before packaging
(§FS-repository-functional-spec.5.3). It is deliberately not gated on the periodic
`test-all-metadata` sweep (§AR-test-all-metadata) so bleeding-edge sweep failures
cannot stall the release cadence.
The workflow considers only semantic version tags when choosing the previous
numbered release tag, so floating snapshot tags such as `SNAPSHOT` are ignored.
It then creates the next `<major>.<minor>.<patch>` release. The packaged ZIP is
the numbered artifact native-build-tools consumes (§FS-repository-functional-spec.4,
§GOAL-fresh-metadata).

## AR-create-snapshot-release: Create snapshot release

On pushes to `master` and on manual dispatch. Publishes a floating `SNAPSHOT`
GitHub Release on the `SNAPSHOT` tag when metadata changed since the previous
`SNAPSHOT` tag; if that tag does not exist yet, it bootstraps the diff from the
latest numbered release tag. The workflow packages metadata with repository
version `SNAPSHOT`, deletes the previous snapshot release/tag when present,
force-pushes a fresh `SNAPSHOT` tag, and marks the release as not GitHub's
Latest release (§FS-repository-functional-spec.4.4, §GOAL-fresh-metadata).

## AR-test-all-metadata-crema: Test all metadata on the Crema JVM

Every Saturday (`0 2 * * 6`) and on manual dispatch. Runs the repository's JVM
test lane against Crema — Native Image's run-time class loading VM — to find
where Crema cannot yet run real library test suites. It is a bug-finding sweep
aimed at Crema, not a metadata gate: `metadata/` correctness is not what it
measures, and its result never blocks a release. Saturday keeps it clear of the
Sunday metadata sweep (§AR-test-all-metadata) and the Monday release
(§AR-create-scheduled-release) so the three never compete for runners.
Serves §FS-repository-functional-spec.5.4 — the Crema lane is a Native Image mode.

The scheduled run takes every input's default — all coordinates over 85 shards on
the `crema` lane with assertions cleared — because the `inputs` context is empty
on a `schedule` event; the workflow restates each default rather than resolving
an empty matrix. The weekly trigger is confined to the canonical repository so a
fork does not sweep on its own schedule, while manual dispatch stays available
everywhere.

The JDK is built once by a dedicated job via §AR-setup-crema-jdk and shared with
every shard as an artifact, because the macro build takes about ten minutes and
produces the same library every time — building it per shard would spend hours of
runner time reproducing identical work. Debug info and sources are stripped
before upload as run-time-irrelevant bulk. A single JDK serves both lanes: the
action leaves `jvm.cfg.hotspot-default` in the tree next to the Crema-default
`jvm.cfg`, so a shard selects its VM by choosing between the two files. Each
shard re-asserts the VM identity after unpacking rather than trusting the build
job, so an unpack or selection mistake cannot let a shard silently measure the
wrong VM.

Shards resolving Maven Central concurrently draw HTTP 429, which fails a shard
during dependency resolution and loses every coordinate in it. Four measures
address it, in order of how directly they attack the cause. The build job
resolves the build-logic classpath once and shares it as a Gradle module cache,
so the sweep no longer repeats one identical request burst 85 times; shards
restore that cache read-only. `max-parallel` caps how many shards resolve at
once. The shard's setup commands are retried with minute-scale waits, because
both observed failures struck in setup before any coordinate ran — the
per-coordinate tests are deliberately *not* retried, so a genuine Crema failure
fails once and fast. Finally, Gradle's own retry backoff is stretched on both the
network-operation and module-repository layers, written to
`GRADLE_USER_HOME/gradle.properties`: `GRADLE_OPTS` configures the Gradle client
JVM while resolution runs in the daemon, which does not inherit those properties,
and the user-home file is also the only one every per-coordinate build reads,
each being a separate Gradle build rooted in its own directory.

A shard lost to rate limiting is never reported as green. Its coordinates went
untested, and a clean sweep over libraries nobody measured is precisely the
outcome this workflow's guards exist to prevent.

Each coordinate's test run is separately time-bounded. A Crema fatal error can
leave the test worker wedged with Gradle waiting on it indefinitely, which
consumed a whole shard and discarded every coordinate queued behind it before the
bound existed. A hang is itself a finding, so it is recorded against the one
library that caused it rather than being allowed to swallow its shard. The job's
own timeout stays below the hosted-runner ceiling so an overrun fails on the
workflow's terms instead of arriving as an unexplained cancellation.

Tests run through the ordinary `javaTest` lane (§AR-test-harness.3) with
`GVM_TCK_TEST_JAVA_HOME` pointing at that JDK (§AR-test-harness.3.1), so workers
execute on Crema while Gradle keeps running on the runner's stock JDK. No Crema-specific JVM flag is ever added: a
library that fails only because Crema rejects an argument the JVM lane normally
passes is a finding, not something the workflow works around. JaCoCo is disabled
via `-PskipJacoco=true` because Crema ignores `-javaagent`, which would otherwise
publish empty coverage as though it were real.

The `vm` input selects the lane: `crema`, `hotspot`, or `both`. `hotspot` runs
the identical tree on the identical JDK with the stock VM, and only
`crema`-fails-while-`hotspot`-passes is reportable — this repository has
coordinates that fail for environmental reasons under any VM. Failures are
attributed per coordinate and published as NDJSON plus log artifacts in the same
shape as §AR-test-all-metadata, so the existing failure tooling applies. The
matrix comes from `generateMatrixBatchedCoordinates` (§AR-test-harness.7) via
the `batches` input; `coordinates` narrows a run to one shard or one library.

Crema currently rejects `-ea` at VM startup, and Gradle puts `-ea` on every test
worker, so leaving it in place fails every coordinate identically before any test
executes and the sweep returns one finding repeated per coordinate instead of a
survey. The `disable-assertions` input therefore defaults to true, clearing
Gradle's `enableAssertions` (§AR-test-harness.3.1) so the sweep reaches the
failures behind that blocker. Setting it false re-checks whether the blocker is
still present. Because assertions do not fire under this default, tests that
verify via `assert` pass vacuously: a green coordinate here means Crema ran the
code, never that the library is supported.
