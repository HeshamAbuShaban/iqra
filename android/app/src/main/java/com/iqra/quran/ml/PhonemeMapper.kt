package com.iqra.quran.ml

import android.util.Log
import com.iqra.quran.data.WordStatus
import org.json.JSONObject
import java.io.File

/**
 * Maps zipformer phoneme emissions onto mushaf words using the canonical
 * phonemisation table (ordered_quran_phonemes.json: "S:A" ->
 * {"aya_phonemes_list": [...]}). Pure logic, no audio, no Android UI.
 *
 * CRITICAL: the table's `aya_phonemes_list` entries are per-WORD phoneme
 * STRINGS ("بِسمِ"), but the model emits per-PHONEME UNITS ("بِ", "س", "مِ").
 * Comparing them directly yields coverage of exactly 0.00 - no ayah can ever
 * be detected. Every expected word is therefore EXPLODED into the model's own
 * unit inventory (tokens.txt) before alignment, with a unit->word map kept so
 * per-word verdicts still work.
 */
/**
 * Fraction of a word's own units that must be heard before a WRONG verdict is
 * claimed; below it a contradiction yields UNKNOWN, meaning no verdict.
 *
 * A word cannot be pronounced wrongly on the part of it nobody heard, and the DP
 * will attribute a neighbour's phonemes to a word it only caught part of -
 * especially once a slice rebase lands mid-word, which happens on every lock
 * move. Measured on 20 Al-Dosari surahs (34059 CORRECT, 1454 WRONG): 40 % of the
 * WRONG verdicts sat on words heard below 0.80 of their own units, and were
 * reported on device as the app "wronging" the reciter. It is the same threshold
 * as the existing "barely covered means it was not said" rule, applied to the
 * other side of the word.
 */
internal const val WRONG_MIN_HEARD_COVERAGE = 0.80f

// ---- free-choice madd ------------------------------------------------------
//
// ~19% of madd sites (9,706 of 51,395) are free choice, where the register
// lists several legal lengths and the shipped table asserts only the canonical
// one. A reciter holding a DIFFERENT legal length is not in error, so all
// lengths of the SAME madd bearer intern to one id and score as a match.
// A 'اا' is never a 'وو' or a 'يي'.
private val ALEF_MADD = setOf("اا", "ااۜ", "اااا", "ااااا", "اااااا")
private val WAW_MADD = setOf(
    "وو", "ووو", "ووَ", "ووُ", "ووِ", "وووَ", "وووُ", "وووِ",
    "ۥ", "ۥۥ", "ۥۥۥ", "ۥۥۥۥ", "ۥۥۥۥۥ", "ۥۥۥۥۥۥ",
)
private val YAA_MADD = setOf(
    "يي", "ييي", "ييَ", "ييُ", "ييِ", "يييَ", "يييُ", "ييييي",
    "ۦ", "ۦۦ", "ۦۦۦ", "ۦۦۦۦ", "ۦۦۦۦۦ", "ۦۦۦۦۦۦ",
)

@Volatile var maddEquivalenceMatching: Boolean = true
    private set

