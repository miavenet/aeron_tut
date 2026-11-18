# Paper study edition

Generated from `lessons/*.md`, `manifest/cards.json` and `manifest/schedule.json` by `python3 -B drive.py lessons --print-t0 > STUDY.md`. Do not edit it by hand: `tools/test_driver.py` and `manifest/verify.sh` fail when it differs from the lessons. The lesson files remain the source; this file only moves every card key to the end.

## How to use this file on paper

1. Read in order. At each CARD, cover what follows, write your answer and a confidence (low, medium or high), then look up the card ID under **Answer keys** at the end.
2. Grade bullet by bullet: every bullet covered without contradiction is HIT, some is PARTIAL, none is MISS. After a miss, a partial or low confidence, write the corrected rule in one sentence.
3. Start each lesson on its day, counting your first sitting as day 1: lesson 1 on day 1, lesson 2 on day 4, lesson 3 on day 8, lesson 4 on day 11, lesson 5 on day 15, lesson 6 on day 18, lesson 7 on day 22, lesson 8 on day 25.
4. Reviews: a review card first returns 1 day after you first answer it. After a success, wait 3, then 7, then 14 days (and 14 after that). After a miss, a partial, low confidence or a hint, it returns the next day.
5. Lab cards on a review or exam day: copy the scaffold named below to a fresh folder, for example `cp -R labs/l02/starter labs/l02/retrieval-L02-R1-DATE`, repair that copy from memory and run the card's check on it. Do not grade your earlier `work` folder again.
6. Delayed exam: at least 7 days after your last first exposure, answer each exam card below from memory, with no hints, on a later day than you last saw its key.

## Review cards

- lesson-01: L01-R0; L01-T0; L01-R1; L01-T1; L01-R2 (lab: fresh copy of `labs/l01/independent`); L01-T2
- lesson-02: L02-R1 (lab: fresh copy of `labs/l02/starter`); L02-T1; L02-R2 (lab: fresh copy of `labs/l02/starter`); L02-T2
- lesson-03: L03-R1 (lab: fresh copy of `labs/l03/completion`); L03-T1; L03-R2 (lab: fresh copy of `labs/l03/independent`); L03-T2
- lesson-04: L04-R1 (lab: fresh copy of `labs/l04/starter`); L04-T1; L04-R2; L04-T2 (lab: fresh copy of `labs/l04/independent`); L04-INT
- lesson-05: L05-R1 (lab: fresh copy of `labs/l05/completion`); L05-T1; L05-R2 (lab: fresh copy of `labs/l05/independent`); L05-T2
- lesson-06: L06-R1 (lab: fresh copy of `labs/l06/completion`); L06-T1; L06-R2 (lab: fresh copy of `labs/l06/independent`); L06-T2
- lesson-07: L07-R1 (lab: fresh copy of `labs/l07/completion`); L07-T1; L07-R2 (lab: fresh copy of `labs/l07/independent`); L07-T2
- lesson-08: L08-R1 (lab: fresh copy of `labs/l08/independent`); L08-T1; L08-R2 (lab: fresh copy of `labs/l08/independent`); L08-T2 (lab: fresh copy of `labs/l08/transfer`)

## Delayed exam cards

L01-R0, L01-T0, L01-T1, L01-R2, L01-T2, L02-R1, L02-T1, L02-R2, L02-T2, L03-R1, L03-T1, L03-R2, L03-T2, L04-R1, L04-T2, L05-R1, L05-T1, L06-R1, L06-T1, L07-R1, L07-T1, L08-R1, L08-T2

---

# Lesson 01 — Your order was sent. Why did it vanish?

A writer reserves a slot in a shared log, starts filling it, then trips over an
exception and walks off. Every message behind that slot now waits for a writer who
is never coming back. Today you learn who must close that slot, and how.

By the end you can: sketch Aeron's moving parts; commit or abort every reserved
slot on every exit path; and prove it with a live check.

Time: about 63 minutes in three sittings: a 19-minute primer and Java pit
stop, then session A of about 23 minutes and session B of about 21. You need the README
setup done and some programming background. No earlier lessons.

**Pinned** in this course means: the Aeron 1.54.1 jar that setup downloads, run on
OpenJDK 26.0.1 (see README). Other Aeron or JDK versions are untested, so facts
about defaults and behaviour below are only claimed for that pair.

For paper mode: cover everything below the current question, write an answer and
a confidence (low, medium, high), then open the folded key. Grade an explanation
bullet by bullet; one contradiction makes it a miss. After a miss, write the
corrected rule in one sentence and try the card again tomorrow.

## Primer — what Aeron is

Aeron moves messages between threads, processes and machines. Its own summary is
"efficient reliable UDP unicast, UDP multicast, and IPC message transport", built
for high throughput with low and predictable latency. Picture a trading gateway
handing thousands of orders a second to a matching engine, with no queue library
pausing at the wrong moment.

Six moving parts carry every message in this course:

- **Media driver**: the engine room. It owns the shared-memory logs and does the
  network work. Your program talks to it through files in an Aeron directory. The
  labs start one inside the test program.
- **Channel and stream**: the address. A channel is a URI such as
  `aeron:udp?endpoint=127.0.0.1:40123` (network) or `aeron:ipc` (same machine). A
  stream ID is a number that keeps several streams apart on one channel.
- **Publication**: the sending end. `offer` copies finished bytes in; `tryClaim`
  reserves space you fill in place.
- **Log buffer and terms**: where messages wait. The log is split into three
  terms (fixed-size sections, used in rotation). A message's **position** is its
  byte distance from the start of the stream.
- **Subscription**: the receiving end. You call `poll`, and Aeron calls your
  handler once per piece of data, called a **fragment**.
- **Image**: one publisher's copy of the stream, as a subscription sees it. Each
  publication has a **session ID**; a subscription holds one image per session.

How one message travels over UDP:

```text
publisher process     its media driver         subscriber's driver      subscriber process
Publication           log buffer               image log buffer         Subscription
  offer() --writes--> [term0|term1|term2]      [term0|term1|term2] <--- poll() -> your handler
                         | Sender reads it        ^ Receiver writes it     (one Image per
                         +------ UDP frames ------+                         publisher session)
IPC on one machine: the subscriber reads the publisher's log directly; no Sender or Receiver.
```

If a UDP packet is lost, the receiving driver asks for it again and the sender
retransmits it; lesson 3 shows that happening. Today's lab uses IPC.

**Self-check, about one minute.** Cover the list and the diagram above. On
paper or in a scratch file, name the six moving parts and say in a few words
what each one does. Then uncover the list, mark any part you missed or
blurred, and reread only those entries before you go on.

## Check results — read this once

A `prepare.sh` command copies exercise files into a `work` folder, replacing the previous exercise. You edit those files. Each check runs its named assertions: some compile and run Java against Aeron, some exercise policy or codecs, and lesson 8 runs Python. The result reports:

```text
HIT             every assertion passed (exit code 0)
MISS            your code broke a rule; the next lines name it (exit code 1)
SETUP PROBLEM   the environment failed, not you (exit code 2)
```

A line such as `MISS: failed encoder must abort, not publish partial bytes` names
the rule to fix. The output includes a schema-version-1 JSON result for the course driver, followed by readable status and any failure details. Other diagnostic output may precede it.
For SETUP PROBLEM, rerun once; a busy network port is one known cause.

## Prerequisite pit stop — about 15 minutes after the primer, before session A

### CARD L01-P0 · Before you learn
Q: Pure guess, before reading any Java. In the primer below, buffer construction is inside `main`. (a) Which method contains the allocation and wrapping steps? (b) Which part gives you access to the bytes? One line each.
*Key: see L01-P0 under Answer keys.*

### CONCEPT L01-JAVA · Enough Java to touch the bytes
Prediction: The program below writes 7 at offset 0 and reads offset 0 back. What does it print? And which command turns the file into runnable code? Commit before opening the worked example.

Here is the whole primer program, `labs/l01/ops/Primer.java`:

```java
import org.agrona.concurrent.UnsafeBuffer;
public final class Primer {
    public static void main(String[] args) {
        byte[] bytes = new byte[4];
        UnsafeBuffer buffer = new UnsafeBuffer(bytes);
        buffer.putByte(0, (byte)7);
        if (buffer.getByte(0) != 7) throw new AssertionError("byte roundtrip");
        System.out.println("PRIMER PASS byte=7");
    }
}
```

Worked:
A class groups methods. Here, `main` is the application entry method and creates the buffer.
`byte[]` is an array of bytes. `UnsafeBuffer`, from Agrona (the library Aeron is
built on), wraps that array. `putByte(offset, value)` writes one byte at an
offset; `getByte(offset)` reads it back. The first argument is always the offset,
never the value. `javac` compiles the file; `java` runs it.

<details><summary>Worked result — open after your prediction</summary>

It prints `PRIMER PASS byte=7`. Run `checks/l01/setup.sh` to see that line,
followed by `HIT`.

The same check shows two setup traps. Agrona reaches into a JDK-internal class,
`jdk.internal.misc.Unsafe`, so plain `java` fails on Java 26 with
`IllegalAccessError` (seen with the pinned jar; other JDK or Aeron versions are untested,
and `checks/l01/setup.sh` re-runs this on yours). The flag `--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED`
allows that access. `tools/java.sh` always adds it, so labs run through it.

The second trap is the shell. In zsh, a scalar variable holding two words,
`F="--add-opens java.base/jdk.internal.misc=ALL-UNNAMED"`, expands as one
argument, so Java reports `Unrecognized option`. A Bash array,
`F=(--add-opens java.base/jdk.internal.misc=ALL-UNNAMED)` used as `"${F[@]}"`,
passes the two words separately. The check runs all four cases.

If you predicted "it prints 7", you had the bytes right; the full line is the proof.

Rule: the first argument of `putByte` and `getByte` is the offset, and Java runs through the wrapper.
</details>

Elaborate: why do the labs launch Java through `tools/java.sh` rather than plain `java`, and what error would tell you that you forgot?

### CARD L01-C0 · Try it
Q: Cover the worked example. Fill the blank so that `buffer.getByte(2)` reads the value just written: `buffer.putByte(__, (byte)11);`. Answer in one line: the number you fill in, the value the read returns, and what the first argument selects.
*Key: see L01-C0 under Answer keys.*

### CARD L01-R0 · Recall and repair
Q: From memory, sketch a `main` method that allocates four bytes, wraps them in an `UnsafeBuffer`, writes 7 at offset 2 and checks `bytes[2]`. Then name the compile command and the course’s Java launcher. Why does checking the array test something different from reading through the wrapper?
*Key: see L01-R0 under Answer keys.*

