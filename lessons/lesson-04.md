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
<details><summary>Key — after your guess</summary>

Key:
- (a) No. It is the registration ID of the recording subscription; the recording ID is found once the publication's image appears.
- (b) No. In this fixture the offer returns `NOT_CONNECTED` (-1), and the recording's start equals its stop; starting a recording did not connect the publication.
</details>

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

<details><summary>Key — after your repair</summary>

Key:
- (a) `return RecordingPos.getRecordingId(counters, counter);` and the core check prints `HIT`.
- (b) The subscription ID names the Archive's subscription; a recording belongs to one publication session and gets its own ID when that image arrives.
</details>

### CARD L04-R1 · Recall and repair
Q: Close the lesson. Prepare the starter (this overwrites your work file). (a) Three methods are marked `TODO(L04-R1)`: `recordingId`, `recordingComplete` and `stopped`. Repair them from memory and run the core check until it prints `HIT`. (b) Name the two waits that stop "accepted" from passing for "recorded and stopped".

```bash
checks/l04/prepare.sh starter
printf '%s\n' labs/l04/work | checks/chk_l04_archive.sh
```

<details><summary>Key — after your repair</summary>

Key:
- (a) `recordingId` reads `RecordingPos.getRecordingId(counters, counter)`, `recordingComplete` needs `active && position >= target`, and `stopped` needs `catalogStop != AeronArchive.NULL_POSITION`; the check prints `HIT`, including exact replay after the publisher exits and after the Archive restarts.
- (b) Wait until RecordingPos reaches the final offered position, then stop and wait until the catalog stop is set; while recording, the stop is `NULL_POSITION`, which does not mean empty.
</details>

### CARD L04-T1 · Use the rule
Q: Judgement call. A fictional service's rule: acknowledge a request only once it is in the recording. An AI assistant wrote:

```java
long position = publication.offer(buffer, 0, length);
if (position > 0 && Files.exists(segmentFile)) {
    acknowledge(requestId);   // "it's in the recording now"
}
```

One or two sentences each: (a) Does it meet the rule? (b) What would you check before acknowledging? (c) Propose an acceptance test, stating the sync setting you assume.
<details><summary>Key — after your proposal</summary>

Key:
- (a) No: a positive offer means only that the local publication accepted the bytes, and a `.rec` file can exist with nothing in it, as this lab's empty run shows; neither shows the request is in the recording.
- (b) The RecordingPos of the matching recording ID: acknowledge only once it reaches the position this offer returned.
- (c) Pause recording progress, send a known request, check that the acknowledgement is withheld, resume, then replay and compare the exact request; state the file sync level you assume separately.
</details>

## Session B — Replay the right bytes, about 24 minutes

If you stopped after session A: before the next card, recall the difference
between the subscription ID and the recording ID. `prepare.sh` overwrites only
`labs/l04/work/ArchiveWork.java`.

### CARD L04-P2 · Before you learn
Q: Guess, one line each. A recording starts at position 4096. (a) Is replaying from 0 safe? (b) Is every position that is a multiple of 32 a valid replay start? (c) The Archive process restarts and replays perfectly. Does that prove the data survives a power cut?
<details><summary>Key — after your prediction</summary>

Key:
- (a) No: read the start from the catalog; 0 is before this recording begins.
- (b) No: a multiple of 32 can still point inside a frame.
- (c) No: a process restart is not a power cut. What survives a power cut depends on the file sync settings; state those settings rather than claim the test proved it.
</details>

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
<details><summary>Key — after your answer</summary>

Key:
- (a) `NULL_POSITION` (-1).
- (b) The `RecordingPos` counter, or `getRecordingPosition(id)`.
- (c) The start position read from the catalog.
- (d) The empty run accepted no message (offer -1) and has start = stop, so a file can exist with nothing in it; only the range and a full byte replay show content.
</details>

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
<details><summary>Key — after your answer</summary>

Key:
- (a) Find the recording ID and its start and stop, wait until recording is complete, replay that range through `AeronArchive.replay`, assemble fragments, and compare whole messages in order.
- (b) Hypothesis: the position is inside a frame. Evidence: the catalog range and `INVALID_POSITION`. Intervention: replay from a known frame start such as the catalog start, then rerun the exact comparison.
- (c) It shows this run recovers after a process restart; it is not a power-cut test, so report the file and catalog sync levels separately.
</details>

### CARD L04-T2 · Use the rule
Q: Your recovering consumer gets a recording that starts at 4096 and contains fragmented messages. Prepare the independent exercise (this overwrites your work file). (a) Repair the two methods marked `TODO(L04-T2)` from memory and run the check. The first run prints `MISS` with `Discover catalog start; do not assume zero or skip first frame`; the repair prints `HIT`. (b) In one or two sentences: why is an implementation that starts at zero and counts fragments not enough?

```bash
checks/l04/prepare.sh independent
printf '%s\n' labs/l04/work | checks/l04/nonzero.sh
```

<details><summary>Key — after your repair</summary>

Key:
- (a) `replayStart` returns `catalogStart`, and `receiver` returns `new FragmentAssembler(complete)`; the check prints `HIT` with exact bytes and the bad offsets rejected.
- (b) A recording need not start at zero, and fragments are not messages: counting callbacks can hide a truncated or altered message, so compare every whole message's bytes.
</details>

### CARD L04-INT · Put it together
Q: Fictional incident: an order's `offer` returned a positive position, its RecordingPos is behind that position, and no reply has come. (a) What can you honestly tell the order's owner? (b) Which observation would you collect next?
<details><summary>Key — after your answer</summary>

Key:
- (a) The local publication accepted the bytes; the recording has not yet been shown to reach that position, and nothing here shows execution or a business reply.
- (b) The matching recording ID and its RecordingPos compared with the offered position, plus the application's own acknowledgement rule.
</details>

## Optional operations — 15 minutes

`labs/l04/ops/README.md` has a guided case on replay deadlines and a table of
runnable reference checks (configuration, isolation, checksums, archive tool).

## Close

Remember: the subscription ID is not the recording ID; wait for RecordingPos to
cover your last position before you stop; replay from the catalog start and
assemble fragments; and a restart test is not a power-cut test. Tomorrow brings
back lessons 1 to 4 mixed. Next, lesson 5: three replicas and one clock.