// ---- final vowel on a word-final consonant ---------------------------------
//
// 26,733 of the table's 77,481 words - 34.5% - end on a bare consonant with no
// i'rab vowel: 'يُنفِقُونَ' is stored as `يُ ںںں فِ قُ ۥۥۥۥ ن`, so the last unit is a
// bare `ن`. The model has no such thing: it emits `نَ`, because a reciter says
// the fatḥa. `ن` and `نَ` are DIFFERENT tokens in tokens.txt, so the DP charges
// the recited vowel as a substitution against that final consonant.
//
// Measured on the user's own 10 October session, using the blame position the
// record now carries: 9 of its 10 WRONG words were blamed on the LAST unit, and
// they are almost all the plural masculine and pronoun endings - 'ٱلضَّآلِّينَ',
// 'يُنفِقُونَ', 'ٱلْمُفْلِحُونَ', 'بِمُؤْمِنِينَ', 'يَعْمَهُونَ', 'وَتَرَكَهُمْ',
// 'يَرْجِعُونَ'. None of them carries a silent-letter mark, so the optional-final
// guard could not see them.
//
// The reference reciter proves the mechanism. In Al-Baqarah he emits `نَ` 680
// times and bare `ن` 450 times, so both forms are real and neither can be
// discarded. The fix therefore mirrors the madd rule: intern every variant of
// ONE word-final consonant to one id, so the DP accepts whichever the reciter
// chose. Like the madd rule, it can only ever remove blame - it cannot accuse.
// Letters measured to end a word BARE in the table while the mushaf prints a
// vowel on them: nun 2,936, ha 2,169, ra 248, mim 228, dal 104, ba 93, ta-marbuta
// 49, lam 41, qaf 24, kaf 13, sin 10.
//
// Waw and ya are deliberately ABSENT. As semi-vowels they carry vowels of their
// own - the fatha on waw is the fatha of 'وَاو' - so relaxing them would hide a
// real error. The list is measured, not chosen: engine/replay/final_vowel_parity.py
// asserts every letter here and asserts that waw and ya stay out.
private val FINAL_VOWELLESS = "نهمردبةقلسعجثزتفطظشح"
private val SHORT_VOWELS = charArrayOf('َ', 'ُ', 'ِ')

/**
 * The id for a word-FINAL consonant, ignoring the case vowel on it.
 *
 * Only applied to the final unit of a word, which is why [canonicalId] cannot do
 * it: the same token can legitimately be mid-word, where its vowel matters.
 * 'ن' inside 'مِن' is a different sound from the 'ن' that ends 'مِنْ'.
 */
private fun finalVowelId(u: String): String? {
    if (u.isEmpty()) return null
    val last = u.last()
    if (last in SHORT_VOWELS) {
        val stem = u.dropLast(1)
        if (stem.isNotEmpty() && stem.last() in FINAL_VOWELLESS) return "<fin-${stem.last()}>"
        return null
    }
    if (last in FINAL_VOWELLESS) return "<fin-$last>"
    return null
}

private fun canonicalId(u: String): String {
    // Restoring single-realisation matching (maddNeverAccuses=false) is a
    // MEASUREMENT mode: the DP must then distinguish "held 4" from "held 2",
    // which it cannot do reliably, so the same reciter scores wrong more
    // often. It must never be the basis for a verdict - the pref doc says so,
    // and this branch is the only thing that makes the setting real.
    if (!maddEquivalenceMatching) return u
    return when {
        u in ALEF_MADD -> "<madd-alef>"
        u in WAW_MADD -> "<madd-waw>"
        u in YAA_MADD -> "<madd-ya>"
        else -> finalVowelId(u) ?: u
    }
}

/**
 * The same equivalence, for a unit known to be the LAST of its word.
 *
 * The expected side is canonicalised through [canonicalId], which cannot know
 * position, so the last unit of every word is rewritten here before interning.
 * Without this the DP compares the table's bare `ن` against the reciter's `نَ`
 * and calls it a mistake.
 */
internal fun finalUnitId(u: String): String = finalVowelId(u) ?: canonicalId(u)

/**
 * Look up an emitted symbol that only matches a word-FINAL unit.
 *
 * The emitted side cannot know which units end a word, so this is a fallback
 * consulted only after [canonicalId] missed. It returns -1 when the ayah does not
 * assert that consonant at any word's end, which keeps the substitution intact.
 */
private fun finalEmissionId(u: String, ids: Map<String, Int>): Int {
    val fv = finalVowelId(u) ?: return -1
    return ids[fv] ?: -1
}

object PhonemeMapper {
    private const val TAG = "PhonemeMapper"

    /**
     * The evidence floor in force right now, which the user's strictness setting
     * can lower. Read from [heardCoverageFloor], set once at engine start.
     *
     * It is a `var` rather than a `const` because a setting must be able to
     * change it, and the recorded constant stays the validated default: a session
     * record carries the floor actually in force, so a number produced under a
     * lowered floor is never mistaken for one produced under the validated one.
     */
    @Volatile var WRONG_MIN_HEARD: Float = WRONG_MIN_HEARD_COVERAGE
        private set