### CARD L01-T0 · Use the rule
Q: A teammate runs the compiled primer with plain `java` and gets `IllegalAccessError` for `jdk.internal.misc`. (a) What fixes it in this course? (b) They then store the two words `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` in one zsh variable, and Java says `Unrecognized option`. Why is that a different problem? One or two sentences each.
*Key: see L01-T0 under Answer keys.*

## Session A — Own the slot, about 23 minutes

### CARD L01-P1 · Before you learn
Q: Honest guess; the next concept explains it. A **claim** reserves a slot in the log that you fill before publishing. (a) An `offer` returns a negative number, so Aeron did not take your bytes. Who owns those bytes now? (b) A claim succeeds, but the code never commits it. Can the next complete message be delivered past it?
*Key: see L01-P1 under Answer keys.*

## A story first: the same trap, in Aeron's family tree

Aeron was originally created by Martin Thompson and Todd Montgomery. A few
years earlier, Thompson co-wrote the 2011 paper on the LMAX Disruptor, a
ring buffer built for a very high-performance financial exchange.

A Disruptor producer works in two phases. It claims the next slot in the ring,
writes the entry, then commits by moving a cursor that consumers watch.

The Disruptor user guide carries a warning you are about to meet again. If you
claim a slot, you must publish it. In the multi-producer case, failing to do so
leaves the consumers stalling, unable to recover without a restart. Its cure is
a `try`/`finally` block that always publishes.

Aeron's `tryClaim` has the same two phases, and the same trap. It also gives
you a second exit, `abort()`, so your `finally` block has a choice to make.

### CONCEPT L01-OWN · Reservation is not publication
Prediction: A connected sender claims 4 bytes, writes them, then throws before committing. Will the subscriber receive the next message, sent after it? Commit before opening the worked example.

Worked:
There are two ways to put a message into a publication:

- `offer(buffer)` copies bytes you already have into the log.
- `tryClaim(length, claim)` reserves `length` bytes in the log and gives you a
  window to write into: `claim.buffer()` at `claim.offset()`. You then call
  `claim.commit()` to publish or `claim.abort()` to cancel.

Both return the new stream position on success, or a negative code (lesson 2).

Which one should you pick?

```text
              offer                          tryClaim
you hand it   finished bytes; Aeron copies   a length; you write in place
size limit    may split a big message        one frame's payload at most (lesson 2)
exits         nothing left to resolve        you must commit or abort, every time
```

Default to `offer`. Reach for `tryClaim` when you encode straight into the
slot and can show that every exit path resolves the claim.

Why can an unfinished claim block everything behind it? Every frame starts with
a 32-byte header whose first field is the frame length. A subscriber reads frames
in order and stops at the first one whose length is zero or negative. Here is
the lab's 4-byte claim, with frame length 32 + 4 = 36:

```text
length field   frame type   what the subscriber does
-36            DATA         claimed, still being written: stop and wait here
+36            DATA         commit() wrote the length: deliver 4 bytes, move on
+36            PAD          abort() wrote it as padding: skip, deliver nothing
```

Before trusting this example, predict what it publishes if encoding throws after writing half an order. The `finally` block still commits. Session B repairs that failure path with a success flag.

```java
if (publication.tryClaim(messageLength, bufferClaim) > 0L) {
    try {
         final MutableDirectBuffer buffer = bufferClaim.buffer();
         final int offset = bufferClaim.offset();
         // Work with buffer directly or wrap with a flyweight
    } finally {
        bufferClaim.commit();
    }
}
```

<details><summary>Worked result — open after your prediction</summary>

The next message waits. The lab fixture proves it: it holds one claim open,
commits a second message behind it, and polls 100 times. The subscriber's
position does not move. Only an explicit `abort()` releases the tail. The driver
does eventually force-unblock a stuck claim (in the pinned jar the default
`aeron.publication.unblock.timeout` is 15 seconds; it is a default, and the property
can override it), but code that waits for that has already failed. Do not carry "15
seconds" to another version or a production setting. To re-check on your jar, run
`javap -cp labs/lib/aeron-all-1.54.1.jar -c -p io.aeron.driver.Configuration | grep -B3 'putstatic.*PUBLICATION_UNBLOCK_TIMEOUT_DEFAULT_NS'`
and look for `15l` (change the jar name for your version). The source constant is
`PUBLICATION_UNBLOCK_TIMEOUT_DEFAULT_NS` in `Configuration.java`. What else would
change it is unknown to this course. The lab check for this lesson also proves the
stuck-claim wait; re-run it after changing the Aeron or JDK version to see whether
that behaviour still holds.

The common wrong belief is "a successful claim means delivered". A claim is a
reservation; visibility needs the commit. A second myth: `tryClaim` makes the
whole path zero-copy. This lab's encoder still copies a byte array into the slot
with `putBytes`. You avoid that copy only by encoding fields straight into the
claimed window.

Rule: every successful claim has one owner, who must commit it or abort it.
</details>

Elaborate: why does one unresolved claim hold back messages sent after it, not only its own bytes?

### CARD L01-C1 · Try it
Q: Prepare the lab (this overwrites `labs/l01/work/Ownership.java`), then open that file. In `resolve`, the success branch marked `TODO(L01-C1)` is empty: make it publish the claimed bytes. Run the check. The first run prints `MISS` with `resolve claim: commit complete bytes or abort failure`; a correct edit prints `HIT`. Then answer in one sentence: why must this call come only after encoding has finished?

```bash
checks/l01/prepare.sh completion
printf '%s\n' labs/l01/work | checks/chk_l01_ipc.sh
```

*Key: see L01-C1 under Answer keys.*

### CARD L01-R1 · Recall and repair
Q: Without looking back: (a) sketch the claim lifecycle as a short sequence of calls; (b) explain in one sentence why a positive `tryClaim` result is not delivery; (c) say where this lab's encoder still copies bytes.
*Key: see L01-R1 under Answer keys.*

## One writer or many?

In 2011 Thompson wrote up the Single Writer Principle on his Mechanical
Sympathy blog: each item of data should be owned by a single execution context
for all mutations. His table makes the point. Incrementing a 64-bit counter 500
million times took 300 ms on one thread, and 118,000 ms with two threads
sharing a lock. That is one 2011 machine's result; the lasting point is the
principle, and you would measure your own hardware to see the gap. Aeron's own design principles list the single writer rule too.

So Aeron has two kinds of publication. The lab uses an `ExclusivePublication`
(from `Aeron.addExclusivePublication`): its `offer` and `tryClaim` are not
thread-safe, so exactly one thread may use them. `Aeron.addPublication` returns
a `ConcurrentPublication`, which several threads may share.

```text
                 ExclusivePublication              ConcurrentPublication
threads          one thread for offer/tryClaim     shared by publishing threads
upstream says    potential for greater throughput  thread-safe offer and tryClaim
wrong-pick smell two threads, no guard             a lone thread paying for sharing
```

Pick exclusive when one thread owns the sending; pick concurrent only when
several threads really must send on the same publication.

### CARD L01-T1 · Use the rule
Q: In a fictional service, two threads want to send through one `ExclusivePublication`. A colleague says: "Fine, our single-producer ring buffer is also a ring." (a) Is sharing the exclusive publication safe, and what are your two options? (b) Why does a passing test of the single-producer ring prove nothing here?
*Key: see L01-T1 under Answer keys.*

## Session B — Make the exception path boring, about 21 minutes

Start with any due reviews. Skip pretests you have already answered. If you were
interrupted, restart at the card you had not finished.

### CARD L01-P2 · Before you learn
Q: Guess: the encoder throws after writing half an order. Should a `finally` block always call `commit()` "to keep the stream moving"? Yes or no, plus one sentence.
*Key: see L01-P2 under Answer keys.*

### CONCEPT L01-FAIL · The exit door also needs a handle
Prediction: In the code below, `failAfterWrite` is true. Which value of `complete` reaches the `finally` block, which call does `resolve` make, and does the caller still see the exception? `resolve` commits when `complete` is true and aborts otherwise. Commit before opening the worked example.


```java
public void encode(BufferClaim claim, byte[] payload, boolean failAfterWrite) {
    boolean complete = false;
    try {
        claim.buffer().putBytes(claim.offset(), payload);
        if (failAfterWrite) throw new IllegalArgumentException("injected encoder failure");
        complete = true;
    } finally {
        resolve(claim, complete);
    }
}
```

Worked:
The lab code uses a **success flag** that becomes true only after successful encoding. `finally` runs on both exits, normal return and exception. There is no
`catch`, so the exception keeps travelling to the caller.

```text
encode succeeds -> complete = true  -> commit -> subscriber gets the order
encode throws   -> complete = false -> abort  -> padding; caller sees the exception
```

<details><summary>Worked result — open after your prediction</summary>

`complete` is still false, because the throw happens before `complete = true`.
`resolve` aborts, and the exception reaches the caller. The lab fixture sends five
good messages, then one claim that fails after writing `{99,98,97,96}`. The
subscriber must receive exactly the five good payloads.

The wrong belief is "abort and doing nothing are the same". Doing nothing leaves
a negative length in the log, and the subscriber stops behind it. Abort frees the
slot without delivering it.

One more lab detail: `guard` rejects a foreign thread and a negative or oversized
length before any publication access. That guard is our application policy;
Aeron itself does not detect a second thread on an exclusive publication.

Rule: decide commit or abort from a success flag set on the last line, and never swallow the exception.
</details>

Elaborate: why must the success flag be set on the last line of the `try` block, not straight after the claim succeeds?

### CARD L01-C2 · Try it
Q: Paper exercise. Keep `finally { resolve(claim, complete); }`. An encoder writes the header, then throws before finishing the payload. (a) Where must `complete` start and become true? (b) Must you catch the exception before `finally` runs, and what reaches the subscriber?
*Key: see L01-C2 under Answer keys.*

### CARD L01-R2 · Recall and repair
Q: Prepare the independent exercise (this overwrites `labs/l01/work/Ownership.java`). One line in `resolve`, marked `TODO(L01-R2)`, mishandles the failure path. Repair it from memory and run the check. The first run prints `MISS` with `failed encoder must abort, not publish partial bytes`; the repaired file prints `HIT`. Then explain in one or two sentences how an aborted claim differs from a claim that is never resolved.

```bash
checks/l01/prepare.sh independent
printf '%s\n' labs/l01/work | checks/chk_l01_ipc.sh
```

*Key: see L01-R2 under Answer keys.*

### CARD L01-T2 · Use the rule
Q: Judgement call. In a fictional matching engine, the output stage claims space before it validates an order. An AI assistant wrote this, and it passes a test that injects one bad order:

```java
public void encode(BufferClaim claim, byte[] payload) {
    try {
        claim.buffer().putBytes(claim.offset(), payload);
        validate(payload);   // throws IllegalArgumentException on a bad order
        claim.commit();
    } catch (Exception e) {
        claim.abort();
        log.warn("rejected order", e);
    }
}
```

