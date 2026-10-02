#!/usr/bin/env python3
"""Faithful port of the app's WHOLE lock policy, as a reusable module.

Why this file exists
--------------------
`lock_policy.py` claimed 28/28 but could not fail: it only ever scored
lock+1 and only ever moved upward, so its trace was monotone by
construction. It also iterated per EMISSION while the app evaluates the
policy once per POLL (~4 Hz), and it modelled none of the backward branch,
the long jump, the surah handoff or the 1.5 s tail replay that
`tail_replay_cost.py:10-12` says invalidate the 28/28.

What is ported, in the app's own order, with the app's own constant names
and values:

  PracticeViewModel.kt:1169-1177   the seven coverage thresholds
  PracticeViewModel.kt:841-857     surah handoff (first, and it returns)
  PracticeViewModel.kt:866-893     forward: strong / pending hysteresis
  PracticeViewModel.kt:897-910     gated long jump
  PracticeViewModel.kt:916-932     backward recovery
  PracticeViewModel.kt:935-956     repeat hook
  PracticeViewModel.kt:308-335     advanceLockTo: wpm + 1.5 s tail replay
  PracticeViewModel.kt:812-835     per-poll slice rebase and obs

Deliberate fidelity notes. These are the APP's behaviour, reproduced on
purpose - do not "fix" them here:

* `nextCov`/`hereCov` are computed ONCE per poll BEFORE any branch runs
  (lines 868/870). A forward move on the same poll therefore still lets the
  backward branch compare the PRE-move `hereCov` against the POST-move
  `lockedAyah - 1` (line 916 reads lockedAyah after advanceLockTo ran).
* A poll that emits no new token returns at line 812, BEFORE the rebase, so
  the first GROWING poll after a move sets the new slice base and then has
  an empty obs (line 829) and evaluates nothing.
* `advanceLockTo` does NOT reset `lastEmitCount`; only
  `resetAudioPipeline()` - used by handoff and by the repeat hook - does.
  So after a plain advance a short fresh stream whose length happens to
  equal the pre-move `lastEmitCount` is read as "no growth" and skipped.
  That is faithfully reproduced here.
* After a move the fresh stream is fed 1.5 s of already-decoded audio
  (TAIL_SAMPLES = 24000 @ 16 kHz), so it runs 6 polls behind live audio and
  re-emits symbols the previous stream already emitted. `backlog` models
  exactly that offset.

Fidelity limits, stated rather than hidden:

* A token dump carries no audio, so a replayed tail cannot be re-decoded.
  `policy.tail_mode` picks the model: "replay" (the fresh stream re-emits the
  same symbols - optimistic, a real fresh stream has no left context), or
  "blank" (it emits nothing for the replay - the pessimistic bound). The
  truth is between them and `--tail-mode blank` shows how much it matters.
* `scopeEndAyah()` (415-427) and the long-jump page check (903) both need the
  Mushaf page map, which is not in the dump. Scope end is supplied as the
  `plan`; the page check is skipped, so an off-page jump cannot be suppressed
  here.
* `speechFramesSinceAdvance` counts polls that emitted a token. Polls gated
  out by the app's RMS/VAD silence check (730-734) are invisible offline, so
  `wpmEma` is measured on a lower bound of speech time and is an upper bound
  on speed. It stayed >= 48 wpm on every clip measured, so `needFrames` was 2
  throughout and the wpm path is not load-bearing for these results.
* The surah handoff branch is implemented and ordered correctly, but no dump
  on disk contains a surah boundary, so it is UNEXERCISED.

The coverage DP is NOT duplicated here: it is `word_verdicts.align`, the
shipped-equivalent symbol-level DP. `lock_policy.py`, `phoneme_explode.py`
and `tail_replay_cost.py` all import `coverage()` from this file.

Related: `tail_replay_cost.py` is the narrow A/B that isolates the 1.5 s
replay's cost and nothing else; `hesitation_policy.py` builds the
repeat/backtrack scenarios from real spans; `back_policy_sweep.py` sweeps
BACK_COVERAGE / STUCK_COVERAGE over them.
"""
import json
import os
import sys

