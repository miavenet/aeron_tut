# L01 operations — the handler is part of the protocol

Pick one case (about 15 minutes); the others are reference. Each case has a
deliberately broken copy of `Ownership.java` under `labs/l01/faults/` and the
working version under `labs/l01/solution/`. The broken copy differs from the
solution in one method. Predict before you run anything.

To practise a repair, copy the broken file into your workspace. This overwrites
`labs/l01/work/Ownership.java`; `checks/l01/prepare.sh` restores an exercise later.

```bash
cp labs/l01/faults/mutate-before-abort/Ownership.java labs/l01/work/Ownership.java
```

## Controlled redelivery — guided case

`controlledPoll` lets your handler return an action. `ABORT` means: stop this
poll and do not advance past this fragment, so the next poll delivers it again.
`CONTINUE` means: accept it and move on.

Predict: downstream is unavailable; `consume` changes the order book and then
returns `ABORT`. What happens on the next poll? Run the broken copy, then the
working one:

```bash
printf '%s\n' labs/l01/faults/mutate-before-abort | checks/l01/controlled.sh
printf '%s\n' labs/l01/solution | checks/l01/controlled.sh
```

The first prints `MISS` (`ABORT must not mutate or advance`): the change happened
on a fragment that will be delivered again, so it would be applied twice. The
second prints `HIT`: the first poll neither changes state nor advances; the second
poll redelivers the fragment and the change happens once.

Repair: in `consume`, return `ABORT` before running `mutate` when downstream is
not ready. Run `controlled.sh` on `labs/l01/work` until it prints `HIT`. Then say
in one sentence where the state change belongs.

## Ordinary poll exception — reference case, about 5 minutes

Predict: with plain `poll`, will a handler that throws see the same fragment
again? The check shows it will not: the exception goes to the client's error
handler and the position moves past that fragment. So the repair is to validate
before changing state and count rejected input explicitly, never to rely on
redelivery.

```bash
printf '%s\n' labs/l01/faults/throw-handler | checks/l01/handler.sh
printf '%s\n' labs/l01/solution | checks/l01/handler.sh
```

The broken copy throws for an invalid record and prints `MISS`. The solution's
`handle` calls `reject` and returns; it prints `HIT` with one rejected and one
applied record. Explain in one sentence why returning `ABORT` and throwing differ.

## Single-owner guard — reference case, about 3 minutes

Predict: should the guard stop a second thread before it touches the
publication? The check starts a separate thread that calls only `guard`, never
`offer` or `tryClaim`, so no unsafe race is ever run.

```bash
printf '%s\n' labs/l01/faults/no-owner | checks/l01/owner.sh
printf '%s\n' labs/l01/solution | checks/l01/owner.sh
```

Expected: `MISS` (the broken copy has no thread check), then `HIT`. Passing this
probe shows that your guard works. It is not evidence that `ExclusivePublication`
is thread-safe; its `offer` and `tryClaim` are not.
