# L05 operations — the clock that changed its units

Guided case budget: **15 minutes**, interruptible after any step. This is an
estimate; historical automated case timings are not included.
Work on the local `labs/l05/work/StateWork.java`; preserve your core repair first.

## Guided clock case — predict, break, diagnose, repair

1. Commit a prediction: does replay make a logged timestamp monotonic? Would
   switching an existing millisecond log to a nanosecond clock just make it more
   precise? State your confidence before reading the key.
2. In `millis(value, unit)`, deliberately return `value` unchanged. Run the clock
   check below. Name the failed unit conversion before editing.
3. Restore explicit conversion using the supplied unit; run the check again.
4. Run the preserved-log unit mismatch case. It creates a real millisecond
   cluster log, tries restarting with a nanosecond clock, requires the release's
   `incompatible timestamp units` error, restores the original clock, and proves
   replay plus subsequent progress. Its injected broken launch is expected
   evidence, not an instruction to edit a real cluster's files.
5. Close the file. Explain why an identical timestamp on replay is a different
   property from increasing timestamps across leader changes.

```bash
printf 'labs/l05/work\n' | checks/l05/clock.sh
printf 'labs/l05/work\n' | checks/l05/unit-mismatch.sh
```


<details><summary>Reveal after predicting and debugging</summary>
Key:
- Preserve logged time, including a backward step; default cluster clock reads wall time. Replay consistency alone is not a monotonic-clock guarantee.
- Convert application units explicitly. The fixture checks nanos and micros against milliseconds.
- The release refuses an existing log with incompatible time units; restore its clock configuration and verify recovery.
</details>


## Searchable cases — choose one, produce a diagnosis, then rerun

Each case below uses your submitted work. Case budgets are planning estimates,
excluding editing time: validation/policy/clock/timers/boundary 3 minutes each;
lifecycle/unit mismatch 5 minutes each. Record actual time if you overrun.

| Case | Deliberate fault in your workspace | Required correction/check |
|---|---|---|
| Validation | Increment count before rejecting input | Reject before changing any state; validation probe rejects bad type, ID, value and length |
| Egress policy | Require success > 1 | Accept positive results including mock 1; never derive business IDs from egress positions |
| Commit fence | Return appended as the poll limit | First bounded poll sees only the first record; advancing commit releases the second |
| Lifecycle | Permit onStart in maySchedule | Reject the policy; a separate live attempt calls scheduleTimer inside onStart and verifies the library exception |
| Timer race | Let expire ignore timerActive | Cancel-before-expiry suppresses the business effect; expiry-before-cancel applies once; an old ID cannot cancel a new timer |



```bash
printf 'labs/l05/work\n' | checks/l05/validation.sh
printf 'labs/l05/work\n' | checks/l05/policy.sh
printf 'labs/l05/work\n' | checks/l05/boundary.sh
printf 'labs/l05/work\n' | checks/l05/lifecycle.sh
printf 'labs/l05/work\n' | checks/l05/timers.sh
```


The timer case injects explicit event permutations into application state. It
is an application-policy test, not a simulation of Aeron's timer scheduler or a
claim that cancel wins every race. The API documents cancellation as asynchronous
and racing expiry; the wheel timer does not promise same-tick order. Both supplied
orders of two independent timer effects must work.

The lifecycle check demonstrates runtime rejection in `onStart`; the other
callback names are covered by the submitted policy test and pinned contract.
`onTakeSnapshot` is forbidden by the service contract, but do not infer that it
has the same runtime guard as `onStart`.

Finish by writing: hypothesis → exact error/event → one intervention → observed
rerun. For each claim above, identify whether your evidence is a live run, an
application-policy fixture, or a source contract. Re-answer the clock prediction
on your next review day.