# `word_verdicts` is imported as a module, not just for `align`, because
# lock_policy.py / phoneme_explode.py / tail_replay_cost.py reach
# load_units() and make_tokenizer() through this file to stop each of them
# keeping its own copy.
import word_verdicts  # noqa: F401
from word_verdicts import align, load_units, make_tokenizer

HERE = os.path.dirname(os.path.abspath(__file__))
PHONEMES = os.path.join(HERE, "..", "shootout", "weights", "zipformer",
                        "ordered_quran_phonemes.json")

TAIL_SAMPLES = 24000          # PracticeViewModel.kt:1157
POLL_SEC = 0.25               # PracticeViewModel.kt:697 `delay(250)`


# --------------------------------------------------------------------------
# the seven thresholds, named exactly as in PracticeViewModel.kt
# --------------------------------------------------------------------------
class LockPolicy(object):
    """Every threshold the app's lock policy uses, at the app's defaults.

    PracticeViewModel.kt:1169-1177 and TAIL_SAMPLES:1157. `back_need_frames`
    is the literal `>= 2` at line 922, lifted to a parameter so the sweep
    can move it. `tail_mode` selects how the post-move 1.5 s replay is
    modelled - see simulate().
    """

    def __init__(self,
                 advance_coverage=0.60,   # ADVANCE_COVERAGE
                 strong_coverage=0.85,    # STRONG_COVERAGE
                 weak_coverage=0.40,      # WEAK_COVERAGE
                 jump_coverage=0.92,      # JUMP_COVERAGE
                 back_coverage=0.80,      # BACK_COVERAGE
                 handoff_coverage=0.60,   # HANDOFF_COVERAGE
                 stuck_coverage=0.35,     # STUCK_COVERAGE
                 tail_seconds=1.5,        # TAIL_SAMPLES / 16000
                 back_need_frames=None,   # None = speed-scaled, like needFrames
                 retreat_gap_sec=1.5,     # MIN_RETREAT_GAP_MS, on the audio clock
                 max_retreats=2,          # MAX_CONSECUTIVE_RETREATS
                 wpm_init=70.0,           # wpmEma at startRecite (line 655)
                 wpm_low=50.0,            # below this, needFrames = 3 (878)
                 wpm_min=25.0,            # coerceIn (line 317)
                 wpm_max=160.0,
                 tail_mode="replay"):
        self.advance = advance_coverage
        self.strong = strong_coverage
        self.weak = weak_coverage
        self.jump = jump_coverage
        self.back = back_coverage
        self.handoff = handoff_coverage
        self.stuck = stuck_coverage
        self.tail_seconds = tail_seconds
        # None means "speed-scaled like the forward path", not "2".
        self.back_need_frames = back_need_frames
        self.retreat_gap_sec = retreat_gap_sec
        self.max_retreats = max_retreats
        self.wpm_init = wpm_init
        self.wpm_low = wpm_low
        self.wpm_min = wpm_min
        self.wpm_max = wpm_max
        self.tail_mode = tail_mode

    def __repr__(self):
        return ("LockPolicy(advance=%.2f strong=%.2f weak=%.2f jump=%.2f "
                "back=%.2f handoff=%.2f stuck=%.2f tail=%.1fs/%s "
                "back_need=%s retreat_gap=%.1fs max_retreats=%d)"
                % (self.advance, self.strong, self.weak, self.jump,
                   self.back, self.handoff, self.stuck, self.tail_seconds,
                   self.tail_mode,
                   self.back_need_frames if self.back_need_frames is not None
                   else "wpm-scaled", self.retreat_gap_sec, self.max_retreats))


# --------------------------------------------------------------------------
# shared alignment primitives - the single copy of the DP
# --------------------------------------------------------------------------
_COVERAGE_CACHE = {}


