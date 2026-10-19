package com.iqra.quran.data

/**
 * Reference reciters for the play-along audio.
 *
 * `relativePath` is quranicaudio.com's own directory name for the recitation,
 * taken from their `/api/qaris` response and verified with range requests
 * against download.quranicaudio.com. The slugs are not derivable from the
 * display names (some transliterate 3 for ain, some carry a `complete`
 * subdirectory), which is exactly why they are recorded here verbatim.
 */
data class Reciter(val id: String, val name: String, val relativePath: String)

object Reciters {
    val ALL = listOf(
        Reciter("husaree", "Mahmoud Khalil Al-Husary", "mahmood_khaleel_al-husaree"),
        Reciter("alafasy", "Mishary Rashid Alafasy", "mishaari_raashid_al_3afaasee"),
        Reciter("abdulbasit", "Abdul Basit (Murattal)", "abdul_basit_murattal"),
        Reciter("sudais", "Abdur-Rahman as-Sudais", "abdurrahmaan_as-sudays"),
        Reciter("shuraym", "Sa'ud ash-Shuraym", "sa3ood_al-shuraym"),
        Reciter("minshawi", "Muhammad Siddiq al-Minshawi", "muhammad_siddeeq_al-minshaawee"),
        Reciter("ghamdi", "Saad al-Ghamdi", "sa3d_al-ghaamidi/complete"),
        Reciter("huthaify", "Ali al-Huthaify", "huthayfi"),
        Reciter("muaiqly", "Maher al-Muaiqly", "maher_256"),
        Reciter("basfar", "Abdullah Basfar", "abdullaah_basfar"),
    )

    val DEFAULT: Reciter = ALL.first()

    fun byId(id: String?): Reciter = ALL.firstOrNull { it.id == id } ?: DEFAULT

    fun url(r: Reciter, surah: Int): String =
        "https://download.quranicaudio.com/quran/${r.relativePath}/%03d.mp3".format(surah)
}