One or two sentences each: (a) What do you check first? (b) What makes you reject it? (c) Which test would settle it?
*Key: see L01-T2 under Answer keys.*

## Optional operations — one 15-minute case

`labs/l01/ops/README.md` has three short cases: controlled redelivery, a handler
that throws, and the single-owner guard. Pick one; the rest are reference.

## Close

Remember: a claim is a reservation, not a delivery. Every successful claim ends in
exactly one commit or one abort; a success flag decides which, and the exception
still reaches the caller. The driver brings back your recall and transfer cards
tomorrow. Next, lesson 2: `offer` says no, and a message arrives in pieces.

---

# Lesson 02 — “Sent” is a local result

A gateway prints "sent" for order zero. The number `offer` returned was -1. Order
zero never left the building, and the log line lied. Today you learn to read that
minus sign, and to put a message that arrives in three pieces back together.

By the end you can: classify all five negative `offer` results; write a bounded
retry that keeps the exact bytes; and reassemble fragmented messages.

Time: about 48 minutes in two sessions of about 24 minutes each. You need lesson 1:
publication, subscription, position, fragment and claim.

## Session A — Keep the rejected bytes, about 24 minutes

### CARD L02-P1 · Before you learn
Q: Guess. The subscriber process is already running. (a) Must the first UDP `offer` succeed? (b) `offer` returns -1 for order zero. What should the sender do with order zero's bytes?
*Key: see L02-P1 under Answer keys.*

### CONCEPT L02-RETRY · The receipt has a minus sign
Prediction: A sender meets the offer results -1, -2, -3, then 64, and it may try at most 4 times. How many calls does it make, and may the payload change between calls? Commit before opening the worked example.

Worked:
Why doesn't `offer` simply wait until it can send? Aeron's design principles
answer that: use non-blocking IO in the message path, because the scheduler's
cost to wake a thread blocked on IO can be greater than the round trip on a fast
local network, and a blocked thread cannot do other work while it waits. So
`offer` hands you a number at once, and the decision about waiting is yours.

`offer` never blocks (upstream docs, checked at tag 1.54.1). It returns either the new stream **position** (a positive
byte count, lesson 1) or one of five negative codes. Each code is a constant on
`Publication`:

```text
code  name                    what happened                              this lab's policy
-1    NOT_CONNECTED           no subscriber connected (yet)              retry
-2    BACK_PRESSURED          connected, but publication progress is currently limited   retry
-3    ADMIN_ACTION            internal housekeeping, e.g. term rotation  retry
-4    CLOSED                  this publication is closed                 stop
-5    MAX_POSITION_EXCEEDED   maximum-position exhaustion prevents further progress   stop
```

The driver maintains a publisher limit that gates further publication progress. It is not a promise that every accepted message ends below that number. When publication cannot advance, the result distinguishes connected back pressure, disconnection and maximum-position exhaustion. A slow consumer can hold progress back, but `BACK_PRESSURED` alone does not identify the cause.

**Admin action** happens at the end of a term. If the message does not fit in what
is left of the current term, Aeron fills the rest with padding, moves to the next
term, and returns -3. The next try normally succeeds.

The upstream docs say a closed publication "should no longer be used". After -5
it "should be closed and a new one added". So -4 and -5 are terminal for this
publication: retrying them only burns the **attempt budget**, the most times
the lab's `send` may call `offer`. The lab helper also stops on
any other negative number, and returns the failure to its caller.

```text
same bytes --offer--> -1/-2/-3 --idle, budget left?--> same bytes again
                      -4/-5/unknown -----------------> return the failure to the caller
                      positive position -------------> accepted by the local publication
```

<details><summary>Worked result — open after your prediction</summary>

Four calls, three idles, the same 14 bytes every time:

```text
call 1: -1  retry, idle
call 2: -2  retry, idle
call 3: -3  retry, idle
call 4: 64  return 64
```

64 is the position after one 14-byte message on a fresh stream: 32 header bytes
plus 14 payload bytes, rounded up to 64 (Session B explains the rounding). The
policy check runs six more tapes too: `[-4,64]` must stop after one call with
-4, and `[-2,-2,-2]` must give up after three calls and return -2.

The live check makes this real. It starts the sender before the subscriber, so
the first offer must return -1. The sender then waits for the subscriber and
retries the same bytes.

Common wrong belief: "the subscriber process is up, so -1 can't happen". UDP
needs a connection setup first. Another: "a positive result means the order was
executed". It only means the local publication accepted the bytes.

Rule: retry -1, -2 and -3 with the same bytes inside a budget; stop on everything else negative.
</details>

Elaborate: why must every retry offer the same bytes, and why does a retry loop need a budget at all?

### CARD L02-C1 · Try it
Q: Prepare the lab (this overwrites `labs/l02/work/Transport.java`). In `send`, the retry condition lists three codes; the one marked `TODO(L02-C1)` is the wrong code. Replace it with the third temporary code and run the policy check. The first run prints `MISS` with `expected rc=64 calls=4, observed rc=-3 calls=3`; the repaired file prints `HIT`. Then answer in one sentence: why must order zero keep the same bytes across retries?

```bash
checks/l02/prepare.sh completion
printf '%s\n' labs/l02/work | checks/l02/policy.sh
```

*Key: see L02-C1 under Answer keys.*

### CARD L02-R1 · Recall and repair
Q: Close the lesson. Prepare the starter (this overwrites `labs/l02/work/Transport.java`). (a) The success test in `send`, marked `TODO(L02-R1)`, accepts results it should not. Repair it from memory and run the policy check; the first run prints `observed rc=-1 calls=1`, and the repair prints `HIT`. (b) In one sentence: why is retrying `CLOSED` forever wrong?

```bash
checks/l02/prepare.sh starter
printf '%s\n' labs/l02/work | checks/l02/policy.sh
```

*Key: see L02-R1 under Answer keys.*

## Judgement: retry, drop, or push back?

A negative result hands you a decision. There are three honest answers:

```text
answer             costs             right when                wrong-pick smell
retry, same bytes  sender time       every message matters     no budget; retries -4/-5
drop and count     data, on purpose  newer data replaces it    an order adapter drops
fail to caller     caller must cope  the source can slow down  logged, then forgotten
```

Choose per stream, write the choice down, and make -4 and -5 loud whichever you pick.

### CARD L02-T1 · Use the rule
Q: Judgement call. Fictional system: a telemetry adapter may drop stale samples, but an order adapter must never lose a request. An AI assistant wrote this send path for the telemetry adapter:

```java
long result = publication.offer(buffer, 0, length);
if (result < 0) {
    droppedSamples.increment();   // stale telemetry: the next sample replaces it
}
```

One or two sentences each: (a) Is it acceptable for telemetry? (b) Which results would you want it to treat differently, and why? (c) Would you accept the same code in the order adapter?
*Key: see L02-T1 under Answer keys.*

## Session B — A fragment is not an order, about 24 minutes

### CARD L02-P2 · Before you learn
Q: Guess. This lab's publication carries at most 1,376 payload bytes per frame. (a) You `offer` a 4,097-byte message. Will a plain fragment handler receive it as one piece? (b) Can `tryClaim(4097, claim)` split it for you?
*Key: see L02-P2 under Answer keys.*

### CONCEPT L02-FRAME · Three parcels, one order
Prediction: After two 14-byte messages, the sender offers one 4,097-byte message. How many fragments arrive, how long is each payload, and what position follows the big message? Commit before opening the worked example.

Worked:
Why does Aeron talk in messages at all? In a 2018 InfoQ podcast, Thompson
described TCP as a byte stream from A to B that does not know where messages
begin and end, and Aeron as TCP plus the ability to frame messages. Framing
means Aeron marks where each message starts and stops. That is the job of the
begin and end flags below.

Four words first:

- **MTU** (maximum transmission unit): the largest frame Aeron sends, header
  included. This lab's channel sets `mtu=1408`.
- **Frame header**: 32 bytes in front of every frame. So the **max payload** per
  frame is MTU − 32 = 1408 − 32 = 1,376. `maxPayloadLength()` returns that.
- **Alignment**: every frame starts on a multiple of 32 bytes, so a frame's space
  is rounded up. A 14-byte message takes 32 + 14 = 46 bytes, rounded up to 64.
  The alignment is still 32; only the frame was rounded.
- **Fragment**: one frame's worth of a larger message. Flags in the header mark
  the begin and end fragments.

`offer` splits a message bigger than the max payload into fragments. `tryClaim`
cannot split: it throws `IllegalArgumentException` above the max payload. A whole message also has a ceiling, `maxMessageLength()`: the smaller of one eighth of the term length and 16 MiB (in the pinned jar: `LogBufferDescriptor.computeMaxMessageLength` and `FrameDescriptor.MAX_MESSAGE_LENGTH` = 16777216). For this lab’s 64 KiB terms, the ceiling is 8,192 bytes.

To get whole messages back, wrap your handler in a `FragmentAssembler`. It passes
small messages straight through without copying. It copies fragments into a
buffer per session, and calls your handler once the end fragment arrives.

```java
FragmentHandler whole =
    (buffer, offset, length, header) -> { /* one complete message */ };

FragmentAssembler assembler = new FragmentAssembler(whole);
// Construct once, outside the polling loop; reuse on every poll.
subscription.poll(assembler, 10);
```

Keep this assembler for the subscription’s polling lifetime; a message can span several calls to `poll`.

There is a second reason to build it once. The first of Aeron's design
principles is "garbage free in steady state running", because garbage
collection pauses are one of the biggest causes of latency in a virtual
machine. A new assembler on every poll would throw away half-built messages and
make fresh garbage each time.

<details><summary>Worked result — open after your prediction</summary>

The large message occupies three fragments:

```text
message      payload   flags        frame space              position after
14 bytes        14     begin+end    32+14=46   -> 64            64
14 bytes        14     begin+end    32+14=46   -> 64           128
4,097 part 1  1,376    begin        32+1376    = 1408         1,536
4,097 part 2  1,376    (middle)     32+1376    = 1408         2,944
4,097 part 3  1,345    end          32+1345=1377 -> 1408      4,352
```

The live check sends five messages, ID 2 being the 4,097-byte one. Without an
assembler the receiver counts 7 callbacks (four small, three pieces), and the check
says `expected=5 messages actual=7 messages`. With the assembler it gets 5 complete
messages of lengths 14, 14, 4097, 14, 14.

With a plain fragment handler, one callback carries one whole message when its length is at most `maxPayloadLength()`. Larger messages need assembly.
Assembly is per session, so pieces from two publishers never mix. Do not invent
an order between different sessions.

Rule: payload per frame is MTU minus 32; frames round up to 32; wrap the handler to get whole messages.
</details>

Elaborate: why can a handler that treats each callback as one order pass with small messages and still fail with a large one?

