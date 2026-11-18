# L03 operations — evidence before intervention

One guided case (about 15 minutes) and four reference cases. Each case has a
deliberately broken `Diagnostics.java` under `labs/l03/faults/` and the working
version under `labs/l03/solution/`; they differ in one method. Read the broken
method, write hypothesis → evidence → one intervention → rerun, then run both.

To practise a repair, copy a broken file over your workspace. This overwrites
`labs/l03/work/Diagnostics.java` without a backup:

```bash
cp labs/l03/faults/no-duty/Diagnostics.java labs/l03/work/Diagnostics.java
```

## Conductor goes on holiday (guided)

The client library has its own conductor agent. With
`useConductorAgentInvoker(true)` it runs only when your code calls
`invoke()` on its invoker. Predict: the driver has linked a publication to your
subscription, but your code never invokes the client conductor. Does the image
appear in your subscription?

```bash
printf '%s\n' labs/l03/faults/no-duty | checks/l03/invoker.sh
printf '%s\n' labs/l03/solution | checks/l03/invoker.sh
```

The first prints `MISS` (`restore client conductor invocation to process image
event`): the image stays pending. The second prints `HIT`: once `duty()` calls
`invoke()`, the image arrives. Both driver and client are alive throughout; this
differs from a stale driver heartbeat because the stalled agent is in your
process. Repair `duty()` in your copied workspace and run `invoker.sh` on
`labs/l03/work` until it prints `HIT`.

## Reopening is not rewind (reference)

Predict the first ID a reopened subscriber receives. The fixture drains three
messages, joins a late subscriber, closes it, sends one more, then reopens and
adds a second publication on the same stream. Lesson 3's last section draws the
timeline.

```bash
printf '%s\n' labs/l03/faults/history | checks/l03/late.sh
printf '%s\n' labs/l03/solution | checks/l03/late.sh
```

The broken `needsReplay` always says no and prints `MISS`; the solution prints
`HIT`. This is an IPC fixture; it does not test UDP reconnect timing or any
Archive replay.

## Policy notebook, a trace model (reference)

This case is arithmetic on supplied numbers, not a live network. For receiver
window limits `[600,200,400]`, where receivers 0 and 2 are tagged, a "max" policy
takes the fastest (600), "min" the slowest (200), and "tagged" the slowest tagged
receiver (400). A gap trace (IDs 10 then 13) and a late rejoin (expected 10,
received 13) both need the application to notice the missing IDs.

```bash
printf '%s\n' labs/l03/faults/wrong-policy | checks/l03/trace.sh
printf '%s\n' labs/l03/solution | checks/l03/trace.sh
```

The broken min policy prints `MISS`. The check also confirms this release's
counter type IDs (`rcv-hwm` 3, `sub-pos` 4, `rcv-pos` 5) and that a missing
counter is never reported as drained. No multicast, tagged group or live rejoin is
exercised here.

## Bounded evidence while the log is alive (reference)

Predict which output tells a repaired network gap apart from consumer lag, then run:

```bash
printf '%s\n' labs/l03/faults/gap-as-lag | checks/l03/gap.sh
printf '%s\n' labs/l03/solution | checks/l03/gap.sh
```

The first calls the gap a slow consumer and prints `MISS`. The second keeps the
publication alive while `AeronStat`, `StreamStat`, `LossStat`, `ErrorStat` and
`LogInspector` capture their output (each log capped at 64 KiB). Open the artifact
directory named in the result and match session, stream and channel across
`gap.log`, `StreamStat.log` and `LossStat.log`. `LossStat.log` records the lost
1,408 bytes even though `ErrorStat.log` reports zero errors, so a clean error log
is not proof of zero loss. A missing log or a broken cap is a SETUP PROBLEM.

## Stale dashboard (reference)

Predict whether equal positions prove the driver is live, then run:

```bash
printf '%s\n' labs/l03/faults/stale-is-drained | checks/l03/liveness.sh
printf '%s\n' labs/l03/solution | checks/l03/liveness.sh
```

The broken diagnosis calls a stale driver drained and prints `MISS`. The solution
sees the stale heartbeat, restores the invoker's duty and sees it refresh. The
1,200 ms threshold is a lab policy, not a production timeout, and a stale
heartbeat does not by itself prove the process died.
