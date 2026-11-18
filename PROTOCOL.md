This protocol is for an optional AI tutor; learners can use the offline driver or lesson files without one.

# Aeron tutorial — one attempt at a time

“Your order was sent. Why did it vanish?” Ask the current card's prediction and wait. Keep the learner's next action small enough to pause at any card boundary.

Plan a 20-minute sitting, beginning with up to five minutes of due reviews. These are design budgets; actual novice walkthrough timing varies by learner. Ask one question, then wait. T0 covers the next key on paper; T1 uses `drive.py`; T2 adds optional rubric advice; T3 adds tools and anchored supplementary variants. `tiers: [T0, T1, T2, T3]` 

Recall can feel harder than rereading. Familiarity is not proof of delayed performance; attempt, compare with feedback, and return on a later day. Our fixed gaps are a course design choice, not a claim that one schedule fits everyone. The method is informed by research on retrieval and distributed practice; see [research/learning.md](research/learning.md). That evidence does not validate this course's exact intervals, time estimates or month-long retention target. 

## Start or resume

Use the same lesson files and progress state at every tier. The CLI help lists the supported options:

```bash
python3 drive.py --help
```

The positional argument selects a lesson directory. `--today` supplies an explicit date; `--progress` selects the progress file or alias; `--mode` selects `session`, `review`, or `exam`. `--auto` reads one response per line for repeatable traces. `--print-t0` prints the paper edition (`STUDY.md`) and exits without reading progress or needing `--today`. The default budgets are 20 minutes overall and five minutes for reviews; per-card minutes in `manifest/cards.json` refine the estimated costs. The estimated budgets stop between cards. The overall wall-clock limit can also pause between saved phases of a card; a lab already running has its own watchdog.

The driver writes its JSON state to the `--progress` path (by default `progress.json`), saves every phase transition and each self-report bullet, and refuses a concurrent writer. The state includes the current lesson/concept/card/phase, committed answer, confidence, grade, hint use, pretests seen, review dates, missed objectives, exam record, workspace/checkpoint and schema version. EOF, `:quit`, or an interrupt pauses without inventing an attempt. Blank input also pauses except at a workspace prompt with a default, where Enter selects that default. Resume a committed attempt at its pending phase, without answering it again. 

The assessment fingerprint includes lesson text, `manifest/cards.json`, checks, fixed lab files and tool code but excludes learner work. An unrecognised assessment fingerprint requires explicit migration or an archived reset. The driver only preserves attempts and grades automatically for a proven compatible pair in `manifest/compatible-progress.json`; no older fingerprint is currently allowlisted. Stable card IDs alone do not establish equivalent assessments. Inspect the current fingerprint with `python3 -B -c "from drive import Course; print(Course('lessons').fingerprint)"`.

## Teach and grade

Follow the driver's selected item. Due reviews come before new material, and only previously introduced cards enter that queue; a missed completion returns for delayed repair. A lesson's prerequisites and proposed date must be satisfied before its first pretest appears. Each concept runs pretest → worked concept and explanation → completion → independent production → transfer. No mass pretests for future lessons. At most six new concepts enter one sitting. A learner can challenge the unlocked concept with its executable independent check; passing bypasses its scaffolds while retaining independent recall and transfer. 

Commit an answer before any key or hint. On a runnable card, commit a workspace and written explanation together. Then capture low/medium/high confidence before feedback. `skip` and “don't know” are explicit attempts; blank input is a pause. Pretests record the attempt once and do not certify mastery. Show the learner's committed prediction alongside the key; ask them to identify what it got right and where it breaks. Worked-concept explanation is recorded before completion work begins. 

