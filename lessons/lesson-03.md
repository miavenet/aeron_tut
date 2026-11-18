# Lesson 03 — Your order is stuck. Which stage has it?

The sender says "accepted"; your screen says nothing. Restarting everything is a
very expensive refresh button. Today you find the first stalled stage, change one
thing, and prove the backlog drained.

By the end you can: read the receiver's three positions; tell a missing frame
from a slow consumer and from a stalled driver; and prove the repair with exact
bytes and a final position.

Time: about 47 minutes in two sessions of about 24 and 23 minutes. You need lesson 1
(positions, images, session IDs) and lesson 2 (back pressure).

## Session A — The pipeline has more than one queue, about 24 minutes

### CARD L03-P1 · Before you learn
Q: Cold guess; the next concept defines these. For one image, the receiver's high-water mark (`rcv-hwm`, the highest position it has seen) is above its rebuilt position (`rcv-pos`, how far it holds complete data). Does that prove the subscriber is slow? Answer yes or no, plus one observation that would test your guess.
<details><summary>Key — after attempt</summary>

Key:
- No. HWM above rebuilt only shows that the receiver has seen data beyond the point where it holds complete data. That can point to a gap in what it received, but these counters alone prove neither packet loss nor a slow subscriber.
- To test for a slow subscriber, compare rebuilt with how far the subscriber has consumed, for the same channel, stream and session.
</details>

## Why Aeron lets you look inside

In a 2018 InfoQ podcast, Martin Thompson told how Aeron began. Having worked
on many messaging systems, he said, getting involved with another one seemed
like a crazy idea. A client convinced him. His condition was that it must be
open source, with the main goal of him never having to be involved with
another messaging system.

The client worked at the extreme end of performance. Commercial tools were
close to what it needed, but it also needed transparency: understanding what is
going on in a distributed system is very difficult. So a key design goal was a
system easy to monitor and understand at runtime. The counters you read today
are that goal at work.

Why build reliability on UDP rather than use TCP? In the same podcast,
Thompson called TCP a wonderfully designed protocol for wide area networks that
may be quite lossy. Data centres, he said, tend to have very little loss, more
bandwidth and lower latency, and Aeron was designed with those assumptions.
Aeron puts each source's messages back in order for a receiver and retransmits
missing data. That work happens in a stage you can watch, between "seen" and
"rebuilt".

### CONCEPT L03-C1 · The first stopped conveyor belt
Prediction: In a fixture that deliberately drops one frame, an image reports HWM 16896, rebuilt 1408 and consumed 0. Which comparison shows data the receiver has seen but not yet rebuilt (the unreconstructed span), and which shows rebuilt data the subscriber has not read yet? Commit before opening the worked example.

Worked:
The media driver publishes **counters**: named 64-bit values in its CnC file
(the command-and-control file, `cnc.dat` in the Aeron directory). The sample tool
`AeronStat` prints them. Three of them follow one image on the receiving side,
and all are byte positions, not message counts:

- **HWM** (`rcv-hwm`): the highest position the receiver has seen. The stream
  may have holes below it.
- **Rebuilt** (`rcv-pos`): the receiver holds complete data up to here.
- **Consumed** (`sub-pos`, or `image.position()`): how far your `poll` has read.

```text
sender --UDP--> HWM      highest seen
                  |      HWM > rebuilt?       unreconstructed span; investigate a possible gap
                rebuilt  complete up to here
                  |      rebuilt > consumed?  subscriber has not read it yet (lag)
                consumed your poll got here
```

Each counter belongs to one channel, stream and session, so compare only
counters with the same identity. Lag is `rebuilt - consumed`.

The lab labels each image with a simplified rule: a missing counter (value -1) means UNKNOWN, never
"drained". Then HWM > rebuilt gives GAP. Then rebuilt > consumed gives
SLOW_CONSUMER. Otherwise the image is DRAINED.

<details><summary>Worked result — open after your prediction</summary>

