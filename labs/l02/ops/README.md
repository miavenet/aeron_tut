# L02 operations — terminate the retry loop

Guided case, about 15 minutes. The broken copy in
`labs/l02/faults/retry-terminal/Transport.java` has lost the two lines in `send`
that stop on terminal codes, so it retries everything.

Predict the calls for the scripted tapes `[-4,64]` and `[-3,128]`. The first should
stop after one call with `CLOSED` (-4). The second should retry `ADMIN_ACTION` (-3)
with the same bytes and return 128 after two calls. Then run the broken copy and
the working one:

```bash
printf '%s\n' labs/l02/faults/retry-terminal | checks/l02/policy.sh
printf '%s\n' labs/l02/solution | checks/l02/policy.sh
```

The first prints `MISS`; the second prints `HIT`. To practise the repair, copy the
broken file over your workspace (this overwrites `labs/l02/work/Transport.java`),
restore the terminal-code test from memory, and run the same check on
`labs/l02/work`:

```bash
cp labs/l02/faults/retry-terminal/Transport.java labs/l02/work/Transport.java
printf '%s\n' labs/l02/work | checks/l02/policy.sh
```

The policy check also covers an exhausted budget, an unknown negative code and a
payload changed between retries. These are scripted result tapes; nothing here
fills a real log to its maximum position.

## Frame and term size rules — reference, about 3 minutes

These rules are checked by the pinned driver code, so a bad value fails at startup:

```text
MTU          more than 32, at most 65,504, and a multiple of 32   (1408 ok, 1409 rejected)
term length  a power of two from 64 KiB to 1 GiB                  (65536 ok, 98304 rejected)
```

Predict whether MTU 1408, MTU 1409 and term length 98304 are accepted, then run:

```bash
printf '%s\n' labs/l02/solution | checks/l02/geometry.sh
```

`HIT` confirms that 1408 is accepted and 1409 and 98304 are rejected. The check
also confirms the 32-byte header and alignment, and that a 14-byte payload takes
64 bytes. Note that 1408 is valid without being a power of two: MTUs only need to
be multiples of 32. Rounding one frame up to 64 does not change the alignment,
which stays 32.