### CARD L02-C2 · Try it
Q: Prepare the independent exercise (this overwrites `labs/l02/work/Transport.java`). Before editing, predict in one sentence why returning the handler unchanged fails a whole-message comparison. Then, in `receiver`, replace the line marked `TODO(L02-C2)`, `return completeMessage;`, with a line that returns a wrapper that reassembles fragments. Run the live check. The first run prints `MISS` with `expected=5 messages actual=7 messages`; the repair prints `HIT`.

```bash
checks/l02/prepare.sh independent
printf '%s\n' labs/l02/work | checks/chk_l02_udp.sh
```

*Key: see L02-C2 under Answer keys.*

### CARD L02-R2 · Recall and repair
Q: Close the lesson. Prepare the starter again (this overwrites your work file). (a) Repair both marked lines, `TODO(L02-R1)` in `send` and `TODO(L02-R2)` in `receiver`, from memory, and run the live check until it prints `HIT`. (b) In one sentence: why does "one callback means one order" fail here? (c) Calculate the frame space, in bytes, of a 14-byte message.

```bash
checks/l02/prepare.sh starter
printf '%s\n' labs/l02/work | checks/chk_l02_udp.sh
```

*Key: see L02-R2 under Answer keys.*

### CARD L02-T2 · Use the rule
Q: Fictional design: your messages carry a 24-byte application header plus up to 1,000 payload bytes. Use this lab's numbers: MTU 1,408, frame header 32 bytes, max payload 1,376, max message 8,192. (a) Does the largest message fit in one Aeron frame? Show the sum. (b) A 3,000-byte message turns up one day. What must the receiver do before decoding your header?
*Key: see L02-T2 under Answer keys.*

## Optional operations — 15 minutes

`labs/l02/ops/README.md` has a guided case on terminal codes and a short
reference on frame and term size rules.

## Close

Remember: a negative `offer` result means "not taken", and you still own the
bytes. Retry -1, -2 and -3 with the same bytes within a budget; stop on -4, -5 and
unknown codes. Frames carry MTU minus 32 bytes and round up to 32; reassemble
before you compare messages. Tomorrow brings back lessons 1 and 2 mixed. Next:
lesson 3, finding where a stuck message is waiting.

---

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
*Key: see L03-P1 under Answer keys.*

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

*Key: see L03-COMP1 under Answer keys.*

### CARD L03-R1 · Recall and repair
Q: (a) Run the full check on the workspace you repaired in L03-COMP1. When this card returns on a later review or exam, repair a fresh copy of the completion exercise instead; in driver mode, use the folder the driver names. Make that copy once per attempt, keep editing it if you resume, and do not rerun `prepare.sh` over your earlier work. The check runs the trace, a live paused consumer, a live dropped frame and a stalled driver, and reports `HIT` when all pass. (b) From memory: give the inequality for this lab’s GAP label, the inequality for consumer lag, and the identity both counters must share. (c) In one sentence: why is a successful rerun stronger evidence than a new counter screenshot?

```bash
printf '%s\n' labs/l03/work | checks/chk_l03_diag.sh
```

*Key: see L03-R1 under Answer keys.*

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
*Key: see L03-T1 under Answer keys.*

## Session B — A dead dashboard can look beautifully caught up, about 23 minutes

### CARD L03-P2 · Before you learn
Q: Guess. The driver writes a timestamp, its **heartbeat**, into the CnC file while it works. All three positions are equal, but the heartbeat is old. (a) Is the image healthy and drained? (b) What should you check before trusting those positions?
*Key: see L03-P2 under Answer keys.*

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
*Key: see L03-COMP2 under Answer keys.*

### CARD L03-R2 · Recall and repair
Q: Close the lesson. Prepare the independent exercise (one previous copy is saved in `labs/l03/work/backups/Diagnostics.java`). (a) Two places are marked `TODO(L03-R2)`: the staleness check is missing from `diagnose`, and the paused consumer never resumes. Repair both from memory and run the full check. The first run prints `MISS: check liveness before stale positions`; the repair prints `HIT`. (b) In one or two sentences: why does resuming polling fix lag, while a gap needs different evidence?

```bash
checks/l03/prepare.sh independent
printf '%s\n' labs/l03/work | checks/chk_l03_diag.sh
```

*Key: see L03-R2 under Answer keys.*

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
*Key: see L03-T2 under Answer keys.*

## Optional operations — 15 minutes

`labs/l03/ops/README.md` has a guided case on the client conductor's duty cycle,
plus reference cases for late joins, trace policies and bounded log inspection.

## Close

Remember: HWM above rebuilt is a reason to look for a gap; rebuilt above consumed means rebuilt data is waiting unread. Compare counters for the same image and check driver liveness. Change one thing,
then prove the drain with exact bytes. Tomorrow brings back lessons 1 to 3 mixed.
Next, lesson 4: the Archive, where "recording" must mean the bytes are really there.

---

# Lesson 04 — The recorder was on. Where is the order?

A baby monitor shows a red "recording" light but has no sound. Would you trust
the light, or play back the clip? Aeron Archive has its own red light, and today
you learn to play back the clip.

By the end you can: find a recording's real identity and range; wait until the
recording covers your last message before stopping it; replay exact bytes from a
valid start; and say what a restart test does and does not prove.

Time: about 49 minutes in two sessions of about 25 and 24 minutes. You need lessons
1 to 3: positions, fragments and the `FragmentAssembler`, counters.

## Session A — Find the recording, about 25 minutes

### CARD L04-P1 · Before you learn
Q: Guess. (a) `startRecording` returned 27. Is 27 the recording ID? (b) The Archive records a local UDP publication through a **spy**, a subscription that reads the publication's log on the same driver. There is no network subscriber, and the driver option that lets a spy count as a connection is off. Can your offers succeed?
*Key: see L04-P1 under Answer keys.*

## A true story: the backups that were never there

On 31 January 2017, an engineer at GitLab, trying to fix database
replication, removed data on the primary database server instead of the
intended one. The team reached for the regular `pg_dump` backups. The storage
bucket was empty.

The backup job had been running `pg_dump` 9.2 against a PostgreSQL 9.6
database. That version mismatch made it stop with an error. Failure emails were
sent, but the receiving mail server rejected them, so nobody knew.

Why was the procedure never tested? GitLab's own postmortem answers: there was
no ownership, so nobody was responsible. They restored from a snapshot an
engineer had made by hand about six hours earlier, and database data such as
projects, issues and snippets created in the hours after it was lost.

A job that runs is the red light. A restore you have actually tried is the clip.
This lesson's checks never trust the light: they replay every byte.

### CONCEPT L04-RECORDING · The idea: recording
Prediction: `startRecording` returns before any publication exists. Which ID is still unknown at that moment, and what must you wait for before calling `stopRecording`? Commit before opening the worked example.

Worked:
**Aeron Archive** is a service that subscribes to streams, writes them to files,
and replays them later. Four names you need:

- **Recording subscription ID**: what `startRecording(channel, streamId, LOCAL)`
  returns. It identifies the Archive's subscription. Pass it to `stopRecording`.
- **Recording ID**: identifies one recording, which is the bytes of one
  publication session. It exists only once that publication's image arrives.
- **RecordingPos**: a counter (lesson 3) per active recording, showing the
  position recorded so far. The lab finds it by session ID, then calls
  `RecordingPos.getRecordingId(counters, counterId)`.
- **Catalog**: the Archive's index file (`archive.catalog`). It stores each
  recording's start and stop positions. `getStopPosition(id)` returns
  `NULL_POSITION` (-1) while the recording is still active. The bytes live in
  segment files ending `.rec`.

`LOCAL` means the Archive runs on the same media driver as the publication.
For a UDP channel it then records through a spy. Without a network subscriber,
the publication counts as connected only if the driver option
`spiesSimulateConnection` is on. The lab's normal runs switch it on; the empty run
switches it off.

```text
startRecording ----------------> subscription ID (27 as an illustrative runtime ID)
publication image arrives -----> RecordingPos counter -> recording ID (0)
offer 5 messages, last position 4800
wait: RecordingPos >= 4800 ----> stopRecording(27) ----> wait: catalog stop != -1
```

<details><summary>Worked result — open after your prediction</summary>

The recording ID is unknown until the image arrives. Before stopping, wait until
`RecordingPos` reaches the publication's final position. Otherwise you may stop
the recording before it has written your last message. Then wait until the
catalog stop is no longer `NULL_POSITION`.

In one run, the five-message tape (14, 73, 4097, 19 and 257 bytes) ended at
position 4800, and the recording stopped at exactly 4800. The empty run, with
spy simulation off, had offer -1 and start = stop = 0. Replaying that empty range
was rejected.

Wrong belief: "a `.rec` file exists, so the order was recorded". The empty run
would pass that test too. The check instead compares the catalog range and
replays every byte, including after the Archive restarts on the same files.

Rule: ask the counter for the recording ID, wait for RecordingPos to cover your last position, then stop and wait for the catalog.
</details>

Elaborate: why is "the recording started" weaker evidence than "RecordingPos covers my last position"?

### CARD L04-C1 · Try it
Q: Prepare the completion exercise (this overwrites `labs/l04/work/ArchiveWork.java`). (a) In `recordingId`, the line marked `TODO(L04-C1)` returns -1: return the recording ID read from the supplied `RecordingPos` counter. Run the core check. The first run prints `MISS` with `Use RecordingPos recording identity, not startRecording subscription ID`; the repair prints `HIT`. (b) In one sentence: why is the subscription ID not enough?

```bash
checks/l04/prepare.sh completion
printf '%s\n' labs/l04/work | checks/chk_l04_archive.sh
```

*Key: see L04-C1 under Answer keys.*

### CARD L04-R1 · Recall and repair
Q: Close the lesson. Prepare the starter (this overwrites your work file). (a) Three methods are marked `TODO(L04-R1)`: `recordingId`, `recordingComplete` and `stopped`. Repair them from memory and run the core check until it prints `HIT`. (b) Name the two waits that stop "accepted" from passing for "recorded and stopped".

```bash
checks/l04/prepare.sh starter
printf '%s\n' labs/l04/work | checks/chk_l04_archive.sh
```

*Key: see L04-R1 under Answer keys.*

### CARD L04-T1 · Use the rule
Q: Judgement call. A fictional service's rule: acknowledge a request only once it is in the recording. An AI assistant wrote:

```java
long position = publication.offer(buffer, 0, length);
if (position > 0 && Files.exists(segmentFile)) {
    acknowledge(requestId);   // "it's in the recording now"
}
```

One or two sentences each: (a) Does it meet the rule? (b) What would you check before acknowledging? (c) Propose an acceptance test, stating the sync setting you assume.
*Key: see L04-T1 under Answer keys.*

## Session B — Replay the right bytes, about 24 minutes

