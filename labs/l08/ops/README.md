# Optional break and repair (15 minutes)

Predict a failure, copy one listed variant into `labs/l08/work/adapter.py`, and run the check named on its row. For request-only use `printf '%s\n' labs/l08/work | checks/l08/policy.sh`. For wrong-offset use `printf '%s\n' labs/l08/work | checks/l08/adapter.sh`. Observe MISS, repair the work file, then rerun the same check. Stop after one case if time is up.

- `labs/l08/faults/request-only/adapter.py`: two origins share one request ID. Check: `checks/l08/policy.sh`.
- `labs/l08/faults/wrong-offset/adapter.py`: encoder swaps epoch and kind. Check: `checks/l08/adapter.sh`.

After repair, recall the difference between a log sequence and a stable application request key without reopening the solution. These checks use a fictional local frame and make no durability or failover claim.