def coverage(query, ref):
    """(coverage, matched, ref_len) - the app's `Alignment.coverage`.

    coverage = unitsMatched / unitsTotal (PhonemeMapper.kt:155): the
    fraction of EXPECTED units aligned 1:1 with an emission. Insertions
    cost 1 and never touch the numerator, so leading noise - istiaadha,
    basmala, a replayed tail - cannot by itself push coverage down.
    """
    n, m = len(ref), len(query)
    if not n or not m:
        return 0.0, 0, n
    key = (tuple(ref), tuple(query))
    hit = _COVERAGE_CACHE.get(key)
    if hit is None:
        _m, _w, _r2q, _e, hits, _nw = align(query, ref, list(range(n)))
        hit = (hits / float(n), hits, n)
        _COVERAGE_CACHE[key] = hit
    return hit


def expected_all(table, tok, surah, n_ayat):
    """{ayah: (units, unit_word, nwords)} for one surah.

    Same construction as `word_verdicts.build_expected`, but per-ayah and
    tolerant of ayat absent from the table (a scoped plan may stop short).
    """
    out = {}
    for a in range(1, n_ayat + 1):
        key = "%d:%d" % (surah, a)
        if key not in table:
            continue
        units, unit_word = [], []
        for wi, word in enumerate(table[key]["aya_phonemes_list"]):
            for u in tok(word):
                units.append(u)
                unit_word.append(wi)
        out[a] = (units, unit_word, len(table[key]["aya_phonemes_list"]))
    return out


def expected_units(exp):
    """{ayah: [units]} view of expected_all()."""
    return dict((a, v[0]) for a, v in exp.items())


def expected_words(exp):
    """{ayah: word_count} view of expected_all() - needed by the wpm
    measurement in advanceLockTo."""
    return dict((a, v[2]) for a, v in exp.items())


# --------------------------------------------------------------------------
# trace rows
# --------------------------------------------------------------------------
class Move(object):
    """One lock move.

    The app logs only "lock a -> b", so backward, jump, handoff and repeat
    are indistinguishable in logcat. `reason` here keeps them apart, and
    `direction` is +1 forward / -1 backward / 0 surah change.
    """
    __slots__ = ("t", "wall_frame", "from_surah", "from_ayah", "to_surah",
                 "to_ayah", "direction", "reason", "coverage", "pending_frames")

    def __init__(self, t, wall_frame, from_surah, from_ayah, to_surah,
                 to_ayah, direction, reason, cov, pending_frames=0):
        self.t = t
        self.wall_frame = wall_frame
        self.from_surah = from_surah
        self.from_ayah = from_ayah
        self.to_surah = to_surah
        self.to_ayah = to_ayah
        self.direction = direction
        self.reason = reason
        self.coverage = cov
        self.pending_frames = pending_frames

    def as_row(self):
        return (round(self.t, 2), "%d:%d" % (self.from_surah, self.from_ayah),
                "%d:%d" % (self.to_surah, self.to_ayah), self.direction,
                self.reason, round(self.coverage, 3))

    def __repr__(self):
        return "%6.2fs %s -> %s %-15s cov=%.2f" % (
            self.t, "%d:%d" % (self.from_surah, self.from_ayah),
            "%d:%d" % (self.to_surah, self.to_ayah), self.reason, self.coverage)


REASONS = ("forward-strong", "forward-pending", "jump", "backward", "handoff",
           "repeat")

ARROW = {"forward-strong": "->", "forward-pending": "->", "jump": "=>",
         "backward": "<-", "handoff": ">>", "repeat": "<<"}

HEADER = ("time", "from", "to", "dir", "reason", "cov")


def oscillations(moves):
    """Direction reversals: a move whose direction reverses the previous
    move's direction. A surah change has no in-surah direction, so it breaks
    the chain - stated here rather than silently folded into the count."""
    n = 0
    prev = 0
    for m in moves:
        d = m.direction
        if d == 0:
            prev = 0
            continue
        if prev != 0 and d != prev:
            n += 1
        prev = d
    return n


def group_frames(dump):
    """Emissions grouped by DISTINCT `frame`, ascending, 0-based poll index.

    The app evaluates the whole policy once per 250 ms poll
    (PracticeViewModel.kt:697 `delay(250)` and the 812 return), not once per
    emission. In out/fatiha-250ms.json only 74 of 228 polls carry
    emissions, 1-4 each, so a per-emission loop lets `need_frames` be
    satisfied inside a single poll - which is how lock_policy.py's "28/28"
    survived a policy the app does not run.
    """
    by_frame = {}
    for e in dump["emissions"]:
        by_frame.setdefault(int(e["frame"]) - 1, []).append(e)
    return by_frame


