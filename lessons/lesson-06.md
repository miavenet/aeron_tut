# Lesson 06 — The receipt went missing

**You tap your card. The screen freezes. You tap again. Did you just buy two
coffees?** A cluster client lives this moment every time a reply goes missing:
it does not know whether its request ran. Sending again is necessary; running
it twice is a bug.

By the end you can make a client survive a lost reply and a killed leader, while
preventing duplicate mutations for the same valid request key.

Time: **about 50 minutes, in two sittings of about 25 minutes** (design
estimates, not measured times). You need lesson 05: logged inputs, commit, and
the application barrier.

## Session A — Keep the receipt book (about 25 min)

### CARD L06-P1 · Before you learn
Q: A client's `AeronCluster.offer` returns a positive number, then no reply ever comes. (a) From the client's point of view, is the request committed, rejected, or unknown? (b) When the client reconnects and sends it again, should it give the request a new ID? One sentence each; guess if unsure.
<details><summary>Key — open after your guess</summary>

Key:
- (a) Unknown: the local publication accepted the request, but that alone does not tell you the application's result; keep tracking it until the reply comes.
- (b) No: keep the same client and request key across the reconnect and the resend, because a new key could stand for a second business action.
</details>

## Words for this lesson

- **Client session**: one connection made by `AeronCluster.connect`. It has its
  own `clusterSessionId`; a reconnect creates a new session with a new ID.
- **Request key**: the ID *your application* gives a business action. Here it is
  client ID + request ID, written `"41:2"`. It never changes on resend.
- **Pending**: sent, but no reply yet. Unknown, not failed.
- **Cached result**: the reply the service computed the first time it ran a key.
- **De-duplication**: when a key arrives again, return its cached result
  instead of running it again.

## Why the receipt book exists (2 minutes)

This hole is not an Aeron quirk. Aeron Cluster is based on Raft, and Raft's
own authors, Diego Ongaro and John Ousterhout, describe it in their paper. As
first described, they write, Raft "can execute a command multiple times": if
the leader crashes after committing a command but before replying, the client
retries with the new leader, and the command runs a second time.

Their fix is the one you are about to build. Clients give every command a
unique serial number. The state machine remembers, for each client, the latest
serial number it ran and the response it sent. A command it has already run
gets that response back "without re-executing the request."

One difference: the paper keeps only the latest number per client. This
course's service keeps a result for every key, so any old key, not only the
latest, gets its own receipt back.

Before you open the next explanation, guess: what does the retry get back,
the old answer or today's?

### CONCEPT L06-CA · The idea: retry
Prediction: Config is 3, so each add adds `value × 3`. Client 41 sends request 1 (add 7), then request 2 (add 2). Then request 1 arrives again, because the client never saw its reply. Predict (a) the final count and total, and (b) the reply the retry gets. Commit before opening.

<details><summary>Explanation — open after committing your prediction</summary>

Worked:
(a) Count 2, total 27. (b) The retry gets **(count 1, total 21)**: its
original answer, not today's (2, 27). The receipt book returns the old receipt;
it does not sell you a second coffee.

| step | key | effect | count | total | receipt book |
|---|---|---|---|---|---|
| 1 | 41:1 add 7 | runs | 1 | 21 | 41:1 → (1, 21) |
| 2 | 41:2 add 2 | runs | 2 | 27 | + 41:2 → (2, 27) |
| 3 | 41:1 again | cache hit | 2 | 27 | unchanged, reply (1, 21) |
| 4 | 42:1 add 5 | new client, new key | 3 | 42 | + 42:1 → (3, 42) |

The service side (`RequestWork.apply`) looks up the key *before* changing anything:

```java
String key = key(client, id);                 // "41:1"
Result cached = results.get(key);
if (cached != null) {
    if (cached.op() != op || cached.value() != value || cached.aux() != aux)
        throw new IllegalArgumentException("request key reused with different payload");
    return cached;                            // old receipt, no mutation
}
if (op == 1) { count++; total += value * config; }
```

The receipt book `results` is part of replicated service state. Replicas that start from the same state and apply the same logged prefix deterministically hold the same receipt book. A key reused with a different payload is rejected without changing that state.

