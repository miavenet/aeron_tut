# L06 operations — find the stalled boundary

Optional guided case: **15 minutes** (design budget). Save the core workspace.
Use four short steps: predict the first stalled event (3 min); run and inspect
logs (4 min); explain one intervention (4 min); rerun and recall the invariant
without notes (4 min). Record your card/workspace before stopping.

## Guided: pause, query, resume

Predict whether a historical LEADER line is enough after a process pause.
Run your repaired workspace:

```bash
printf 'labs/l06/work\n' | checks/l06/pause.sh
```

The fixture pauses only its queried leader, permits the other members to progress,
resumes its owned process, then restarts that member from preserved state to
repair clients expired during the whole-JVM pause. It checks reconciliation,
quorum loss and rejoin.
Inspect `ROLE`, application `BARRIER`, membership-query and client events in the
returned artifacts. Old role history cannot select the current leader reliably.
Heartbeat/election warnings during this injected failure are diagnostics, not
proof that the final state check failed. Unexpected exceptions still require
investigation.

Produce: hypothesis → first stopped application event → intervention → final
barrier. Self-grade: did you query live leadership, retain pending keys and prove
at most one mutation per valid key on every replica? If not, restate the missing criterion and
rerun. Return tomorrow to explain why role history is insufficient.

## Searchable reference

Predict the failure before each command; then locate the fault and repaired
progress in the recorded events. Commands compile the supplied workspace.


| Case | Command | Required evidence |
|---|---|---|
| Missing keepalive/poll | `printf 'labs/l06/work\n' \| checks/l06/polling.sh` | Deliberately idle session expires with TIMEOUT; fresh polling client reaches a barrier |
| Session limit | `printf 'labs/l06/work\n' \| checks/l06/session-limit.sh` | Limit set to one; second connect is refused; release/expiry allows fresh progress |
| Wrong endpoint | `printf 'labs/l06/work\n' \| checks/l06/wrong-endpoint.sh` | Wrong-endpoint connect reaches bounded timeout; corrected endpoint reaches barrier |
| Retry policy | `printf 'labs/l06/work\n' \| checks/l06/policy.sh` | Stable client/key cache, pending retention, duplicate-result consistency and terminal-result reconnect |

These are local live scenarios plus the executable application-policy probe.
No real network-partition safety claim follows. Full-restart/new-session recovery
is exercised in L07. Historical check timings are not included; the stated budgets are planning estimates.