# --------------------------------------------------------------------------
# the simulator
# --------------------------------------------------------------------------
class TraceResult(object):
    def __init__(self, moves, surah, ayah, wpm, polls_evaluated, n_emissions,
                 n_polls, rebase_polls, stuck=None):
        # `stuck` samples the coverage pair while the lock refuses to move, so a
        # stall explains itself instead of having to be re-derived by hand.
        self.stuck = stuck or []
        self.moves = moves
        self.final_surah = surah
        self.final_ayah = ayah
        self.wpm = wpm
        self.polls_evaluated = polls_evaluated
        self.n_emissions = n_emissions
        self.n_polls = n_polls
        self.rebase_polls = rebase_polls
        self.oscillations = oscillations(moves)

    @property
    def final_lock(self):
        return "%d:%d" % (self.final_surah, self.final_ayah)

    def backward_moves(self):
        return [m for m in self.moves if m.reason == "backward"]

    def visits(self, surah, ayah):
        return any(m.to_surah == surah and m.to_ayah == ayah
                   for m in self.moves)

    def reached(self, surah, ayah):
        return any(m.to_surah == surah and m.to_ayah == ayah
                   for m in self.moves)

    def trace_text(self):
        if not self.moves:
            return "(no moves)"
        return " ".join(
            "%s%s%s" % ("%d:%d" % (m.from_surah, m.from_ayah),
                        ARROW[m.reason],
                        "%d:%d" % (m.to_surah, m.to_ayah))
            for m in self.moves)


class _Session(object):
    """Mutable session state, so the policy body reads like the Kotlin
    instead of threading eight locals through helper functions."""

    def __init__(self, plan, exp_by_surah, policy, repeat, start, tail_frames):
        self.plan = list(plan)
        self.exp_by_surah = exp_by_surah
        self.policy = policy
        self.tail_frames = tail_frames

        self.si = 0
        self.surah = plan[0][0]
        self.scope_end = plan[0][1]
        self.ayah = start
        self.wpm = policy.wpm_init

        self.pending_next_ayah = None
        self.pending_next_frames = 0
        self.pending_back_ayah = None
        self.pending_back_frames = 0
        # Retreat budget (retreatAllowed): the app measures the gap on the wall
        # clock, which offline has no meaningful relationship to the policy, so
        # the port uses the audio clock - "how much recitation passed" is the
        # thing the gap is protecting against.
        self.last_move_t = 0.0
        self.last_move_wall = None
        self.stuck = []
        self.consecutive_retreats = 0
        self.repeat_left = repeat[2] if repeat else 0
        self.repeat_target = repeat[:2] if repeat else None

        # stream / slice state
        self.backlog = 0
        self.epoch = []
        self.slice_start = 0
        self.rebase_pending = False
        self.last_count = 0
        self.speech_since_advance = 0

        self.moves = []
        self.evaluated = 0
        self.rebase_polls = 0

    # -- exp accessors -------------------------------------------------
    def exp(self):
        return self.exp_by_surah.get(self.surah, {})

    # -- stream lifecycle ----------------------------------------------
    def reset_stream(self, tail_replay):
        """advanceLockTo's stream recycle (330-335).

        With tail_replay the fresh stream is handed 1.5 s of already-decoded
        audio, so it runs `tail_frames` polls behind live audio and re-emits
        symbols the previous stream already produced. `last_count` is left
        alone on purpose: advanceLockTo never touches lastEmitCount.
        """
        self.epoch = []
        self.slice_start = 0
        self.rebase_pending = True
        self.backlog = self.tail_frames if tail_replay else 0
        self.pending_next_ayah = None
        self.pending_next_frames = 0
        self.pending_back_ayah = None
        self.pending_back_frames = 0
        self.speech_since_advance = 0

    def full_reset(self):
        """resetAudioPipeline() (352-378): everything above PLUS
        lastEmitCount = 0. Used by handoff and by the repeat hook."""
        self.reset_stream(tail_replay=False)
        self.last_count = 0

    # -- move recording -------------------------------------------------
    def move(self, t, wall, to_surah, to_ayah, direction, reason, cov,
             pending=0, measure_speed=True):
        """advanceLockTo (308-335) plus the trace row.

        measureSpeed is False for the jump and the backward move, exactly as
        at lines 907 and 923.
        """
        prev = self.ayah
        self.moves.append(Move(t, wall, self.surah, prev, to_surah, to_ayah,
                               direction, reason, cov, pending))
        if measure_speed:
            self._measure_speed(prev)
        self.ayah = to_ayah
        if to_surah != self.surah:
            self.surah = to_surah
        self.speech_since_advance = 0
        self.last_move_t = t
        self.last_move_wall = wall
        self.consecutive_retreats = (
            self.consecutive_retreats + 1 if direction < 0 else 0)

    def retreat_allowed(self, now):
        """retreatAllowed(): a minimum gap and a consecutive-retreat cap."""
        p = self.policy
        if now - self.last_move_t < p.retreat_gap_sec:
            return False
        return self.consecutive_retreats < p.max_retreats

    def _measure_speed(self, prev_ayah):
        """advanceLockTo's wpm measurement (311-318).

        dtSec counts SPEECH polls only. Offline the only speech polls we can
        see are the ones that emitted a token, so dtSec is a lower bound and
        the measured wpm an upper bound.
        """
        dt_sec = self.speech_since_advance * POLL_SEC
        prev_words = self.exp().get(prev_ayah, (None, None, 0))[2]
        if 2.0 <= dt_sec <= 180.0 and prev_words > 0:
            inst = prev_words / dt_sec * 60.0
            wpm = 0.7 * self.wpm + 0.3 * inst
            self.wpm = min(max(wpm, self.policy.wpm_min), self.policy.wpm_max)