Client side (`ClientWork`): `submit` puts the ID in `pending`. `offered` must
change nothing: a positive `offer` means the request was accepted into the client’s local publication log. It may not yet have been sent, received, committed or applied.
`reply` stores the result and removes the ID from `pending`. An identical second
reply is fine; a different one is an error.

While waiting, the client keeps calling `pollEgress()` (replies arrive only
inside it) and `sendKeepAlive()`. It resends a pending ID now and then. If
`offer` returns `CLOSED` (−4) or `MAX_POSITION_EXCEEDED` (−5), that connection
is finished: reconnect, and keep the same keys.

Three ways a client can treat a missing reply:

| client strategy | what you get | cost | smell that it was the wrong pick |
|---|---|---|---|
| never resend (at most once) | the request may or may not have run, and you never learn which | cheapest | "unknown" quietly logged as "failed" |
| retry without deduplication | a request may execute more than once; eventual execution requires continued retries and an available service | repeated execution must be acceptable | duplicate business effects |
| retry the same key with durable service receipts | at most one mutation per valid key; a processed retry gets the original result | receipt state must survive recovery | forgotten receipts let old requests run again |

For this lab’s business requests, keep the stable key and receipt book to prevent duplicate mutation. Separately test eventual completion after service progress resumes.

Common wrong belief: “positive offer means done.” It means “accepted into the local publication log.”

The receipt book protects state inside the cluster. An outside side effect
(an email, a payment) needs its own duplicate check; this lab does not test one.

Rule: one stable key per business action; look it up before mutating.
</details>

Elaborate: why must a late retry return the original result rather than today's total?

### CARD L06-C1 · Try it
Q: Run the first command once (it overwrites both files in `labs/l06/work`). In `RequestWork.java`, replace the line marked `TODO(L06-C1)` so the method looks up the key. Run the policy check until HIT. Then answer in one sentence: why must the lookup come before any change, and what must the key contain besides the request number?
```bash
bash checks/l06/prepare.sh completion
printf 'labs/l06/work\n' | checks/l06/policy.sh
```
The first run reports MISS: `return ORIGINAL cached result`. The policy check
takes a few seconds and does not start a cluster.
<details><summary>Key — open after your repair passes or you give up</summary>

Key:
- Look up the stable client-and-request key, check that a repeated key carries the same payload, and return its original result before changing anything; the policy check then prints HIT.
- The lookup comes first so a retry cannot become a second state change; the key must also contain the client ID, because different clients may reuse the same request number.
</details>

### CARD L06-R1 · Recall and repair
Q: Without looking back, run the live failover check. First time: submit `labs/l06/work` as it is. When this card returns on a later review or exam, repair a fresh copy of the completion exercise instead; in driver mode, use the folder the driver names. Make that copy once per attempt, keep editing it if you resume, and do not rerun `prepare.sh` over your earlier work. Then answer: (a) when may an ID leave `pending`, and why must a reconnect keep it? (b) what must the client keep doing while it waits?
```bash
printf 'labs/l06/work\n' | checks/chk_l06_failover.sh
```
What the check does (allow roughly 30 seconds; runtime varies): client 41 sends requests 1–5 (each adds
its own number) and barrier 900. The first reply to request 2 is thrown away.
Once every replica has applied request 2, the check asks the cluster who leads
and kills that process.

The client must reconnect, retry, and end with 6 replies and nothing pending.
The reply for request 2 must be its original (2, 3). At barrier 900 all three
replicas must show count 5, total 15. Then comes the no-quorum part from
Session B.
<details><summary>Key — open after the run</summary>

Key:
- Your work passes the live check: each unique client-and-request key changes state once, and every submitted request is acknowledged at the application level.
- (a) A successful offer leaves the ID in `pending`; it leaves only when a consistent reply settles it. A reconnect keeps the same requests and keys, because their results are still unknown.
- (b) Keep calling `pollEgress()` and `sendKeepAlive()`; resends are bounded, and a terminal offer result (`CLOSED` or `MAX_POSITION_EXCEEDED`) triggers a reconnect.
</details>