If you stopped after session A: before the next card, recall the difference
between the subscription ID and the recording ID. `prepare.sh` overwrites only
`labs/l04/work/ArchiveWork.java`.

### CARD L04-P2 · Before you learn
Q: Guess, one line each. A recording starts at position 4096. (a) Is replaying from 0 safe? (b) Is every position that is a multiple of 32 a valid replay start? (c) The Archive process restarts and replays perfectly. Does that prove the data survives a power cut?
*Key: see L04-P2 under Answer keys.*

### CONCEPT L04-RECOVERY · The idea: recovery
Prediction: The recording starts at 4096. Its first 14-byte payload produces a 46-byte frame occupying 64 aligned bytes. Is 4128 (start + 32) a valid replay start? And what about 4097? Commit before opening the worked example.

Worked:
To replay, call `archive.replay(recordingId, position, length, channel,
streamId)`. It returns a `Subscription`, and you poll it as usual. The lab replays
the whole range, from the catalog start for `stop - start` bytes. A replay
delivers fragments, so wrap the handler in a `FragmentAssembler` before comparing
whole messages.

A replay start must pass three rules in the pinned Archive:

```text
rule                                   bad example (start 4096, stop 8896)   result
a multiple of 32                       4097                                  error naming FRAME_ALIGNMENT
inside [start, stop]                   4064                                  INVALID_POSITION
the beginning of a real frame          4128 (inside the first frame, whose aligned space ends at 4160) INVALID_POSITION
```

Alignment is the ruler, not the map: it tells you where frames *may* start, not
where they *do*.

What does "saved" mean? Two settings say how hard the Archive pushes writes to disk:

```text
level  recording files (fileSyncLevel)   catalog (catalogFileSyncLevel)
0      normal writes                     normal writes
1      sync file data                    sync file data
2      sync data and metadata            sync data and metadata
```

The catalog level may not be lower than the file level; the Archive refuses to
start otherwise. This lab uses 0 and 0, and its config check proves that file level 1 with
catalog level 0 is rejected.

`ReplayMerge` is for catching up from a replay toward a live stream. The pinned version rejects IPC. This course tests only that rejection; it does not test a successful handover to the live stream, and it does not teach a complete recipe for running one.

<details><summary>Worked result — open after your prediction</summary>

Neither. 4128 is a multiple of 32 but lands inside the first frame, so it fails
with `INVALID_POSITION` (16). 4097 is not a multiple of 32, so it fails with an
error naming `FRAME_ALIGNMENT`. The valid start is 4096, the catalog start. The
nonzero fixture sets that initial position on purpose. The five messages then
end at 8896 (4096 + 4800), and all five replay byte for byte.

The restart part of the core check stops the Archive and starts it again on the
same files. The exact replay afterwards shows recovery from a process restart
with sync level 0. It is not a power-cut experiment.

Wrong belief: "aligned means valid". Only a frame start from the catalog, or one
you know is a real frame, is safe.

Rule: replay from the catalog start, assemble fragments, and report the sync levels instead of claiming durability.
</details>

Elaborate: why does passing the 32-byte alignment test still not make an offset a valid replay start?

### CARD L04-C2 · Try it
Q: Paper exercise, one short line each. (a) While recording, the catalog stop is ____. (b) Live recording progress comes from ____. (c) After stopping, replay begins at ____. (d) Why would checking that a `.rec` file exists miss this lab's empty recording?
*Key: see L04-C2 under Answer keys.*

## Judgement: three ways to read a stream

A consumer that wakes up late has three tools, and each answers a different
question:

```text
tool               gives you                                         right when
live subscription  data from around its join; history not promised   only what happens next matters
replay             recorded bytes from a valid start                 you need a known recorded range
ReplayMerge        replay, then a handover to live (UDP only)        a known recorded range, then live
```

The smell to watch for: a recovering consumer that subscribes live and assumes
it saw everything. Lesson 3's reopened subscriber missed `id4` exactly that way.
This course runs the first two; for `ReplayMerge` it only shows the IPC
rejection.

### CARD L04-R2 · Recall and repair
Q: From memory, one or two lines each: (a) the replay procedure, start to finish; (b) how you debug a replay start that is a multiple of 32 but rejected; (c) what the restart experiment establishes about durability.
*Key: see L04-R2 under Answer keys.*

### CARD L04-T2 · Use the rule
Q: Your recovering consumer gets a recording that starts at 4096 and contains fragmented messages. Prepare the independent exercise (this overwrites your work file). (a) Repair the two methods marked `TODO(L04-T2)` from memory and run the check. The first run prints `MISS` with `Discover catalog start; do not assume zero or skip first frame`; the repair prints `HIT`. (b) In one or two sentences: why is an implementation that starts at zero and counts fragments not enough?

```bash
checks/l04/prepare.sh independent
printf '%s\n' labs/l04/work | checks/l04/nonzero.sh
```

*Key: see L04-T2 under Answer keys.*

### CARD L04-INT · Put it together
Q: Fictional incident: an order's `offer` returned a positive position, its RecordingPos is behind that position, and no reply has come. (a) What can you honestly tell the order's owner? (b) Which observation would you collect next?
*Key: see L04-INT under Answer keys.*

## Optional operations — 15 minutes

`labs/l04/ops/README.md` has a guided case on replay deadlines and a table of
runnable reference checks (configuration, isolation, checksums, archive tool).

## Close

Remember: the subscription ID is not the recording ID; wait for RecordingPos to
cover your last position before you stop; replay from the catalog start and
assemble fragments; and a restart test is not a power-cut test. Tomorrow brings
back lessons 1 to 4 mixed. Next, lesson 5: three replicas and one clock.

---

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
*Key: see L05-P1 under Answer keys.*

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
*Key: see L05-C1 under Answer keys.*

### CARD L05-R1 · Recall and repair
Q: Without looking back, run the full check on your repaired workspace. First time: submit `labs/l05/work` as it is. When this card returns on a later review or exam, repair a fresh copy of the completion exercise instead; in driver mode, use the folder the driver names. Make that copy once per attempt, keep editing it if you resume, and do not rerun `prepare.sh` over your earlier work. Then answer: (a) state the three conditions for replicas to agree; (b) name two ways to break them; (c) why must a replay check compare state at the same barrier, not just which members are up?
```bash
printf 'labs/l05/work\n' | checks/chk_l05_cluster.sh
```
*Key: see L05-R1 under Answer keys.*

### CARD L05-T1 · Use the rule
Q: In an imagined matching service, prices depend on a fee table and on time. Answer in three short parts: (a) how should a fee-table update and a time tick reach the matching logic? (b) what would you compare after a fresh replay? (c) what would a replay test still not prove?
*Key: see L05-T1 under Answer keys.*

## Session B — A fence, a bad record, and a fake success (about 24 min)

Start here after a break. Warm-up from memory: in lesson 04, what is the difference between "recorded"
and "applied by the application"?

### CARD L05-P2 · Before you learn
Q: (a) A member has appended two records, but only the first is committed. How many may its service apply? (b) On a follower, `session.offer` returns 1. Does that prove a reply went over the network? One sentence each; guess if unsure.
*Key: see L05-P2 under Answer keys.*

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
*Key: see L05-C2 under Answer keys.*

### CARD L05-R2 · Recall and repair
Q: Without opening `labs/l05/solution`, repair the two remaining faults in the independent workspace. They are not marked; the check names the first failing criterion. Work in loops: guess → read the first failing message → one edit → rerun. (On a later review day, run `bash checks/l05/prepare.sh independent` first and redo all four repairs from memory.) Then answer: (a) why must invalid input leave all state unchanged? (b) why does an order-sensitive digest need a defined traversal?
```bash
printf 'labs/l05/work\n' | checks/chk_l05_cluster.sh
```
The check compiles your file, runs the focused probes, starts a three-member
cluster, checks every transition and barrier 900, restarts for a fresh replay,
then sends one more command and barrier 901. Logs stay under the `artifacts`
path it prints.
*Key: see L05-R2 under Answer keys.*

### CARD L05-T2 · Use the rule
Q: An imagined auction service runs on Aeron Cluster. During replay it sees a timestamp lower than the previous one, so you ask an AI assistant to fix it. It proposes this patch inside `onSessionMessage`, and its test checks only the final count:
```java
long now = System.currentTimeMillis();      // "logged time went backwards; use a real clock"
if (now > lastTime) lastTime = now;
long result = session.offer(replyBuffer, 0, replyLength);
tradeId = tradeId * 31 + result;            // "mix in the reply result so IDs stay unique"
```
Answer in three parts: (a) accept or reject the patch, and why? (b) what check would you write to settle it? (c) what would that check still not prove?
*Key: see L05-T2 under Answer keys.*

## Close

Remember: state changes only from the log; reject before mutating and fix
every order; a positive egress result is not a delivery receipt. Your recall and transfer cards return tomorrow. Next, lesson 06: the reply goes missing and the leader
dies. Optional: the 15-minute clock case in `labs/l05/ops/README.md`.

---

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
*Key: see L06-P1 under Answer keys.*

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
*Key: see L06-C1 under Answer keys.*

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
*Key: see L06-R1 under Answer keys.*

### CARD L06-T1 · Use the rule
Q: An imagined order gateway sends orders to a cluster. An acknowledgement vanishes, then the gateway restarts. Answer in three parts: (a) what must the gateway keep, and what does it do after restart? (b) A separate sequencer acknowledges an order before its replacement has recorded it. The writer then dies. Does this lesson’s client-retry test prove that the replacement can recover that order or prevent the old writer from publishing after restart? (c) what about a consumer that sends emails?
*Key: see L06-T1 under Answer keys.*

## Session B — One lonely leader cannot sign for the committee (about 25 min)

Start here after a break. Warm-up from memory: in lesson 05, what does a
barrier prove that "all members are up" does not?

### CARD L06-P2 · Before you learn
Q: All earlier work has finished (barrier 900 ran everywhere). Then two of the three members stop. (a) If the client's `offer` for a new barrier returns positive, does that prove the barrier ran? (b) An old log line says node 1 is "LEADER". Is that enough to pick the node to act on now? One sentence each; guess if unsure.
*Key: see L06-P2 under Answer keys.*

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
*Key: see L06-C2 under Answer keys.*

### CARD L06-R2 · Recall and repair
Q: Without opening `labs/l06/solution`, run the full failover check on your independent repair. (On a later review day, run `bash checks/l06/prepare.sh independent` first and repair from memory.) If it misses, loop: guess → find the first failing event in the message → one edit → rerun. Then answer: (a) why must quorum be removed only after earlier work has drained, using a new barrier? (b) what does a watchdog expiry tell you?
```bash
printf 'labs/l06/work\n' | checks/chk_l06_failover.sh
```
*Key: see L06-R2 under Answer keys.*

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
*Key: see L06-T2 under Answer keys.*

## Close

