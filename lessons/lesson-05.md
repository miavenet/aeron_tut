# Lesson 05 — Three replicas walk into a clock

**Three cooks get the same recipe card. Why do three different soups come out?**
Because each cook also peeks into their own fridge. Aeron Cluster runs your
service on three machines from one shared list of commands. Any "fridge" (a
local clock, a local config file, a hash map's private order) splits them apart.

By the end you can repair a clustered service so all three copies, and a fresh
replay, reach exactly the same state.

Time: **about 48 minutes, in two sittings of about 24 minutes**. These are design
estimates, not measured times. You need: lesson 04 (recording and replay) and the
lesson 01 Java primer.

## Session A — Make every replica read the same recipe (about 24 min)

### CARD L05-P1 · Before you learn
Q: Three copies of one service (replicas) receive identical input bytes in the same order. One copy also reads its own local config file and its own clock. Must all three end with identical state? Answer yes or no in one sentence, then say what a replay (re-running the stored inputs from the start) would need to reproduce the state. This is a guess: you have not been taught this yet.
<details><summary>Key — open after your guess</summary>

Key:
- No: identical input bytes do not control the extra local inputs (the config file and the clock), so if those affect state, the copies can differ.
- A replay needs the same starting state, the same inputs in the same order (including any configuration or time that changes state), and deterministic processing: the same inputs always give the same result.
</details>

## Primer — what Aeron Cluster adds (3 minutes)

Lessons 01–04 moved bytes (transport) and kept them (Archive). Neither keeps a
*service* alive when its machine dies. Aeron Cluster does: it runs the same
service on several machines (**nodes**, usually three) so that, after applying the same
stretch of the log from its start (the same **log prefix**; the log comes next), they hold the same state.
It can keep committing requests (committing is defined below) while a majority of nodes work, can
reach each other, and can elect a leader or keep the current one.

The trick is one **replicated log**: the ordered list of every input. Each node
keeps a copy, recorded by its own Archive.

```text
client ──ingress──▶ LEADER node                    FOLLOWER nodes (two more)
                    consensus module ──log copy──▶ consensus module
                      append to log (Archive)        append to log (Archive)
                      commit position: the agreed application limit   (same commit position)
                            ▼                               ▼
                    clustered service (your code)  clustered service (same code)
client ◀──egress──  reply offer attempted          reply NOT sent; offer returns 1
```

- **Leader and followers.** One node is the leader. Clients send it requests;
  it puts them in the log and copies the log to the **followers**.
- **Consensus module.** Aeron's part on each node. It elects the leader, appends
  and replicates the log, and hands log messages to your service.
- **Clustered service.** Your code: a class implementing `ClusteredService`.
  Each input arrives in `onSessionMessage(session, timestamp, buffer, ...)`.
- **Ingress and egress.** Ingress is client → cluster. Egress is the reply,
  offered with `session.offer(...)`. Every replica runs the reply code; only the
  leader can publish it. On a follower `offer` returns 1 (`ClientSession.MOCKED_OFFER`).
- **Appended versus committed.** Appended means stored in this node’s log; it may still be uncommitted. Committed means consensus has advanced the commit position past the entry, permitting the service to apply it. Majority replication is part of that decision; counting copies alone is not the complete rule.
- **Determinism.** Copying and crash recovery are one mechanism: same starting
  state + same ordered log → same state. So the service may depend on nothing
  outside the log.
- **Snapshots** (lesson 07) save state at a log position, so recovery replays
  only the rest.

## A true story — seven servers out of eight (2 minutes)

On 1 August 2012, Knight Capital's order router, SMARS, ran on eight servers.
New code had been copied to them in stages over the previous days, but one
technician did not copy it to the eighth. That server still held old, unused
code called Power Peg. The new code reused a flag that used to switch Power
Peg on.

Orders carrying that flag arrived. The seven updated servers handled them
correctly. The eighth ran Power Peg, which kept sending child orders without
regard to how many shares had already been filled. In about 45 minutes, 212
small customer orders became over 4 million executions. The US Securities and
Exchange Commission's order puts Knight's loss at $460 million.

This was a deployment failure: one server ran different code and handled flagged orders differently. The lesson for our replicated service is that identical logged inputs are not enough; every replica must also apply compatible deterministic code.

Your turn: name one input or code difference that could make your service’s replicas disagree.

### CONCEPT L05-CI · The idea: inputs
Prediction: The course tape includes: set config to 2, add 5, set config to 3, add 4. Each add adds `value × config`. (a) Predict count and total when every replica uses the logged config. (b) Predict replica 1's total if it ignores the logged config and uses its local config value, 4, instead. Commit both numbers before opening the explanation.

<details><summary>Explanation — open after committing your prediction</summary>

Worked:
(a) Count 2, total 5×2 + 4×3 = **22**. (b) 5×4 + 4×4 = **36**. In this course's
fixture the local config is 3 + node number + restart number, so the three
members get totals 27, 36 and 45. Three cooks, three soups.

A **logged input** is anything that reached the service through the log: the
command bytes, a config change sent as a command, and the `timestamp` argument.
The leader reads its cluster clock once and writes that number into the log entry.
Any replica or replay that applies the same prefix from the same starting state
therefore calculates the same number. A **local input**
is anything else: `System.currentTimeMillis()`, a file on this machine.

The repair is one choice per helper in `StateWork.java`:

```java
// apply(...) calls the two helpers like this:
lastTime = eventTime(timestamp, localClock);       // logged vs local
if (op == 1) { count++; total += value * multiplier(config, localConfig); }
// eventTime and multiplier must return their LOGGED argument.
```

Quick choice before you read on: a replica stamps a trade with `Instant.now()`.
Logged or local? Local: it reads this machine's clock. Use `timestamp`.

How the check compares replicas: the tape ends with command 9, ID 900, a
**barrier**. It changes nothing; each replica prints its full state, plus a
SHA-256 digest of it, at that exact point in the log. Equal membership only
tells you which nodes belong; the barrier proves they applied the same prefix.

Then the check stops all three, keeps their logs, and restarts them with empty
state: Cluster **replays** the whole log, and barrier 900 must match again. On
restart the fixture's local values change on purpose, so a hidden local input
shows up.

Time and timers follow the same rule. In the pinned jar, the default cluster clock reads the
leader's wall clock in milliseconds (`MillisecondClusterClock` calls
`System.currentTimeMillis`; `aeron.cluster.clock` can name another clock). Nothing forces it to increase if a new
leader's clock is behind, so use the logged value as it is. A timer set with
`cluster.scheduleTimer(id, deadline)` expires as a log entry, which every replica
receives through `onTimerEvent(id, timestamp)`.