### CARD L06-T1 · Use the rule
Q: An imagined order gateway sends orders to a cluster. An acknowledgement vanishes, then the gateway restarts. Answer in three parts: (a) what must the gateway keep, and what does it do after restart? (b) A separate sequencer acknowledges an order before its replacement has recorded it. The writer then dies. Does this lesson’s client-retry test prove that the replacement can recover that order or prevent the old writer from publishing after restart? (c) what about a consumer that sends emails?
<details><summary>Key — open after answering</summary>

Key:
- (a) Store durably each order's stable request key and every submission still unresolved. After restart, match incoming results to those keys, resend the same keys, and keep the de-duplication and result state inside the replicated application.
- (b) No. Test that sequencer separately: its replacement must recover every request covered by its acknowledgement promise, and a restarted old writer must be fenced from publishing. Client deduplication establishes neither property.
- (c) A consumer with outside side effects, like emails, needs its own rules for reconciling and for idempotency (a repeated request takes effect only once); this lab proves state changes inside the application, not exactly-once delivery to outside systems.
</details>

## Session B — One lonely leader cannot sign for the committee (about 25 min)

Start here after a break. Warm-up from memory: in lesson 05, what does a
barrier prove that "all members are up" does not?

### CARD L06-P2 · Before you learn
Q: All earlier work has finished (barrier 900 ran everywhere). Then two of the three members stop. (a) If the client's `offer` for a new barrier returns positive, does that prove the barrier ran? (b) An old log line says node 1 is "LEADER". Is that enough to pick the node to act on now? One sentence each; guess if unsure.
<details><summary>Key — open after your guess</summary>

Key:
- (a) No: the new barrier counts only when you see the application run it; in this live test it stalls without quorum (a majority of members, defined next) and runs after quorum returns.
- (b) No: ask the cluster now which members exist and which one leads; a role recorded earlier can be out of date after a failure or restart.
</details>

## More words

- **Quorum**: a majority, `floor(n / 2) + 1`; for three members, two. Without a functioning quorum, new requests cannot commit. Previously committed entries may still be applied. This lesson drains earlier work before removing quorum so the new barrier cannot be confused with that backlog.
- **Election**: the members choosing a new leader after the old one fails.
- **Drained**: all earlier work has visibly finished (its barrier ran everywhere).
- **Watchdog**: the check's own time limit on a wait.

## Why a majority (2 minutes)

Todd Montgomery, who built Aeron with Martin Thompson, laid out the dilemma in
a 2019 QCon talk on implementing Aeron Cluster. Act on an event as soon as it
arrives, and if you then fail, "you may have been the only one to act on that
event." Wait for every replica instead, and: "what happens if you wait for
everyone and you never get everyone?" His rule: record the event on a majority
before any replica consumes it.

The Raft paper adds the reason for majority votes in elections: each server
votes for at most one candidate per term, so the majority rule means at most
one candidate can win the election for a given term.

So with three members, losing one is fine. Losing two leaves one member, which
is not a majority. Predict what that lonely member can still do for a new
request, then read on.

### CONCEPT L06-CB · The idea: quorum
Prediction: Barrier 900 ran on all three. The check stops two members, so one remains. The client offers barrier 901 and gets a positive result, but no replica reports BARRIER 901. (a) Will 901 ever run as things stand? (b) What would you do next, and what would prove recovery? Commit before opening.

<details><summary>Explanation — open after committing your prediction</summary>

Worked:
(a) No. One member out of three is not a majority, so 901 cannot commit. The
leader's own log says `inactive follower quorum`. The positive offer only showed that the client’s local publication log accepted the barrier; it showed neither commitment nor application. (b) Restart the stopped members from their kept directories.
Then 901 commits and runs, and all three show the same state.

The whole live check, as a timeline:

```text
1. three members up; client 41 sends 1..5 + barrier 900; reply to 2 dropped
2. every replica has applied 2 → ask who leads → kill -9 that process
3. two members left = quorum → election → new leader
4. client reconnects, retries key 2 → cached (2, 3); sends 3, 4, 5, 900
5. barrier 900 on all three: count 5, total 15          ← drained
6. stop the two non-leaders; client offers 901 → positive, but no BARRIER 901
7. restart all three from kept directories → BARRIER 901, same digest everywhere
```

Why drain first (step 5 before step 6)? If old work were still in flight when
quorum went away, a callback seen later might be that old committed work, not
new progress. A distinct new barrier, 901, makes any execution unambiguous.

