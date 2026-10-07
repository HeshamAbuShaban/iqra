# Tajweed, memorisation, and what a word-verifying app may call wrong

Written after reading the expected table rather than trusting it. Every acoustic
claim below is either something a phoneme recogniser can distinguish or it is
marked as not. Numbers were measured on the shipped assets, not estimated; where
a number is absent it is because it is not known, and it says so.

The purpose is not to teach tajweed. It is to establish what the app is allowed
to accuse a reciter of, and — more importantly — what it must never accuse them
of. The user is a serious practitioner. Everything below should be read with the
assumption that they know more about recitation than this document does, and that
anything it asserts about their practice is a claim to be checked against their
mouth, not against their app.

---

## What the app currently assumes, stated plainly

The expected table is `android/app/src/main/assets/word_aligned_phonemes.json`,
built by `engine/replay/build_word_table.py` from
`Quran-Lab/quran-tajweed-phonetics`, using the **tajweed dataset's word
boundaries** and the **Zipformer corpus's own 250-unit phones**. Measured over
all 6,236 ayat:

```
words in the table                                   77,481
distinct units used                                    220
madd units: 2-count 41,553 | 4-count 9,705 | 6-count 146
2-count ghunna unit (ںںں) occurrences                   5,304
word-initial doubled letters with no shadda in the mushaf  2,048
madd units at an ayah-final position       2-count 2,966 | 4-count 4,592 | 6-count 41
mid-ayah stop marks in the mushaf                          9,950  (3,970 ayat)
```