def simulate(by_frame, n_frames, frame_sec, plan, exp_by_surah, policy, drain_frames=None,
             repeat=None, start=1):
    """Run the app's policy over grouped emissions.

    by_frame    : {0-based poll index: [emission dicts]}, from group_frames()
    n_frames    : total polls (dump["frames"])
    frame_sec   : seconds of audio per poll (0.25 in the app)
    plan        : [(surah, last_ayah_in_scope), ...] - the surah-change list
    exp_by_surah: {surah: {ayah: (units, unit_word, nwords)}}
    repeat      : (surah, ayah, count) to arm the repeat hook, or None

    policy.tail_mode selects how the post-move 1.5 s replay is modelled,
    because a token dump carries no audio to re-decode:
      "replay" - the fresh stream re-emits the symbols it emitted before.
                 Optimistic: a real fresh stream has no left context, so its
                 tail symbols will usually be worse than these.
      "blank"  - the fresh stream emits NOTHING while it works through the
                 replayed audio. Worst case: the replay is pure loss and the
                 first 1.5 s of live audio after a move is unseen.
      "none"   - no replay; the fresh stream is live immediately. This is
                 what the OLD harness measured, i.e. a policy that does not
                 exist on device.
    """
    tail_frames = int(round(policy.tail_seconds / frame_sec))
    if policy.tail_mode == "none":
        tail_frames = 0
    s = _Session(plan, exp_by_surah, policy, repeat, start, tail_frames)

    # Drain the tail backlog past the end of the recording.
    #
    # After every forward move the fresh stream is handed `tail_frames` polls of
    # already-decoded audio, so it runs that far BEHIND live audio. At end of
    # file that backlog can never drain, because the loop simply stops - and the
    # last frames of the surah are never consumed. The symptom was a lock that
    # refused to advance onto the FINAL ayah of a surah, on audio whose tokens
    # ran to 100% of the file, with coverage of the target sitting comfortably
    # above ADVANCE. Ten surahs "failed" this way, and it was the harness, not
    # the policy.
    #
    # On a device this cannot happen: the microphone keeps delivering frames
    # after the reciter stops, so the backlog drains within ~1.5 s and the
    # buffered emissions are evaluated. Extending the loop by `tail_frames`
    # models exactly that - the extra polls have no emissions of their own, but
    # `dump_frame` walks back into the real frames and finally consumes them.
    drain = tail_frames if drain_frames is None else drain_frames
    total = n_frames + max(0, drain)

    for wall in range(total):
        # The stream consumes audio `backlog` polls behind live audio, so a
        # replayed symbol re-emerges one poll after it first appeared.
        dump_frame = wall - s.backlog
        if dump_frame < 0:
            new = []
        elif policy.tail_mode == "blank" and dump_frame < wall:
            # still inside the replayed tail: the fresh stream yields nothing
            new = []
        else:
            new = by_frame.get(dump_frame, [])
        if new:
            s.epoch.extend(e["symbol"] for e in new)
        size = len(s.epoch)

        # PracticeViewModel.kt:812 - no growth, or growth identical to the
        # last poll, returns BEFORE the rebase and before the policy.
        if not new or size == s.last_count:
            continue
        s.last_count = size
        audio_sec = (wall + 1) * frame_sec

        # PracticeViewModel.kt:823-826 then 829 - the first growing poll
        # after a move rebases the slice and then has an empty obs.
        if s.rebase_pending:
            s.slice_start = size
            s.rebase_pending = False
            s.rebase_polls += 1
            continue

        obs = s.epoch[s.slice_start:]
        if not obs:
            continue

        s.evaluated += 1
        exp = s.exp()

        # ---- handoff (841-857): FIRST, and it returns --------------
        if s.ayah >= s.scope_end and s.si + 1 < len(s.plan):
            nxt_surah = s.plan[s.si + 1][0]
            nxt = (s.exp_by_surah.get(nxt_surah) or {}).get(1)
            if nxt:
                cov = coverage(obs, nxt[0])[0]
                if cov >= policy.handoff:
                    s.moves.append(Move(audio_sec, wall, s.surah, s.ayah,
                                        nxt_surah, 1, 0, "handoff", cov))
                    s.si += 1
                    s.surah = nxt_surah
                    s.scope_end = s.plan[s.si][1]
                    s.ayah = 1
                    s.full_reset()
                    continue

        # ---- coverages computed BEFORE any branch (868/870) --------
        next_ayah = s.ayah + 1
        next_cov = coverage(obs, exp[next_ayah][0])[0] if next_ayah in exp else 0.0
        here_cov = coverage(obs, exp[s.ayah][0])[0] if s.ayah in exp else 0.0
        need_frames = 3 if s.wpm < policy.wpm_low else 2
        moved = False

        # ---- stuck probe -------------------------------------------
        # While the lock will not move, record WHY: the coverage of the locked
        # ayah and of the candidate. A stall is otherwise only a duration, and
        # two stalls of the same length can have opposite causes - the dead band
        # [weak, advance) where neither the forward gate nor the jump gate can
        # open, versus the lock sitting on an ayah whose successor was never
        # heard at all. Surah 94 was 27 seconds of audio that never advanced and
        # the report said nothing about why.
        if (s.last_move_wall is not None
                and (wall - s.last_move_wall) * frame_sec >= 20.0):
            s.stuck.append({
                "t": round(audio_sec, 2),
                "ayah": s.ayah,
                "here_cov": round(here_cov, 3),
                "next_cov": round(next_cov, 3),
                "in_dead_band": bool(policy.weak <= next_cov < policy.advance),
            })

        # ---- forward (879-893) --------------------------------------
        if next_cov >= policy.advance:
            if next_cov >= policy.strong:
                reason, pend = "forward-strong", 0
            elif s.pending_next_ayah == next_ayah:
                s.pending_next_frames += 1
                pend = s.pending_next_frames
                reason = ("forward-pending"
                          if s.pending_next_frames >= need_frames else None)
            else:
                s.pending_next_ayah = next_ayah
                s.pending_next_frames = 1
                pend = s.pending_next_frames
                reason = None
            if reason:
                s.move(audio_sec, wall, s.surah, next_ayah, +1, reason,
                       next_cov, pend, measure_speed=True)
                s.reset_stream(tail_replay=True)
                moved = True
        elif next_cov < policy.weak:
            if s.pending_next_frames > 0:
                s.pending_next_frames -= 1
        else:
            s.pending_next_ayah = None
            s.pending_next_frames = 0

        # ---- gated long jump --------------------------------------
        # `here_cov < policy.stuck` is the nested-ayah gate. Coverage is
        # unitsMatched/unitsTotal, so a candidate whose units are a SUBSEQUENCE
        # of the locked ayah's already scores 1.00 while the reciter is still
        # on the lock - Al-Fatiha 1:3 is a subsequence of 1:1 - and the jump
        # skipped 1:2 on clean audio. Requiring the lock to look un-recited is
        # the only thing that separates a skipped ayah from a nested one.
        if next_cov < policy.advance and here_cov < policy.stuck:
            best = None
            for a in sorted(exp):
                if a <= s.ayah + 1:
                    continue
                c = coverage(obs, exp[a][0])[0]
                if c >= policy.jump and (best is None or a < best[0]):
                    best = (a, c)
            if best is not None:
                # the page check at line 903 needs a page map we do not have
                s.move(audio_sec, wall, s.surah, best[0], +1, "jump",
                       best[1], 0, measure_speed=False)
                s.reset_stream(tail_replay=True)
                moved = True

        # ---- backward ----------------------------------------------
        # here_cov describes the lock as the frame began, so if the forward or
        # jump block already moved it the "is it stuck?" test is being asked
        # about an ayah the policy has left. Not evaluated at all in that case,
        # which also makes a same-frame reversal structurally impossible.
        back_ayah = s.ayah - 1
        lock_held = not moved
        back_cov = (coverage(obs, exp[back_ayah][0])[0]
                    if back_ayah in exp else 0.0)
        need_back = (policy.back_need_frames if policy.back_need_frames is not None
                     else (3 if s.wpm < policy.wpm_low else 2))
        if (lock_held and back_cov >= policy.back and here_cov < policy.stuck
                and back_ayah in exp):
            if s.pending_back_ayah == back_ayah:
                s.pending_back_frames += 1
                if (s.pending_back_frames >= need_back
                        and s.retreat_allowed(audio_sec)):
                    s.move(audio_sec, wall, s.surah, back_ayah, -1,
                           "backward", back_cov, s.pending_back_frames,
                           measure_speed=False)
                    s.reset_stream(tail_replay=True)
                    s.pending_back_ayah = None
                    s.pending_back_frames = 0
                    moved = True
            else:
                s.pending_back_ayah = back_ayah
                s.pending_back_frames = 1
        elif back_cov >= policy.back:
            # Contradictory evidence: hard reset, mirroring the forward path.
            s.pending_back_ayah = None
            s.pending_back_frames = 0
        elif s.pending_back_frames > 0:
            s.pending_back_frames -= 1

        # ---- repeat hook (935-956) ---------------------------------
        if (s.repeat_left > 0 and s.repeat_target is not None
                and s.surah == s.repeat_target[0]
                and s.ayah > s.repeat_target[1]):
            s.repeat_left -= 1
            s.moves.append(Move(audio_sec, wall, s.surah, s.ayah, s.surah,
                                s.repeat_target[1], -1, "repeat", 0.0))
            s.ayah = s.repeat_target[1]
            s.full_reset()
            moved = True

        if not moved:
            s.speech_since_advance += 1

    n_emissions = sum(len(v) for v in by_frame.values())
    return TraceResult(s.moves, s.surah, s.ayah, s.wpm, s.evaluated,
                       n_emissions, n_frames, s.rebase_polls, s.stuck)


