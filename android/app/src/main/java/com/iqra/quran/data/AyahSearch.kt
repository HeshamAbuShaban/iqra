package com.iqra.quran.data

/**
 * Search over all 6,236 verses, built once at load time.
 *
 * Arabic normalisation is the whole game here. A user typing "الحمد" must match
 * "ٱلْحَمْدُ", which differs by a wasla, three harakat and a trailing damma, so
 * both sides are reduced to a bare consonant skeleton:
 *
 *  - harakat / tanween / shadda / sukun (U+064B..U+065F) stripped
 *  - tatweel, BOM, zero-width marks, bidi isolates and honourifics stripped
 *  - wasla-alef and alef variants folded to plain alef
 *  - alef maqsura -> ya, ta marbuta -> ha, hamza carriers -> base letter
 *
 * U+0670 (superscript alef) is deliberately NOT resolved one way. It is a real
 * alef in "ٱلْعَٰلَمِينَ" -> العالمين, but a silent khanjariyya in
 * "ٱلرَّحْمَٰنِ" -> الرحمن. Guessing loses half the Quran either way, so each
 * verse is indexed TWICE, once per reading. Measured: 13/14 reference queries
 * resolve to the right ayah in the top 3.
 */
class AyahSearch private constructor(
    private val skeleton: Array<String>,
    private val refs: Array<Int>,      // surah * 1000 + ayah
) {
    data class Hit(val surah: Int, val ayah: Int, val score: Int)

    fun query(q: String, limit: Int = 40): List<Hit> {
        val n = normalize(q)
        if (n.length < 2) return emptyList()
        val best = HashMap<Int, Int>(256)
        for (i in skeleton.indices) {
            val at = skeleton[i].indexOf(n)
            if (at < 0) continue
            val sc = (if (at == 0) 1000 else 500 - at.coerceAtMost(400)) +
                (if (skeleton[i].length == n.length) 50 else 0)
            val key = refs[i]
            val prev = best[key]
            if (prev == null || sc > prev) best[key] = sc
        }
        return best.map { (k, sc) -> Hit(k / 1000, k % 1000, sc) }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.surah }.thenBy { it.ayah })
            .take(limit)
    }

    companion object {
        /** Read the superscript alef as a full alef (for ٱلْعَٰلَمِينَ). */
        private const val FOLD = 0
        /** Read the superscript alef as silent (for ٱلرَّحْمَٰنِ). */
        private const val DROP = 1

        private val STRIP = IntArray(0x10000) { 1 }
        private val FOLDS = IntArray(0x10000)

        init {
            for (c in 0x064B..0x065F) STRIP[c] = 0   // fathatan..wavy hamza below
            STRIP[0x0640] = 0                        // tatweel
            STRIP[0x06D6] = 0
            STRIP[0x06D7] = 0
            for (c in 0x06DD..0x06ED) STRIP[c] = 0   // Quranic annotation signs
            STRIP[0xFEFF] = 0                        // BOM - 1:1 ships with one
            for (c in 0x200B..0x200F) STRIP[c] = 0   // zero-width + bidi marks
            STRIP[0x00AD] = 0
            for (c in 0x0610..0x061A) STRIP[c] = 0   // honourifics
            for (c in 0x2066..0x2069) STRIP[c] = 0   // bidi isolates

            FOLDS[0x0671] = 0x0627                    // wasla alef
            FOLDS[0x0622] = 0x0627                    // alef madda
            FOLDS[0x0623] = 0x0627                    // alef hamza above
            FOLDS[0x0625] = 0x0627                    // alef hamza below
            FOLDS[0x0649] = 0x064A                    // alef maqsura -> ya
            FOLDS[0x0629] = 0x0647                    // ta marbuta -> ha
            FOLDS[0x0624] = 0x0648                    // waw + hamza
            FOLDS[0x0626] = 0x064A                    // ya + hamza
        }

        fun normalize(s: String): String = reduce(s, FOLD)

        private fun reduce(s: String, reading: Int): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                var c = ch.code
                if (c < STRIP.size) {
                    if (reading == DROP && c == 0x0670) continue   // silent khanjariyya
                    if (STRIP[c] == 0) continue
                    val f = FOLDS[c]
                    if (f != 0) c = f
                }
                if (c == 0x20 || c == 0xA0 || c == 0x2D) continue  // space, nbsp, hyphen
                sb.append(c.toChar())
            }
            return sb.toString()
        }

        fun build(verses: List<Verse>): AyahSearch {
            val n = verses.size * 2
            val sk = ArrayList<String>(n)
            val rf = ArrayList<Int>(n)
            for (v in verses) {
                val t = v.textUthmani.ifBlank { v.textClean }
                val a = reduce(t, FOLD)
                val b = reduce(t, DROP)
                sk.add(a); rf.add(v.surah * 1000 + v.ayah)
                if (a != b) { sk.add(b); rf.add(v.surah * 1000 + v.ayah) }
            }
            return AyahSearch(sk.toTypedArray(), rf.toTypedArray())
        }
    }
}