The 4-count units total 9,705 and the tajweed dataset marks **9,716 sites as
free-choice madd** (madd al-'aridh, muttasil, munfasil, silah kubra, leen). The
6-count units total 146 and the tajweed dataset marks **exactly 146 fixed-6
sites** (madd lazim). Per-ayah the two agree on 6,225 of 6,236 ayat.

That agreement is the whole problem in one line: **the shipped table is a single
realisation of every madd, and it picks the canonical one — 4 counts at 9,705
places where Hafs permits 2, 4 or 6.** Nine thousand seven hundred and five
sites where a reciter's legal choice differs from the table. This document is
mostly about that, and about the other realisations the table also cannot hold.

---

## 1. The two realisations of one ayah

### 1.1 What stopping physically changes

Waqf is a stop at the end of a word with the intention of resuming. The
tradition's definition insists on the breath: stopping the *sound* without
renewing the breath is not waqf, it is saktah (§4). Cross-checked against
[Islamic Studies Resources tajweed module](http://www.islamicstudiesresources.com/uploads/1/9/8/1/19819855/tajweed_module_4.pdf),
[BDIslam](http://www.bdislam.com/learnQuran/waqf.htm),
[puretajweed](https://puretajweed.org/25-rules-of-stopping-waqf/), and
[eduQuran](https://eduquran.com/en/learn-to-read-quran/rules-of-stopping/).
These four agree on all of the following.

**Harakat replaced by sukoon.** A word-final fatha, kasra or damma becomes a
sukun: `الْعَـٰلَمِينَ` → `الْعَـٰلَمِينْ`, `مَـٰلِكِ` → `مَـٰلِكْ`. Acoustically this
is the difference between releasing the consonant with a resonant vowel tail and
releasing it and stopping. It is a change in the vowel's formant trajectory and
in the voicing offset at the boundary. **Distinguishable.** A phoneme recogniser
trained on connected speech has no reason to emit a vowel there if the reciter
does not produce one.

**Tanween.** Kasratan and dammatan lose the noon entirely and become plain
sukun. Fathatan becomes a single fatha plus a two-count alif: `ظُلُمَـٰتٍۢ` →
`ظُلُمَـٰتْ`, `عَظِيمٌۭ` → `عَظِيمْ`, `مُرْتَفَقًا` → `مُرْتَفَقَا` (two counts of
alif). **Distinguishable**, and it is a change in segment count, not just
quality.

**Round ta → ha.** `ة` is read `ه` at a stop, whatever vowel it carries. The
tradition also names a small list of *taa mabsuta* written as `ت` that keep the
t at a stop (`رَحْمَتَ`, `نِعْمَتَ`, `امْرَأَتَ`, `كَلِمَتَ`, `سُنَّتَ`,
`لَعْنَتَ`, `مَعْصِيَتَ` and a few more); the distinction is purely orthographic —
determined by how the word is written, not by sound or convention. Sources:
[learnqurantajweed](https://learnqurantajweed.com/blog/waqf-rules-in-tajweed/),
[al-dirassa](https://al-dirassa.com/en/the-rules-of-the-7-alif-tawjeed-rules).

*Measured in the shipped table:* ta marbuta is rendered `ت` at 1,709 mid-ayah
positions and `ه` at 113 ayah-final positions. The table has chosen the ta
realisation everywhere inside an ayah and the ha realisation at every ayah end.
The mabsuta exception list does not appear as a separate class in the table at
all — see the gaps section.

**Dropping a madd letter before a sakin** (*man' iltiqa' al-sakinayn*, "the two
sakinah cannot meet"). If a word ends in a madd letter and the next begins with
a sakinah, the madd letter is dropped and its vowel takes over:
`وَقَالَ الحمد لله` (continuing) vs `وَقَالَا` (stopped);
`غَيْرُ مُحِلَّةِ الصَّيْدِ` vs `غَيْرُ مُحِلِّ`; `ءَامَنُوا اتَّقُوا` vs `ءَامَنُوا`. A
related family governs the noon of `مِن` and the tanween when the next word
begins with a hamzat al-wasl: the sakinah takes a fatha (`مِنَ الشَّهِدِينَ`) or a
kasrah (`أَحَدٌ اللهُ`), plural meem and waw take a damma. Sources:
[tajweed.me](https://tajweed.me/2011/12/18/preventing-two-saakins-grammer-tajweed-rule-hathf-iltiqaa-al-sakinayn/),
[learnqurantajweed on hamzat al-wasl](https://learnqurantajweed.com/blog/rules-of-hamzatul-wasl/),
and the Arabic reference
[دليل الإتقان](https://sites.google.com/view/alitkaan/%D8%A3%D8%AD%D9%83%D8%A7%D9%85-%D8%B9%D8%A7%D9%85%D8%A9/%D8%A7%D9%84%D8%AA%D9%82%D8%A7%D8%A1-%D8%A7%D9%84%D8%B3%D8%A7%D9%83%D9%86%D9%8A%D9%86).
The tajweed dataset carries this as `R131_MADD_SHORTENING` and
`R131_NOON_WIQAYA` (46 sites), both phase P5 (connected speech only).

*Measured:* the tajweed engine's default render emits `R131_MADD_SHORTENING`
**zero times** — because its rows are rendered at ayah-end waqf, where the rule
does not apply. The app's table therefore has no dropping anywhere.

**Qalqalah becoming major.** A qalqalah letter (ب ت ط ج د ق) that is sakin
bounces. Word-medially or when continuing, the bounce is *sughra*, "small",
barely there. When you stop on it the bounce strengthens to *kubra*, "large".
Cross-checked against
[surahquran](https://surahquran.com/Tajweed/qalqalah-en.html),
[learningquranonline](https://learningquranonline.com/tajweed-rules-for-qalqalah/),
and Brierley et al., *Instructions for 2006 Proceedings*
([LREC](http://www.lrec-conf.org/proceedings/lrec2014/pdf/119_Paper.pdf)), which
gives the same three-degree scheme and the same contextual triggers
(gemination + pausal position → *akbar*).

*Is the difference audible, and would a recogniser see it?* Two independent
acoustic studies say the mechanism is real and measurable. A spectrographic
analysis in [*Qalqala in Qur'ān Recitation*](https://ijbss.thebrpi.org/journals/Vol_10_No_7_July_2019/16.pdf)
records the three-segment structure of a qalqalah phone — voiced segment, a
silent segment where the articulation is closed and air is trapped, then a burst
— and measures the formants of the release across four reciters.
[*Spectrogram technique: a case study on Qalqalah letters*](https://ceur-ws.org/Vol-1539/paper2.pdf)
finds the same structure and reports first-formant values that separate ط and ق
from ب ج د. So the release is physically present and its quality varies by
reciter. **What I could not establish:** that kubra is reliably longer or louder
than sughra in a way a phoneme-level recogniser's output would reflect. The
LREC paper explicitly says that without a boundary the effect is "negligible",
and the second study concludes there is "no stability among the four reciters'
performance of qalqala sounds". The honest statement is that the phone is the
same phone with a stronger release, and the shipped vocabulary has one unit per
qalqalah letter either way.

**Waqf mutlaq / waqf tamm vs the unmarked stops.** *Waqf tamm* is a stop where
the sense is complete with no grammatical or semantic link to what follows.
*Waqf kafi* is where the sense is complete but there is a semantic link.
*Waqf hasan* is where there is a link but the local sense is complete — stopping
there is good but you must go back before resuming. *Waqf qabih* is where the
sense is incomplete and the meaning is distorted; it is forbidden except out of
necessity. These four are from al-Dani and Ibn al-Jazari, and are set out in
Arabic in [شرح متن الجزرية](https://albarmah.blogspot.com/2025/09/Al-Jazariyyah-Chapter-on-stopping-and-starting.html)
and in English in [the Ashmuni/Dani comparison](https://exa.ai/library/publication/5d1kftgb6ll).

The mushaf's printed signs are a separate axis. `ط` (waqf mutlaq) means: stop
here, and starting from the next word is correct — which happens only where the
ruling is tamm or kafi, so ط marks exactly those positions. `م` (waqf lazim) is
an obligation, not a preference. `ج` (ja'iz) permits either. `لا` forbids
stopping. `صلى` / `قلى` (rendered by some mushafs as `ص` / `ق`) are "continuing is
better" and "stopping is better". `∴` (mu'anaqah) means stop at one of the two
adjacent marks, not both. [learningquraan](https://www.learningquraan.co.uk/guides/tajweed-rules/waqf-signs/)
and [hidayahnetwork](https://hidayahnetwork.com/rules-of-stopping-in-quran-with-signs/)
give compatible tables; [abobakracademy](https://abobakracademy.com/quran-stop-signs/)
adds the useful historical note that the six-sign system is al-Sajawandi's
(560 AH) and that later printers added, dropped and rearranged signs, so which
sign appears where depends on the edition. **That last point matters for the
app** — see §7.

**Which of these rules is Hafs-specific.** Only a small number:

- **Saktah** (§4) is unique to Hafs 'an 'Asim via al-Shatibiyyah. Not one of the
  ten; not one of the turuq within Hafs. This is stated by
  [abouttajweed](https://www.abouttajweed.com/tajweed-rules/59-as-sakt-the-breathless-pause/84-the-sakt),
  [tajweedrules](https://www.tajweedrules.com/2015/06/lesson-24sakt-or-breathless-pause.html),
  and
  [Buruj Academy](https://burujacademy.com/blog/tajweed-hafs/), and Ibn al-Jazari
  records it in al-Nashr as a Hafs-specific transmission.
- **Iqlab of the noon** as a separate rule is Hafs. Warsh and al-Khashi'iyy
  have not. [al-tanzil's Al-Bayan of the turuq](https://al-tanzil.co.za/books/Al-Bayaan_Turuq.of.Hafs.B5.pdf)
  lists four `uṣūl` differences between the turuq of Hafs: the lengths of the
  madds, ghunna in the lām and rā' idghām, saktah applied consistently, and
  takbir. Iqlab is not among them because it is not a Hafs-vs-other-riwaya
  difference at all — it is Hafs-vs-other-*ṭarīq*, and al-tanzil's list shows
  how many of those exist.
- **The lām of the jalāla** — tafkhim after fatha or damma, tarqiq after
  kasra — is shared by all qurra' with two documented Hafs-specific exceptions
  in the lām rules, and the Hafs-vs-Warsh divergence on rā' tafkhim/tarqiq is
  large and specific ([Nasution 2023](https://exa.ai/library/publication/s4qyw95zb0m)).
- **Iqlab's *waqf* reading** (dropping the meem at a stop) and **madd muttasil
  admitting six counts at a stop** are Hafs-tarīq details, not riwaya-wide.

Everything else in §1 and §2 is shared across the ten readings.

### 1.2 What the shipped table does, measured

The tajweed dataset renders every row **at ayah-end waqf**. This is stated
plainly in its own dataset card: *"Rows are phonemised at ayah-end waqf.
Mid-ayah pause variants, rawm and ishmam alternates, and junction-true multi-ayah
wasl labels can all be generated with the engine."* Its phone-level flags
confirm it — `R122_TAA_MARBUTA_WAQF` 122 sites, `R201_QALQALAH_KUBRA` 422, all
at ayah-final positions, and `R130_WASL_ELISION` and `R131_MADD_SHORTENING`
**zero** times.

So the app's expected table is **waqf at every ayah end, wasl everywhere
inside an ayah**. Measured:

```
mid-ayah stop marks in the mushaf                          9,950
ayat with >= 1 mid-ayah stop mark                       3,970 of 6,236
  of those, waqf-lazim (م)                                  2,343 ayat
  of those, waqf-mutlaq (ط)                                 1,505 ayat
mid-ayah stop-marked junctions the table renders as WASL    3,638  (2,403 ayat)
  ... where the merge changes the next word's units,
      and the merged word is >= 5 units                     1,409
mid-ayah stop-marked words where the table keeps the
  final vowel (wasl) rather than substituting sukoon        5,419
```

The app's mushaf carries the stop signs as distinct codepoints: `ۭ` U+06ED
small low meem = waqf lazim (4,807, of which 3,628 mid-ayah), `ۢ` U+06E2 small
high meem = waqf mutlaq (2,445, 2,060 mid-ayah), `ۚ` U+06DA small high jeem =
waqf ja'iz (1,972, all mid-ayah), `ۖ` U+06D6 ṣalā = prefer wasl (1,682, all
mid-ayah), `ۗ` U+06D7 qalā = prefer waqf (603, all mid-ayah), `ۜ` U+06DC small
high seen = saktah (7). **The information needed to model both realisations is
already in the assets.** It is simply not used.

That last count is the concrete form of the user's complaint. At 3,638
mid-ayah junctions — including 2,343 ayat carrying a mandatory stop — the
expected table has already decided the reciter is going to continue, and it has
already applied the idghām. If the user stops there, the following word loses
its merged consonant and gains a sukun it was not going to have. One unit of
difference in a word of five or more still clears the `WRONG_MIN_HEARD_COVERAGE`
floor of 0.80 (`PhonemeMapper.kt:33`), so a **legitimate, rule-compliant stop
produces a WRONG verdict on the next word**. 1,409 such sites by the
measurement above.

---

## 2. Noon saakinah and tanween

The four rules are decided by one thing: the letter immediately after the noon
or the tanween. All four sources below agree on the letter sets and on the fact
that ghunna accompanies idgham-with-ghunnah, iqlab and ikhfa:
[alwafaainstitute](https://alwafaainstitute.com/noon-sakinah-rules/),
[equrancoaching](https://equrancoaching.com/tajweed-guide/noon-sakinah),
[riwaqalquran](https://riwaqalquran.com/blog/difference-between-ikhfa-idgham-izhar-iqlab/),
[faseeh](https://faseeh.com.sa/en/blog/rules-of-noon-saakin-and-tanween).

| rule | trigger letters | what happens acoustically | ghunna | Hafs sites |
|---|---|---|---|---|
| **izhar** | ء ه ع ح غ خ (throat letters) | /n/ released clean and complete, no nasal hold, no anticipatory coarticulation of the next consonant | no | 1,592 (`R140_IZHAR`) + 1,419 (`R140_IZHAR_HALQI`) |
| **idgham bi-l-ghunnah** | ي و م ن | /n/ assimilated into the following nasal/glide; **the next consonant is realised doubled and sustained with a nasal hum** | yes, 2 counts | 1,219 (`R141_IDGHAM_GHUNNA`) |
| **idgham bila-l-ghunnah** | ل ر | complete assimilation, fully oral, no nasal residue; next consonant still doubled | no | 697 (`R142_IDGHAM_BILA_GHUNNA`) |
| **iqlab** | ب only | /n/ **becomes** /m/ with the lips, then glides into /b/; the whole cluster is nasalised | yes | 562 (`R143_IQLAB`) |
| **ikhfa** | the other 15 | /n/ neither released nor merged: a two-count nasal hum while the tongue moves to the next articulation; the hum takes the weight of the following letter (heavy after ص ض ط ظ ق) | yes | 5,305 (`R144_IKHFA`) + 879 (`R214`, tafkhim-graded) |

Excluded: `min` before a hamzat al-wasl takes a fatha; tanween before one takes
a kasra. Both are `R131_NOON_WIQAYA`, 46 sites. And izhar *mutlaq* applies inside
a single word where a noon sakinah meets و or ي — the only four cases in the
Quran are الدنيا، بنيان، صون، قِنوان (`R141_IZHAR_MUTLAQ`, 125 sites). So idghām
is a *cross-word* rule; the tradition is explicit that `الدنيا` is not merged.

### What a recogniser actually sees

The noon is not a separate sound in three of the four rules. **The merge
deletes a segment from the end of one word and doubles a segment at the start of
the next.** That is the crux:

- **idghām.** `مِن لَدُنْ` → the table emits `مِ` + `ن` for `مِن` (bare noon, no
  vowel) and then `ل` doubled — `للِ` — for `لَدُنْ`. Measured: **2,048
  word-initial positions across 1,519 ayat** carry a doubled letter where the
  mushaf writes no shadda. Every one of those is an idghām boundary. 938 of them
  are in words of five or more units.
- **ikhfa and iqlab.** No consonant is deleted and nothing is doubled. The noon
  becomes a two-count nasal hum, written in the table as a single unit `ںںں`,
  and it is attached to the **following** word, not the preceding one. Measured:
  3,232 word-final positions, 2,030 mid-word, and 42 word-initial.
- **izhar.** The noon survives as a bare `ن` unit. Measured: 2,330 word-final
  positions carry `ن` with the next word beginning in a throat letter — 534 ع,
  806 أ, 320 إ, 207 ح, 132 خ, 122 ء, 117 ه, 81 غ. That letter distribution is
  itself the proof that the table is applying izhar rather than a default.

Consequence for the app: **at an idghām boundary the expected phoneme sequence
contains one unit fewer than the words suggest, at one word's tail and one more
at the next word's head.** A reciter who stops at that boundary produces the
opposite. This is not a subtle tajweed variation. It is a segment appearing and
disappearing, and no threshold on `WRONG_MIN_HEARD` can distinguish "they merged
the noon" from "they stopped here".

Two sub-classes that a phoneme-level recogniser structurally cannot separate:

- **ikhfa before a heavy letter vs before a light letter.** The distinction is
  that the *nasalisation itself* carries the following letter's weight
  ([quranica](https://quranica.com/articles/difference-between-ikhfa-idgham-izhar-iqlab/),
  [idealabstudios on ikhfa](https://cloudpanel01.idealabstudios.com/idealabstudios-news/ikhfa-tajweed-a-simple-guide-in-urdu-1764798724)).
  It is real but it is a difference in the *colour* of the nasal, within the one
  segment the model emits as `ںںں`. The shipped table carries one unit for all
  of them, which is the correct design choice for a recogniser and the reason
  the app must not opine on it either way.
- **madd al-layn vs madd 'aridh** (§3) — both surface as "vowel held, then a
  sakin consonant". Distinguished only by whether the stop is marked.

---

## 3. Madd

Madd is the only rule in tajweed where the tradition explicitly frames the
number of counts as a **reciter's choice within a permitted range**, and it is
the rule an app is most likely to accuse someone over. A count is not a sound.
It is a duration. The shipped table encodes duration *in the unit itself* —
`اا` is two counts, `اااا` is four, `اااااا` is six — so a table that picks one
count is asserting one duration, and the DP compares it as if it were a segment.

Sources for the categories and counts:
[learnqurantajweed's madd rules table](https://learnqurantajweed.com/blog/madd-rules/),
[quranica](https://quranica.com/articles/tajweed-madd/),
[surahquran](https://surahquran.com/Tajweed/almodood-en.html),
[Buruj on madd tabee'i](https://burujacademy.com/blog/madd-asli/), and
[Buruj on madd 'aridh](https://burujacademy.com/blog/madd-arid-li-sukoon/).
The *counts* are measured from the tajweed dataset's own register, which cites
al-Shatibiyyah, its commentaries, Ibn al-Jazari's al-Nashr and al-Tayyibah,
al-Dani's al-Taysir and Jami' al-Bayan, and Tuhfat al-Atfal, and whose
`length` field is a set-valued prescription (`allowed` / `canonical` /
`scoring`) rather than an observation.

| category | trigger | allowed counts (Hafs, al-Shatibiyyah) | ruling | sites | app table encodes |
|---|---|---|---|---|---|
| **tabee'i** (natural) | madd letter, no hamza, no sukoun after | **2, fixed** | wājib; shortening below 2 is forbidden | 39,681 | `اا` — correct |
| **badal** | hamza *before* the madd letter | **2** for Hafs (a far'i madd in other paths, where 2/4/6) | wājib at 2 | 1,860 | `اا` — correct |
| **muttasil** (connected) | madd letter + hamza, **same word** | **4 or 5** | wājib to hold; qaṣr forbidden | 1,944 | **`اااا` = 4** |
| **munfasil** (separated) | madd letter at word end + hamza, **next word** | **4 or 5** | jā'iz; some turuq read 2 | 2,895 | **`اااا` = 4** |
| **silah kubra** | hā' al-dhameer between two voweled letters + hamza | **4 or 5** | jā'iz; follows muttasil/munfasil length | 321 | **`اااا` = 4** |
| **lazim** (necessary) | madd letter + a **fixed** sukoon (waqf *and* wasl) | **6, fixed** | wājib, no choice | 146 | `اااااا` — correct |
| **muttasil at waqf** | as muttasil, but stopping on the hamza | 4, 5 **or 6** | jā'iz | 10 | **`اااا` = 4** |
| **'aridh lil-sukoon** | madd (or layn) letter + a vowel that becomes sakin at a stop | **2, 4 or 6** | jā'iz; 4 and 6 preferred | 4,536 | **`اااا` = 4** |
| **layn** | waw/ya sakinah after fatha, at a stop | **2, 4 or 6** | jā'iz | 8 | `ۦۦ` = 2 |
| **al-'ayn in kahe-asad** | as lazim but with the 'ayn factor | **4 or 6**, 6 preferred | khilaf; both transmitted | 2 | **`اااا` = 4** |
| **'iwad** | stopping on a **fathatan** | **2**, fixed | wājib | 1,823 | `اا` — correct |

Cross-checks on the disagreements, since they are the ones that matter:

- **muttasil 4-or-5 vs 4-5-6.** [learnqurantajweed](https://learnqurantajweed.com/blog/madd-rules/)
  states the Shatibiyyah range is 4 or 5 and that other turuq allow 6; the
  tajweed register's `R185_MUTTASIL` is `{4,5}` with `scoring {4,5,6}` and a
  separate `R185_MUTTASIL_WAQF` widens it to `{4,5,6}` at a stop.
  [al-Bayaan's turuq treatise](https://al-tanzil.co.za/books/Al-Bayaan_Turuq.of.Hafs.B5.pdf)
  lists six harakat for Warsh and Ibn Dhakwan. The two-tier rule in the register
  is consistent with both.
- **munfasil 2 vs 4-5.** A genuine khilaf. Qaṣr (two counts) is permitted to
  Qalun, Hisham, Abu 'Amru and Ya'qub, and *khilaf* to Hafs, per the register's
  `R186_MUNFASIL` (which nonetheless records `{4,5}` canonical 4, because the
  register follows the Shatibiyyah's transmitted default). The Malay-language
  mad-far'i study
  ([Felza Zulhibri & Nor Hafizi](https://exa.ai/library/publication/bl22sctgfrg))
  argues at length that *ja'iz* and *wajib* have been persistently
  mis-explained in modern tajweed manuals, which is the same disagreement seen
  from the other side. **I cannot resolve whether Hafs-by-Shatibiyyah admits 2
  at munfasil.** The register says no; the classical summary says khilaf. Say so
  rather than pick.
- **badal as far'i or not.** The register's `R181_BADAL` fixes 2 and counts it
  among the 1,860 tabee'i-class sites, agreeing with
  [learnqurantajweed](https://learnqurantajweed.com/blog/madd-rules/) ("for Hafs,
  read as natural madd — two counts only; not a far'i madd in Hafs's
  transmission") and disagreeing with Warsh from the Azraq path, who takes it at
  2/4/6.

**Measured, and this is the design consequence:**

```
tajweed free-choice madd sites                            9,716
app-table 4-count madd units                             9,705   (99.9% agreement)
app-table 6-count madd units                               146
tajweed fixed-6 (lazim) sites                              146   (exact)
words containing a free-choice madd                      9,611   in 5,201 ayat
  ... of which the word is >= 5 units                    5,886
ayah-final 4-count madd units                            4,592   in 4,552 ayat
```

The model vocabulary has exactly one unit per (letter, count) pair:
`اا` `اااا` `ااااا` `اااااا` for alif, `ۦۦ` `ۦۦۦۦ` `ۦۦۦۦۦ` `ۦۦۦۦۦۦ` for ya,
`ۥۥ` `ۥۥۥۥ` `ۥۥۥۥۥ` `ۥۥۥۥۥۥ` for waw. So the recogniser *can* distinguish two
counts from four from six, and there is no count it cannot express. Good. But
at 9,705 sites the expected sequence says four and the reciter may lawfully say
six — and in a word of five or more units, **one differing unit clears the 0.80
WRONG floor and the app calls it wrong.** 5,886 words are in that position.

The 4,592 ayah-final 4-count units are the sharpest case. Those exist only
because the tajweed rows are rendered at waqf. A reciter who **stops** at the end
of an ayah — the ordinary thing to do, and the default for a memoriser working
through surahs — gets 2, 4 or 6 counts there, all permitted. A reciter who
**continues** across the ayah boundary gets 2. 4,552 ayat are affected, and in
3,534 of them the final word is long enough that the floor does not save it.

> **The madd length must never produce a WRONG verdict.** Not "rarely", not
> "below a threshold". It is a permitted choice at 9,705 sites and the app
> currently asserts one answer at every one of them. This is the single largest
> class of false accusation in the product, and it is structural rather than a
> tuning problem.

---

## 4. Saktah

Saktah is a pause **without taking a breath**, about one alif long — some
sources say two harakat, some one alif, and the disagreement is a matter of
measuring a unit that is itself a matter of taste (§7.4). It is the "moulding"
of the sound, not a stop. Sources: [abouttajweed](https://www.abouttajweed.com/tajweed-rules/59-as-sakt-the-breathless-pause/84-the-sakt),
[tajweedrules lesson 24](https://www.tajweedrules.com/2015/06/lesson-24sakt-or-breathless-pause.html),
[khudzilkitab](https://www.khudzilkitab.com/2019/03/saktah-wajib-riwayat-hafs.html),
[NU Indonesia's Islam house](https://islam.nu.or.id/ilmu-al-quran/bacaen-saktah-dan-letak-letaknya-dalam-al-quran-hG10f),
and the Turkish journal article
[*Saktah according to Hafs' report of Asim*](https://dergipark.org.tr/en/pub/did/article/383256).

**The four obligatory positions under Hafs**, marked with a small `س` in the
King Fahd mushaf:

| where | words | note |
|---|---|---|
| 18:1–2 | `عِوَجَا ۜ قَيِّمًا` | also a valid stopping point (ra's al-ayah), so wasl, waqf and saktah are all permitted |
| 36:52 | `مَّرْقَدِنَا ۜ هَٰذَا` | also a valid stopping point (waqf tamm), same three-way choice |
| 75:27 | `مَنْ ۜ رَاقٍ` | **saktah only**; stopping would leave the sense incomplete |
| 83:14 | `بَلْ ۜ رَانَ` | **saktah only**, same |

Two further saktah are *permitted* (jā'iz), and these are **not Hafs-specific** —
other paths allow them: 8:75 → 9:1 (the end of al-Anfal into al-Tawbah, on the
meem of `عَلِيمٌ`) and 69:28 → 69:29 (on the hā' of `مَالِيَهْ`, where the
alternative reading is to idghām the hā' into the hā' of `هَلَكَ`). Both are
recorded as a documented khilaf in the register (`R132_MALIYAH_SAKT`, verdict
`فيه وجهان`, cited to al-Nashr 2:21-22 and three other books).

**The acoustically decisive fact, and it is the one the app needs: saktah occurs
only when CONTINUING.** Every source above says it explicitly —
*tajweedrules*: *"required … as long as he/she is reading the two words that
have a س in between them in continuum. If the reader stops between the two
words … then the sakt would not be employed, since it is now a stop."*
*khudzilkitab*: *"wajib membaca saktah apabila mewasalkan … boleh diganti waqaf
karena ra's ayat."* NU: *"Hanya berlaku pada keadaan wasal sahaja; tidak berlaku
pada keadaan waqaf."* And the tajweed register marks only three phones with
`sakt: true` (36:52, 75:27, 83:14) — because the dataset's default rendering is
waqf, and under waqf there is no saktah anywhere.

So **the presence or absence of saktah tells the app which mode the reciter
chose.** That is a rare piece of free information, and it is the opposite of a
liability. Two uses:

1. A roughly one-alif gap of *non-silent* audio at one of four specific points,
   with no breath, is evidence of wasl. An actual breath (audible, with the
   cessation of voicing and the noise of inhalation) is evidence of waqf.
   Distinguishing breath from saktah acoustically is not trivial but it is the
   ordinary VAD problem, and the app already solves an easier version of it.
2. Wherever the app infers wasl — from saktah at these four points, from the
   absence of a mandatory-stop's audible effect, from where the recogniser's
   idghām merge actually appears in the emission — it can then apply the wasl
   expected sequence at that junction *and* only that junction, instead of
   assuming wasl at all 9,950 mid-ayah marks.

*Measured:* the app's table carries a saktah marker at exactly three positions,
as a `ۜ` glyph inside a unit: `نۜ` at 75:27, `لۜ` at 83:14, and none at 36:52
(the register's third `sakt: true` phone is the alif of `مرقدنا`, which the
build maps to a plain `اا`). These three units exist in the model vocabulary.
The other three of the seven `ۜ` sites in the mushaf (2:245, 7:69, 69:28) are
in the mushaf text but not in the table's units. The app is therefore *already*
partially carrying the saktah signal in its expected sequence and does not use
it.

---

## 5. Qalqalah, tafkhim/tarqiq, wasl/qat', silent letters

### 5.1 Qalqalah

The five letters are ب ت ط ج د ق, permanently *majhūrah* (unbreathed) and
*shadīdah* (intense). The degree is graded by two independent factors — whether
the letter is word-final or geminated, and whether you stopped there
([LREC](http://www.lrec-conf.org/proceedings/lrec2014/pdf/119_Paper.pdf)):

| degree | condition | sites (tajweed register) |
|---|---|---|
| **sughra** | sakin by its own nature, mid-word or word-final *while continuing* | 3,414 (`R200_QALQALAH_SUGHRA`) |
| **kubra** | made sakin by stopping on it | 422 (`R201_QALQALAH_KUBRA`) |
| **akbar** | stopping on a **mushaddad** one, so gemination and silence and the release all coincide | 1 (`R202`, `وَتَبَّ` at 111:1) |

The third degree is disputed in scope rather than in existence: the register's
own citation notes *"أن مذهب ابن الجزري أن مIncluded ... وبعض أهل الأداء يقتصر
على مرتبتين"* — the three-degree scheme is Ibn al-Jazari's position, and some
reciters hold only two. `surahquran` presents two; `learningquranonline`
presents two and says so explicitly; Brierley et al. present three. Say two or
three, not which.

The app's table does not distinguish them: `بڇ`, `جڇ`, `دڇ`, `قڇ`, `طڇ` are
single units with no degree marker, and the `ڇ` is present at all 3,837 qalqalah
sites regardless of degree. Two of the register's degrees — the 3,414 sughra and
the 422 kubra — are therefore *indistinguishable in the expected sequence*.
**That is a defensible design choice** (see §7.1), and it should be made
deliberately and recorded as such rather than discovered later as a missing
feature.

### 5.2 Tafkhim and tarqiq

Two grades of vowel quality — full, emphatic (`mofakham`, with a rank) and
empty/thin (`morafaq`) — applied to alif, waw, rā' and lām, triggered by an
adjacent letter of isti'lā' (ء ه ع ح غ خ). This is a formant question: it is
real, measurable, and it is the difference between F2 in the region of an
emphatic and in the region of a plain vowel. The tajweed register applies
`R210_ISTILA` to 16,635 phones and `R211_REH` to 9,622 — the two largest rules
in the system after natural madd.

**Rā'** is thickened by default and softened in three situations, the last two
of which are *chained* (a sukoon preceded by a kasra preceded by a sukoon):
when it carries a kasra, when it carries a sukoon preceded by a kasra, when it
carries a sukoon preceded by a sukoon which is preceded by a kasra.
[Hidayat al-Qari via the Safina Society text](https://uploads.teachablecdn.com/attachments/YxX0qFTwSaChMzauXyGQ_Safina+Society+Tajweed+Text+updated.pdf).
**At a stop the rā' is re-derived from scratch** — the rā' rules must be
re-applied to the sakin rā', and the answer can differ from the mid-word answer.
[Buruj Academy](https://burujacademy.com/blog/tajweed-hafs/) states this
explicitly and calls it the habit that has to be trained. The tajweed register
carries the rā' khilaf words as `R211_WAQF_KHILAF` — 7 sites, each with a
recorded second transmitted *wajh* in the expert review
(`26:63 فِرْق`, `54:16 نُذُر`, `89:4 يَسْر`, plus `12:99 مِصْر`, `34:12 ٱلْقِطْر`,
`أَسْرِ`).

**Lām of Allāh** (and Allāhumma): tafkhim after a fatha or damma, tarqiq after
a kasra — original or *presented*, whether attached or detached
(`بِسْمِ اللَّهِ`, `قُلِ اللَّهُمَّ`, `أَحَدٌ اللهُ`, `قَوْمًا الله`). The dagger alif
of the name is madd lazim (six counts, 2,704 sites, `R134`), and the *lām* itself
is 1,990 sites, `R212`. Nasution's study
([2023](https://exa.ai/library/publication/s4qyw95zb0m)) sets out how far Warsh
diverges on both rā' and lām, including five named departures.

**Audibility.** Tafkhim/tarqiq is the most reliably *measurable* thing in this
document — formant frequencies are exactly what a spectrogram shows, and the
qalqalah study above measured F1/F2 values for four reciters across the emphatic
set. Whether a phoneme recogniser trained on this corpus *separates* them in its
output is a different question and is not established. Treat as: real, audible,
probably represented in training, not verified in the emission stream.

### 5.3 Hamzat al-wasl vs hamzat al-qat'

**Hamzat al-qat'** is pronounced always, in both wasl and ibtida'. **Hamzat
al-wasl** is pronounced only when you *begin* on its word and is dropped entirely
in connected speech. In the mushaf it is written with a small `ص` over the
alif. Sources: [tajweed.me](https://tajweed.me/2011/09/04/the-connecting-hamzah-hamzatul-wasl/),
[learnqurantajweed](https://learnqurantajweed.com/blog/rules-of-hamzatul-wasl/),
[iqranetwork](https://iqranetwork.com/blog/cutting-hamzah-hamzatul-qat/).

Where it occurs: the definite article `ال` (always with fatha when sounded); ten
or seven preserved nouns (always with kasra — the sources disagree on seven
versus ten, and the seven-word list is اثنتين، ابن، ابنت، اسم، امرأة، امرؤ، eye
*imāʼ*); and five- and six-letter verb forms. **Three- and four-letter verbs
never carry hamzat al-wasl** — the opening hamza is qat' — and that is the most
frequent error the sources report in learners.

The vocalisation of a sounded wasl hamza (fatha / kasra / damma, chosen from the
third letter of the verb root) is a separate matter and is where the sources
disagree most. When the interrogative hamza meets `ال`, the wasl hamza is
replaced by a lengthened alif (*ibdāl*) or softened into a half-hamza
(*tasḥīl*) — the tasheel is the preferred form under al-Shatibiyyah
([abouttajweed lesson 8](https://abouttajweed.com/tajweed-rules/56-hamzah-al-wasl/76-hamzah-al-wasl-lesson-8)).
Only three words in six places, so it does not matter much.

*Measured in the app's table:* 462 hamzat al-wasl start sites, **all at word 0 of
their ayah**. Mid-ayah, 10,517 words written with `ٱ` carry **no** hamza unit.
Ayah-initially, 566 ayat carry one. That is precisely the intended pattern: the
table sounds the wasl hamza at every ayah start (correct — a reciter starting an
ayah must sound it) and drops it mid-ayah. It is right, and it is *only* right
for a reciter who starts every ayah from rest and continues through the middle.
Both are legal; a memoriser reciting from memory across a surah boundary mid-flow
will not do this.

### 5.4 Letters written but not pronounced

- **Alif maqsurā (ى) versus alif (ا).** Not pronounced as an alif; the mushaf
  writes the same shape for both, and a reciter supplies the missing information
  from context. Audible as a *yā* — the table emits `ۦۦ` for it, in madd units.
  So this is handled.
- **Seven alifs sounded only at a stop** — `لَّٰكِنَّ` (18:38), `ٱلظُّنُونَ`
  (33:10), `ٱلرَّسُولَا` (33:66), `ٱلسَّبِيلَا` (33:67), `سَلَـٰسِيلَا` (76:4),
  `قَوَارِيرَا` (76:15), and **every occurrence of `أَنَا`**. Marked with a
  round sukun-like sign over the alif. Sources:
  [quranacademy on silent letters](https://alphabet.quranacademy.org/en/lesson/tajweed/silent-letters),
  [tajweed.me on the silent alif](https://tajweed.me/2011/11/30/the-silent-and-pronounced-alif-tajweed-quran-reading-rules-2/).
  On `سَلَـٰسِيلَا` there is a khilaf about whether stopping may be on the alif or
  only on the sukun. The tajweed register does **not** carry this rule at all —
  see gaps.
- **The `dāl` of `وَتَبَّ`** is not silent but carries the single qalqalah akbar in
  the Quran. Handled.
- **`ۭ` U+06ED after a tanween.** Measured: 4,807 sites, of which 4,807
  immediately follow a tanween. This is not a stop mark — it is the tanween's own
  Uthmani notation. It does not appear in the phoneme table at all, correctly.
- **Verse numerals.** `بِمُؤْمِنِينَ ١٩` — the number is inside the last mushaf
  word, cannot match a phoneme, and must be stripped. Already recorded in
  `WORD_ALIGNMENT_4116.md` as a systematic trap.

---

## 6. Memorisation — the part I knew least about

### 6.1 What hifz actually is

Hifz is *preservation*, not acquisition. The word is from حَفِظَ "to preserve",
which is why the whole enterprise is framed as protecting the Quran in memory,
not collecting pages. [IIUM dissertation](https://studentrepo.iium.edu.my/server/api/core/bitstreams/e4d04de5-7331-414a-b6c8-e319da445f82/content)
gives the standard definition and the etymology.

The unit of work is **sadr** — the chest, i.e. a page at a time, with a new page
starting at a verse boundary and ending at one. The [Annoor Academy Hifdh
Manual](https://annoorquranacademy.org/wp-content/uploads/2025/07/Hifdh-Manual-7-2-25.pdf)
is the most operationally detailed source I found and states the physical
reason: a mushaf whose pages begin and end at verse boundaries gives stable visual
landmarks, and landmarks are what you navigate by when you are reciting from
memory. [quranicvalues](https://quranicvalues.com/memorization-techniques-from-scholars/)
makes the same point and adds that *changing mushaf layout weakens the visual
cues*, which is an argument for the app never silently changing the layout under
a memoriser.

### 6.2 The recite-verify loop, concretely

The Annoor manual's method, which is representative of the South Asian
madrasa norm and close to what most established programmes do:

1. **Warm-up.** Read the page you intend to memorise, slowly, with attention to
   tajweed. Optionally listen to a reciter.
2. **Verse by verse.** Read one ayah 5–7 times. Close the mushaf and recite it
   without looking. If unsure, open it, read that portion 3–5 more times, close
   it, and repeat 5–7 times. Move on. Do not restart the page.
3. **Link.** Once verses 1 and 2 are individually secure, recite them together 5–7
   times. Then 1–3 together. The linking is the hard part and it is done
   explicitly, not assumed.
4. **Repeat the page.** Once the page is complete, recite it all together 5–7
   times. **Record yourself, listen back, fix mistakes.**
5. **Repeat through the day.** 4–5 times the same day, around the prayers.

Note what step 4 says: *record yourself, listen to it, fix any mistakes*. The
memoriser **is expected to review their own audio**, and the manual treats that
as a standard part of the loop rather than an optional extra. That is the single
most useful finding for this app.

The failure handling is equally informative. When a memoriser gets stuck the
prescribed response is: look at that portion, read it a few times, close the
mushaf, repeat 5–7 times, then **continue from where you were** — not restart
the page. The manual says this twice, in two different phrasings, and emphasises
it. **Re-reading and re-reciting a small piece mid-flow is normal practice, not
an error.**

### 6.3 Muraja'ah vs tilawah

- **Tilawah** is reading the Quran aloud, ordinarily with the intention of
  worship, in order to obtain reward. It is a *practice*, not a syllabus.
- **Muraja'ah** is deliberately returning to what is already memorised to keep it
  alive. [IIUM](https://studentrepo.iium.edu.my/server/api/core/bitstreams/e4d04de5-7331-414a-b6c8-e319da445f82/content)
  and the [Alibrah study](https://ejournal.stital.ac.id/index.php/alibrah/article/download/192/119/)
  both make the distinction explicit: muraja'ah is periodic, systematic, and
  by definition revisits material already presented to a teacher.

The classical apparatus for muraja'ah is the three-part daily structure, which
the [Suffah Quran Academy](https://suffahquran.com/sabaq-sabqi-manzil-hifz-review-system/)
sets out and which the [Tabarak plan](https://tabarakacademy.com/blog/hifz-revision-plan-for-students/)
and [Ilmify's teacher guide](https://ilmify.app/blog/hifz-revision-schedule/)
corroborate:

| | Arabic | what | frequency | typical load |
|---|---|---|---|---|
| **Sabaq** | سَبَق | today's new portion | daily, evaluated by a teacher | half a page to a page |
| **Sabqi** | سَبْقِي | the last 7–15 days of new memorisation | daily | 5–7 pages |
| **Manzil / dhor** | مَنْزِل / دَوْر | everything older, on a rotation | weekly cycle | scales with total; 4–5 juz daily at completion |

The load table is consistent across Tabarak and Ilmify: Juz 1–5 needs ~15–20 pages
of total daily recitation; Juz 26–30 needs ~40–60. **A complete hafiz is
reciting 40–60 pages a day, every day.** The app's session model — follow the
lock, page by page, for one surah — is a *tilawah* model, not a muraja'ah model,
and it will not look like what a hafiz actually does.

Two further constraints from the sources that bear directly on design:
**never add new sabaq until the previous day's portion is completely solid**,
recitable without a single error and without looking (Suffah; echoed by
[mubarakacademy](https://mubarakacademy.online/en/timetable-for-quran-memorization/)
— "each new page at the cost of an old one"); and **the mushaf must not be
visible during muraja'ah**, which is why [one Indonesian programme's rule of thumb
is ten pages of muraja'ah per page of new material](https://exa.ai/library/publication/8yw62l5n4hx).

### 6.4 Why a memoriser's tempo and delivery differ from a first reading

Three classical speeds are named, and Ibn al-Jazari names them: **tahqīq**
(slowest, for instruction and correction), **tadwīr** (moderate, everyday
recitation), **haḍr** (fastest, for long stretches and review). Sources:
[learnqurantajweed on the levels](https://learnqurantajweed.com/blog/levels-of-quran-recitation/),
[quranicvalues on tajweed vs tarteel](https://quranicvalues.com/difference-between-tajweed-and-tarteel/).
Two of those three are worth stating for the app, because they say opposite
things about tempo:

- The sources are explicit that **speed is not correctness**. tahqīq is *not*
  automatically more correct, and haḍr is *not* incorrect provided the letters,
  madd and ghunnah survive. "Every recognised pace must remain murattal and
  mujawwad."
- But **haḍr is specifically the pace for reviewing memorised passages.** The
  Faster you read the worse you retain, so the fastest pace is for the material
  you know best. **The best memorised material is recited fastest.**

This is the opposite of the intuition an app built for first readings would
carry. A reciter's tempo is inversely related to how well they know the passage.
Any timing heuristic that treats "slow" as "uncertain" or "fast" as "fluent" is
backwards for a memoriser.

There is also a documented finding that vocal intensity correlates positively with
syllable duration across 31 reciters
([*QURANICA* 18:1](https://jice.um.edu.my/index.php/quranica/article/view/72159)),
which is interesting and, for a phoneme recogniser, unusable — intensity is not
in the emission stream.

### 6.5 Observable acoustic behaviour that distinguishes memorisation from fluent reading

This is what the app could actually detect. Ranked by how reliably a
phoneme-level emission stream supports it.

**1. Recitation latency and restart structure.** When a memoriser loses the
thread, the manual's prescribed recovery is to re-read a short span and continue
from there. So the acoustic signature of a hesitation is: a gap, then a *partial*
re-emission of already-passed material, then continuation. Not a gap followed by
correct continuation. The current pipeline has an instrument for exactly this —
`engine/replay/hesitation_policy.py`, and `stuck` / `trailing_stall_sec` on
`TraceResult` — and it currently treats a stall as a defect
(`STATUS.md` §3: 30.9% of corpus audio time stalled, "not yet explained"). **A
memoriser's re-reading is that stall.** It is expected behaviour and should be
labelled as such.

**2. Emission repeating a word already heard.** This is the cleanest signal and
it is directly measurable from the emission log: a word index that goes backwards,
or the same word index with a different token run, after the lock has passed it.
The app already tracks lock reversals and found none on Al-Baqarah
(`STATUS.md`: "26 moves, 0 reversals") because the lock refuses to move backwards.
A memoriser *does* move backwards. That is the difference.

**3. Zero or low-idle latency at word starts.** A first reader's inter-word
gaps scale with word length and difficulty. A memoriser reciting from memory has
learned the *chunk*, not the word, so gaps cluster at the chunk boundaries — the
verse, the phrase, the waqf mark — and not inside them. **Silence distribution,
not silence duration, is the signal.** The app has no measure of this.

**4. Saktah and the four positions.** A memoriser working through surahs will
very often be *continuing* rather than stopping at every ayah end, and at 18:1,
36:52, 75:27 and 83:14 the saktah marks exactly that (§4). This is a positive
identifier of mode, and it is free.

**5. Absence of the wasl-start hamza mid-ayah.** A memoriser working from memory
across a phrase will frequently continue past a mid-ayah `م`, which means the
hamzat al-wasl is *sounded* at the start of the next word — which the table
predicts is absent. Conversely a reciter reading slowly will stop and sound it.
The app measures the hamza as a unit at ayah starts and never mid-ayah, so its
table is wrong for the memoriser at exactly the junctions where the memoriser's
behaviour differs most.

**What is not a signal.** Louder or softer: no. Slower: no, and in fact inverted
(§6.4). More hesitation overall: no — a hafiz reviewing a juz they know has
almost none. **Fewer hesitations than a first reader** is the expected finding,
and an app that rewards hesitation as "careful" will mis-model both populations.

### 6.6 What "progress" should mean for someone reciting from memory

Not accuracy percentage. Not words coloured. Here is what the sources actually
measure, and it is the same list in every one of them:

| what is measured | where |
|---|---|
| **kelancaran** — fluency, hesitation-free | the standard manzil scoring sheet in every Indonesian programme: [lembar penilaian tahfidz](https://id.scribd.com/document/648636571/FILE-MANZIL-SELURUH-HAFALAN-1) scores fluency, makhraj and tajweed **equally** and deducts 4 points per error on each |
| **makhraj** — articulation | same |
| **tajweed** — rules applied | same |
| absence of *hesitation*, specifically | [Suffah](https://suffahquran.com/sabaq-sabqi-manzil-hifz-review-system/): *"noticeable hesitation during Sabqi is a signal that the portion isn't as solid as it looked"* — hesitation is the primary weakness indicator |
| whether the reciter **looked** | [Ilmify](https://ilmify.app/blog/hifz-revision-schedule/): *"revising silently from the Mushaf … strengthens reading fluency, not memory recall"* |
| consistency **across repetitions**, not one good pass | [Annoor](https://annoorquranacademy.org/wp-content/uploads/2025/07/Hifdh-Manual-7-2-25.pdf): review the juz three times in a day, and by the third time *"the student should not have a single mistake"*; if not, do it again next day |

Two things follow that the app currently gets wrong. First, **the scoring
weight is equal across fluency, articulation and tajweed** — a reciter who
recites a page flawlessly and without hesitation has met the standard even if one
makhraj is imperfect, and vice versa. Second, **the unit of judgement is a
repetition, not a pass.** The Annoor rule is that a portion is *consolidated*
when it survives three consecutive correct repetitions, and the surah/juz is
re-taught if it does not.

So a defensible definition for the app:

> **Progress = the number of consecutive repetitions of a page or juz in which
> the reciter produced every word with no omission, no wrong consonant, and no
> hesitation longer than one count — plus, separately, the reciter's own rating
> after listening back to their own recording.**

That last clause is not decoration. The Annoor manual makes listening back to
one's own recitation a standard step. A tool that never lets the reciter hear
themselves is omitting a step that the tradition considers part of the work.

---

## 7. Design implications

### 7.1 A worked table: what may never be WRONG, what may, and what is real error

| rule | mark WRONG? | what to do instead | why |
|---|---|---|---|
| **madd length** (any category) | **never** | accept any count in the transmitted range; report the observed count | 9,705 free-choice sites; a count is a duration, not a segment. `allowed` in the register is a *set*; `canonical` is a default, and the dataset card says so explicitly |
| **stopping on a waqf mark** | **never** | detect the stop, apply the wasl or waqf expected sequence to that junction | 9,950 mid-ayah marks; 3,638 already render as wasl; 2,343 ayat carry a mandatory stop |
| **continuing past a stop mark** | **never** | same | "when in doubt, continue" is the standard fallback |
| **not taking madd al-'iwad** at a fathatan stop | never | — | `R121_MADD_EWAD`, 1,823 sites; the source says the tanween-fath *seat alif is silent in wasl*, so under wasl there is no alif to accuse |
| **ikhlāf on qalqalah degree** | never | — | sughra/kubra are one unit in the vocabulary; the *acoustic* difference is a release-strength difference that is not reliably separable (§5.1) |
| **ikhfa before heavy vs light letter** | never | — | the distinction is the colour of one nasal segment; one unit covers all of them |
| **rawm vs ishmam** | never | — | register has 0 sites of each in the default rendering; only 1 ishmam (12:11) and 3 imala (11:41) exist and they are not app-visible |
| **tafkhim/tarqiq** | never as a word verdict | could be a separate, opt-in acoustic check | real and measurable in formants, but nothing establishes the recogniser separates it, and it is not a consonantal error |
| **saktah present/absent** | never | use it as *mode evidence* (§4) | only 3 table sites carry a marker today; it is the one signal that tells the app which realisation to expect |
| **ghunna length** | never | accept | 5,304 `ںںں` sites, fixed 2 counts in the register, but 1 and 3 counts are attested reciter practice |
| **iqlab before bāʾ vs ikhfa** | never | — | both surface as a nasal transition; the app cannot see which |
| **silent alef** (7 words + `أَنَا`) | never | expected table must not contain a vowel there | the table never does (0 mid-ayah ta-marbuta-in-waqf, 0 silent-alif sites) |
| **waqf on the seven "taa mabsuta"** | never | — | orthographic exception the table does not model at all; would be a guaranteed false WRONG |
| **omitted word** | **YES** | — | `ok * 2 < total` → SKIPPED, already correct; this is real and unambiguous |
| **wrong consonant** | **YES** | — | substitution at the consonant, `bad > 0 && ok >= 0.80 * total`, already correct |
| **wrong vowel on a consonantal word** | **YES, but raise the floor** | require ≥ 2 differing units for short words | a 2-unit word with one wrong vowel is 50% — currently SKIPPED by `ok*2 < total`; a 3-unit word with one wrong vowel is 67% — currently UNKNOWN; neither is reachable as WRONG, which is correct |
| **hamzat al-qat' dropped** | **YES** | — | qat' is pronounced always; dropping it is a genuine error, `R110_BADAL_IBTIDA` |
| **harakat al-wasl sounded mid-ayah** | never | — | correct in wasl |
| **harakat al-wasl dropped at a start** | **YES** | — | correct at ibtida' |

### 7.2 Where error detection is still legitimate, and why

Omission and substitution at a **consonant** are the two things that survive
everything above, and they are the only two things that should. The reasoning:

- **A wrong consonant changes what was said.** ض for ظ, س for ش, ح for ه — these
  are not durations or colours, they are different places of articulation, and
  the LREC and spectrogram studies show the recognition problem is precisely
  about *place*, not *length*.
- **An omission removes a word.** No tajweed rule deletes a whole word. A word
  is either said or not said. Nothing in §§1–5 makes a word optional.
- **Everything else is a parameter inside a segment, or a boundary decision.**
  Madd and ghunna are durations. Qalqalah degree, tafkhim and ikhfa weight are
  release and formant properties. Saktah and the noon rules are boundary
  decisions. Stop-or-continue is a boundary decision. A DP over a token stream
  with a 0.80 hearing floor has no way to say "this substitution is a legitimate
  alternative" — it can only say "these tokens differ".

So the verdict function should be: **substitution at a consonantal unit, in a
word whose consonants are otherwise heard, is WRONG. Everything else is either
ignored or reported as an observation.**

### 7.3 The two changes that would remove most false accusations

**First: make madd length free.** The register already carries the permitted set
per site. The table build already reads the tajweed labels. Emitting a *set* of
acceptable units per madd position, rather than one unit, and having the DP
treat "any member of the set" as a match, is a small change with a large effect:
it removes the false-accusation class at 5,886 words. It requires no new data
source and no re-derivation of the unit vocabulary — `build_word_table.py` would
emit a parallel set-valued column alongside the existing units, and the flat
sequence assertion (`flat identical to today's table`) would still hold because
recognition is unchanged.

**Second: make the junction's realisation a decision, not an assumption.** The
mushaf asset already carries 9,950 mid-ayah stop marks with their grades. When
the app can tell that the reciter stopped (an audible breath, or a sukun where
wasl predicts a vowel), it should align the *next* word against the waqf
expectation instead of the wasl one. This is the difference between a table that
has already decided and a table that has two options and picks the right one.
It also removes the 1,409 sites where a legitimate stop currently produces a
wrong next word.

Both are, notably, *less* work than a tolerance heuristic. A tolerance
heuristic has to guess at thresholds. The set-valued madd has the answer written
down in the source. The two-way junction has the marks written down in the
mushaf.

### 7.4 Where I would stop short of asserting things

- **How long a harakat is.** The literature is explicit that the
  finger-movement and one-second definitions are impractical and that a
  harakat's duration varies with the recitation level
  ([the harakat-measurement study](http://testmagzine.biz/index.php/testmagzine/article/download/6975/5383/12234)).
  Two counts at haḍr and two counts at tahqīq are not the same duration. **Any
  count in the table is therefore a relative measure at best**, and this is an
  independent reason madd cannot be graded.
- **The 31 surah-opening ayat** the app already leaves UNKNOWN, where the tajweed
  text and the mushaf segment the bismillah differently (`STATUS.md` §2). Leave
  them UNKNOWN. Guessing is worse than the gap.
- **The forbidden fourth basmala join.** `R136_BASMALA_JOINS` exists in the
  register and enumerates three legal joins, but the *rule* has 0 sites in the
  default rendering. Whether the app should ever model a basmala join is out of
  scope for word verification; it is a surah-level concern.
- **Ta marbuta in wasl.** The sources I read agree that `ة` is read `ه` at a stop
  and are less consistent about wasl, where the Uthmani orthography writes it
  over a letter that is *not* a silent alif. The shipped table reads mid-ayah
  `ة` as `ت` (1,709 sites). See gaps.

---

## 8. What I could not establish

Stated plainly, because the user will check these against their own practice
and should know which claims are load-bearing and which are not.

1. **Whether the hub al-wāṣil of `ة` in wasl is `ه` or `ت`.** The tajweed
   register carries `R122_TAA_MARBUTA_WAQF` for 122 waqf sites and carries
   nothing for wasl; the shipped table reads it as `ت` at 1,709 mid-ayah
   positions. Every source I found is clear about the waqf reading and silent
   about wasl. This is a real gap and it affects 1,709 sites.

2. **The taa mabsuta exception list.** The orthographic exception (a small fixed
   set of words where `ت` keeps its `t` at a stop) is described by
   [learnqurantajweed](https://learnqurantajweed.com/blog/waqf-rules-in-tajweed/)
   with a word list. The tajweed register does **not** carry it. The app would
   mark those words WRONG at a stop and there is no data to prevent it.

3. **The seven silent alefs.** 462 hamzat al-wasl sites are in the register and
   the table handles them. The *seven* silent alefs (six words plus every `أَنَا`)
   are in three sources and in **no** part of the register, the table, or the
   mushaf asset's phoneme data. If a reciter sounds the alif in `أَنَا` at a stop,
   or fails to sound it in `قَوَارِيرَا`, the app cannot tell. Small in number,
   but `أَنَا` is common.

4. **Whether muttasil admits 2 counts under Hafs-by-Shatibiyyah.** The register
   says `{4,5}` and the classical summary as I found it says khilaf. Unresolved;
   see §3.

5. **Whether qalqalah has two or three degrees** as a practical matter. Both
   positions are held by sources, and the register's own note records the
   disagreement. Immaterial to design — the app should use one unit either way.

6. **How audible kubra-versus-sughra is to *this* recogniser.** The acoustic
   difference is real and has been measured in the signal
   (§5.1, two independent studies). That it changes the *emitted token* is not
   established, and the studies are explicit that there is no stability across
   reciters. Do not build on it without measuring it on the emission stream.

7. **Whether the recogniser separates tafkhim from tarqiq.** The signal is real
   and measurable in formants. Whether the emission stream carries it is
   unknown. The single most likely thing to check first with the existing token
   dumps, since 26,257 sites carry one of the two isti'lā'/rā' rules.

8. **How a hafiz's silence distribution actually differs from a first reader's.**
   §6.5 item 3 is a reasoned prediction from the practice, not a measurement. It
   needs six real clips — which `STATUS.md` already flags as the outstanding gap
   under "Hesitation clips".

9. **What a reciter's own rating after listening back correlates with.** The
   practice is standard and documented; the correlation with anything measurable
   is not, because nobody has instrumented it.

10. **The aṣ-ṣādḥ (sadr) boundary in a *digital* mushaf.** Every source assumes
    a printed mushaf with fixed pages. The app's page model is the mushaf.json
    page structure, and I have not checked that its page boundaries coincide with
    the 604-page Madinah layout a memoriser navigates by. If they do not, the
    `۞` positions in the asset — 199 of them — are not the landmarks the user
    navigates by. **Worth checking before any memorisation mode is designed.**

---

## Sources

Rule content and counts were cross-checked across at least two independent
sources in every case; where they disagree it is said above. Primary:

- **Quran-Lab/quran-tajweed-phonetics** — the dataset the app's table is built
  from. 6,236 ayat, 522,475 phones, each carrying madd class with transmitted
  length range, ghunna grade, qalqalah class, tafkhim with rank, sakt, the
  seventeen sifat, and the rule that produced it. Rule counts, `length.allowed`
  sets, `qalqalah` and `ghunna` grades, and the phase structure are all measured
  from its `rule_index.jsonl`, `rulings.jsonl` and `register_reviewed.jsonl`.
  <https://huggingface.co/datasets/Quran-Lab/quran-tajweed-phonetics>
  Engine: <https://github.com/Quran-Lab/quran-g2p>. Its dataset card states the
  render convention directly: *"Rows are phonemised at ayah-end waqf"* and
  *"Do not treat canonical values as ground truth at free-choice positions."*
- **al-Shatibiyyah and its commentaries, Ibn al-Jazari's al-Nashr and
  al-Muqaddimah, al-Dani's al-Taysir and Jami' al-Bayan, Tuhfat al-Atfal,
  Hidayat al-Qari** — cited by the register for every one of its 62 engine
  rulings. Where I needed a rule's content rather than its count I went to the
  explanatory sources below.
- **al-tanzil, Al-Bayan Turuq of Hafs** — the four `uṣūl` differences between
  Hafs's turuq: madd lengths, ghunna in the lām/rā' idghām, saktah consistency,
  takbir. <https://al-tanzil.co.za/books/Al-Bayaan_Turuq.of.Hafs.B5.pdf>

Explanatory, per section:

- §1 waqf: <http://www.islamicstudiesresources.com/uploads/1/9/8/1/19819855/tajweed_module_4.pdf>,
  <http://www.bdislam.com/learnQuran/waqf.htm>,
  <https://puretajweed.org/25-rules-of-stopping-waqf/>,
  <https://eduquran.com/en/learn-to-read-quran/rules-of-stopping/>,
  <https://www.learningquraan.co.uk/guides/tajweed-rules/waqf-signs/>,
  <https://hidayahnetwork.com/rules-of-stopping-in-quran-with-signs/>,
  <https://abobakracademy.com/quran-stop-signs/>
- §1 tamm/mutlaq: <https://albarmah.blogspot.com/2025/09/Al-Jazariyyah-Chapter-on-stopping-and-starting.html>,
  <https://exa.ai/library/publication/5d1kftgb6ll>
- §1 two sakinah: <https://tajweed.me/2011/12/18/preventing-two-saakins-grammer-tajweed-rule-hathf-iltiqaa-al-sakinayn/>,
  <https://sites.google.com/view/alitkaan/%D8%A3%D8%AD%D9%83%D8%A7%D9%85-%D8%B9%D8%A7%D9%85%D8%A9/%D8%A7%D9%84%D8%AA%D9%82%D8%A7%D8%A1-%D8%A7%D9%84%D8%B3%D8%A7%D9%83%D9%86%D9%8A%D9%86>
- §2 noon: <https://alwafaainstitute.com/noon-sakinah-rules/>,
  <https://equrancoaching.com/tajweed-guide/noon-sakinah>,
  <https://riwaqalquran.com/blog/difference-between-ikhfa-idgham-izhar-iqlab/>,
  <https://faseeh.com.sa/en/blog/rules-of-noon-saakin-and-tanween>,
  <https://quranica.com/articles/difference-between-ikhfa-idgham-izhar-iqlab/>
- §3 madd: <https://learnqurantajweed.com/blog/madd-rules/>, <https://quranica.com/articles/tajweed-madd/>,
  <https://surahquran.com/Tajweed/almodood-en.html>, <https://burujacademy.com/blog/madd-asli/>,
  <https://burujacademy.com/blog/madd-arid-li-sukoon/>,
  <https://riwaqalquran.com/blog/comparison-madd-mutasil-madd-munfasil-with-examples/>,
  and the mad-far'i dispute at <https://exa.ai/library/publication/bl22sctgfrg>
- §4 saktah: <https://www.abouttajweed.com/tajweed-rules/59-as-sakt-the-breathless-pause/84-the-sakt>,
  <https://www.tajweedrules.com/2015/06/lesson-24sakt-or-breathless-pause.html>,
  <https://khudzilkitab.com/2019/03/saktah-wajib-riwayat-hafs.html>,
  <https://islam.nu.or.id/ilmu-al-quran/bacaen-saktah-dan-letak-letaknya-dalam-al-quran-hG10f>,
  <https://dergipark.org.tr/en/pub/did/article/383256>,
  <https://pengajianalhira.com/tajwid/hukum-saktah>
- §5 qalqalah, acoustic: <http://www.lrec-conf.org/proceedings/lrec2014/pdf/119_Paper.pdf>,
  <https://ijbss.thebrpi.org/journals/Vol_10_No_7_July_2019/16.pdf>,
  <https://ceur-ws.org/Vol-1539/paper2.pdf>; rules:
  <https://surahquran.com/Tajweed/qalqalah-en.html>,
  <https://learningquranonline.com/tajweed-rules-for-qalqalah/>
- §5 tafkhim/tarqiq: <https://uploads.teachablecdn.com/attachments/YxX0qFTwSaChMzauXyGQ_Safina+Society+Tajweed+Text+updated.pdf>,
  <https://exa.ai/library/publication/s4qyw95zb0m>, <https://burujacademy.com/blog/tajweed-hafs/>
- §5 wasl/qat': <https://tajweed.me/2011/09/04/the-connecting-hamzah-hamzatul-wasl/>,
  <https://learnqurantajweed.com/blog/rules-of-hamzatul-wasl/>,
  <https://learnqurantajweed.com/blog/hamzatul-qat-in-tajweed/>,
  <https://iqranetwork.com/blog/cutting-hamzah-hamzatul-qat/>,
  <https://abouttajweed.com/tajweed-rules/56-hamzah-al-wasl/76-hamzah-al-wasl-lesson-8>
- §5 silent letters: <https://alphabet.quranacademy.org/en/lesson/tajweed/silent-letters>,
  <https://tajweed.me/2011/11/30/the-silent-and-pronounced-alif-tajweed-quran-reading-rules-2/>
- §6 hifz: <https://annoorquranacademy.org/wp-content/uploads/2025/07/Hifdh-Manual-7-2-25.pdf>,
  <https://suffahquran.com/sabaq-sabqi-manzil-hifz-review-system/>,
  <https://tabarakacademy.com/blog/hifz-revision-plan-for-students/>,
  <https://ilmify.app/blog/hifz-revision-schedule/>,
  <https://mubarakacademy.online/en/timetable-for-quran-memorization/>,
  <https://quranicvalues.com/memorization-techniques-from-scholars/>,
  <https://studentrepo.iium.edu.my/server/api/core/bitstreams/e4d04de5-7331-414a-b6c8-e319da445f82/content>,
  <https://www.atlantis-press.com/article/126018105.pdf>,
  <https://ejournal.stital.ac.id/index.php/alibrah/article/download/192/119/>
- §6 levels of recitation: <https://learnqurantajweed.com/blog/levels-of-quran-recitation/>,
  <https://quranicvalues.com/difference-between-tajweed-and-tarteel/>
- §6 harakat measurement: <http://testmagzine.biz/index.php/testmagzine/article/download/6975/5383/12234>
- §6 scoring: <https://id.scribd.com/document/648636571/FILE-MANZIL-SELURUH-HAFALAN-1>
- Existing project context: `docs/STATUS.md`, `docs/DEFECTS_AND_METHOD.md`,
  `docs/WORD_ALIGNMENT_4116.md`