# --------------------------------------------------------------------------
# convenience wrappers
# --------------------------------------------------------------------------
def load_table(path=PHONEMES):
    with open(path) as f:
        return json.load(f)


def surah_ayah_count(table, surah, cap=300):
    n = 0
    for a in range(1, cap + 1):
        if "%d:%d" % (surah, a) not in table:
            break
        n = a
    return n


def build_exp(table, tok, plan):
    """{surah: {ayah: (units, unit_word, nwords)}} for a whole plan."""
    return dict((s, expected_all(table, tok, s, end)) for s, end in plan)


def run_dump(dump, plan, policy=None, exp=None, table=None, tok=None,
             repeat=None, start=1, drain_frames=None):
    """Run the policy over a token dump. Returns TraceResult."""
    policy = policy or LockPolicy()
    table = table if table is not None else load_table()
    tok = tok if tok is not None else make_tokenizer(load_units())
    exp = exp if exp is not None else build_exp(table, tok, plan)
    frame_sec = dump.get("frame_ms", 250) / 1000.0
    return simulate(group_frames(dump), dump["frames"], frame_sec, plan, exp,
                    policy, repeat=repeat, start=start,
                    drain_frames=drain_frames)


def print_result(res, title="MOVE TRACE"):
    print(title)
    print("  %-6s %-6s %-6s %-4s %-15s %s" % HEADER)
    print("  " + "-" * 52)
    for m in res.moves:
        print("  %-6s %-6s %-6s %-4s %-15s %.3f" % m.as_row())
    print("  moves=%d  oscillations=%d  final lock=%s"
          % (len(res.moves), res.oscillations, res.final_lock))
    print("  polls evaluated=%d/%d  emissions=%d  rebase polls=%d  wpm=%.1f"
          % (res.polls_evaluated, res.n_polls, res.n_emissions,
             res.rebase_polls, res.wpm))
    print()