This fixture drops a frame on purpose, and HWM 16896 sits above rebuilt 1408 because of the hole that frame left. The gap fixture sends 12 messages
of 1,376 bytes; each takes 1,408 bytes of stream. It drops one data frame once,
the one at position 1408. The difference, 16896 − 1408 = 15488, covers the dropped 1408-byte frame plus the data after it; it is not the number of bytes lost. In this fixture the receiver has everything up to 1408, has seen data up to 12 × 1408 = 16896, and the subscriber has read nothing yet.

The fixture expects the receiver to send a NAK (a request for the missing range), the sender to retransmit, and the stream to drain to position 16896 with exact bytes. Each fresh run must see the NAK and the retransmission in the driver's counters; their exact values depend on timing.

Calling every difference "slow subscriber" confuses two stalled stages. Polling
faster cannot create a frame that never arrived; retransmission repairs the gap.
Your code's job is to name the stage correctly, resume polling, and prove the drain.

Rule: compare HWM with rebuilt to see whether the receiver is rebuilding what it has seen, and rebuilt with consumed to measure subscriber lag. Compare only counters of the same image, and back a suspected loss with evidence of the loss and its retransmission.
</details>

Elaborate: why is comparing counters taken from two different images meaningless, even when the numbers look plausible?

### CARD L03-COMP1 · Try it
Q: Prepare the completion exercise. This replaces `labs/l03/work/Diagnostics.java` and saves one previous copy in `labs/l03/work/backups/Diagnostics.java`. (a) Fill the two lines marked `TODO(L03-COMP1)`: the gap result and the lag calculation. Run the trace check: the first run prints `MISS: gap trace`, and the repair prints `HIT`. (b) Predict the lag for rebuilt = 128 and consumed = 32. (c) In one sentence: why is `hwm - rebuilt` the wrong lag?

```bash
checks/l03/prepare.sh completion
printf '%s\n' labs/l03/work | checks/l03/trace.sh
```

<details><summary>Key — after editing and predicting</summary>

Key:
- (a) Return `"GAP"` when `hwm > rebuilt`, and return `rebuilt - consumed` from `lag`; the trace check prints `HIT`.
- (b) 128 − 32 = 96.
- (c) `hwm - rebuilt` is the span between those positions, not a count of lost bytes and not subscriber lag; subscriber lag is rebuilt minus consumed.
</details>

### CARD L03-R1 · Recall and repair
Q: (a) Run the full check on the workspace you repaired in L03-COMP1. When this card returns on a later review or exam, repair a fresh copy of the completion exercise instead; in driver mode, use the folder the driver names. Make that copy once per attempt, keep editing it if you resume, and do not rerun `prepare.sh` over your earlier work. The check runs the trace, a live paused consumer, a live dropped frame and a stalled driver, and reports `HIT` when all pass. (b) From memory: give the inequality for this lab’s GAP label, the inequality for consumer lag, and the identity both counters must share. (c) In one sentence: why is a successful rerun stronger evidence than a new counter screenshot?

```bash
printf '%s\n' labs/l03/work | checks/chk_l03_diag.sh
```

<details><summary>After recall</summary>

Key:
- (a) The full check prints `HIT` on the repaired workspace.
- (b) GAP is HWM > rebuilt; consumer lag is rebuilt > consumed, meaning rebuilt data not yet read; both counters must share channel, stream and session. Outside this controlled fixture, GAP is only a hypothesis to test.
- (c) The rerun shows the work finished: the stream drains to its known final position with the exact expected bytes; a screenshot only shows one moment.
</details>

## Judgement: one stage, one change

For one image, each comparison points at a stage to investigate:

```text
observation          investigate first                  evidence of repair
HWM > rebuilt        rebuild progress; corroborate       rebuilt catches up;
                     any suspected loss                 if loss occurred, verify its repair
rebuilt > consumed   subscriber polling and processing   consumed reaches the known end
```