    /**
     * Apply the user's strictness. Called once when the engine starts, never
     * mid-session: changing the floor between two frames of one session would
     * make the verdicts incomparable, which is worse than a strict setting.
     */
    fun setHeardCoverageFloor(floor: Float) {
        WRONG_MIN_HEARD = floor.coerceIn(0.30f, 0.95f)
        Log.i(TAG, "WRONG evidence floor set to $WRONG_MIN_HEARD (validated default $WRONG_MIN_HEARD_COVERAGE)")
    }

    /**
     * Apply the madd-tolerance preference, once at engine start, never
     * mid-session (same contract as [setHeardCoverageFloor]). Default true is
     * the only judging mode: madd length must never decide a verdict. False
     * restores single-realisation matching for measuring only.
     *
     * Clears the expected-unit cache because every ayah's symbol-id table is
     * built through canonicalId; leaving it would let one session be judged by
     * two rules.
     */
    @Synchronized
    fun setMaddEquivalence(on: Boolean) {
        if (maddEquivalenceMatching == on) return
        maddEquivalenceMatching = on
        expectedCache.clear()
        Log.i(TAG, "madd-equivalent matching set to $on")
    }

    @Volatile private var table: Map<String, List<String>>? = null

    /** Model unit inventory, longest-first for greedy matching. */
    @Volatile private var unitsByLength: List<String>? = null
    @Volatile private var unitSet: Set<String>? = null
    private val expectedCache = HashMap<String, Expected>()

    /**
     * Word-aligned units per ayah, from `word_aligned_phonemes.json`.
     *
     * The canonical table segments an ayah on phoneme-PHRASE boundaries: idgham
     * across a word boundary, or a waqf mark, merges two Mushaf words into one
     * entry. The word count is therefore lost by the physics of connected
     * recitation, and `PracticeViewModel` refuses to judge an ayah whose counts
     * disagree - which silently left 4,116 of 6,236 ayat (66%) with no colouring
     * at all. Not a rendering fault: no status reached the renderer.
     *
     * This table carries the same model units, attributed to Mushaf words by the
     * Quran-Lab tajweed dataset (`word_index` per phone, with its own lab's
     * bijection onto the old 250-unit inventory, stated as aligned across all
     * 6,236 ayat with 0 mismatches). It is the SAME units, so the flat sequence
     * the DP runs over is identical for 6,170 of 6,236 ayat and differs by one
     * unit on the other 66 - a doubled alef present or absent, or a vowel
     * attached to the neighbouring consonant. Only word ownership moves.
     */
    private var wordTable: Map<String, Array<Array<String>>>? = null

    @Volatile private var wordTableLoaded = false

    /**
     * Idempotent. Kept separate from [ensureTable] so a missing word-aligned
     * file degrades to the old behaviour rather than failing the engine.
     */
    @Synchronized
    fun ensureWordTable(source: java.io.InputStream): Boolean {
        if (wordTableLoaded) return wordTable != null
        wordTableLoaded = true
        return try {
            val root = JSONObject(source.bufferedReader().use { it.readText() })
            val map = HashMap<String, Array<Array<String>>>(7000)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = root.optJSONArray(k) ?: continue
                val words = Array(arr.length()) { _ -> emptyArray<String>() }
                for (i in 0 until arr.length()) {
                    val wa = arr.optJSONArray(i) ?: continue
                    words[i] = Array(wa.length()) { j -> wa.optString(j) }
                }
                map[k] = words
            }
            wordTable = map
            Log.i(TAG, "word-aligned table ready (${map.size} ayat)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "word-aligned table load failed", t)
            false
        }
    }

