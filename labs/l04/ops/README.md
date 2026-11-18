# Archive operations — a recorder is a state machine, not a red light

Optional guided case: 15 minutes, interruptible after each prompt. This is a
design budget, not a novice measurement. Use a copy of `ArchiveWork.java`; never
repair a valuable archive by experimenting on its only copy.

## Pretest, 3 minutes

Before opening the worked route, predict: a replay subscriber never connects;
does the replay wait forever? What if it connects, consumes the known prefix,
then disconnects? Write a hypothesis, evidence to seek, and one intervention.

<details><summary>Worked route — after prediction</summary>

The lifecycle fixture starts a replay with no subscriber and observes the
archive's connection-deadline error. Then a live follow replay consumes its
prefix; closing the subscription is followed by the active-replay counter
returning to zero. Its application wait policy also has an explicit deadline.
The fixture uses a short configured timeout; it does not promise a production
replay timeout.
</details>

## Completion, 4 minutes

In `labs/l04/faults/no-deadline/ArchiveWork.java`, locate the broken method without
copying the solution. On paper complete: “expire when now ___ deadline.” Predict
the cases now=9,10,11 and deadline=10. Then open the key.

<details><summary>Key — after your attempt</summary>

Use `>=`: false, true, true. The lifecycle check tests those boundaries before
its live never-connect/disconnect scenarios.
</details>

## Independent diagnosis, 5 minutes

Copy the broken adapter over your workspace (this overwrites
`labs/l04/work/ArchiveWork.java`; `checks/l04/prepare.sh` restores an exercise
later). Run the lifecycle check, repair `deadlineExpired`, and run it again:

```bash
cp labs/l04/faults/no-deadline/ArchiveWork.java labs/l04/work/ArchiveWork.java
printf '%s\n' labs/l04/work | checks/l04/lifecycle.sh
```

The first run prints `MISS` (`Deadline policy must bound replay waiting`); the
repaired file prints `HIT`. Read `lifecycle.log` in the artifact directory named
in the result and state which observation distinguishes failure to connect from
an ended follow replay.


## Explain and transfer, 3 minutes

Close the log. A replay consumer exits intentionally after a prefix. Explain why
the restart procedure should reconnect and request the remaining range, rather
than treat a stopped replay session as delivery of the missing suffix. Grade
your answer against: consumption of the prefix is observed; disconnect ends the
replay path; the application must establish the remaining range and compare its
bytes. Carry a miss to tomorrow.

## Runnable reference

Each check reads a workspace path on stdin and compiles that adapter. HIT/MISS/
INFRA are exits 0/1/2; infrastructure failure retains logs and does not grade the
learner. `labs/l04/solution` is the complete worked adapter; fault directories are
deliberately wrong adapters. Allow roughly 3–7 minutes to investigate one case; this is a planning estimate.


| Case / executable | What its live or explicit fixture checks | Broken adapter |
|---|---|---|
| `checks/l04/config.sh` | Missing server control/replication and client request/response channels rejected; inconsistent sync rejected; candidate configuration inspected before launch | `missing-control`, `missing-replication`, `sync-mismatch`, `delete-on-start` |
| `checks/l04/nonzero.sh` | Configured nonzero recording start, exact replay and invalid offsets | `zero-start`, `no-assembler` |
| `checks/l04/isolation.sh` | Two clients with distinct response streams, two exclusive publication images; wildcard plus session-specific recording creates three descriptors that progress | `shared-response` |
| `checks/l04/lifecycle.sh` | Never-connect error and disconnect termination at a known consumed prefix, plus application deadline boundaries | `no-deadline` |
| `checks/l04/geometry.sh` | Wrong-MTU extension rejected; descriptor-matched geometry extends the same recording | `wrong-mtu` |
| `checks/l04/storage.sh` | CRC-32 recording/replay, intact verification, corrupted disposable copy rejected, original preserved | `wrong-checksum` |
| `checks/l04/tool.sh` | Disposable ambiguous page-straddling tail rejects catalog recovery; ArchiveTool verify truncates that copy; describe works and help-advertised capacity is rejected by dispatch | `truncate-original`, `trust-help` |
| `checks/l04/merge.sh` | Actual IPC constructor rejection plus an application guard against a shared client | `shared-merge` |

Each row names a runnable check and deliberate fault; historical run artifacts are not included.

In the checksum fixture the on-disk session slot stores the checksum; it is not
the original publication session ID. Java 26 needs an additional
`--add-opens=java.base/java.util.zip=ALL-UNNAMED` for this jar's CRC implementation;
the storage wrapper supplies it alongside the baseline module opening.


The tail fixture manufactures a corrupt copy using a package-local catalog test
helper, then exercises public ArchiveTool verification. It is a deterministic
recovery-boundary test, not a simulated power loss. File/catalog sync and the
checksummed recording's original contents remain separate evidence.


The course tests an IPC rejection and an adapter guard for sharing an Archive client. These tests do not verify a successful live merge cutover or replication.