Remember: one stable key per business action, looked up before mutating; a
positive offer is not a reply; progress is a new barrier after a functioning quorum commits it.
Your recall and transfer cards return tomorrow. Next, lesson 07: restart from a snapshot
without losing the receipt book. Optional: the 15-minute pause case in
`labs/l06/ops/README.md`.

---

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
*Key: see L07-P1 under Answer keys.*

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
*Key: see L07-C1 under Answer keys.*

### CARD L07-R1 · Recall and repair
Q: Without looking back, run the live snapshot check. First time: submit `labs/l07/work` as it is. When this card returns on a later review or exam, repair a fresh copy of the completion exercise instead; in driver mode, use the folder the driver names. Make that copy once per attempt, keep editing it if you resume, and do not rerun `prepare.sh` over your earlier work. Then answer: (a) list every field the snapshot must hold; (b) which observation shows that only the suffix was replayed? (c) which observation shows the receipt book survived?
```bash
printf 'labs/l07/work\n' | checks/chk_l07_snapshot.sh
```
Allow roughly 7 seconds as a planning estimate; runtime varies by machine. The check follows the five steps in the explanation above.
*Key: see L07-R1 under Answer keys.*

### CARD L07-T1 · Use the rule
Q: For an imagined matching engine, plan recovery of the order book, pending cancels, request IDs and fee config. Answer in three parts: (a) what must the snapshot capture, and at what point? (b) what test would expose a forgotten receipt book? (c) your lab's snapshot check passes; how would you describe this plan's status for the engine, and why?
*Key: see L07-T1 under Answer keys.*

## Session B — Refuse the wrong recipe before cooking (about 25 min)

Start here after a break. Warm-up from memory: in lesson 06, what does a
positive local offer prove, and what does a reply prove?

### CARD L07-P2 · Before you learn
Q: (a) A cut-off snapshot decodes count fine, then fails halfway through the receipt book. Is it acceptable to keep the new count with the old receipts? (b) Does taking a snapshot delete the older log? One sentence each; guess if unsure.
*Key: see L07-P2 under Answer keys.*

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
*Key: see L07-C2 under Answer keys.*

### CARD L07-R2 · Recall and repair
Q: Without opening `labs/l07/solution`, run both recovery paths on your independent repair (one command runs the snapshot check, then the no-snapshot check). On a later review day, run `bash checks/l07/prepare.sh independent` first and repair from memory. Then answer: (a) what equation joins snapshot recovery to full replay? (b) what must the snapshot contain, and what must match after recovery? (c) name one durability conclusion this test cannot support.
```bash
printf 'labs/l07/work\n' | checks/l07/both-recovery-paths.sh
```
The command runs both recovery checks; when both pass, it prints the last check’s JSON result and one HIT line. The runtime varies by machine.
*Key: see L07-R2 under Answer keys.*

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
*Key: see L07-T2 under Answer keys.*

## Close

Remember: a snapshot holds everything the next command depends on, receipts
included; decode into scratch and publish only when all of it is valid;
snapshot + suffix must equal full replay. Your recall and transfer cards return tomorrow.
Next, lesson 08: carry these rules to a system of your own. Optional: the
15-minute stalled-snapshot case in `labs/l07/ops/README.md`.

---

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
*Key: see L08-P1 under Answer keys.*

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
*Key: see L08-COMP1 under Answer keys.*

### CARD L08-R1 · Recall and repair
Q: Run the first command once (it replaces all three work files). In `apply`, the line `key = request` drops the origin. Repair it without opening the solution, then run the policy check until HIT. (On a later review day, prepare again and repair from memory.) Then answer: (a) why do origin 3/request 1 and origin 4/request 1 both execute, while a retry of origin 3/request 1 returns the old reply? (b) which bad frames halt the reader, and what happens to later frames? (c) what does a fresh replay of the tape produce?
```bash
bash checks/l08/prepare.sh independent
printf '%s\n' labs/l08/work | checks/l08/policy.sh
```
The first run reports MISS with `frame 5: expected APPLIED, actual CONFLICT`.
*Key: see L08-R1 under Answer keys.*

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
*Key: see L08-T1 under Answer keys.*

## Session B — Put a boundary on the promise (about 26 min)

Start here after a break; do not prepare the workspace again. Warm-up from
memory: what does a snapshot need besides the business totals?

### CARD L08-P2 · Before you learn
Q: (a) Three processes run on one laptop. Does a two-of-three rule protect against losing the laptop? (b) 300 messages were accepted but only 299 latency samples exist. May a good-looking p99 pass? (c) A p99 from this course's check looks fine on your laptop: what would make it stop meaning anything, and how would you find out? One sentence each; guess if unsure.
*Key: see L08-P2 under Answer keys.*

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
*Key: see L08-COMP2 under Answer keys.*

### CARD L08-R2 · Recall and repair
Q: With the key closed: (a) state the local, pair and quorum release conditions, including a stale epoch. (b) `labs/l08/work/performance.json` holds five samples with accepted 5 and delivered 5. Predict count, median, p99 and maximum, then run the check. Change only `delivered` to 4, predict and run again, then restore 5. Why would inventing a replacement sample not repair the evidence? (c) what does this exercise not prove?
```bash
printf '%s\n' labs/l08/work | checks/l08/performance.sh
```
With delivered = 4 the check reports MISS: `sample accounting`.
*Key: see L08-R2 under Answer keys.*

### CARD L08-T2 · Use the rule
Q: An imagined matching engine is slow, and a recovering consumer needs its history. Run the full check on your workspace. (On a delayed review or exam the driver's fresh copy has everything repaired except the `promotion` row's `"commit": "TODO"` in `migration.json`; write that one commit sentence first.) Then answer in four parts: (a) where does recovery read from? (b) under back pressure, how do a required consumer and an optional one differ? (c) why can neither a transport swap nor sharding remove serial application work? (d) which claims must you keep separate?
```bash
printf '%s\n' labs/l08/work | checks/chk_l08_sequencer.sh
```
*Key: see L08-T2 under Answer keys.*

## Return ticket

Remember: check bytes against an independent source; log identity is not
request identity; every promise names its gate, failure domain and evidence.
Your recall and transfer cards return tomorrow. The mixed course exam opens at least seven
days after this lesson. Optional: the 15-minute repair cases in `labs/l08/ops/README.md`.

---

## Answer keys

### L01-P0 · Before you learn

Key:
- (a) `main` contains those steps in this primer. Java can run class initializers before calling `main`.
- (b) `new byte[4]` allocates the bytes; `UnsafeBuffer` wraps them, and its `putByte` and `getByte` methods access them.

### L01-C0 · Try it

Key:
- Fill `2`; the read returns `11`; the first argument selects the byte offset, not the value.

### L01-R0 · Recall and repair

Key:
- The method allocates `byte[4]`, wraps that same array, calls `putByte(2, (byte)7)` and checks that `bytes[2] == 7`. The wrapper accesses the array’s storage; it is not an independent copy. `javac` compiles and `tools/java.sh` launches the program.

### L01-T0 · Use the rule

Key:
- (a) Run through `tools/java.sh`, which adds `--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED` so Agrona may use the JDK-internal class on this Java 26 setup.
- (b) The flag is right but the shell is wrong: a zsh scalar passes both words as one argument, while a Bash array used as `"${F[@]}"` passes two.

### L01-P1 · Before you learn

Key:
- (a) The application still owns the rejected bytes and must decide what to do with them.
- (b) No: in this lab an unresolved claim blocks the messages behind it; commit valid bytes or abort the claim.

### L01-C1 · Try it

Key:
- Call `claim.commit()` in the success branch; commit publishes, so it must come after all bytes are written, because until then the claim is only a reservation.
- The check prints `HIT`: exact ordered payloads, no message from the aborted claim, a held claim blocks until resolved, and the size and owner guards pass.

### L01-R1 · Recall and repair

Key:
- (a) `tryClaim` → write into `claim.buffer()` at `claim.offset()` → `commit()`; on failure, `abort()` instead.
- (b) A positive result is a reservation: subscribers stop at the claimed frame until `commit()` writes its length.
- (c) `putBytes` copies the supplied array into the claimed slot; writing fields directly into the slot would avoid that copy.

### L01-T1 · Use the rule

Key:
- (a) No: `offer` and `tryClaim` on an `ExclusivePublication` are not thread-safe. Keep one owning thread, or use `Aeron.addPublication`, which returns a thread-safe `ConcurrentPublication`.
- (b) That ring allows one writer by contract, so its test never exercised two writers; sharing a shape is not sharing a safety rule.

### L01-P2 · Before you learn

Key:
- No: `commit()` would publish the half-written order. `abort()` also frees the slot, by turning it into padding that subscribers skip.

### L01-C2 · Try it

Key:
- `complete` starts false and becomes true only after all encoding succeeds.
- No catch is required for `finally` to run. The false flag makes `resolve` abort; the failed message is not delivered, later messages can proceed, and the exception propagates.

### L01-R2 · Recall and repair

Key:
- The failure branch calls `claim.abort()` instead of `claim.commit()`; the check prints `HIT`.
- Abort turns the slot into padding, so later messages flow and the failed bytes are never delivered; an unresolved claim leaves a negative length that blocks every later message.

### L01-T2 · Use the rule

