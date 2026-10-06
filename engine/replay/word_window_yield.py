#!/usr/bin/env python3
"""How many words actually get a verdict, with the window rule the app uses.

NOT IN THE CI GATE - it replays real corpus audio through the DP and takes
minutes. Run it by hand after touching the evidence window, the lock thresholds
or the verdict rules. `evidence_window_parity.py` is the fast structural guard
and runs on every build; this is the measurement behind it.

WHAT IT ANSWERS
---------------
Found on the device, reading the session records: a 302 s recitation of
2:59-2:76 that the lock followed correctly to 0.933 coverage produced

    365 SKIPPED, 24 UNKNOWN, 0 CORRECT, 0 WRONG

and SKIPPED painted wrongColor with a strikethrough, so "no evidence" was drawn
as "you got this wrong". Recognition was never the problem.

WHY, and why the obvious fix was wrong
--------------------------------------
Words were judged against `obs`, the emission slice since the last lock move.
That slice is rebased on every move, so the audio that would judge an ayah's
words was gone the moment the lock left it.

Judging ayah N against [arrival(N), arrival(N+1)) looks right and is not: the
lock advances when the reciter is ADVANCE_COVERAGE (0.60) through the target, so
on ARRIVING at N you are already 60% of the way through it. N's first 60% is
still in the PREVIOUS slice. Measured over 1,045 real words:

    window [arrival(N), arrival(N+1))        reached CORRECT   5.3%
    window [arrival(N-1), arrival(N+1))     reached CORRECT  93.4%

Both facts are asserted in evidence_window_parity.py; this measures the yield.

Measured on Al-Dosari gold audio, 1,378 words over 4 surahs:

    CORRECT 1307 (94.8%)   WRONG 52 (3.8%)   UNKNOWN 16 (1.2%)   SKIPPED 3 (0.2%)
"""
import json,os,sys
sys.path.insert(0,'engine/replay')
from collections import Counter
import lock_trace as L
from word_verdicts import align
wt=L.load_word_table() or {}
ADV=0.60; FLOOR=0.80
def exp_of(s,a):
    wa=wt.get("%d:%d"%(s,a))
    if not wa: return None
    q=[u for w in wa for u in w]; uw=[]
    for wi,w in enumerate(wa): uw.extend([wi]*len(w))
    return q,uw,len(wa)
def ayah_obs(syms,arrival,s,a,LIMIT=480):
    """Mirror of PracticeViewModel.ayahObs."""
    if a not in arrival: return None
    frm=arrival.get(a-1,0); until=arrival.get(a+1,len(syms))
    if arrival[a]<frm: return None
    end=min(until,len(syms))
    if end<=frm: return []
    st=max(frm,end-LIMIT)
    return syms[st:end]
def verdicts(matched,wrong,uw,m):
    out=[]
    for wi in range(m):
        idx=[k for k,x in enumerate(uw) if x==wi]
        t=len(idx); o=sum(1 for k in idx if matched[k]); b=sum(1 for k in idx if wrong[k])
        if t==0: out.append('SKIPPED'); continue
        if o==t: out.append('CORRECT'); continue
        if o*2<t: out.append('SKIPPED'); continue
        if b>0 and o>=(t*FLOOR): out.append('WRONG'); continue
        out.append('UNKNOWN')
    return out
tot=Counter(); per_surah={}
for sur in (1,36,55,67):
    f='engine/corpus/out/%03d.json'%sur
    if not os.path.isfile(f): continue
    d=json.load(open(f,encoding='utf-8')); byf=L.group_frames(d)
    syms=[]; arrival={}; lock=1; streak=0; cand=None; lastMove=0
    for pi in range(d["frames"]):
        ch=byf.get(pi,[])
        if ch: syms.extend(e["symbol"] for e in ch)
        if len(syms)==lastMove: continue
        e=exp_of(sur,lock+1)
        if not e: continue
        q,uw,m=e
        mtc,_,_,_,_,_=align(syms[lastMove:],q,uw)
        cov=sum(1 for x in mtc if x)/float(len(q))
        if cov>=ADV:
            if cand==lock+1: streak+=1
            else: cand=lock+1; streak=1
            if streak>=2:
                arrival[lock+1]=len(syms); lock+=1; cand=None; streak=0; lastMove=len(syms)
        else: cand=None; streak=0
    arrival.setdefault(1,0)
    sc=Counter()
    for a in sorted(arrival):
        e=exp_of(sur,a)
        if not e: continue
        q,uw,m=e
        w=ayah_obs(syms,arrival,sur,a)
        if w is None:   # ahead of the lock: UNKNOWN, nothing earned
            sc['UNKNOWN']+=m; continue
        mtc,wr,_,_,_,_=align(w,q,uw)
        v=verdicts(mtc,wr,uw,m); sc.update(v); tot.update(v)
    per_surah[sur]=sc
    print("  surah %-3d %s"%(sur,dict(sc))); sys.stdout.flush()
n=sum(tot.values()) or 1
print("\nTOTAL per-word verdicts with the shipped window rule (%d words):"%n)
for k in ('CORRECT','WRONG','UNKNOWN','SKIPPED'): print("   %-8s %5d  %5.1f%%"%(k,tot[k],100*tot[k]/n))
verd=tot['CORRECT']+tot['WRONG']
print("\nreal verdict rate: %.1f%%   painted red without cause: %.1f%%"%(100*verd/n,100*tot['SKIPPED']/n))