This was a deliberate choice. In a 2019 QCon talk on implementing Aeron Cluster,
Todd Montgomery, who built Aeron with Martin Thompson, described adding
timestamps to the log's events so that time lives in the log, and timers too.
His summary: "Time doesn't progress unless the log progresses."

| you need | unsafe local input | safe replacement |
|---|---|---|
| the time | `System.currentTimeMillis()` | the logged `timestamp` argument |
| a setting | a config file on this machine | a config command in the log |
| "later" | a thread on this machine that wakes up | `cluster.scheduleTimer`, then `onTimerEvent` |

Plain advice: if code in a log callback reads anything in the middle column,
treat it as a bug until someone shows why it cannot change state.

Common wrong belief: "same tape, so same state." The order was shared; the
fridge was not. And this check never kills a leader while clients wait: that is
lesson 06.

Rule: state may change only from what came through the log.
</details>

Elaborate: why is restarting with the same config file weaker evidence than replaying with deliberately different local config?

### CARD L05-C1 · Try it
Q: Repair `eventTime` and `multiplier` in `labs/l05/work/StateWork.java` (each is marked `TODO(L05-C1)`). Run the first command once (it overwrites the work file), predict which field the check will name, run the second command, edit, and rerun until HIT. Then answer in one sentence: why can local config not affect these results?
```bash
bash checks/l05/prepare.sh completion
printf 'labs/l05/work\n' | checks/chk_l05_cluster.sh
```
A first run reports MISS and names the first wrong transition, for example
`state transition id=10 node=0: state expected=0,0,2,0,<logged time>,17
actual=0,0,2,0,100000,17`. The six numbers are count, total, config, tick, last
time and order digest. 100000 is node 0's fake local clock. Runtime varies by machine; allow roughly 5 seconds as a planning estimate.
<details><summary>Key — open after your repair passes or you give up</summary>

Key:
- Make `eventTime` return the logged timestamp and `multiplier` return the logged config; the check then prints HIT.
- Local config cannot affect the results because the service now uses only values that arrived through the ordered log; node-local values can differ between replicas and between a run and its replay.
</details>