Increasing subscriber polling cannot replace a genuinely missing frame. But HWM above rebuilt alone does not establish that a frame is missing. Check driver progress and corroborate loss before choosing a remedy.

### CARD L03-T1 · Use the rule
Q: Judgement call. A fictional team asks an AI assistant for a health alert on a UDP subscriber. It proposes:

```java
if (rcvHwm - subPos > 64 * 1024) {
    alert("subscriber is slow: increase poll rate");
}
```

One or two sentences each: (a) Is this alert right? (b) What do you check first when it fires? (c) What version would you accept, and what result proves a fix?
<details><summary>After the design</summary>

Key:
- (a) No: HWM minus consumed adds two different spans, the unreconstructed span (HWM − rebuilt) and subscriber lag (rebuilt − consumed), so it cannot name the stalled stage, and "poll faster" is wrong whenever the span is a gap.
- (b) That both counters belong to the same channel, stream and session; then compare HWM with rebuilt and rebuilt with consumed separately.
- (c) One that reports the gap span and the lag separately; the proof is naming one stage, changing only that, and showing a known final message reach its final position with exact bytes.
</details>

## Session B — A dead dashboard can look beautifully caught up, about 23 minutes

### CARD L03-P2 · Before you learn
Q: Guess. The driver writes a timestamp, its **heartbeat**, into the CnC file while it works. All three positions are equal, but the heartbeat is old. (a) Is the image healthy and drained? (b) What should you check before trusting those positions?
<details><summary>Key — after attempt</summary>

Key:
- (a) Not proven: equal positions from a driver that has stopped working are frozen, not current.
- (b) Check that you read the right Aeron directory and that its heartbeat is fresh; an old heartbeat alone does not prove the process died.
</details>

### CONCEPT L03-C2 · Timestamp the evidence
Prediction: A driver runs in a mode where nothing happens unless your code calls `invoke()`. Your code stops calling it for 1.3 seconds. What happens to the heartbeat, and what should the diagnosis say before it looks at any position? Commit before opening the worked example.

Worked:
Aeron's moving parts are **agents**: objects that do a small slice of work each
time their `doWork` is called. That repeated call is the **duty cycle**.
For message flow, focus on these three driver agents:

```text
conductor  handles client commands, tracks rebuild/loss, and writes the heartbeat
sender     sends and retransmits frames from publication logs
receiver   inserts received frames and sends status messages and NAKs
```

The pinned driver also runs `NativeResourceAgent`, which handles tasks such as resource allocation and cleanup.

Normally the driver runs them on its own threads. In `INVOKER` mode it starts no
threads at all. The agents run only when your code calls `invoker.invoke()`. The
client library has its own conductor, which can also be run this way
(`useConductorAgentInvoker(true)`). If nobody gives an agent its duty cycle,
nothing it owns moves: positions freeze and the heartbeat ages.

**Heartbeat age** is now minus the last heartbeat, in milliseconds
(`CncFileReader.driverHeartbeatAgeMs()`). The lab treats an age above 1,200 ms as
stale. That threshold is a lab policy, not a production timeout.

<details><summary>Worked result — open after your prediction</summary>

The heartbeat stops advancing. The liveness fixture launches a driver in
`INVOKER` mode, invokes it until a first heartbeat appears, then stops invoking
and waits until the heartbeat is more than 1,300 ms old. `diagnose(0, 0, 0, age, 1200)` must answer `STALE_DRIVER`, even
though the positions are equal. Then `duty()` calls `invoke()` once, the
heartbeat refreshes, and the same call must answer `DRAINED`.

So staleness is checked first. Equal positions from a frozen driver describe
the past. The trace check also tests this order: positions 128, 64, 32 with age
1,300 must give `STALE_DRIVER`, not `GAP`.

Wrong belief: "old heartbeat means the driver process is dead". Here the process
is alive; it just was not given duty. The evidence says "stale", and the next
step is to find out why.

Rule: check heartbeat freshness first; only then compare positions.
</details>

