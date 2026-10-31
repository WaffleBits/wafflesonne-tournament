# Tournament scheduling: how it should work

Written during the August 2026 redesign. The code has since moved from hourly slots to 672 fifteen-minute bins, and from a Monday lock to a window anchored on the previous tournament. The finding and the principle below still drive it.

## The finding that shapes everything

Measured from `activity.db` on 2026-08-10, 24,502 samples over 16 ISO weeks:

```
Car 21:00  mean=6.17  weeks=14        Cmt 20:00  mean=5.86  weeks=14
Paz 16:00  mean=6.10  weeks=15        Cmt 17:00  mean=5.84  weeks=14
Per 22:00  mean=6.04  weeks=14        Cum 22:00  mean=5.82  weeks=14
Car 22:00  mean=5.93  weeks=14        Cum 21:00  mean=5.80  weeks=14
Cmt 21:00  mean=5.88  weeks=15        Pzt 22:00  mean=5.75  weeks=14
```

**The top twelve slots span 0.42 players.** With ~14 observations each, that gap is
noise. There is no single best time to discover, every weekday evening between
20:00 and 22:00 is equivalent within measurement error.

So the plugin has been running careful EMA mathematics to answer a question whose
honest answer is "it barely matters", and then acting on differences too small to
be real. That is the root of the drift, not the smoothing constant.

A second effect makes chasing the peak actively counterproductive: **players
adapt to the schedule**. A tournament that runs at a fixed time every week builds
its own attendance, because people plan around it. A tournament that moves
weekly cannot. The optimum is not where players happen to be; it is wherever you
reliably say it will be.

Note the existing code already half-acknowledges this, it purges samples around
a tournament so the event cannot inflate its own slot. That guards against a
feedback loop in one direction while the design depends on one in the other.

## Principle: separate estimating from committing

The current design has one value, `current`, that is simultaneously the estimate
and the schedule. Recomputing the estimate therefore silently rewrites the
promise. Those are different concerns with opposite requirements:

| | Estimate | Commitment |
|---|---|---|
| Updates | continuously | once per cycle |
| Should move | freely | almost never |
| Visible to players | no | yes |
| Stored | derived on demand | persisted to disk |

Splitting them fixes the drift by construction: the estimate can wobble every
night and the announced time still cannot move.

---

## Layer 1: Estimator (advisory only)

Unchanged from today, plus one guard:

- sample player count every 5 minutes into `(weekday, hour)` buckets
- score each slot with an EMA across weeks, smoothed with the hours either side
- **eligibility: at least 6 observed weeks.** Stops a slot winning on thin data,
  which is how a one-off busy evening becomes a permanent schedule change

This layer never changes the schedule. It only produces a ranking that the
commitment layer consults once a week.

## Layer 2: Commitment (weekly, sticky, persisted)

**Lock moment.** Once per cycle at a fixed time, Monday 12:00, pick the slot
for the coming week and write it to disk.

**Candidates must be at least 48 hours away.** This single rule kills the bug
that skipped three weeks: re-picking at 00:04 kept selecting the 23:00 peak of
the evening that had just ended, pushing the next occurrence a week out, every
night. A minimum lead time makes selecting a just-passed slot impossible, and
guarantees room for the announcement.

**The incumbent keeps the slot unless clearly beaten.** Given a 0.42 spread
across the top twelve, the existing `hysteresis: 0.75` means the slot should
essentially never move. That is the correct behaviour, and it should be
deliberate rather than a side effect of a threshold nobody has hit, today the
comparison never executes at all, because `current` is null at every boot.

**Persisted state** (`schedule.yml`):

```yaml
locked:
  weekday: 3          # 0 = Monday
  hour: 22
  start-epoch: 1786...  # exact instant of the next occurrence
  locked-at: 1786...
  score: 6.04
last-run:
  start-epoch: 1786...  # which occurrence, not "when did we last run"
```

Loading `locked` on enable is what makes hysteresis work at all. Keying
`last-run` to the **occurrence** rather than to a wall-clock time is what makes
the once-per-week guard survive a restart, the current in-memory `lastRunStart`
resets on every boot.

## Layer 3: Execution (must actually run)

A ticker every 30 seconds:

```
fire when   now >= start-epoch
      and   now <  start-epoch + grace
      and   last-run.start-epoch != start-epoch
```

**Grace window, 90 minutes.** Today the condition is `now.getHour() == slot.hour`,
so if the server is restarting, lagging, or briefly down during that one hour,
the tournament is skipped for the entire week with no trace. A grace window turns
a transient problem into a late tournament instead of a missing one.

**Too few players.** `min-players: 3` is checked once. It should retry every five
minutes inside the grace window, and if the window expires, announce a
cancellation. Silently doing nothing is the failure mode that started this
investigation, nobody knew whether it was broken or just unpopular.

**Blackout.** Never schedule inside the nightly restart window. `latest-hour`
should be 22, not 23: signup alone is 120 seconds, and a 23:00 start collides
with the 23:50 restart warning.

## Layer 4: Announcements (bound to the commitment)

At lock, then T-24h, T-1h, T-10min, T-0. Because they read the locked value
rather than the live estimate, they cannot contradict each other, which is what
produced "tournament will be Sunday 15:00" followed by it starting on Saturday.

## Manual control

- `/turnuva set <day> <hour>`, pin a slot, suspend auto-selection
- `/turnuva unlock`, resume auto-selection
- `/starttournament`, unchanged, always available

## Observability

`/turnuva` should show both layers at once:

```
Locked:   Perşembe 22:00  (in 3g 4s)   this week: not yet run
Estimate: Çarşamba 21:00  (6.17)  vs locked 6.04 , margin 0.13, needs 0.75
```

Showing the estimate alongside the commitment makes drift visible without
letting it act. If the margin column is permanently far below the threshold , 
which the data says it will be, that is the system telling you the slot is
already as good as any, and it is working correctly by refusing to move.

---

## Why not just make the EMA longer

The request was a "200 EMA". Two reasons that is not the lever:

1. **The data does not exist.** 16 ISO weeks of history means at most 16 points
   per slot. A 200-period average over weekly means is not computable.
2. **It would not help.** More smoothing yields a more stable ranking of
   differences that are still inside the noise floor. It would pick a different
   arbitrary winner, more slowly.

The stability problem is solved by committing and persisting, not by averaging
harder. Once the slot is locked and only moves on a margin the data can actually
support, the schedule is stable regardless of the smoothing constant.
