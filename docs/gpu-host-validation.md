# CachyOS GPU-host validation (fish)

Run from the repository root with Java 25 available. These commands use the
normal Gradle wrapper and do not create a release artifact with Sodium bundled.

Detailed hot-path metrics are disabled during normal play. Add
`-Dthreadium.metrics.hotPath=true` only for diagnostic runs; do not use that
property for FPS or frame-time comparisons.

```fish
set -lx JAVA_HOME /usr/lib/jvm/zulu-25
set -lx PATH $JAVA_HOME/bin $PATH
./gradlew clean build
./gradlew runClient
```

Vanilla checklist: join a world, wait at least 35 seconds for `Phase 0 interval
metrics`, return to title, join again, press `F3+T` for a resource reload, then
exit normally. Inspect `run/logs/latest.log`:

```fish
rg -n 'Mixin|threadium|Phase 0|Exception|ERROR' run/logs/latest.log
```

Expected lifecycle debug events, when debug logging is enabled, are one world
generation per JOIN and DISCONNECT and one resource generation per completed
client resource reload. Confirm scheduler shutdown is logged after normal exit.

Current intended sequence: startup creates the scheduler and registers hooks;
first join advances world generation once; a dimension/world replacement is
expected to produce the Fabric disconnect/join pair (two intentional world
invalidations); disconnect advances it once; reconnect advances it on join;
the synchronous client resource listener advances resource generation once after
reload; normal shutdown advances both generations once and shuts the scheduler.
The installed Fabric API still exposes this resource listener through its
deprecated v0 API; no non-deprecated equivalent was verified, so it is retained
rather than inventing a replacement.

Run Sodium separately, retaining the same checklist:

```fish
./gradlew -Pthreadium.sodium=true runClient
rg -n 'sodium|Mixin|threadium|Phase 0|Exception|ERROR' run/logs/latest.log
```

With Sodium, confirm block-entity extraction timing remains zero/absent and the
other three timing fields appear once per reporting interval. Do not compare raw
world-render totals between configurations as equivalent performance results.
