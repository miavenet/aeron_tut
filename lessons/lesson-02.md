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
<details><summary>Key — after attempt</summary>

Key:
- (a) No. -1 is `NOT_CONNECTED`: no subscriber is connected yet, and that state can be temporary even while the process runs.
- (b) Keep the exact bytes and retry within a bounded budget; a negative result means Aeron did not accept them.
</details>

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

<details><summary>Key — after repair</summary>

Key:
- Replace `Publication.CLOSED` with `Publication.ADMIN_ACTION`, so -1, -2 and -3 retry while -4, -5 and unknown codes stop; the check prints `HIT`.
- A negative result means Aeron took nothing, so the caller still owns the order, and every retry must send it unchanged; the check compares the bytes on every call.
</details>

### CARD L02-R1 · Recall and repair
Q: Close the lesson. Prepare the starter (this overwrites `labs/l02/work/Transport.java`). (a) The success test in `send`, marked `TODO(L02-R1)`, accepts results it should not. Repair it from memory and run the policy check; the first run prints `observed rc=-1 calls=1`, and the repair prints `HIT`. (b) In one sentence: why is retrying `CLOSED` forever wrong?

```bash
checks/l02/prepare.sh starter
printf '%s\n' labs/l02/work | checks/l02/policy.sh
```

<details><summary>Key — after repair</summary>

Key:
- (a) Only a result of 0 or more is success (`result >= 0`); the check prints `HIT` with the right call counts and unchanged bytes.
- (b) `CLOSED` means this publication should no longer be used, so no number of retries can succeed; stop and let the caller recover with a new publication.
</details>

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
<details><summary>Key — after attempt</summary>

Key:
- (a) Yes for the temporary codes: dropping a stale sample on -1, -2 or -3 is a legitimate telemetry policy, and counting drops keeps the loss visible.
- (b) -4 `CLOSED` and -5 `MAX_POSITION_EXCEEDED`: this code would quietly count drops for ever on a publication that should no longer be used, so those codes should be reported for someone to replace the publication.
- (c) No: the order adapter must keep the unchanged bytes, retry -1, -2 and -3 within a budget, then report failure to its caller; and even a positive result means only that the local publication accepted the bytes, not that the order was executed.
</details>

## Session B — A fragment is not an order, about 24 minutes

### CARD L02-P2 · Before you learn
Q: Guess. This lab's publication carries at most 1,376 payload bytes per frame. (a) You `offer` a 4,097-byte message. Will a plain fragment handler receive it as one piece? (b) Can `tryClaim(4097, claim)` split it for you?
<details><summary>Key — after attempt</summary>

Key:
- (a) No: plain polling hands you fragments, so the message must be reassembled before you compare whole-message bytes.
- (b) No: `tryClaim` rejects any length above `maxPayloadLength()`; only `offer` splits a large message.
</details>

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

<details><summary>Key — after attempt</summary>

Key:
- Unchanged, the handler sees each fragment as a message: the 4,097-byte message arrives as three pieces, so 7 callbacks instead of 5 messages.
- `return new FragmentAssembler(completeMessage);`, and the check prints `HIT`: ID 0 survives the forced rejection, IDs 0 to 4 arrive in order with lengths 14, 14, 4097, 14, 14, and every byte matches.
</details>

### CARD L02-R2 · Recall and repair
Q: Close the lesson. Prepare the starter again (this overwrites your work file). (a) Repair both marked lines, `TODO(L02-R1)` in `send` and `TODO(L02-R2)` in `receiver`, from memory, and run the live check until it prints `HIT`. (b) In one sentence: why does "one callback means one order" fail here? (c) Calculate the frame space, in bytes, of a 14-byte message.

```bash
checks/l02/prepare.sh starter
printf '%s\n' labs/l02/work | checks/chk_l02_udp.sh
```

<details><summary>Key — after repair</summary>

Key:
- (a) `send` returns only on `result >= 0`, and `receiver` returns `new FragmentAssembler(completeMessage)`; the live check prints `HIT`.
- (b) A message larger than the 1,376-byte max payload arrives as several fragments, each with its own callback, until the assembler joins them.
- (c) 32 + 14 = 46, rounded up to a multiple of 32: 64 bytes.
</details>

### CARD L02-T2 · Use the rule
Q: Fictional design: your messages carry a 24-byte application header plus up to 1,000 payload bytes. Use this lab's numbers: MTU 1,408, frame header 32 bytes, max payload 1,376, max message 8,192. (a) Does the largest message fit in one Aeron frame? Show the sum. (b) A 3,000-byte message turns up one day. What must the receiver do before decoding your header?
<details><summary>Key — after attempt</summary>

Key:
- (a) Yes: 24 + 1,000 = 1,024, which is at most 1,376 (= 1,408 − 32), so it fits one frame. Your 24-byte header sits inside Aeron's payload, separate from Aeron's 32-byte frame header.
- (b) Reassemble it first with a `FragmentAssembler`, because it arrives as three fragments. `offer` can send it, being under 8,192, while `tryClaim` would reject it.
</details>

## Optional operations — 15 minutes

`labs/l02/ops/README.md` has a guided case on terminal codes and a short
reference on frame and term size rules.

## Close

Remember: a negative `offer` result means "not taken", and you still own the
bytes. Retry -1, -2 and -3 with the same bytes within a budget; stop on -4, -5 and
unknown codes. Frames carry MTU minus 32 bytes and round up to 32; reassemble
before you compare messages. Tomorrow brings back lessons 1 and 2 mixed. Next:
lesson 3, finding where a stuck message is waiting.
