# AGENTS.md — Iqra

Quran recitation-teaching Android app. It judges each word the user recites by
aligning a phoneme recogniser's output against expected phonemes.

**Read `.opencode/skill/iqra-recognition/SKILL.md` first.** It routes to ~3,700
lines of project documentation, records where everything lives, and states the
tajweed limits of the engine. Do not work in this repo without it.

## Standing rules

1. **A verdict is an accusation.** Painting a word red tells the user they
   recited it wrongly. Never let a false accusation ship, and never let a
   non-verdict be drawn as one.

2. **The gate is `engine/.venv-replay/bin/python engine/replay/run_checks.py`.**
   Nothing else is evidence. Before quoting a check's result, confirm the check
   can fail: break the thing it measures and see it go red. Three checks here were
   green for weeks while structurally unable to report failure.

3. **Never report a number without naming what produced it** — and whether it
   came from the harness or the device. They have disagreed, repeatedly.
   Advisories are never a verdict and never enter `correct`/`wrong`/`judgedWords`;
   see the advisory section of the skill file.
   Any Settings toggle must be consumed by the engine, or it is a lie.

4. **"pushed" means git. "installed" means the phone.** Both must be said
   explicitly. Conflating them has twice left the user believing a build was on
   the device when it was not.

5. **Stage by path.** Another agent works in this tree. Run `git status` and add
   only the files you changed; do not `git add -A` over someone else's
   in-progress work.

6. **Update `docs/CORE_LOGIC.md` in the same commit as any change to the six
   pipeline steps.** It is the file a new reader uses to learn the app. A
   change to the alignment, the lock policy, a verdict rule or the record format
   that leaves it stale teaches the next person a lie.

7. **A measured negative result is a result.** Write it down with its number. A
   hypothesis that failed, and the measurement that killed it, is worth more than
   a passing check that proves nothing. `docs/STATUS.md` holds three of them.

8. **The harness and the phone are two different instruments.** A sweep over the
   reference corpus is evidence that a class of defect exists. It is not a count
   of what a user saw. Never quote a harness number as a device number, and say
   which window it was measured in.

9. **Never edit a comment to make a check pass.** When a gate check greps for
   code you just deleted, the check named a symbol, not a behaviour. Fix the
   check to test the behaviour, then fault-inject the fixed check. Do not restore
   the symbol to satisfy a name.

6. **Prefer `edit` on existing files** to creating new ones. Match surrounding
   style, and do not add comments unless asked — this codebase earns its
   comments, so if you are adding one it must explain something a reader could
   not infer.

## The user's standing priorities

- **Core function first.** The recitation engine, before UI. They have said so
  directly.
- **Do not fake data.** A fabricated metric is worse than a missing one. If a
  session judged nothing, report that, not a percentage.
- **Defer the optional.** 32-bit ABI, the full everyayah download, visual
  redesign, and the mic spectrum work are all deliberately parked.
- **They will tell you when something is wrong and mean it.** Red masking,
  colouring gaps and page-turn fights have all been reported accurately.

## Subagents

- `researcher` — writes documentation. Must publish what it could not establish.
- `verifier` — may only break things. Every claim must survive fault injection.

Prefer delegating research and audit to them over doing it inline: it keeps the
main context for decisions, and their output contract forces the gaps to be
visible.