Why ask who leads? After step 2 the leader can change. An old "LEADER" line can name a dead process. The check asks the live
cluster through `ClusterTool.listMembers`.

The watchdog keeps a stuck check from hanging forever. If it fires, the result is
a setup problem, and it tells you nothing about whether a request committed.

What each piece of evidence proves:

| evidence | what it proves | enough for "recovered"? |
|---|---|---|
| a positive client `offer` | the client's local publication log accepted it | no |
| an old log line saying "LEADER" | who led when that line was written | no |
| BARRIER 901 seen at the application on every replica | the new barrier committed and ran | yes, for this check |

Plain advice: when a recovery test asserts on the first two rows, ask for the
third.

Common wrong belief: "resend with a new ID." That might get *another* request
accepted, but the first one's fate stays unknown, and now it may run twice.

Rule: progress is a new barrier seen at the application, with a functioning quorum;
never an offer result or an old log line.
</details>

Elaborate: why does "all offers succeeded" fail as a recovery assertion?

### CARD L06-C2 · Try it
Q: Run the first command (it overwrites both work files). In `ClientWork.java`, repair `offered`, marked `TODO(L06-C2)`, and run the policy check until HIT. Then complete in writing: "a positive local offer must leave __ intact until __."
```bash
bash checks/l06/prepare.sh independent
printf 'labs/l06/work\n' | checks/l06/policy.sh
```
The first run reports MISS: `offer is not ack; retain and retry pending`.
<details><summary>Key — open after your repair passes or you give up</summary>

Key:
- A positive local offer must leave `pending` intact until a consistent reply comes from the application; local acceptance does not settle the request.
- The policy check passes: terminal offer results lead to a reconnect, and a duplicate reply does not count as a second acknowledgement.
</details>

### CARD L06-R2 · Recall and repair
Q: Without opening `labs/l06/solution`, run the full failover check on your independent repair. (On a later review day, run `bash checks/l06/prepare.sh independent` first and repair from memory.) If it misses, loop: guess → find the first failing event in the message → one edit → rerun. Then answer: (a) why must quorum be removed only after earlier work has drained, using a new barrier? (b) what does a watchdog expiry tell you?
```bash
printf 'labs/l06/work\n' | checks/chk_l06_failover.sh
```
<details><summary>Key — open after the independent attempt</summary>

Key:
- Your work passes the complete failover and quorum check, returns the cached result for the lost reply, and changes state once per ID.
- (a) Wait until the known earlier barrier has run before removing quorum, then use a distinct new barrier; otherwise a callback from old committed work can pass for new progress.
- (b) A watchdog expiry reports a problem with the test setup; do not treat it as proof that no earlier request committed.
</details>

### CARD L06-T2 · Use the rule
Q: An imagined payment client times out and reconnects with a new session. An AI assistant wrote its reconnect code below. Its test notes say: "all offers returned positive; the monitor log shows node 1 as LEADER, so we sent to node 1."
```java
void onReconnect() {
    for (Payment p : pending.values()) {
        p.requestId = nextRequestId++;   // "fresh ID so the cluster cannot mistake it for a stale request"
        send(p);
    }
}
void onReply(long requestId, Result r) {
    ledger.record(r);                    // records every reply that arrives
    pending.remove(requestId);
}
```
Answer in three parts: (a) is it right here, and what is the smallest repair? (b) which two assertions would tell recovery apart from a double payment? (c) which evidence in the test notes is not enough?
<details><summary>Key — open after diagnosing</summary>

Key:
- (a) No. A fresh ID turns one payment into two business actions, and recording every reply counts a repeated reply twice. The smallest repair: keep the same business request key across sessions, settle repeated replies to one original result, and ask the live cluster who leads.
- (b) Assert that each unique request changed state exactly once, and assert the full application state at a shared barrier; do not assert one network reply per request.
- (c) An old role log line and a local offer result: neither is enough evidence of what the application committed.
</details>

## Close

Remember: one stable key per business action, looked up before mutating; a
positive offer is not a reply; progress is a new barrier after a functioning quorum commits it.
Your recall and transfer cards return tomorrow. Next, lesson 07: restart from a snapshot
without losing the receipt book. Optional: the 15-minute pause case in
`labs/l06/ops/README.md`.
