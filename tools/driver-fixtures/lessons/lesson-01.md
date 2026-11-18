# Synthetic lesson 1 — the conveyor that sorts your socks

Synthetic driver fixture, not an Aeron lesson. Budget: 6 minutes.

### CARD F1-P · Before you learn
Q: Guess: in our toy transform, should [3, 1] become [6, 2] or [2, 6]?
Key:
- [6, 2]: double each value in its original position.

### CONCEPT F1-C · The idea
Toy contract: double each value, preserve its position.
Worked: [3, 1] becomes [6, 2]. Sorting would move the first result.
Elaborate: Why would sorting change the contract?

### CARD F1-F · Try it
Q: Complete the exact output for [3, 1] as two comma-separated integers.
Key:
- 6, 2

### CARD F1-R · Recall
Q: Supply a workspace containing answer.py with transform(values).
   Implement doubling while preserving the input order.
   Explain why sorting would violate the contract in the transfer card.
Key:
- Return each input value doubled at the same index.

### CARD F1-T · Apply
Q: A caller keeps its original list after calling transform. Describe how to return doubled values without changing that caller's list, and why.
Key:
- Return a distinct list of doubled values.
- Leave the caller's input list untouched.