Key:
- (a) Every exit path: does each successful claim end in exactly one commit or one abort, and does the caller still learn about the failure?
- (b) It swallows the exception, so the caller thinks the order went out; and `catch (Exception e)` misses a Java `Error` such as `AssertionError`, which is not an `Exception`, so that exit leaves the claim unresolved and the messages behind it wait (until the driver's unblock timeout at best). A success flag checked in `finally` covers both.
- (c) Inject a failure after writing and assert three things: none of the failed bytes reach the subscriber, later messages still arrive, and the caller sees the exception; repeat with an `Error`.

### L02-P1 · Before you learn

Key:
- (a) No. -1 is `NOT_CONNECTED`: no subscriber is connected yet, and that state can be temporary even while the process runs.
- (b) Keep the exact bytes and retry within a bounded budget; a negative result means Aeron did not accept them.

### L02-C1 · Try it

Key:
- Replace `Publication.CLOSED` with `Publication.ADMIN_ACTION`, so -1, -2 and -3 retry while -4, -5 and unknown codes stop; the check prints `HIT`.
- A negative result means Aeron took nothing, so the caller still owns the order, and every retry must send it unchanged; the check compares the bytes on every call.

### L02-R1 · Recall and repair

Key:
- (a) Only a result of 0 or more is success (`result >= 0`); the check prints `HIT` with the right call counts and unchanged bytes.
- (b) `CLOSED` means this publication should no longer be used, so no number of retries can succeed; stop and let the caller recover with a new publication.

### L02-T1 · Use the rule

Key:
- (a) Yes for the temporary codes: dropping a stale sample on -1, -2 or -3 is a legitimate telemetry policy, and counting drops keeps the loss visible.
- (b) -4 `CLOSED` and -5 `MAX_POSITION_EXCEEDED`: this code would quietly count drops for ever on a publication that should no longer be used, so those codes should be reported for someone to replace the publication.
- (c) No: the order adapter must keep the unchanged bytes, retry -1, -2 and -3 within a budget, then report failure to its caller; and even a positive result means only that the local publication accepted the bytes, not that the order was executed.

### L02-P2 · Before you learn

Key:
- (a) No: plain polling hands you fragments, so the message must be reassembled before you compare whole-message bytes.
- (b) No: `tryClaim` rejects any length above `maxPayloadLength()`; only `offer` splits a large message.

### L02-C2 · Try it

Key:
- Unchanged, the handler sees each fragment as a message: the 4,097-byte message arrives as three pieces, so 7 callbacks instead of 5 messages.
- `return new FragmentAssembler(completeMessage);`, and the check prints `HIT`: ID 0 survives the forced rejection, IDs 0 to 4 arrive in order with lengths 14, 14, 4097, 14, 14, and every byte matches.

### L02-R2 · Recall and repair

Key:
- (a) `send` returns only on `result >= 0`, and `receiver` returns `new FragmentAssembler(completeMessage)`; the live check prints `HIT`.
- (b) A message larger than the 1,376-byte max payload arrives as several fragments, each with its own callback, until the assembler joins them.
- (c) 32 + 14 = 46, rounded up to a multiple of 32: 64 bytes.

### L02-T2 · Use the rule

Key:
- (a) Yes: 24 + 1,000 = 1,024, which is at most 1,376 (= 1,408 − 32), so it fits one frame. Your 24-byte header sits inside Aeron's payload, separate from Aeron's 32-byte frame header.
- (b) Reassemble it first with a `FragmentAssembler`, because it arrives as three fragments. `offer` can send it, being under 8,192, while `tryClaim` would reject it.

### L03-P1 · Before you learn

Key:
- No. HWM above rebuilt only shows that the receiver has seen data beyond the point where it holds complete data. That can point to a gap in what it received, but these counters alone prove neither packet loss nor a slow subscriber.
- To test for a slow subscriber, compare rebuilt with how far the subscriber has consumed, for the same channel, stream and session.

### L03-COMP1 · Try it

Key:
- (a) Return `"GAP"` when `hwm > rebuilt`, and return `rebuilt - consumed` from `lag`; the trace check prints `HIT`.
- (b) 128 − 32 = 96.
- (c) `hwm - rebuilt` is the span between those positions, not a count of lost bytes and not subscriber lag; subscriber lag is rebuilt minus consumed.

### L03-R1 · Recall and repair

Key:
- (a) The full check prints `HIT` on the repaired workspace.
- (b) GAP is HWM > rebuilt; consumer lag is rebuilt > consumed, meaning rebuilt data not yet read; both counters must share channel, stream and session. Outside this controlled fixture, GAP is only a hypothesis to test.
- (c) The rerun shows the work finished: the stream drains to its known final position with the exact expected bytes; a screenshot only shows one moment.

### L03-T1 · Use the rule

Key:
- (a) No: HWM minus consumed adds two different spans, the unreconstructed span (HWM − rebuilt) and subscriber lag (rebuilt − consumed), so it cannot name the stalled stage, and "poll faster" is wrong whenever the span is a gap.
- (b) That both counters belong to the same channel, stream and session; then compare HWM with rebuilt and rebuilt with consumed separately.
- (c) One that reports the gap span and the lag separately; the proof is naming one stage, changing only that, and showing a known final message reach its final position with exact bytes.

### L03-P2 · Before you learn

Key:
- (a) Not proven: equal positions from a driver that has stopped working are frozen, not current.
- (b) Check that you read the right Aeron directory and that its heartbeat is fresh; an old heartbeat alone does not prove the process died.

### L03-COMP2 · Try it

Key:
- (a) The driver's duty cycle is not running (in this fixture, nobody calls `invoke()`), so the positions are frozen.
- (b) That I am reading the right Aeron directory and how old its heartbeat is.
- (c) Restore the duty cycle by calling `invoke()`, without claiming the process died.
- (d) The heartbeat advances; for a data backlog I also need the stream to drain to its final position with exact bytes.

### L03-R2 · Recall and repair

Key:
- (a) The first line of `diagnose` returns `"STALE_DRIVER"` when `ageMs > staleAfterMs`, and `resumePolling` returns `true`; the full check prints `HIT`.
- (b) Lag is rebuilt data waiting to be consumed, so resuming polling reads it. A gap needs other proof: in this fixture, the injected loss is confirmed by the NAK and retransmission counters and by the stream recovering to its final position with exact bytes.

### L03-T2 · Use the rule

Key:
- (a) Being connected does not prove the subscriber got the messages published while it was away; this fixture shows IDs missing after reopening. Find the actual break in the sequence from application IDs, and recover the missing range explicitly.
- (b) Tell images apart by session ID (and channel), not by stream ID alone, and never merge their positions into one order.
- (c) Compare the first received application ID with the expected next ID; on a gap, request replay or a resend, then check the exact missing messages plus a known final message.

### L04-P1 · Before you learn

Key:
- (a) No. It is the registration ID of the recording subscription; the recording ID is found once the publication's image appears.
- (b) No. In this fixture the offer returns `NOT_CONNECTED` (-1), and the recording's start equals its stop; starting a recording did not connect the publication.

### L04-C1 · Try it

Key:
- (a) `return RecordingPos.getRecordingId(counters, counter);` and the core check prints `HIT`.
- (b) The subscription ID names the Archive's subscription; a recording belongs to one publication session and gets its own ID when that image arrives.

### L04-R1 · Recall and repair

Key:
- (a) `recordingId` reads `RecordingPos.getRecordingId(counters, counter)`, `recordingComplete` needs `active && position >= target`, and `stopped` needs `catalogStop != AeronArchive.NULL_POSITION`; the check prints `HIT`, including exact replay after the publisher exits and after the Archive restarts.
- (b) Wait until RecordingPos reaches the final offered position, then stop and wait until the catalog stop is set; while recording, the stop is `NULL_POSITION`, which does not mean empty.

### L04-T1 · Use the rule

Key:
- (a) No: a positive offer means only that the local publication accepted the bytes, and a `.rec` file can exist with nothing in it, as this lab's empty run shows; neither shows the request is in the recording.
- (b) The RecordingPos of the matching recording ID: acknowledge only once it reaches the position this offer returned.
- (c) Pause recording progress, send a known request, check that the acknowledgement is withheld, resume, then replay and compare the exact request; state the file sync level you assume separately.

### L04-P2 · Before you learn

Key:
- (a) No: read the start from the catalog; 0 is before this recording begins.
- (b) No: a multiple of 32 can still point inside a frame.
- (c) No: a process restart is not a power cut. What survives a power cut depends on the file sync settings; state those settings rather than claim the test proved it.

### L04-C2 · Try it

Key:
- (a) `NULL_POSITION` (-1).
- (b) The `RecordingPos` counter, or `getRecordingPosition(id)`.
- (c) The start position read from the catalog.
- (d) The empty run accepted no message (offer -1) and has start = stop, so a file can exist with nothing in it; only the range and a full byte replay show content.

### L04-R2 · Recall and repair

Key:
- (a) Find the recording ID and its start and stop, wait until recording is complete, replay that range through `AeronArchive.replay`, assemble fragments, and compare whole messages in order.
- (b) Hypothesis: the position is inside a frame. Evidence: the catalog range and `INVALID_POSITION`. Intervention: replay from a known frame start such as the catalog start, then rerun the exact comparison.
- (c) It shows this run recovers after a process restart; it is not a power-cut test, so report the file and catalog sync levels separately.

### L04-T2 · Use the rule

Key:
- (a) `replayStart` returns `catalogStart`, and `receiver` returns `new FragmentAssembler(complete)`; the check prints `HIT` with exact bytes and the bad offsets rejected.
- (b) A recording need not start at zero, and fragments are not messages: counting callbacks can hide a truncated or altered message, so compare every whole message's bytes.

### L04-INT · Put it together

Key:
- (a) The local publication accepted the bytes; the recording has not yet been shown to reach that position, and nothing here shows execution or a business reply.
- (b) The matching recording ID and its RecordingPos compared with the offered position, plus the application's own acknowledgement rule.

### L05-P1 · Before you learn

Key:
- No: identical input bytes do not control the extra local inputs (the config file and the clock), so if those affect state, the copies can differ.
- A replay needs the same starting state, the same inputs in the same order (including any configuration or time that changes state), and deterministic processing: the same inputs always give the same result.

### L05-C1 · Try it

Key:
- Make `eventTime` return the logged timestamp and `multiplier` return the logged config; the check then prints HIT.
- Local config cannot affect the results because the service now uses only values that arrived through the ordered log; node-local values can differ between replicas and between a run and its replay.

### L05-R1 · Recall and repair

Key:
- Your workspace passes the live cluster and replay check.
- (a) The same starting state, the same state-changing inputs in the same order, and deterministic processing.
- (b) Any two of: reading config that is not in the log, reading the local clock, or walking a collection in an order that differs between replicas when that order changes the output.
- (c) The barrier is one shared point in the log, so states compared there come from the same processed prefix; which members are up, or how far each has appended, does not show equal applied state.

### L05-T1 · Use the rule

Key:
- (a) Send the fee-table update and the time tick through the log as inputs, ordered before the orders that depend on them; do not read a fee file that can change outside the log during matching.
- (b) Replay the same logged inputs, with their timestamps, into fresh state, and compare the output bytes and the state digest at an identified point in the log, such as a barrier; vary the external config on purpose.
- (c) A fresh replay alone does not show recovery after the leader fails, or how requests whose replies were lost get reconciled; test those failure paths separately. This is a proposal for your own service; the course fixture does not prove how that service behaves.

### L05-P2 · Before you learn

Key:
- (a) Only the committed prefix, here the first record: the service polls the log only up to the commit-position counter.
- (b) No: a follower can return a mocked success without actually sending the reply over the network.

### L05-C2 · Try it

Key:
- Make `commitLimit` return the committed position as the poll limit, and make `egressSucceeded` test `result > 0`; both focused checks then pass.
- Appended says how far this node has written the log; commit permits the service to read up to that position and no further; a positive egress result means the leader handed the reply to the egress stream, or a follower returned its mocked 1, and it does not prove delivery.

### L05-R2 · Recall and repair

Key:
- Your work passes the complete live and replay check and both focused policy checks.
- (a) Validate before changing anything, so the bad record leaves state unchanged; replicas that all agree on a wrong count are still wrong.
- (b) The digest changes with the order of values, so replicas walking in different orders split; fix one order, for example by sorting. The digest compared at the shared barrier covers more than the count alone.

### L05-T2 · Use the rule

Key:
- (a) Reject it. Local time can differ across replicas and replay. On the leader, `session.offer` can return a publication position or a negative code; on a follower it returns mocked success. Neither result belongs in business state. Use the logged timestamp and derive trade IDs from logged inputs or replicated counters.
- (b) At a shared barrier, on every replica and on a replay, compare the exact input IDs in order and the full state and digest built in a defined order; the count alone misses changed totals, config or order.
- (c) A follower's success can be mocked, and this lesson does not guarantee exactly-once delivery (each message delivered once and only once). Client retries and removing duplicate requests need application logic beyond this counter fixture.

### L06-P1 · Before you learn

Key:
- (a) Unknown: the local publication accepted the request, but that alone does not tell you the application's result; keep tracking it until the reply comes.
- (b) No: keep the same client and request key across the reconnect and the resend, because a new key could stand for a second business action.

### L06-C1 · Try it

Key:
- Look up the stable client-and-request key, check that a repeated key carries the same payload, and return its original result before changing anything; the policy check then prints HIT.
- The lookup comes first so a retry cannot become a second state change; the key must also contain the client ID, because different clients may reuse the same request number.

### L06-R1 · Recall and repair

Key:
- Your work passes the live check: each unique client-and-request key changes state once, and every submitted request is acknowledged at the application level.
- (a) A successful offer leaves the ID in `pending`; it leaves only when a consistent reply settles it. A reconnect keeps the same requests and keys, because their results are still unknown.
- (b) Keep calling `pollEgress()` and `sendKeepAlive()`; resends are bounded, and a terminal offer result (`CLOSED` or `MAX_POSITION_EXCEEDED`) triggers a reconnect.

### L06-T1 · Use the rule

Key:
- (a) Store durably each order's stable request key and every submission still unresolved. After restart, match incoming results to those keys, resend the same keys, and keep the de-duplication and result state inside the replicated application.
- (b) No. Test that sequencer separately: its replacement must recover every request covered by its acknowledgement promise, and a restarted old writer must be fenced from publishing. Client deduplication establishes neither property.
- (c) A consumer with outside side effects, like emails, needs its own rules for reconciling and for idempotency (a repeated request takes effect only once); this lab proves state changes inside the application, not exactly-once delivery to outside systems.

### L06-P2 · Before you learn

Key:
- (a) No: the new barrier counts only when you see the application run it; in this live test it stalls without quorum (a majority of members, defined next) and runs after quorum returns.
- (b) No: ask the cluster now which members exist and which one leads; a role recorded earlier can be out of date after a failure or restart.

### L06-C2 · Try it

Key:
- A positive local offer must leave `pending` intact until a consistent reply comes from the application; local acceptance does not settle the request.
- The policy check passes: terminal offer results lead to a reconnect, and a duplicate reply does not count as a second acknowledgement.

### L06-R2 · Recall and repair

Key:
- Your work passes the complete failover and quorum check, returns the cached result for the lost reply, and changes state once per ID.
- (a) Wait until the known earlier barrier has run before removing quorum, then use a distinct new barrier; otherwise a callback from old committed work can pass for new progress.
- (b) A watchdog expiry reports a problem with the test setup; do not treat it as proof that no earlier request committed.

### L06-T2 · Use the rule

Key:
- (a) No. A fresh ID turns one payment into two business actions, and recording every reply counts a repeated reply twice. The smallest repair: keep the same business request key across sessions, settle repeated replies to one original result, and ask the live cluster who leads.
- (b) Assert that each unique request changed state exactly once, and assert the full application state at a shared barrier; do not assert one network reply per request.
- (c) An old role log line and a local offer result: neither is enough evidence of what the application committed.

### L07-P1 · Before you learn

Key:
- (a) No: if the keys are forgotten, the old request can run again; if the cached results are forgotten, the reply can be wrong even when nothing changes state again.
- (b) Yes: the config and the details of timers your application set affect what happens next, so recovery must restore them too.

### L07-C1 · Try it

Key:
- After validation, copy the complete decoded result map into the live map, along with the business, config and timer fields; the codec check then prints HIT.
- Old results must survive so that a repeated old key returns its original cached count and total, instead of changing state again or reporting the current total.

### L07-R1 · Recall and repair

Key:
- (a) The check passes. The snapshot holds count, total, config, tick, the application timer's ID and deadline, the snapshot schema (magic and version), and every request key with its original result.
- (b) Snapshot recovery applies only the suffix IDs, 3 and 4, and on every replica the state and digest equal those at the barrier before the stop.
- (c) Retrying old key 1 returns its original count 1 and total 21 without changing state again; a new request then advances every replica.

### L07-T1 · Use the rule

Key:
- (a) Capture the order book, the stable request keys with their results, the ID generators, the logged config and schema, and the details of pending work the application owns, all at one point in the log (the end of one prefix).
- (b) Restore, replay a known suffix, reconnect, resend a key from before the snapshot, and assert it gets its original result with no extra state change; then check that a new request still makes progress.
- (c) Call it proposed work for your own system, not a proven property: the course check covers only the course's counter service, so the engine still needs its own snapshot field list, schema-version rule and the old-key retry test from (b).

### L07-P2 · Before you learn

Key:
- (a) No: reject the snapshot without changing live state, and never make a partial restore live.
- (b) No: taking a snapshot and keeping or deleting old log are separate operations; this check keeps the existing recording segment files.

### L07-C2 · Try it

Key:
- Decode into a separate, temporary `RequestWork`; publish only after every part has been validated. The codec check then prints HIT.
- A snapshot with an unsupported version, cut-off bytes or extra trailing bytes must be rejected without changing the existing state.

### L07-R2 · Recall and repair

Key:
- (a) Both recovery checks pass. The equation: the state restored at the snapshot position, plus the suffix applied in order, equals a full replay, compared at the same application barrier.
- (b) The snapshot holds every application field and the cache of original results; after recovery, the suffix IDs and every replica's state and digest must match.
- (c) Any one of: a process restart does not test a real power failure, data still in disk-controller caches, or any guarantee of recovery speed in production.

### L07-T2 · Use the rule

Key:
- (a) Reject it. The snapshot does not restore old receipts, and suffix replay cannot generally reconstruct them; inspect the retry handler to explain today’s-balance reply. Decode into temporary state so truncated input cannot partly overwrite live state. Accept the USD default only if the version-1 schema guarantees USD; otherwise require an explicit migration or reject the snapshot.
- (b) Save the original results and stable keys; after the suffix replays, resend a reservation from before the snapshot and assert it gets its original result with no extra state change.
- (c) Compare the full state at a named point in the log across all replicas and a no-snapshot replay; also test that new requests make progress, not only that the load message appears.

### L08-P1 · Before you learn

Key:
- Local transport acceptance alone does not prove a durable or replicated commit, so the answer to (a) is no: the acknowledgement must name its release gate, the condition that releases it.
- The answer to (b) is no: a repeated log sequence number is a log error, while a retry that got a new sequence number needs an application cache of requests to catch it.

### L08-COMP1 · Try it

Key:
- Swap the two back so the epoch is at byte 4 and kind at byte 6; the bytes then match the independent golden encoder and the check prints HIT.
- An equal decoded payload can hide a header or checksum error when the encoder and decoder share the same bug.

### L08-R1 · Recall and repair

Key:
- (a) The application key is the stable (origin, request) pair, independent of log sequence or transport session: (3, 1) and (4, 1) are different keys, so both run, and a retry of (3, 1) gets its cached reply. A different value under an existing key leaves application data unchanged and sets `halted=True`.
- (b) A repeated sequence, gap, wrong epoch, malformed frame or checksum error leaves all state unchanged except `halted=True`. Every later frame returns `HALTED` without further changes.
- (c) Fresh replay of the ordered tape reproduces total 31, three mutations, version 2, and the original cached reply of 5.

### L08-T1 · Use the rule

Key:
- (a) Yes, for these byte-identical copies. A different copy still reaches the strict reader. It halts at the first failed check: length, checksum or epoch may fail before sequence; an otherwise valid repeat returns `DUP`.
- (b) Reject the note. Log identity is (epoch, sequence) and catches repeated, skipped or wrong-epoch log entries; application identity is the stable (origin, request) pair and catches retried requests. The retry at 43 has a new sequence, so the front layer passes it and only the (origin, request) cache returns the old reply. Dropping a duplicate does not prove a quorum commit.
- (c) Sorting cannot create the missing 41. Start from a trusted checkpoint or log prefix, replay the exact frames after it in order, then verify that the switch to live frames leaves no gap.

### L08-P2 · Before you learn

Key:
- (a) No: copies on one machine share a failure domain (they die together), so this local model cannot show that the system survives losing the machine.
- (b) No: the accepted, delivered and sampled counts must match before you read anything into a percentile.
- (c) It describes only the machine and workload you measured; the lesson names no other limit, so treat the rest as unknown. Re-measure on the target machine and workload, keep the raw samples, and check the counts match. This course's check is an exercise, not a latency benchmark.

### L08-COMP2 · Try it

Key:
- Each of the six rows states its current status, acceptance, commit, acknowledgement, replay source, failure domain and a concrete test.
- Before claiming durability for a proposed gate, you need an explicit recording or replication condition, a fence against old writers, a storage policy and a failure test.
- The structural JSON check does not judge the prose; compare each row with your own system's evidence honestly.

### L08-R2 · Recall and repair

Key:
- (a) Local releases once the local copy is recorded; pair also waits for the designated secondary; quorum needs local plus either of the other two copies; a stale epoch blocks every rule.
- (b) The result is count 5, median 6, p99 10 and maximum 10. Keep the raw samples; accepted, delivered and sampled counts must match, so delivered=4 fails. For an even count, median averages the middle pair; nearest-rank p99 selects sorted sample ⌈0.99n⌉. Inventing a sample proves neither delivery nor latency.
- (c) This exercise with numbers is not a latency benchmark, and it is no proof of safety under power loss, network partitions or elections.

### L08-T2 · Use the rule

Key:
- (a) The byte, policy, model, migration and accounting checks all pass. Recovery reads from a trusted replay source that you propose, and you test that the switch to live frames leaves no gap.
- (b) A required consumer, such as a journal or state consumer, needs a bounded policy that keeps the required data; an optional one may disconnect and catch up by replay, with the loss of availability stated as an explicit tradeoff.
- (c) Eight serial inputs costing five units each still need forty units of work. Batching may reduce per-message overhead; sharding moves the ordering boundary, so work across shards needs a cross-shard ordering rule.
- (d) Keep claims about existing code, the course fixture and your proposals separate. State the commit, acknowledgement, sync policy and failure domain, and do not conclude from this exercise that failover is durable or that wire delivery is exactly once.