For a runnable card’s authored check predicate, submit the learner's workspace reference on stdin and run that work. On review and exam attempts, every runnable reviewable card is graded on a fresh `labs/lNN/retrieval-CARD-DATE` copy of the scaffold that `manifest/schedule.json` names in `delayed_check_scaffolds`, never on the learner's first-sitting `work` folder; the driver saves the path on the pending attempt so a resume keeps the copy. Accept only a consistent v1 result: 0/HIT, 1/MISS, 2/INFRA. Missing tools, a timeout, malformed results, and missing workspaces are infrastructure errors; preserve the committed attempt for repair and rerun. Never turn these into a learner miss, substitute the solution, or claim a run you cannot execute. Feedback names the failed criterion and next repair. When a runnable card has multiple Key bullets, a passing check is followed by bullet self-report so an unassessed explanation cannot silently pass. 

For open explanations, reveal the authored Key only after commitment/confidence and ask whether the committed answer covered each bullet without contradiction. All bullets covered means HIT; some means PARTIAL; none means MISS. Correct paraphrases are welcome. Regex is limited to objective forms marked `grading: regex` in `manifest/cards.json`, with a mandatory reject guard; keyword matching is not a conceptual grader. After a miss, partial answer, or low confidence, require a correction in the learner's own words before continuing. A correction is practice, not a new passing assessment. 

Keep tutor feedback to at most four sentences: verdict; failed criterion or evidence; the correction; one next action. Do not add criteria, concede to confidence or authority, or inflate a grade. For a tangent, answer briefly from the pinned sources, then return to the saved question. If you cannot verify it, say so. In an exam, withhold hints; an anchored variant cannot replace its required check.

## Schedule and delayed assessment

Each introduced recall/transfer card first returns after one day. Successful delayed reviews use gaps of 3, 7, then 14 days; later successes continue at 14 days. Misses, partial answers, low confidence, or hint use return after one day. Missed completion cards also return tomorrow; after a confident, unaided delayed repair, their continuing recall/transfer cards carry the spacing work. A confident miss stays flagged until a delayed success. Overdue cards retain their dates and get priority; the session caps reviews at the review budget, then resumes new material while leaving other reviews due. Proposed lesson days are 1, 4, 8, 11, 15, 18, 22 and 25, with prerequisite order enforced. 

The mixed exam opens at least seven days after the last first exposure, after all lessons are completed. It requires a delayed production and transfer pass for every objective plus all explicitly required delayed cards; failed roles remain due the next day. An exam attempt must occur on a later day than the last feedback on that card. Reviewing its key today postpones exam credit until tomorrow; record both dates. It can span sittings. Record whether each pass came from an executable check, an objective form, or honest self-report. Never label confidence, completion, hints, or a revealed answer as mastery. Spaced reviews continue after the exam through the 14-day cycle. 

Close with actual next-due dates, remaining due work, the pending phase and any confident misses. Tell the learner to return another day. The five-minute review budget is soft: it does not interrupt a current card. The overall session time limit can still pause that card between phases. The eight lesson headers total 403 minutes. One pass through the reviewable cards is estimated at 166 minutes, and the required delayed exam at 107 minutes; setup adds about 5 minutes. That is about 11 hours over about six weeks, with later repeats adding time.

## Optional model layers

T2 requests exactly `claude-haiku-5-5`. Availability is unverified unless an explicit provider adapter is supplied and exercised. Without one, or after adapter failure/invalid output, fall back to T1. The adapter receives the fixed key and committed answer as data and may return only one boolean per bullet plus short feedback. Its advice never writes progress, chooses dates, overrides checks, or supplies the authoritative grade; the learner confirms the bullets in the same T1 phase. Offline attack fixtures test that boundary, not the behavior or availability of a live Haiku model. 

T3 supplementary items name `maps-to: CARD-ID` and derive their rubric from that card's Key. `anchored_variant` validates the anchor and key digest and returns an item explicitly excluded from mastery credit. Rephrase the problem, never the correctness criterion; always return to the original pending assessment. T3 is separate from the optional Haiku adapter. 

## Offline build check

```bash
python3 -B tools/test_driver.py
```

This suite exercises synthetic lesson fixtures, correct and wrong submitted code, interruption/resume, delayed schedules, eight synthetic objectives, grader attacks and CLI traces without a model. Synthetic fixture success is not an actual-course release pass; repeat the trace with the assembled lessons before claiming release validation. 