### CARD L05-R1 · Recall and repair
Q: Without looking back, run the full check on your repaired workspace. First time: submit `labs/l05/work` as it is. When this card returns on a later review or exam, repair a fresh copy of the completion exercise instead; in driver mode, use the folder the driver names. Make that copy once per attempt, keep editing it if you resume, and do not rerun `prepare.sh` over your earlier work. Then answer: (a) state the three conditions for replicas to agree; (b) name two ways to break them; (c) why must a replay check compare state at the same barrier, not just which members are up?
```bash
printf 'labs/l05/work\n' | checks/chk_l05_cluster.sh
```
<details><summary>Key — open after answering</summary>

Key:
- Your workspace passes the live cluster and replay check.
- (a) The same starting state, the same state-changing inputs in the same order, and deterministic processing.
- (b) Any two of: reading config that is not in the log, reading the local clock, or walking a collection in an order that differs between replicas when that order changes the output.
- (c) The barrier is one shared point in the log, so states compared there come from the same processed prefix; which members are up, or how far each has appended, does not show equal applied state.
</details>

### CARD L05-T1 · Use the rule
Q: In an imagined matching service, prices depend on a fee table and on time. Answer in three short parts: (a) how should a fee-table update and a time tick reach the matching logic? (b) what would you compare after a fresh replay? (c) what would a replay test still not prove?
<details><summary>Key — open after answering</summary>

Key:
- (a) Send the fee-table update and the time tick through the log as inputs, ordered before the orders that depend on them; do not read a fee file that can change outside the log during matching.
- (b) Replay the same logged inputs, with their timestamps, into fresh state, and compare the output bytes and the state digest at an identified point in the log, such as a barrier; vary the external config on purpose.
- (c) A fresh replay alone does not show recovery after the leader fails, or how requests whose replies were lost get reconciled; test those failure paths separately. This is a proposal for your own service; the course fixture does not prove how that service behaves.
</details>

## Session B — A fence, a bad record, and a fake success (about 24 min)

Start here after a break. Warm-up from memory: in lesson 04, what is the difference between "recorded"
and "applied by the application"?

### CARD L05-P2 · Before you learn
Q: (a) A member has appended two records, but only the first is committed. How many may its service apply? (b) On a follower, `session.offer` returns 1. Does that prove a reply went over the network? One sentence each; guess if unsure.
<details><summary>Key — open after your guess</summary>

Key:
- (a) Only the committed prefix, here the first record: the service polls the log only up to the commit-position counter.
- (b) No: a follower can return a mocked success without actually sending the reply over the network.
</details>

### CONCEPT L05-CB · The idea: boundary
Prediction: After "add 5" the state has count 1, total 10. Next comes "add −7", which is invalid (adds must be 0 to 1000). (a) What should count be after the service rejects it? (b) A batch command folds two values into an order digest, `order = order × 31 + v`, starting from 17. Do the orders 2-then-9 and 9-then-2 give the same digest? Commit before opening.

<details><summary>Explanation — open after committing your prediction</summary>

Worked:
Four boundaries, each tested by the fixture.

**1. Validate before you change anything.** (a) Count stays 1. Check the input
first, then mutate:

```java
if (!valid(op, id, value, aux, length)) return false;   // reject: nothing changed
if (op == 1) { count++; total += value * multiplier(config, localConfig); }
```

A version that bumps `count` and *then* rejects is wrong on all three replicas
in the same way, so they still agree. Agreement is not correctness. The check
therefore recomputes the expected state after every command itself.

**2. Fix the traversal order.** (b) No. 2-then-9: 17 → 529 → 16408. 9-then-2:
17 → 536 → 16618. If one replica walks a hash map in a different order, the
digests split. The fixture makes odd-numbered members walk backwards on
purpose. Sort the values, or use a collection with a defined order.

**3. Read only up to commit.** The service may apply only committed records:

```text
log:   [101][202]
          ▲     ▲
     commit    append
limit = commit → applies 101 only      (correct)
limit = append → applies 101 and 202   (202 is beyond the supplied commit limit)
```

`commitLimit(committed, appended)` returns the poll limit. The check appends two
records, polls with the first position as the limit, and expects only 101.

Three words that sound alike and are not:

| word | means | may the service act on it? |
|---|---|---|
| appended | stored in this node’s log | only if also committed |
| committed | included in the consensus commit position | yes |
| applied | the service has processed it | its effect is now in state |

Plain advice: when someone says "it's in the log", ask which of the three they
mean.