    /** Idempotent; ~5 MB JSON parsed once per process. */
    fun ensureTable(file: File): Boolean {
        if (table != null) return true
        return try {
            if (!file.exists() || file.length() == 0L) return false
            val root = JSONObject(file.bufferedReader().use { it.readText() })
            val map = HashMap<String, List<String>>(7000)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = root.optJSONObject(k) ?: continue
                val arr = o.optJSONArray("aya_phonemes_list") ?: continue
                val words = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) words.add(arr.optString(i))
                map[k] = words
            }
            table = map
            Log.i(TAG, "phoneme table ready (${map.size} ayat)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "phoneme table load failed", t)
            false
        }
    }

    /**
     * Load the acoustic model's unit inventory from tokens.txt. Without this
     * the expected side cannot be expressed in the model's own alphabet.
     */
    fun ensureUnits(tokensFile: File): Boolean {
        if (unitsByLength != null) return true
        return try {
            if (!tokensFile.exists() || tokensFile.length() == 0L) return false
            val set = HashSet<String>(512)
            tokensFile.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val s = line.trimEnd('\n', '\r')
                    if (s.isEmpty()) continue
                    val sp = s.lastIndexOf(' ')
                    if (sp <= 0) continue
                    val sym = s.substring(0, sp)
                    if (sym.isNotEmpty()) set.add(sym)
                }
            }
            if (set.isEmpty()) return false
            unitSet = set
            // Longest first: units are multi-codepoint and ambiguous if
            // scanned shortest-first ("ا" vs "اا" vs "اااااا").
            unitsByLength = set.sortedByDescending { it.length }
            Log.i(TAG, "model unit inventory ready (${set.size} units)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "unit inventory load failed", t)
            false
        }
    }

    fun isReady(): Boolean = table != null && unitsByLength != null


    /**
     * An ayah expressed in the model's own alphabet: the phoneme UNITS to
     * align against, plus the unit->word map used for per-word verdicts.
     */
    data class Expected(
        val wordCount: Int,
        val units: List<String>,
        /** units[i] belongs to word unitWord[i]. */
        val unitWord: IntArray,
    ) {
        @Volatile private var ids: Map<String, Int>? = null

        /**
         * Unit -> dense id, built once per ayah and reused by every alignment
         * against it. The lock policy aligns the same candidate ayat several
         * times a second, so this must not be rebuilt per call. The lazy init is
         * a benign race: two threads may both build it, both produce identical
         * content, and the last write wins.
         */
        val symbolIds: Map<String, Int>
            get() {
                var m = ids
                if (m == null) {
                    m = HashMap<String, Int>(units.size * 2)
                    // The last unit of a word registers BOTH its bare form and
                    // the form with a case vowel, so the reciter's `نَ` can find
                    // the id the table gave the bare `ن`. Registering the extra
                    // key cannot make a wrong word right: it only lets the DP
                    // treat two spellings of one final sound as a match, and the
                    // id is only reachable if this ayah really asserts that
                    // consonant at the end of a word.
                    val lastIdx = HashMap<Int, Int>(wordCount)
                    for (k in unitWord.indices) lastIdx[unitWord[k]] = k
                    val isFinal = BooleanArray(units.size)
                    for (k in lastIdx.values) if (k < isFinal.size) isFinal[k] = true
                    for (i in units.indices) {
                        val u = units[i]
                        if (!m.containsKey(canonicalId(u))) m[canonicalId(u)] = m.size
                        if (isFinal[i]) {
                            val fv = finalVowelId(u)
                            if (fv != null && !m.containsKey(fv)) m[fv] = m.size
                        }
                    }
                    ids = m
                }
                return m
            }
    }

    /** Split a word's phoneme string into model units (greedy longest match). */
    private fun explode(wordPhonemes: String): List<String> {
        val ordered = unitsByLength ?: return wordPhonemes.split(" ").filter { it.isNotEmpty() }
        val out = ArrayList<String>(wordPhonemes.length)
        var i = 0
        val n = wordPhonemes.length
        while (i < n) {
            var matched = false
            for (u in ordered) {
                if (u.length <= n - i && wordPhonemes.regionMatches(i, u, 0, u.length)) {
                    out.add(u)
                    i += u.length
                    matched = true
                    break
                }
            }
            if (!matched) i++ // codepoint the model does not emit
        }
        return out
    }

    /** Expected units for an ayah, cached. Null if the table has no entry. */
    @Synchronized
    fun expected(surah: Int, ayah: Int): Expected? {
        val key = "$surah:$ayah"
        expectedCache[key]?.let { return it }
        // Word-aligned first: it carries the same units with Mushaf-correct
        // ownership. Already exploded, so explode() is skipped entirely.
        wordTable?.get(key)?.let { wa ->
            if (wa.isNotEmpty()) {
                var n = 0
                for (w in wa) n += w.size
                if (n > 0) {
                    val units = ArrayList<String>(n)
                    val unitWord = ArrayList<Int>(n)
                    for (wi in wa.indices) {
                        for (u in wa[wi]) {
                            units.add(u)
                            unitWord.add(wi)
                        }
                    }
                    val e = Expected(wa.size, units, unitWord.toIntArray())
                    if (expectedCache.size > 8000) expectedCache.clear()
                    expectedCache[key] = e
                    return e
                }
            }
        }
        val words = table?.get(key) ?: return null
        if (words.isEmpty()) return null
        val units = ArrayList<String>(words.size * 4)
        val unitWord = ArrayList<Int>(words.size * 4)
        for (wi in words.indices) {
            for (u in explode(words[wi])) {
                units.add(u)
                unitWord.add(wi)
            }
        }
        if (units.isEmpty()) return null
        val e = Expected(words.size, units, unitWord.toIntArray())
        if (expectedCache.size > 8000) expectedCache.clear()
        expectedCache[key] = e
        return e
    }

    data class Alignment(
        val statuses: List<WordStatus>,
        val emitWord: IntArray,
        /** Mean chosen-token probability per expected word (-1 = no evidence). */
        val wordProb: FloatArray,
        /** Expected units aligned 1:1 with an emission. */
        val unitsMatched: Int = 0,
        val unitsTotal: Int = 0,
        /**
         * Per expected word, the fraction of ITS OWN units that were matched -
         * how much of that word the emission accounts for.
         *
         * The per-word ok/total was always computed to build `statuses` and then
         * discarded. It is the evidence needed to tell "this word was said
         * wrongly" from "we only caught part of this word": both produce
         * `bad > 0`, and the WRONG verdict used to be claimed on either. A word
         * cannot be pronounced wrongly on the part of it nobody heard.
         *
         * -1 means the word has no units, so no evidence either way.
         */
        val wordCoverage: FloatArray = FloatArray(0),
        /**
         * For each word, true when its FIRST expected unit was judged wrong -
         * the precise shape of "the reciter may have changed how this word
         * begins" at a waqf mark. Set inside align; never guessed afterward.
         */
        val wrongAtWordStart: BooleanArray = BooleanArray(0),
        /**
         * For each word, true when its LAST expected unit was judged wrong -
         * the precise shape of "the final letter did not sound as the table
         * asserts". 3,559 words in the mushaf end in a high mark (۟, ۢ, ۭ) that
         * licenses the final letter to be unsounded, and a table that cannot
         * represent that must not accuse on it.
         */
        val wrongAtWordEnd: BooleanArray = BooleanArray(0),
        /**
         * For each word, where inside that word the mismatch BEGINS, as a
         * fraction of its own units: 0.0 is the first unit, 1.0 is the last,
         * -1f when the word was not contradicted at all.
         *
         * Added after the whole-corpus sweep and the device sessions disagreed
         * about which words get accused. The corpus (which fits its own window)
         * blames the FIRST word of an ayah 42% of the time; the device blames it
         * 10% of the time, and the dominant device class has no textual
         * signature at all. Neither figure can say WHERE in a word the mismatch
         * sits, and that is the question that separates a boundary artefact from
         * a wrong phoneme. This answers it, per session, from the phone.
         */
        val wrongStartFrac: FloatArray = FloatArray(0),
    ) {
        /** Fraction of this ayah's phonemes the emission accounts for. */
        val coverage: Float
            get() = if (unitsTotal == 0) 0f else unitsMatched.toFloat() / unitsTotal
    }

    /**
     * Unit-level edit DP of emitted phonemes against an ayah's expected units.
     * Insertions (extra sounds) are ignored, so leading noise such as
     * istiaadha or basmala cannot reduce coverage. A word is CORRECT only when
     * every one of its units matches, WRONG on substitution, SKIPPED when none
     * match. emitWord maps each emission index to its expected word index (-1).
     */
    fun align(
        emitted: List<String>,
        expected: Expected,
        probs: FloatArray? = null,
    ): Alignment {
        val m = expected.wordCount
        val flat = expected.units
        val wordOf = expected.unitWord
        val n = emitted.size
        val len = flat.size
        if (len == 0 || n == 0) {
            return Alignment(
                if (len == 0) List(m) { WordStatus.SKIPPED } else emptyList(),
                IntArray(n) { -1 },
                FloatArray(m) { -1f },
                0, len,
            )
        }
        // Units are interned to ints once per ayah, so the hot loop compares
        // ints instead of Arabic strings. Value-identical: an emitted symbol
        // outside this ayah's vocabulary becomes -1, which can never equal a ref
        // id (every expected unit is in the map by construction), so it stays a
        // substitution exactly as the string compare was.
        val ids = expected.symbolIds
        // The LAST unit of each word is matched through the final-vowel rule.
        // Everywhere else uses the plain canonical id, because a vowel inside a
        // word is load-bearing: the 'ن' in 'مِن' is not the 'ن' that ends 'مِنْ'.
        val isFinal = BooleanArray(len)
        val lastOfWord = HashMap<Int, Int>(expected.wordCount)
        for (k in flat.indices) lastOfWord[wordOf[k]] = k
        for (k in lastOfWord.values) isFinal[k] = true
        val ref = IntArray(len) {
            val u = flat[it]
            ids[if (isFinal[it]) finalUnitId(u) else canonicalId(u)] ?: -1
        }
        // No safe-call: `emitted` is a List<String>, so element access is
        // non-null and the `?.let` plus the trailing `?: -1` on the line this
        // replaces could never fire. The reference side above does it right.
        //
        // An emitted symbol is matched through the final-vowel rule only when
        // the DP can tell it belongs to a word-final position. It cannot, until
        // the alignment has run - so instead the query side interns BOTH forms
        // of a consonant that can end a word, and the reference side is the
        // narrower of the two. That is sound because `symbolIds` is built from
        // the reference side: a bare `ن` only gets an id here if the ayah
        // actually asserts one.
        val qry = IntArray(n) { ids[canonicalId(emitted[it])] ?: finalEmissionId(emitted[it], ids) }

        // The traceback itself lives in UnitAligner so CI can execute it on the
        // JVM. Everything below is DERIVED from that one path, so the flags, the
        // word map and the coverage cannot drift from the alignment that
        // produced them - there is only one traceback in the codebase.
        val refToQuery = UnitAligner.refToQuery(qry, ref)

        val matched = BooleanArray(len)
        val wrong = BooleanArray(len)
        val emitWord = IntArray(n) { -1 }
        var unitsMatched = 0
        for (j in ref.indices) {
            val qi = refToQuery[j]
            if (qi < 0) continue
            if (qry[qi] == ref[j]) {
                matched[j] = true
                unitsMatched++
            } else {
                wrong[j] = true
            }
            emitWord[qi] = wordOf[j]
        }

        val wordHeard = FloatArray(m) { -1f }
        // Which words have a substitution on their very first unit: the marker
        // for "the word's beginning may have been read in isolation" at a waqf
        // boundary. The verdict rule itself stays where it is; this only hands
        // the consumer the information a waqf policy needs.
        val wrongAtWordStart = BooleanArray(m)
        val wrongAtWordEnd = BooleanArray(m)
        val lastOwner = HashMap<Int, Int>(m)
        val seenOwner = HashSet<Int>(m)
        for (k in flat.indices) {
            val owner = wordOf[k]
            if (owner !in 0 until m) continue
            if (seenOwner.add(owner) && wrong[k]) wrongAtWordStart[owner] = true
            lastOwner[owner] = k
        }
        for ((owner, k) in lastOwner) if (wrong[k]) wrongAtWordEnd[owner] = true
        // Where inside each word the contradiction begins, normalised. Built
        // here because `wrong` is local to align and the caller needs the value
        // per word, in the same pass that builds the flags above.
        val wrongStartFrac = FloatArray(m) { -1f }
        for (k in flat.indices) {
            val owner = wordOf[k]
            if (owner !in 0 until m || !wrong[k]) continue
            if (wrongStartFrac[owner] >= 0f) continue
            var total = 0
            var pos = 0
            var found = false
            for (j in 0..k) {
                if (wordOf[j] != owner) continue
                total++
                if (j == k) { pos = total - 1; found = true }
            }
            if (found && total > 1) wrongStartFrac[owner] = pos / (total - 1).toFloat()
            else if (found) wrongStartFrac[owner] = 0f
        }
        // Emissions the DP assigned to each expected word. Used only by the
        // diagnostic dump; the verdict rule reads `bad` alone, and
        // word_rule_sweep.py measures that at zero collateral.
        val emittedPerWord = IntArray(m)
        for (owner in emitWord) {
            if (owner >= 0 && owner < m) emittedPerWord[owner]++
        }
        val statuses = List(m) { wi ->
            var total = 0
            var ok = 0
            var bad = 0
            for (k in flat.indices) {
                if (wordOf[k] != wi) continue
                total++
                if (matched[k]) ok++
                if (wrong[k]) bad++
            }
            if (total > 0) wordHeard[wi] = ok / total.toFloat()
            when {
                total == 0 -> WordStatus.SKIPPED
                ok == total -> WordStatus.CORRECT
                // Barely covered AND contradicted is a different claim from bare
                // coverage, and this used to conflate them.
                //
                // Proven by construction: expected ررَ ح مَ اا نِ against emitted
                // قرَ ك كَ با مِ - every unit replaced by a distant sound - gives
                // ok = 0, wrong = 5 of 5. `ok * 2 < total` fired first, so the
                // verdict was SKIPPED: "not said". The reciter DID say something
                // there and it was not this word, so the result was that a
                // reciter substituting throughout a word is recorded as having
                // skipped it. Found by the substitution test in
                // engine/replay/word_verdicts.py, which could not detect a wrong
                // word at all until this rule changed - the test was reporting
                // 0 detection and calling it a pass.
                //
                // `bad` is non-zero only where the DP actively substituted an
                // emission against a different expectation, so it is evidence the
                // word was UTTERED. Coverage alone cannot distinguish silence
                // from a wrong word, and conflating them turns a real error into
                // an invisible gap. A genuinely skipped word has bad == 0 here:
                // nothing was heard, so nothing contradicts.
                ok * 2 < total ->
                    if (bad > 0) {
                        if (ok >= (total * WRONG_MIN_HEARD)) WordStatus.WRONG
                        else WordStatus.UNKNOWN
                    } else {
                        WordStatus.SKIPPED
                    }
                // Contradicted, but only after hearing most of the word - see
                // WRONG_MIN_HEARD_COVERAGE. Below the floor this falls through
                // to UNKNOWN, which already means "no verdict", and the caller's
                // streak never accumulates because UNKNOWN is not WRONG.
                bad > 0 && ok >= (total * WRONG_MIN_HEARD) -> WordStatus.WRONG
                // Partly covered, nothing contradicted: no verdict. This used
                // to be WRONG, which is what made a skipped word paint its
                // neighbour red.
                else -> WordStatus.UNKNOWN
            }
        }
        val wordProb = FloatArray(m) { -1f }
        if (probs != null) {
            val sum = FloatArray(m)
            val cnt = IntArray(m)
            for (k in emitted.indices) {
                val wi = emitWord[k]
                if (wi in 0 until m && k < probs.size) {
                    sum[wi] += probs[k]
                    cnt[wi]++
                }
            }
            for (wi in 0 until m) {
                if (cnt[wi] > 0) wordProb[wi] = sum[wi] / cnt[wi]
            }
        }
        return Alignment(
            statuses, emitWord, wordProb, unitsMatched, len, wordHeard,
            wrongAtWordStart, wrongAtWordEnd, wrongStartFrac,
        )
    }

    /** Word holding the most recent emission within [recencySec] of now. */
    fun timedWord(
        emitWord: IntArray,
        timestamps: FloatArray,
        audioSec: Float,
        recencySec: Float = 2.5f,
    ): Int? {
        if (emitWord.isEmpty() || timestamps.isEmpty()) return null
        var best = -1
        var bestTs = -1f
        val lim = minOf(emitWord.size, timestamps.size)
        for (k in 0 until lim) {
            if (emitWord[k] >= 0 && timestamps[k] >= bestTs) {
                bestTs = timestamps[k]
                best = emitWord[k]
            }
        }
        if (best < 0 || bestTs < 0) return null
        return if (audioSec - bestTs <= recencySec) best else null
    }
}
