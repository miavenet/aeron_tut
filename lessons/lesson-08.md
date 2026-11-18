# Lesson 08 — Write down the guarantee

**"It's replicated, so it's safe." Safe against what, exactly?** A promise
without a named boundary is a rumour. This lesson moves everything you learned
onto a small invented system, where you check every byte and every promise by
hand and in Python.

By the end you can check a frame byte by byte, keep log identity and request
identity apart, and write a guarantee table for a system of your own.

Time: **about 50 minutes, in two sittings of about 24 and 26 minutes** (design
estimates). You need lessons 05–07. Python needed: reading short functions and
changing one line; the bridge below covers the rest.

Everything here is fictional: a counter service and a frame format invented for
this course.

## Session A — Preserve bytes and request identity (about 24 min)

### CARD L08-P1 · Before you learn
Q: (a) A local sender accepts a frame, but the only durable copy is behind. Can swapping in a different transport make a business acknowledgement recoverable? (b) A retried request arrives at a new log sequence number. Is it a duplicate log entry? One sentence each; guess if unsure.
<details><summary>Key — open after your guess</summary>

Key:
- Local transport acceptance alone does not prove a durable or replicated commit, so the answer to (a) is no: the acknowledgement must name its release gate, the condition that releases it.
- The answer to (b) is no: a repeated log sequence number is a log error, while a retry that got a new sequence number needs an application cache of requests to catch it.
</details>

## Python bridge (2 minutes)

- `state['total']` reads the dictionary entry named `total`.
- `(origin, request)` is a tuple: two values used together as one dictionary
  key. `(3, 1)` and `(4, 1)` are different keys.
- `results.get(key)` returns the stored value, or `None` if there is none.
- Indentation groups statements; `return` leaves the function.
- `struct.pack('<IHHII', ...)` writes bytes: `<` little-endian, `I` a 4-byte
  unsigned integer, `H` a 2-byte one. `zlib.crc32(data)` is a checksum.

The frame, invented for this course (all integers little-endian):