**4. A positive egress result is not a delivery receipt.** `session.offer`
returns a negative code on failure. On the leader, a positive stream position
means the reply was handed to the egress stream; it can still be lost later
(lesson 06). In the pinned jar, on a follower it is always 1 and nothing is sent. So test
`result > 0`, and never store the returned number in state: the leader can get
a publication position or a negative code; a follower gets mocked success.

Common wrong belief: "equal counts on every node means equal state." Totals,
config and order digest can still differ.

Rule: reject before mutating, define every order, read to commit, branch on
`> 0`.
</details>

Elaborate: why can equal membership and equal counts still hide unequal state?

### CARD L05-C2 · Try it
Q: Prepare the independent workspace (this overwrites `labs/l05/work/StateWork.java`). Repair only `commitLimit` and `egressSucceeded`, both marked `TODO(L05-C2)`, and run the combined check until HIT. Then complete in writing: "appended says __; commit permits __; a positive egress result means __."
```bash
bash checks/l05/prepare.sh independent
printf 'labs/l05/work\n' | checks/l05/boundary-and-policy.sh
```
The first run reports MISS: `commit fence: first poll must exclude appended
second record`. The command runs both checks; when both pass, it prints the last check’s JSON result and one HIT line. The full
suite still fails after this card: two more faults remain for the next recall card.
<details><summary>Key — open after your repair passes or you give up</summary>

Key:
- Make `commitLimit` return the committed position as the poll limit, and make `egressSucceeded` test `result > 0`; both focused checks then pass.
- Appended says how far this node has written the log; commit permits the service to read up to that position and no further; a positive egress result means the leader handed the reply to the egress stream, or a follower returned its mocked 1, and it does not prove delivery.
</details>

### CARD L05-R2 · Recall and repair
Q: Without opening `labs/l05/solution`, repair the two remaining faults in the independent workspace. They are not marked; the check names the first failing criterion. Work in loops: guess → read the first failing message → one edit → rerun. (On a later review day, run `bash checks/l05/prepare.sh independent` first and redo all four repairs from memory.) Then answer: (a) why must invalid input leave all state unchanged? (b) why does an order-sensitive digest need a defined traversal?
```bash
printf 'labs/l05/work\n' | checks/chk_l05_cluster.sh
```
The check compiles your file, runs the focused probes, starts a three-member
cluster, checks every transition and barrier 900, restarts for a fresh replay,
then sends one more command and barrier 901. Logs stay under the `artifacts`
path it prints.
<details><summary>Key — open after your independent attempt</summary>

Key:
- Your work passes the complete live and replay check and both focused policy checks.
- (a) Validate before changing anything, so the bad record leaves state unchanged; replicas that all agree on a wrong count are still wrong.
- (b) The digest changes with the order of values, so replicas walking in different orders split; fix one order, for example by sorting. The digest compared at the shared barrier covers more than the count alone.
</details>

### CARD L05-T2 · Use the rule
Q: An imagined auction service runs on Aeron Cluster. During replay it sees a timestamp lower than the previous one, so you ask an AI assistant to fix it. It proposes this patch inside `onSessionMessage`, and its test checks only the final count:
```java
long now = System.currentTimeMillis();      // "logged time went backwards; use a real clock"
if (now > lastTime) lastTime = now;
long result = session.offer(replyBuffer, 0, replyLength);
tradeId = tradeId * 31 + result;            // "mix in the reply result so IDs stay unique"
```
Answer in three parts: (a) accept or reject the patch, and why? (b) what check would you write to settle it? (c) what would that check still not prove?
<details><summary>Key — open after diagnosing</summary>

Key:
- (a) Reject it. Local time can differ across replicas and replay. On the leader, `session.offer` can return a publication position or a negative code; on a follower it returns mocked success. Neither result belongs in business state. Use the logged timestamp and derive trade IDs from logged inputs or replicated counters.
- (b) At a shared barrier, on every replica and on a replay, compare the exact input IDs in order and the full state and digest built in a defined order; the count alone misses changed totals, config or order.
- (c) A follower's success can be mocked, and this lesson does not guarantee exactly-once delivery (each message delivered once and only once). Client retries and removing duplicate requests need application logic beyond this counter fixture.
</details>

## Close

Remember: state changes only from the log; reject before mutating and fix
every order; a positive egress result is not a delivery receipt. Your recall and transfer cards return tomorrow. Next, lesson 06: the reply goes missing and the leader
dies. Optional: the 15-minute clock case in `labs/l05/ops/README.md`.
