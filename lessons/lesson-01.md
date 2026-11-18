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
<details><summary>Key — open after your attempt</summary>

Key:
- (a) `main` contains those steps in this primer. Java can run class initializers before calling `main`.
- (b) `new byte[4]` allocates the bytes; `UnsafeBuffer` wraps them, and its `putByte` and `getByte` methods access them.
</details>

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
<details><summary>Key — after attempt</summary>

Key:
- Fill `2`; the read returns `11`; the first argument selects the byte offset, not the value.
</details>

### CARD L01-R0 · Recall and repair
Q: From memory, sketch a `main` method that allocates four bytes, wraps them in an `UnsafeBuffer`, writes 7 at offset 2 and checks `bytes[2]`. Then name the compile command and the course’s Java launcher. Why does checking the array test something different from reading through the wrapper?
<details><summary>Key — after attempt</summary>

Key:
- The method allocates `byte[4]`, wraps that same array, calls `putByte(2, (byte)7)` and checks that `bytes[2] == 7`. The wrapper accesses the array’s storage; it is not an independent copy. `javac` compiles and `tools/java.sh` launches the program.
</details>

### CARD L01-T0 · Use the rule
Q: A teammate runs the compiled primer with plain `java` and gets `IllegalAccessError` for `jdk.internal.misc`. (a) What fixes it in this course? (b) They then store the two words `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` in one zsh variable, and Java says `Unrecognized option`. Why is that a different problem? One or two sentences each.
<details><summary>Key — after attempt</summary>

Key:
- (a) Run through `tools/java.sh`, which adds `--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED` so Agrona may use the JDK-internal class on this Java 26 setup.
- (b) The flag is right but the shell is wrong: a zsh scalar passes both words as one argument, while a Bash array used as `"${F[@]}"` passes two.
</details>

## Session A — Own the slot, about 23 minutes

### CARD L01-P1 · Before you learn
Q: Honest guess; the next concept explains it. A **claim** reserves a slot in the log that you fill before publishing. (a) An `offer` returns a negative number, so Aeron did not take your bytes. Who owns those bytes now? (b) A claim succeeds, but the code never commits it. Can the next complete message be delivered past it?
<details><summary>Key — after attempt</summary>

Key:
- (a) The application still owns the rejected bytes and must decide what to do with them.
- (b) No: in this lab an unresolved claim blocks the messages behind it; commit valid bytes or abort the claim.
</details>

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

<details><summary>Key — after coding and prediction</summary>

Key:
- Call `claim.commit()` in the success branch; commit publishes, so it must come after all bytes are written, because until then the claim is only a reservation.
- The check prints `HIT`: exact ordered payloads, no message from the aborted claim, a held claim blocks until resolved, and the size and owner guards pass.
</details>

### CARD L01-R1 · Recall and repair
Q: Without looking back: (a) sketch the claim lifecycle as a short sequence of calls; (b) explain in one sentence why a positive `tryClaim` result is not delivery; (c) say where this lab's encoder still copies bytes.
<details><summary>Key — after attempt</summary>

Key:
- (a) `tryClaim` → write into `claim.buffer()` at `claim.offset()` → `commit()`; on failure, `abort()` instead.
- (b) A positive result is a reservation: subscribers stop at the claimed frame until `commit()` writes its length.
- (c) `putBytes` copies the supplied array into the claimed slot; writing fields directly into the slot would avoid that copy.
</details>

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
<details><summary>Key — after attempt</summary>

Key:
- (a) No: `offer` and `tryClaim` on an `ExclusivePublication` are not thread-safe. Keep one owning thread, or use `Aeron.addPublication`, which returns a thread-safe `ConcurrentPublication`.
- (b) That ring allows one writer by contract, so its test never exercised two writers; sharing a shape is not sharing a safety rule.
</details>

## Session B — Make the exception path boring, about 21 minutes

Start with any due reviews. Skip pretests you have already answered. If you were
interrupted, restart at the card you had not finished.

### CARD L01-P2 · Before you learn
Q: Guess: the encoder throws after writing half an order. Should a `finally` block always call `commit()` "to keep the stream moving"? Yes or no, plus one sentence.
<details><summary>Key — after attempt</summary>

Key:
- No: `commit()` would publish the half-written order. `abort()` also frees the slot, by turning it into padding that subscribers skip.
</details>

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
<details><summary>Key — after attempt</summary>

Key:
- `complete` starts false and becomes true only after all encoding succeeds.
- No catch is required for `finally` to run. The false flag makes `resolve` abort; the failed message is not delivered, later messages can proceed, and the exception propagates.
</details>

### CARD L01-R2 · Recall and repair
Q: Prepare the independent exercise (this overwrites `labs/l01/work/Ownership.java`). One line in `resolve`, marked `TODO(L01-R2)`, mishandles the failure path. Repair it from memory and run the check. The first run prints `MISS` with `failed encoder must abort, not publish partial bytes`; the repaired file prints `HIT`. Then explain in one or two sentences how an aborted claim differs from a claim that is never resolved.

```bash
checks/l01/prepare.sh independent
printf '%s\n' labs/l01/work | checks/chk_l01_ipc.sh
```

<details><summary>Key — after repair</summary>

Key:
- The failure branch calls `claim.abort()` instead of `claim.commit()`; the check prints `HIT`.
- Abort turns the slot into padding, so later messages flow and the failed bytes are never delivered; an unresolved claim leaves a negative length that blocks every later message.
</details>

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
<details><summary>Key — after attempt</summary>

Key:
- (a) Every exit path: does each successful claim end in exactly one commit or one abort, and does the caller still learn about the failure?
- (b) It swallows the exception, so the caller thinks the order went out; and `catch (Exception e)` misses a Java `Error` such as `AssertionError`, which is not an `Exception`, so that exit leaves the claim unresolved and the messages behind it wait (until the driver's unblock timeout at best). A success flag checked in `finally` covers both.
- (c) Inject a failure after writing and assert three things: none of the failed bytes reach the subscriber, later messages still arrive, and the caller sees the exception; repeat with an `Error`.
</details>

## Optional operations — one 15-minute case

`labs/l01/ops/README.md` has three short cases: controlled redelivery, a handler
that throws, and the single-owner guard. Pick one; the rest are reference.

## Close

Remember: a claim is a reservation, not a delivery. Every successful claim ends in
exactly one commit or one abort; a success flag decides which, and the exception
still reaches the caller. The driver brings back your recall and transfer cards
tomorrow. Next, lesson 2: `offer` says no, and a message arrives in pieces.
