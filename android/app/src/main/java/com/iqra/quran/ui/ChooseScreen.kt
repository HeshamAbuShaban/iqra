package com.iqra.quran.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.SurahInfo
import com.iqra.quran.ui.theme.IqraColors
import com.iqra.quran.ui.theme.LocalIqraColors

/**
 * "What would you like to recite?"
 *
 * WHY THIS IS A SEPARATE SCREEN
 * -----------------------------
 * The recitation test used to be a card on the home screen that jumped straight
 * into a session, passing whatever surah and page the reader happened to be on.
 * That is wrong as a matter of intent: testing from memory is a different act from
 * carrying on reading, and a control that only exists once you have read something
 * is invisible to exactly the people who most want it. It also answered a question
 * nobody asked. Opening the door should ask what you want to work on, not infer it
 * from where you stopped.
 *
 * So the feature is now standalone. This screen is what the door opens onto, and it
 * offers what a person actually has in mind when they want to drill:
 *
 *  - a surah they have been learning
 *  - a range they keep failing on
 *  - the last passage, because most sessions are a retry
 *
 * The preview matters as much as the pickers. Choosing "2:255-256" blind, with no
 * way to see what got selected, is how you end up reciting the wrong thing and
 * wondering why the accuracy is bad.
 */
@Composable
fun ChooseScreen(
    data: QuranData,
    lastRead: Pair<Int, Int>?,
    onBegin: (surah: Int, startAyah: Int, count: Int) -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalIqraColors.current
    val surahs = remember(data) { data.surahList() }

    // Two questions, asked in order: which surah, then which part of it.
    //
    // The first version put the passage card and the Begin button at the bottom of
    // the same list as all 114 surahs, which meant scrolling past the entire Quran
    // to reach the thing you press. Two stages is not a flourish - a control you
    // have to scroll past 113 rows to reach is a control most people never use.
    var stage by remember { mutableStateOf(0) }

    var surahNo by remember { mutableStateOf(lastRead?.first ?: 1) }
    var wholeRange by remember { mutableStateOf(true) }
    var startAyah by remember { mutableStateOf(1) }
    var endAyah by remember { mutableStateOf(1) }
    var query by remember { mutableStateOf(TextFieldValue("")) }

    val info: SurahInfo? = remember(surahNo) { data.surahInfo(surahNo) }
    val ayahCount = info?.ayahCount ?: 7

    fun openPassage(n: Int) {
        val len = data.surahInfo(n)?.ayahCount ?: 7
        surahNo = n
        startAyah = 1
        endAyah = len
        stage = 1
    }

    val filtered = remember(query.text, surahs) {
        val q = query.text.trim()
        if (q.isEmpty()) surahs
        else surahs.filter {
            it.number.toString() == q ||
                it.nameEn.contains(q, true) ||
                it.name.contains(q) ||
                it.ayahCount.toString() == q
        }
    }

    Scaffold(
        containerColor = c.ground,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (stage == 1) stage = 0 else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.inkMuted)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (stage == 0) "Recite from memory" else "Which part?",
                        fontSize = 19.sp, color = c.ink,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    Text(
                        if (stage == 0) "Nothing is shown until you say it"
                        else "Surah ${info?.number ?: surahNo} · ${info?.ayahCount ?: 0} ayat",
                        fontSize = 11.sp, color = c.inkFaint,
                    )
                }
            }
        },
    ) { pad ->
        if (stage == 0) {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(c.surface).padding(16.dp)
                    ) {
                        Text(
                            "What would you like to recite?",
                            fontSize = 17.sp, lineHeight = 24.sp, color = c.ink,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Pick the surah, then choose whether to do all of it " +
                                "or just a stretch.",
                            fontSize = 12.sp, lineHeight = 18.sp, color = c.inkMuted,
                        )
                        if (lastRead != null) {
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Last passage",
                                        fontSize = 13.sp, color = c.ink,
                                    )
                                    Text(
                                        "${data.surahInfo(lastRead.first)?.nameEn
                                            ?: "Surah ${lastRead.first}"}",
                                        fontSize = 11.sp, color = c.inkFaint,
                                    )
                                }
                                Text(
                                    "Drill it",
                                    fontSize = 13.sp, color = c.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(c.accent.copy(alpha = 0.13f))
                                        .clickable { openPassage(lastRead.first) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                item {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = {
                            Text("Search by name or number",
                                color = c.inkFaint, fontSize = 13.sp)
                        },
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.inkFaint) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = c.surface,
                            unfocusedContainerColor = c.surface,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = c.ink,
                            unfocusedTextColor = c.ink,
                            cursorColor = c.accent,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Text("Nothing matches \u201C${query.text}\u201D.",
                            fontSize = 13.sp, color = c.inkMuted,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                items(filtered, key = { it.number }) { s ->
                    SurahChoice(s, c) { openPassage(s.number) }
                }
            }
        } else {
            // Stage two. Everything needed to commit is on one screen with no
            // scrolling required to reach the button.
            Column(
                Modifier.fillMaxSize().padding(pad).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(c.surface).padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                info?.name ?: "",
                                fontFamily = quranFont, fontSize = 27.sp,
                                lineHeight = 48.sp, color = c.ink,
                            )
                            Text(
                                "${info?.nameEn ?: ""} · ${ayahCount} ayat · " +
                                    "starts on page ${info?.startPage ?: 1}",
                                fontSize = 11.sp, color = c.inkFaint,
                            )
                        }
                        Text(
                            "Change",
                            fontSize = 12.sp, color = c.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.accent.copy(alpha = 0.12f))
                                .clickable { stage = 0 }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("The whole surah", wholeRange, c, Modifier.weight(1f)) {
                        wholeRange = true
                    }
                    ModeChip("Just a range", !wholeRange, c, Modifier.weight(1f)) {
                        wholeRange = false
                    }
                }

                Box(Modifier.weight(1f)) {
                    if (wholeRange) {
                        Column(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                                .background(c.surface).padding(16.dp)
                        ) {
                            Text(
                                "All $ayahCount ayat",
                                fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                info?.name ?: "",
                                fontFamily = quranFont, fontSize = 24.sp, lineHeight = 46.sp,
                                color = c.ink,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "The whole surah. ${ayahCount} ayat, starting from the first.",
                                fontSize = 12.sp, lineHeight = 18.sp, color = c.inkMuted,
                            )
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                                .background(c.surface).padding(16.dp)
                        ) {
                            Text("Range", fontSize = 11.sp, color = c.inkMuted,
                                letterSpacing = 1.1.sp)
                            Spacer(Modifier.height(6.dp))
                            AyahStepper("From ayah", startAyah, ayahCount, c) {
                                startAyah = it.coerceAtMost(endAyah)
                            }
                            AyahStepper("To ayah", endAyah, ayahCount, c) {
                                endAyah = it.coerceAtLeast(startAyah)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                buildString {
                                    append(if (startAyah == endAyah) "Ayah $startAyah"
                                    else "Ayat $startAyah\u2013$endAyah")
                                },
                                fontSize = 11.sp, color = c.inkFaint,
                            )
                            Spacer(Modifier.height(10.dp))
                            // The preview is the point of this panel. Choosing a
                            // range with nothing to read is how you recite the wrong
                            // passage and blame the accuracy.
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    (startAyah..endAyah).take(4).mapNotNull {
                                        data.getVerse(surahNo, it)
                                    }.forEachIndexed { i, v ->
                                        Text(
                                            text = v.textUthmani,
                                            fontFamily = quranFont, fontSize = 21.sp,
                                            lineHeight = 42.sp, color = c.ink,
                                            textAlign = TextAlign.Start,
                                            modifier = Modifier.padding(bottom = 6.dp),
                                        )
                                        if (i == 3 && endAyah - startAyah + 1 > 4) {
                                            Text(
                                                "\u2026 and ${endAyah - startAyah + 1 - 4} more",
                                                fontSize = 11.sp, color = c.inkFaint,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val count = if (wholeRange) 0 else endAyah - startAyah + 1
                val start = if (wholeRange) 1 else startAyah
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(c.accent)
                        .clickable(enabled = info != null) { onBegin(surahNo, start, count) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (wholeRange) "Recite ${info?.nameEn ?: ""}"
                        else "Recite " + (if (startAyah == endAyah) "ayah $startAyah"
                                         else "${endAyah - startAyah + 1} ayat"),
                        fontSize = 16.sp, color = c.ground,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String, selected: Boolean, c: IqraColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) c.accent else c.surfaceHigh, tween(220), label = "chip"
    )
    val fg by animateColorAsState(
        if (selected) c.ground else c.inkMuted, tween(220), label = "chipfg"
    )
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, color = fg,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold
            else androidx.compose.ui.text.font.FontWeight.Normal)
    }
}

@Composable
private fun SurahChoice(
    s: SurahInfo, c: IqraColors, onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(15.dp))
                .background(c.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${s.number}", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = c.inkMuted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            s.name, fontFamily = quranFont, fontSize = 19.sp, lineHeight = 30.sp,
            color = c.ink, modifier = Modifier.width(96.dp),
            maxLines = 1, textAlign = TextAlign.Start,
        )
        Column(Modifier.weight(1f)) {
            Text(s.nameEn, fontSize = 13.sp, color = c.ink,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                "${s.ayahCount} ayat · page ${s.startPage}",
                fontSize = 10.sp, color = c.inkFaint,
            )
        }
    }
}

@Composable
private fun AyahStepper(
    label: String, value: Int, max: Int, c: IqraColors, onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = c.inkMuted, modifier = Modifier.weight(1f))
        StepButton("–", enabled = value > 1, c) { onChange(value - 1) }
        Text(
            "$value",
            fontFamily = FontFamily.Monospace, fontSize = 17.sp, color = c.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(52.dp),
        )
        StepButton("+", enabled = value < max, c) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, c: IqraColors, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (enabled) c.surfaceHigh else c.surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 18.sp, color = if (enabled) c.ink else c.inkFaint)
    }
}