def main(argv):
    import argparse
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("dump")
    ap.add_argument("--surah", type=int, default=1)
    ap.add_argument("--ayat-count", type=int, default=7)
    ap.add_argument("--start", type=int, default=1)
    ap.add_argument("--surah-change", action="append", default=[],
                    metavar="SURAH:END",
                    help="scope end for a surah, repeatable; overrides "
                         "--surah/--ayat-count when given")
    ap.add_argument("--repeat", default=None, metavar="SURAH:AYAH:COUNT")
    ap.add_argument("--advance", type=float, default=0.60)
    ap.add_argument("--strong", type=float, default=0.85)
    ap.add_argument("--weak", type=float, default=0.40)
    ap.add_argument("--jump", type=float, default=0.92)
    ap.add_argument("--back", type=float, default=0.80)
    ap.add_argument("--handoff", type=float, default=0.60)
    ap.add_argument("--stuck", type=float, default=0.35)
    ap.add_argument("--tail-sec", type=float, default=1.5)
    ap.add_argument("--back-need", type=int, default=2)
    ap.add_argument("--drain-frames", type=int, default=None,
                    help="polls to run past end of file so the tail backlog "
                         "drains; default = the tail length. Use a negative "
                         "value to reproduce the un-drained bug.")
    ap.add_argument("--tail-mode", default="replay",
                    choices=("replay", "blank", "none"))
    args = ap.parse_args(argv)

    policy = LockPolicy(args.advance, args.strong, args.weak, args.jump,
                        args.back, args.handoff, args.stuck, args.tail_sec,
                        args.back_need, tail_mode=args.tail_mode)
    if args.surah_change:
        plan = []
        for spec in args.surah_change:
            s, e = spec.split(":")
            plan.append((int(s), int(e)))
    else:
        plan = [(args.surah, args.ayat_count)]
    repeat = None
    if args.repeat:
        p = [int(x) for x in args.repeat.split(":")]
        repeat = (p[0], p[1], p[2])

    with open(args.dump) as f:
        dump = json.load(f)
    res = run_dump(dump, plan, policy=policy, repeat=repeat,
                   start=args.start, drain_frames=args.drain_frames)
    print("dump      : %s (%d emissions over %d polls @ %.0f ms)"
          % (os.path.basename(args.dump), res.n_emissions, dump["frames"],
             dump.get("frame_ms", 250)))
    print("plan      : %s" % ", ".join("%d:1-%d" % p for p in plan))
    print("policy    : %s" % policy)
    print()
    print_result(res)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
