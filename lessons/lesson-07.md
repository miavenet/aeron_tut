# Lesson 07 — The counter survived; the receipt book did not

**Your service restarts from a snapshot. The balance is right. Then a customer's
old retry arrives, and they are charged again.** The snapshot saved the number
but forgot the receipts. A snapshot must hold everything the *next* command
depends on, or recovery quietly changes the rules.

By the end you can write and restore a complete snapshot, reject a broken one
without damage, and prove recovery with replay and an old retry.

Time: **about 48 minutes, in two sittings of about 23 and 25 minutes** (design
estimates, not measured times). You need lesson 06: stable request keys and
the receipt book of cached results.

## Session A — Save the whole contract (about 23 min)

### CARD L07-P1 · Before you learn
Q: Your service's snapshot saves count and total, but not the receipt book, the config, or a pending timer. After a restart, an old request is retried. (a) Is restoring the right total enough? (b) Do config and timer details matter after restart? One sentence each; guess if unsure.
<details><summary>Key — open after your guess</summary>

Key:
- (a) No: if the keys are forgotten, the old request can run again; if the cached results are forgotten, the reply can be wrong even when nothing changes state again.
- (b) Yes: the config and the details of timers your application set affect what happens next, so recovery must restore them too.
</details>

## Words for this lesson

- **Snapshot**: your service's whole state written as bytes at one log position.
  Cluster calls `onTakeSnapshot(publication)`; you write the bytes. On restart,
  `onStart(cluster, snapshotImage)` hands them back (`null` if there is none).
- **Suffix**: the log entries after the snapshot position. Recovery = load the
  snapshot, then replay only the suffix.
- **Schema version**: a number at the start of your bytes that says which
  layout they use.
- **Atomic restore**: decode into a temporary object; only if everything is
  valid, copy it into the live service in one step.

### CONCEPT L07-CA · The idea: snapshot
Prediction: Config 3 (each add adds `value × 3`). Requests 1 and 2 add 7 and 2. Snapshot. Then requests 3 and 4 add 4 and 6. Restart from the snapshot. Predict (a) count and total in the snapshot, (b) count and total after recovery, (c) which request IDs the service sees again during recovery. Commit before opening.

<details><summary>Explanation — open after committing your prediction</summary>

Worked:
(a) Count 2, total 21 + 6 = **27**. (b) Count 4, total 27 + 12 + 18 = **57**.
(c) Only **3 and 4**, the suffix. Without a snapshot, the service would see the
whole history again: 10, 11, 12 (config, tick, timer), then 1, 2, 3, 4.

What goes in? Everything the next command depends on. This course's
`RequestWork.snapshot()` writes:

| bytes | field | why it matters |
|---|---|---|
| 4 + 4 | magic `0x57503553`, version 1 | refuse bytes you cannot read |
| 6 × 8 | count, total, config, tick, timerId, timerDeadline | business state, config, pending timer |
| 4 | number of receipts, N | so the decoder knows when to stop |
| N × … | key text + op, value, aux, count, total | the receipt book |

Aeron snapshots its own state too. After a snapshot the check finds two entries
at the same log position: one for the consensus module (service ID −1, which
holds client sessions and Aeron timers) and one for your service (ID 0). Your
receipts, config and timer intentions are your job.

Why do all the replicas snapshot at the same point? In Aeron Cluster the
request to snapshot is itself a record in the log, and each service acts on it
when it reads that record. Todd Montgomery, who built Aeron with Martin
Thompson, put it this way in a 2019 QCon talk: "If it's an event in the log
that says, 'Take a snapshot,' guess what? Everyone takes a snapshot at the
exact same point in the log."

The check proves recovery by behaviour, not by a "loaded" message:

1. Prefix 10, 11, 12, 1, 2 and barrier 900 → count 2, total 27.
2. Snapshot on the leader. (A follower's request is refused.)
3. Suffix 3, 4 and barrier 901 → count 4, total 57.
4. Stop all three, restart from kept directories. Each must print LOADED with
   the barrier-900 digest, then apply only 3 and 4.
5. A *new* client session retries key 1. It must get (1, 21), the old receipt.
   Then request 5 (add 8) → count 5, total 81 everywhere.

The completion bug: `restore` empties the receipt book and never refills it. The
retry in step 5 would run again and add 21 a second time.

Common wrong belief: "save the number." You saved the result but lost the rules
for the next request.

Rule: a snapshot holds state, config, ID counters, pending intentions and the
receipt book.
</details>

Elaborate: why is checking the restored counter weaker than retrying an old key?

### CARD L07-C1 · Try it
Q: Run the first command once (it overwrites both files in `labs/l07/work`). In `RequestWork.restore`, repair the line marked `TODO(L07-C1)` so the decoded receipts reach the live map. Run the codec check until HIT. Then answer in one sentence: why must old results survive a restart?
```bash
bash checks/l07/prepare.sh completion
printf 'labs/l07/work\n' | checks/l07/codec.sh
```
The first run reports MISS: `snapshot omitted business/dedup/config/timer state`.
The codec check takes a few seconds and does not start a cluster.
<details><summary>Key — open after your repair passes or you give up</summary>

Key:
- After validation, copy the complete decoded result map into the live map, along with the business, config and timer fields; the codec check then prints HIT.
- Old results must survive so that a repeated old key returns its original cached count and total, instead of changing state again or reporting the current total.
</details>

### CARD L07-R1 · Recall and repair
Q: Without looking back, run the live snapshot check. First time: submit `labs/l07/work` as it is. When this card returns on a later review or exam, repair a fresh copy of the completion exercise instead; in driver mode, use the folder the driver names. Make that copy once per attempt, keep editing it if you resume, and do not rerun `prepare.sh` over your earlier work. Then answer: (a) list every field the snapshot must hold; (b) which observation shows that only the suffix was replayed? (c) which observation shows the receipt book survived?
```bash
printf 'labs/l07/work\n' | checks/chk_l07_snapshot.sh
```
Allow roughly 7 seconds as a planning estimate; runtime varies by machine. The check follows the five steps in the explanation above.
<details><summary>Key — open after the run</summary>

Key:
- (a) The check passes. The snapshot holds count, total, config, tick, the application timer's ID and deadline, the snapshot schema (magic and version), and every request key with its original result.
- (b) Snapshot recovery applies only the suffix IDs, 3 and 4, and on every replica the state and digest equal those at the barrier before the stop.
- (c) Retrying old key 1 returns its original count 1 and total 21 without changing state again; a new request then advances every replica.
</details>

### CARD L07-T1 · Use the rule
Q: For an imagined matching engine, plan recovery of the order book, pending cancels, request IDs and fee config. Answer in three parts: (a) what must the snapshot capture, and at what point? (b) what test would expose a forgotten receipt book? (c) your lab's snapshot check passes; how would you describe this plan's status for the engine, and why?
<details><summary>Key — open after answering</summary>

Key:
- (a) Capture the order book, the stable request keys with their results, the ID generators, the logged config and schema, and the details of pending work the application owns, all at one point in the log (the end of one prefix).
- (b) Restore, replay a known suffix, reconnect, resend a key from before the snapshot, and assert it gets its original result with no extra state change; then check that a new request still makes progress.
- (c) Call it proposed work for your own system, not a proven property: the course check covers only the course's counter service, so the engine still needs its own snapshot field list, schema-version rule and the old-key retry test from (b).
</details>

## Session B — Refuse the wrong recipe before cooking (about 25 min)

Start here after a break. Warm-up from memory: in lesson 06, what does a
positive local offer prove, and what does a reply prove?

### CARD L07-P2 · Before you learn
Q: (a) A cut-off snapshot decodes count fine, then fails halfway through the receipt book. Is it acceptable to keep the new count with the old receipts? (b) Does taking a snapshot delete the older log? One sentence each; guess if unsure.
<details><summary>Key — open after your guess</summary>

Key:
- (a) No: reject the snapshot without changing live state, and never make a partial restore live.
- (b) No: taking a snapshot and keeping or deleting old log are separate operations; this check keeps the existing recording segment files.
</details>

## Recall first — lesson 04's empty backups (1 minute)

From memory: in lesson 04's GitLab story, why did the regular backups hold
nothing? Answer before reading the next line.

The backup job ran `pg_dump` 9.2 against a PostgreSQL 9.6 database, the version
mismatch made it stop with an error, and the failure emails were rejected, so
nobody knew. That was the writer's side of a version mismatch. This session is
the reader's side: your `restore` meets bytes in a layout it does not support,
or bytes cut short. It must refuse them loudly and leave live state untouched.
The codec check below feeds `restore` bad bytes and demands exactly that.

### CONCEPT L07-CB · The idea: version
Prediction: A snapshot is cut off halfway through the receipt book. The decoder has already read count. (a) Which object should the decoder have been writing into: the live service or a temporary one? (b) After the failure, which live fields may have changed? Commit before opening.

<details><summary>Explanation — open after committing your prediction</summary>

Worked:
(a) A temporary one. (b) None. A failed restore must leave the service exactly
as it was. This course's `restore` does it in two phases (shortened):

```java
RequestWork next = new RequestWork();            // 1. decode into a scratch object
if (in.readInt() != 0x57503553 || in.readInt() != 1) throw ...;   // magic, version
next.count = in.readLong(); /* ...all fields, then N receipts... */
if (in.available() != 0 /* trailing bytes */ || /* bad field values */ ...) throw ...;
count = next.count; /* ...every field... */      // 2. only now touch live state
results.clear(); results.putAll(next.results);
```

The independent bug is one word: `next = this`. Now every field is written
straight into live state as it is read. A cut-off snapshot leaves a new count
with old receipts. Worse, even a *good* snapshot loses its receipts:
`results.clear()` empties the very map it is about to copy from.

The codec check throws bad bytes at `restore`: version changed to 2, the bytes
cut to 0, 7, 20 and one-less-than-full length, and one extra byte on the end.
Each must be refused, with state unchanged.

Two more boundaries:

- **Compatibility.** Your version number guards your layout. In the pinned jar, Aeron also stores
  the container's `appVersion` with each snapshot, and refuses to load one its
  validator calls incompatible (`incompatible app version`). The optional
  incompatible case shows this.
- **Retention.** Taking a snapshot keeps the older log. The check confirms every
  earlier recording file still exists. Deleting history is a separate decision.

Compare the no-snapshot path: it replays all of 10, 11, 12, 1, 2, 3, 4 and
reaches the same barrier-901 state. Snapshot + suffix must equal full replay.

| recovery path | work at restart | depends on | smell of trouble |
|---|---|---|---|
| snapshot + suffix | decode one snapshot, replay only the suffix | a complete, readable snapshot | restore "works", then an old retry runs again |
| full replay | replay the whole log | every log entry kept since the start | more entries must be replayed |

Plain advice: use snapshots to keep restarts short, and keep full replay as the
test that proves the snapshot is complete.

What this cannot show: the check stops processes cleanly. It never cuts power,
so it says nothing about bytes still in disk caches, or about how fast recovery
would be in production.

Common wrong belief: "load what you can." Good for inspection; never for the
running service.

Rule: decode into scratch, validate everything, then publish all at once.
</details>

Elaborate: why is "LOADED" insufficient even when every node prints it?

### CARD L07-C2 · Try it
Q: Run the first command (it overwrites both work files). In `RequestWork.restore`, repair the line marked `TODO(L07-C2)` and run the codec check until HIT. Then complete in writing: "decode into __; publish only after __."
```bash
bash checks/l07/prepare.sh independent
printf 'labs/l07/work\n' | checks/l07/codec.sh
```
The first run reports MISS: `snapshot omitted business/dedup/config/timer state`
(the good-snapshot failure described above).
<details><summary>Key — open after your repair passes or you give up</summary>

Key:
- Decode into a separate, temporary `RequestWork`; publish only after every part has been validated. The codec check then prints HIT.
- A snapshot with an unsupported version, cut-off bytes or extra trailing bytes must be rejected without changing the existing state.
</details>

### CARD L07-R2 · Recall and repair
Q: Without opening `labs/l07/solution`, run both recovery paths on your independent repair (one command runs the snapshot check, then the no-snapshot check). On a later review day, run `bash checks/l07/prepare.sh independent` first and repair from memory. Then answer: (a) what equation joins snapshot recovery to full replay? (b) what must the snapshot contain, and what must match after recovery? (c) name one durability conclusion this test cannot support.
```bash
printf 'labs/l07/work\n' | checks/l07/both-recovery-paths.sh
```
The command runs both recovery checks; when both pass, it prints the last check’s JSON result and one HIT line. The runtime varies by machine.
<details><summary>Key — open after the independent run</summary>

Key:
- (a) Both recovery checks pass. The equation: the state restored at the snapshot position, plus the suffix applied in order, equals a full replay, compared at the same application barrier.
- (b) The snapshot holds every application field and the cache of original results; after recovery, the suffix IDs and every replica's state and digest must match.
- (c) Any one of: a process restart does not test a real power failure, data still in disk-controller caches, or any guarantee of recovery speed in production.
</details>

### CARD L07-T2 · Use the rule
Q: An imagined reservation system adds a currency field, so its snapshot layout moves to version 2. An AI assistant wrote the restore below. Its test restores one old version-1 snapshot and checks that LOADED is printed. In staging, a retry after restart returned today's balance.
```java
void restore(DataInputStream in) throws IOException {
    int version = in.readInt();
    if (version != 1 && version != 2) throw new IOException("unsupported version");
    balance = in.readLong();                               // straight into live state
    currency = (version == 2) ? in.readUTF() : "USD";      // "old snapshots: assume USD"
    results.clear();                                       // "receipts get rebuilt by replay"
}
```
Answer in three parts: (a) is it right here, and what would make you reject it? (b) what retry test would you add? (c) what state comparison would you add?
<details><summary>Key — open after specifying tests</summary>

Key:
- (a) Reject it. The snapshot does not restore old receipts, and suffix replay cannot generally reconstruct them; inspect the retry handler to explain today’s-balance reply. Decode into temporary state so truncated input cannot partly overwrite live state. Accept the USD default only if the version-1 schema guarantees USD; otherwise require an explicit migration or reject the snapshot.
- (b) Save the original results and stable keys; after the suffix replays, resend a reservation from before the snapshot and assert it gets its original result with no extra state change.
- (c) Compare the full state at a named point in the log across all replicas and a no-snapshot replay; also test that new requests make progress, not only that the load message appears.
</details>

## Close

Remember: a snapshot holds everything the next command depends on, receipts
included; decode into scratch and publish only when all of it is valid;
snapshot + suffix must equal full replay. Your recall and transfer cards return tomorrow.
Next, lesson 08: carry these rules to a system of your own. Optional: the
15-minute stalled-snapshot case in `labs/l07/ops/README.md`.
