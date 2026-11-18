# L07 operations — a snapshot is work

Optional guided case: **15 minutes** (design budget). Predict (3 min), run and
inspect (4 min), explain the repair (4 min), then recall the invariant without
notes (4 min). Save the current workspace/card when interrupted.

## Guided: hold the snapshot callback

Predict whether `SNAPSHOT_BEGIN` proves a completed recovery point.

```bash
printf 'labs/l07/work\n' | checks/l07/slow-snapshot.sh
```

The fixture holds the application callback behind a release gate and a watchdog.
It requires no completed service/consensus snapshot pair before release, then
waits for matching completed positions, restarts and verifies suffix/retry/fresh
progress. BEGIN or a written application payload alone is insufficient.


Produce a diagnosis: what was waiting, what released it, and what proved recovery?
Self-grade: name the callback gate, the completed pair at a shared position, and
the application-state/retry checks. Restate any missing boundary and retrieve it
tomorrow before reopening this file.

## Searchable reference

Predict the refusal or replay IDs before each run. Each command checks your work;
codec failures are MISS and infrastructure/watchdog failures are INFRA.


| Case | Command | Required evidence |
|---|---|---|
| No snapshot | `printf 'labs/l07/work\n' \| checks/l07/no-snapshot.sh` | Actual full-history IDs; same recovered state and old cached result |
| Incompatible app version | `printf 'labs/l07/work\n' \| checks/l07/incompatible.sh` | Startup refuses changed container app version; compatible restart restores/progresses |
| Low storage | `printf 'labs/l07/work\n' \| checks/l07/storage.sh` | Archive threshold injected above usable space; recording refusal, then repaired progress without filling disk |
| Codec | `printf 'labs/l07/work\n' \| checks/l07/codec.sh` | Whole state and result cache restored; unsupported/truncated/trailing bytes rejected atomically |
| Maximum log position | `printf 'labs/l07/work\n' \| checks/l07/max-position.sh` | Terminal-result policy for maximum position; no live log exhaustion |



Snapshots and retained history have separate policies. Process restart does not
prove power-loss durability. Timer metadata here is application state; the fixture
does not claim timer-race exploration. Historical check timings are not included.