| bytes | field | size |
|---|---|---|
| 0–3 | sequence number (log order) | 4 |
| 4–5 | epoch (which writer's term) | 2 |
| 6–7 | kind: 1 = add, 2 = set version | 2 |
| 8–11 | origin (which client) | 4 |
| 12–15 | payload length, always 8 here | 4 |
| 16–19 | CRC32 of bytes 0–15 plus the payload | 4 |
| 20–23 | request ID | 4 |
| 24–27 | value | 4 |

### CONCEPT L08-C1 · The idea: adapter
Prediction: (a) An encoder swaps the epoch and kind fields, and its matching decoder swaps them back. Would a round trip (encode, then decode) catch the bug? (b) The tape below starts at version 1 and applies five frames. Kind 1 adds `value × current version` and adds one to the mutation count (the count of business changes). Kind 2 sets the version to 1 or 2 without changing the total or the mutation count. Every newly applied request also advances the expected log sequence and records its result. A retry of an existing (origin, request) key returns its original result without another business change. Predict the final total and the reply to frame 4. Commit both before opening.

```text
frame  seq  kind  origin  request  value
1      1    1     3       1        5
2      2    2     3       2        2      (set version to 2)
3      3    1     3       3        6
4      4    1     3       1        5      (a retry of frame 1)
5      5    1     4       1        7
```

<details><summary>Explanation — open after committing your prediction</summary>

Worked:
(a) No. Two codecs that share a bug agree with each other. The check compares
your bytes with an independent "golden" encoder instead. For frame 1 the right
bytes are:

```text
01000000 0700 0100 03000000 08000000 d58d42f8 01000000 05000000
seq=1    ep=7 k=1  origin=3 len=8    crc      req=1    value=5
```

The swapped encoder writes `0100 0700`, so the check reports "differs at byte 4".

(b) The arithmetic rule: **Kind 1 adds `value × current version` and adds one to the mutation count (the count of business changes). Kind 2 sets the version to 1 or 2 without changing the total or the mutation count. Every newly applied request also advances the expected log sequence and records its result.**

| frame | result | total | mutations | version |
|---|---|---|---|---|
| 1 | applied: +5 × 1 | 5 | 1 | 1 |
| 2 | applied: version := 2 | 5 | 1 | 2 |
| 3 | applied: +6 × 2 | 17 | 2 | 2 |
| 4 | cached: key (3, 1) seen; reply 5 | 17 | 2 | 2 |
| 5 | applied: +7 × 2, new key (4, 1) | 31 | 3 | 2 |

Total 5 + 12 + 14 = **31**. The retry gets its original reply, **5**, and the new
multiplier is not applied to it.

Two identities, two kinds of duplicate:

- **Log identity** = (epoch, sequence). The reader expects sequence 1, 2, 3…
  A repeat is `DUP`; a jump is `GAP`; a wrong epoch is `EPOCH`.
- **Application identity** = (origin, request). Frame 4 has a *new* sequence but
  an old key, so it is a legitimate retry: return the cached reply.

A bad length, checksum, epoch, sequence or input, or a known key with a different payload (`CONFLICT`), leaves application data unchanged and sets `halted` to `True`. Every later frame returns `HALTED` without further state changes.

Sorting the frames you have cannot invent a missing one. Recovery needs a
trusted ordered copy (a checkpoint plus the exact following frames), like
lessons 04 and 07, and then a switch to live frames with no gap between the two.
If two feed paths deliver the same frame, a layer in front of
the reader may drop a byte-identical second copy. That removes a transport
duplicate; it proves nothing about commit.

Common wrong belief: "the sequence number is the request ID." It names a log
slot, not a business action.

Rule: compare bytes with an independent source; key retries by (origin, request).
</details>

Elaborate: why is "my decoder reads what my encoder wrote" weak evidence?

### CARD L08-COMP1 · Try it
Q: Run the first command once (it replaces all three files in `labs/l08/work`; one previous copy of each goes to `labs/l08/work/backups/`). In `encode` in `labs/l08/work/adapter.py`, the line `prefix = struct.pack('<IHHII', sequence, kind, epoch, origin, len(payload))` has two fields swapped. Repair it and run the adapter check until HIT. Then answer in one sentence: why is equal decoded payload not enough?
```bash
bash checks/l08/prepare.sh completion
printf '%s\n' labs/l08/work | checks/l08/adapter.sh
```
The first run reports MISS: `independent golden frame differs at byte 4`,
followed by the expected and actual bytes in hex.
<details><summary>Key — open after your repair passes or you give up</summary>

Key:
- Swap the two back so the epoch is at byte 4 and kind at byte 6; the bytes then match the independent golden encoder and the check prints HIT.
- An equal decoded payload can hide a header or checksum error when the encoder and decoder share the same bug.
</details>

### CARD L08-R1 · Recall and repair
Q: Run the first command once (it replaces all three work files). In `apply`, the line `key = request` drops the origin. Repair it without opening the solution, then run the policy check until HIT. (On a later review day, prepare again and repair from memory.) Then answer: (a) why do origin 3/request 1 and origin 4/request 1 both execute, while a retry of origin 3/request 1 returns the old reply? (b) which bad frames halt the reader, and what happens to later frames? (c) what does a fresh replay of the tape produce?
```bash
bash checks/l08/prepare.sh independent
printf '%s\n' labs/l08/work | checks/l08/policy.sh
```
The first run reports MISS with `frame 5: expected APPLIED, actual CONFLICT`.
<details><summary>Key — open after your repair passes or you give up</summary>

Key:
- (a) The application key is the stable (origin, request) pair, independent of log sequence or transport session: (3, 1) and (4, 1) are different keys, so both run, and a retry of (3, 1) gets its cached reply. A different value under an existing key leaves application data unchanged and sets `halted=True`.
- (b) A repeated sequence, gap, wrong epoch, malformed frame or checksum error leaves all state unchanged except `halted=True`. Every later frame returns `HALTED` without further changes.
- (c) Fresh replay of the ordered tape reproduces total 31, three mutations, version 2, and the original cached reply of 5.
</details>

### CARD L08-T1 · Use the rule
Q: Two feed paths both deliver sequence 42. Then a client's retry arrives as sequence 43. An AI assistant proposes this layer in front of the strict reader, with the note: "This makes delivery exactly-once and proves 42 is committed, so the application cache of (origin, request) keys can be removed."
```python
def front(frame, seen):              # seen: dict of sequence -> frame bytes
    seq = int.from_bytes(frame[0:4], 'little')   # bytes 0-3: the sequence
    if seen.get(seq) == frame:
        return None                  # drop a byte-identical second copy
    seen[seq] = frame
    return frame                     # the strict reader still checks epoch, sequence and CRC
```
Answer in three parts: (a) is the code acceptable for the two copies of 42? (b) is the note right? Use the two identities and the retry at 43 in your answer. (c) if sequence 41 never arrived, why is sorting the frames you have not enough, and what would a trusted recovery path in your own project look like?
<details><summary>Key — open after answering</summary>

Key:
- (a) Yes, for these byte-identical copies. A different copy still reaches the strict reader. It halts at the first failed check: length, checksum or epoch may fail before sequence; an otherwise valid repeat returns `DUP`.
- (b) Reject the note. Log identity is (epoch, sequence) and catches repeated, skipped or wrong-epoch log entries; application identity is the stable (origin, request) pair and catches retried requests. The retry at 43 has a new sequence, so the front layer passes it and only the (origin, request) cache returns the old reply. Dropping a duplicate does not prove a quorum commit.
- (c) Sorting cannot create the missing 41. Start from a trusted checkpoint or log prefix, replay the exact frames after it in order, then verify that the switch to live frames leaves no gap.
</details>

## Session B — Put a boundary on the promise (about 26 min)

Start here after a break; do not prepare the workspace again. Warm-up from
memory: what does a snapshot need besides the business totals?

### CARD L08-P2 · Before you learn
Q: (a) Three processes run on one laptop. Does a two-of-three rule protect against losing the laptop? (b) 300 messages were accepted but only 299 latency samples exist. May a good-looking p99 pass? (c) A p99 from this course's check looks fine on your laptop: what would make it stop meaning anything, and how would you find out? One sentence each; guess if unsure.
<details><summary>Key — open after your guess</summary>

Key:
- (a) No: copies on one machine share a failure domain (they die together), so this local model cannot show that the system survives losing the machine.
- (b) No: the accepted, delivered and sampled counts must match before you read anything into a percentile.
- (c) It describes only the machine and workload you measured; the lesson names no other limit, so treat the rest as unknown. Re-measure on the target machine and workload, keep the raw samples, and check the counts match. This course's check is an exercise, not a latency benchmark.
</details>

## A true story — 43 seconds, then 24 hours (2 minutes)

On 21 October 2018, maintenance work cut the link between GitHub's US East
Coast network hub and its primary East Coast data center. The link was back in
43 seconds. GitHub's failover tool, Orchestrator, runs Raft among its own
nodes. During the cut, its nodes on the West Coast and in a public cloud "were
able to establish a quorum" and began failing database clusters over, so that
writes went to the West Coast.

But the East Coast databases held a brief period of writes that had not been
replicated to the West Coast. Once the West Coast took new writes too, each
side had writes the other lacked. In GitHub's words, "we were unable to fail
the primary back over" safely. Service was degraded for 24 hours and 11 minutes. GitHub
reported that no user data was lost, but a few seconds of writes needed manual
reconciliation.

The catch: a quorum agreed on *who should write next*. That is a different
promise from "a quorum has recorded every write the old writer already held."
In this lesson's terms, choosing the writer and the release gate for a write
are separate rules, and each needs its own row of evidence.

### CONCEPT L08-C2 · The idea: guarantees
Prediction: Three recorders confirm a frame at invented times: local copy at 1, the designated secondary at 5, a third copy at 9. (a) When does each rule release the reply: local, pair (local + designated secondary), quorum (local + any one other)? (b) Now the secondary never confirms. Which rule stalls? Commit before opening.

<details><summary>Explanation — open after committing your prediction</summary>

Worked:
(a) Local releases at **1**, pair at **5**, quorum at **5**. (b) Pair stalls
forever; quorum releases at **9** using the third copy.

The course's `release(mode, recorded, writer_epoch, active_epoch)` returns the
release time, or `None` for "never":

| recorded (local, secondary, third) | local | pair | quorum |
|---|---|---|---|
| 1, 5, 9 | 1 | 5 | 5 |
| 1, none, 9 | 1 | none | 9 |
| 1, none, none | 1 | none | none |
| 9, 1, 5 (local is late) | 9 | 9 | 9 |
| any, but writer epoch is stale | none | none | none |

Which rule to pick, in this model:

| rule | waits for | if one non-local copy goes silent | smell of the wrong pick |
|---|---|---|---|
| local | the local copy only | no wait for another copy | docs say "replicated", but replies leave before any other copy exists |
| pair | local + the designated secondary | stalls while that secondary stays silent | one slow box freezes every reply |
| quorum | local + any one other | can release when the remaining copy confirms | three copies, yet all on one laptop |

Plain advice: pick the rule from the failure you must survive, then write that
failure next to it in the table.

A **stale epoch** means the writer belongs to an older term. It must release
nothing, whatever the copies say: this fences an old writer that has not noticed
it was replaced. A **failure domain** is everything that dies together. Three
copies on one laptop are one failure domain. "Recorded" also needs a stated
sync policy before anyone says "survives power loss."

**Measurements have a boundary too.** The performance check first requires
accepted = delivered = number of samples. A missing sample might be the slow
one, and an invented one is not a measurement.

Then it reports count, the ordinary median (the average of the two middle values
when the count is even) and p99 = sorted sample number ⌈0.99 × n⌉, counting from
one, plus the maximum. For samples 4, 8, 6, 10, 5: sorted 4, 5, 6, 8, 10, so
median 6, p99 sample 5 (= 10), maximum 10.

**Slow consumers.** Suppose a journal is required but a dashboard is optional.
If the journal falls behind, slow down admission or keep the data; silently
dropping it breaks the guarantee. The dashboard may disconnect and catch up by
replay, if a gap in it is acceptable.

**Serial work stays serial.** Eight dependent operations costing five units each
need 40 units in order, whatever the transport. Batching may cut per-message
overhead, not those 40 units. Separate books may run on separate shards, but an
operation that spans books then needs an explicit ordering rule.

Last, the guarantee table. For each step, `admit → record/replicate → chosen
gate → apply → reply`, say whether it is existing code, a course exercise or a
proposal; where replay reads from; what failure it survives; what was tested.
`labs/l08/work/migration.json` holds six such rows.

Common wrong belief: "replicated means durable." Only a named release gate, a
fence against old writers, a sync policy and a failure test support that claim.

Rule: every promise names its gate, its failure domain and its evidence.
</details>

Elaborate: which row of your own system's table would you be least able to fill with evidence today?

### CARD L08-COMP2 · Try it
Q: In `labs/l08/work/migration.json`, the `promotion` row has `"commit": "TODO"`; the other five rows are worked examples for this fictional system. Write the proposed gate as one sentence naming the required recordings and the current writer epoch, then run the combined check. For your own system, what evidence would show that this gate and the fence against an old writer actually work?
```bash
printf '%s\n' labs/l08/work | checks/l08/migration-and-model.sh
```
The first run reports MISS: `promotion needs a concrete commit`. The check runs
the migration check, then the release-model check, and prints the last HIT.
<details><summary>Key — open after your attempt</summary>

Key:
- Each of the six rows states its current status, acceptance, commit, acknowledgement, replay source, failure domain and a concrete test.
- Before claiming durability for a proposed gate, you need an explicit recording or replication condition, a fence against old writers, a storage policy and a failure test.
- The structural JSON check does not judge the prose; compare each row with your own system's evidence honestly.
</details>

### CARD L08-R2 · Recall and repair
Q: With the key closed: (a) state the local, pair and quorum release conditions, including a stale epoch. (b) `labs/l08/work/performance.json` holds five samples with accepted 5 and delivered 5. Predict count, median, p99 and maximum, then run the check. Change only `delivered` to 4, predict and run again, then restore 5. Why would inventing a replacement sample not repair the evidence? (c) what does this exercise not prove?
```bash
printf '%s\n' labs/l08/work | checks/l08/performance.sh
```
With delivered = 4 the check reports MISS: `sample accounting`.
<details><summary>Key — open after your attempt</summary>

Key:
- (a) Local releases once the local copy is recorded; pair also waits for the designated secondary; quorum needs local plus either of the other two copies; a stale epoch blocks every rule.
- (b) The result is count 5, median 6, p99 10 and maximum 10. Keep the raw samples; accepted, delivered and sampled counts must match, so delivered=4 fails. For an even count, median averages the middle pair; nearest-rank p99 selects sorted sample ⌈0.99n⌉. Inventing a sample proves neither delivery nor latency.
- (c) This exercise with numbers is not a latency benchmark, and it is no proof of safety under power loss, network partitions or elections.
</details>

### CARD L08-T2 · Use the rule
Q: An imagined matching engine is slow, and a recovering consumer needs its history. Run the full check on your workspace. (On a delayed review or exam the driver's fresh copy has everything repaired except the `promotion` row's `"commit": "TODO"` in `migration.json`; write that one commit sentence first.) Then answer in four parts: (a) where does recovery read from? (b) under back pressure, how do a required consumer and an optional one differ? (c) why can neither a transport swap nor sharding remove serial application work? (d) which claims must you keep separate?
```bash
printf '%s\n' labs/l08/work | checks/chk_l08_sequencer.sh
```
<details><summary>Key — open after your attempt</summary>

Key:
- (a) The byte, policy, model, migration and accounting checks all pass. Recovery reads from a trusted replay source that you propose, and you test that the switch to live frames leaves no gap.
- (b) A required consumer, such as a journal or state consumer, needs a bounded policy that keeps the required data; an optional one may disconnect and catch up by replay, with the loss of availability stated as an explicit tradeoff.
- (c) Eight serial inputs costing five units each still need forty units of work. Batching may reduce per-message overhead; sharding moves the ordering boundary, so work across shards needs a cross-shard ordering rule.
- (d) Keep claims about existing code, the course fixture and your proposals separate. State the commit, acknowledgement, sync policy and failure domain, and do not conclude from this exercise that failover is durable or that wire delivery is exactly once.
</details>

## Return ticket

Remember: check bytes against an independent source; log identity is not
request identity; every promise names its gate, failure domain and evidence.
Your recall and transfer cards return tomorrow. The mixed course exam opens at least seven
days after this lesson. Optional: the 15-minute repair cases in `labs/l08/ops/README.md`.