Elaborate: why can positions that look fully caught up be the wrong evidence when the heartbeat is stale?

### CARD L03-COMP2 · Try it
Q: Paper exercise. Complete this incident report in four short lines: "Positions equal, heartbeat old. (a) My hypothesis is ____. (b) Before reading positions I check ____. (c) In this invoker fixture my one intervention is ____. (d) My recovery evidence is ____."
<details><summary>After completing the report</summary>

Key:
- (a) The driver's duty cycle is not running (in this fixture, nobody calls `invoke()`), so the positions are frozen.
- (b) That I am reading the right Aeron directory and how old its heartbeat is.
- (c) Restore the duty cycle by calling `invoke()`, without claiming the process died.
- (d) The heartbeat advances; for a data backlog I also need the stream to drain to its final position with exact bytes.
</details>

### CARD L03-R2 · Recall and repair
Q: Close the lesson. Prepare the independent exercise (one previous copy is saved in `labs/l03/work/backups/Diagnostics.java`). (a) Two places are marked `TODO(L03-R2)`: the staleness check is missing from `diagnose`, and the paused consumer never resumes. Repair both from memory and run the full check. The first run prints `MISS: check liveness before stale positions`; the repair prints `HIT`. (b) In one or two sentences: why does resuming polling fix lag, while a gap needs different evidence?

```bash
checks/l03/prepare.sh independent
printf '%s\n' labs/l03/work | checks/chk_l03_diag.sh
```

<details><summary>After the independent attempt</summary>

Key:
- (a) The first line of `diagnose` returns `"STALE_DRIVER"` when `ageMs > staleAfterMs`, and `resumePolling` returns `true`; the full check prints `HIT`.
- (b) Lag is rebuilt data waiting to be consumed, so resuming polling reads it. A gap needs other proof: in this fixture, the injected loss is confirmed by the NAK and retransmission counters and by the stream recovering to its final position with exact bytes.
</details>

## Before the last card — a live subscription is not a history request

A new live subscription does not ask for any range of history, and it is not guaranteed the messages published before it joined. Where it starts depends on the driver and on the stream's existing state, so it can sometimes receive earlier data that is still retained. In this fixture, a separate subscription (the anchor) reads everything sent before each new subscription attaches, so the new subscription misses the earlier IDs. Here is the sequence:

```text
sent:        id0 id1 id2 | id3        | id4             | id5
late joiner:             | gets id3   | (closed)        |
reopened:                |            |                 | gets id5   (id4 missing)
```

The reopened subscription also sees a second publication on the same stream as a
separate image with its own session ID. Detect missing history by comparing the
first application ID you receive with the one you expected next. Recovering it
needs a replay (lesson 4) or an application-level resend.

### CARD L03-T2 · Use the rule
Q: In a fictional system, a replacement subscriber connects successfully after being closed for a while, and another publication shares its stream ID. (a) What does "healthy and connected" fail to prove? (b) How do you keep the two publications' data apart? (c) Name one measurable recovery test.
<details><summary>After your policy</summary>

Key:
- (a) Being connected does not prove the subscriber got the messages published while it was away; this fixture shows IDs missing after reopening. Find the actual break in the sequence from application IDs, and recover the missing range explicitly.
- (b) Tell images apart by session ID (and channel), not by stream ID alone, and never merge their positions into one order.
- (c) Compare the first received application ID with the expected next ID; on a gap, request replay or a resend, then check the exact missing messages plus a known final message.
</details>

## Optional operations — 15 minutes

`labs/l03/ops/README.md` has a guided case on the client conductor's duty cycle,
plus reference cases for late joins, trace policies and bounded log inspection.

## Close

Remember: HWM above rebuilt is a reason to look for a gap; rebuilt above consumed means rebuilt data is waiting unread. Compare counters for the same image and check driver liveness. Change one thing,
then prove the drain with exact bytes. Tomorrow brings back lessons 1 to 3 mixed.
Next, lesson 4: the Archive, where "recording" must mean the bytes are really there.
