package com.iqra.quran.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.SurahInfo
import com.iqra.quran.ui.theme.IqraShape
import com.iqra.quran.ui.theme.LocalIqraColors
import androidx.compose.material3.MaterialTheme

/**
 * The home screen: where you are, and the one door into the recitation test.
 *
 * Extracted from MainActivity, which had grown to 2,865 lines holding the app
 * shell, the surah index, the search results, the juz and bookmark lists and the
 * page renderer. It is a mechanical move - no behaviour changed - and it is worth
 * making before the theming reaches this file, because the hardcoded palette here
 * is the last thing standing between the app and one visual language.
 */

@Composable
fun HomeScreen(
    vm: PracticeViewModel,
    lastRead: Pair<Int, Int>?,
    onOpen: (Int, Int) -> Unit,
    onOpenAyah: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onDiag: () -> Unit = {},
    onData: () -> Unit = {},
    onSettings: () -> Unit = {},
    onTest: (Int, Int) -> Unit = { _, _ -> },
    onProgress: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    val data = vm.data.collectAsStateWithLifecycle().value ?: return
    var tab by rememberSaveable { mutableStateOf(HomeTab.Surahs) }
    // surahList() rebuilds all 114 SurahInfo objects on every call, so it is
    // built once here and handed down rather than each child rebuilding its own
    // copy. HomeScreen recomposes on every keystroke in the search field, which
    // made that 114 allocations per character typed.
    val surahs = remember(data) { data.surahList() }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(cs.background)) {
        // Deliberately quiet: no gradient slab, no tagline stack, no 9sp
        // disclaimer. One wordmark, one search field, one tab underline.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Iqra",
                fontFamily = quranFont,
                fontSize = 24.sp,
                color = cs.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (lastRead != null) {
                // Resuming is a navigation decision, so it lives here - on
                // home - rather than inside the reading surface.
                IconButton(onClick = onResume) {
                    Icon(Icons.Outlined.History, "Back to last read", tint = cs.onSurface.copy(alpha = 0.55f))
                }
            }
            IconButton(onClick = onData) {
                Icon(Icons.Outlined.FolderOpen, "Data files", tint = cs.onSurface.copy(alpha = 0.55f))
            }
            IconButton(onClick = onProgress) {
                Icon(Icons.Outlined.ShowChart, "Your practice", tint = cs.onSurface.copy(alpha = 0.55f))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Tune, "Settings", tint = cs.onSurface.copy(alpha = 0.55f))
            }
            IconButton(onClick = onDiag) {
                Icon(Icons.Outlined.MonitorHeart, "Engine check", tint = cs.onSurface.copy(alpha = 0.55f))
            }
        }

        // One search field for both surah names and ayah text.
        var q by remember { mutableStateOf("") }
        val focus = LocalFocusManager.current
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            placeholder = {
                Text(
                    "Search a surah, or a word from a verse",
                    fontSize = 14.sp,
                    color = cs.onSurface.copy(alpha = 0.45f),
                )
            },
            singleLine = true,
            shape = IqraShape.pill,
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = cs.onSurface.copy(alpha = 0.5f)) },
            trailingIcon = {
                if (q.isNotEmpty()) {
                    IconButton(onClick = { q = "" }) {
                        Icon(Icons.Outlined.Close, "Clear", tint = cs.onSurface.copy(alpha = 0.5f))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentColor,
                unfocusedBorderColor = cs.onSurface.copy(alpha = 0.18f),
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val trimmed = q.trim()
        val surahHits = remember(trimmed, surahs) {
            if (trimmed.isBlank()) emptyList() else surahs.filter {
                it.number.toString() == trimmed ||
                    it.name.contains(trimmed) ||
                    it.nameEn.contains(trimmed, ignoreCase = true) ||
                    it.nameEn.startsWith(trimmed, ignoreCase = true)
            }
        }
        val ayahHits = remember(trimmed) { if (trimmed.isBlank()) emptyList() else vm.searchAyat(trimmed) }

        if (trimmed.isNotBlank()) {
            SearchResults(
                surahHits = surahHits,
                ayahHits = ayahHits,
                data = data,
                onOpen = onOpen,
                onOpenAyah = onOpenAyah,
            )
            return@Column
        }

        HomeTabRow(tab) { tab = it }
        when (tab) {
            HomeTab.Surahs -> SurahIndex(vm, lastRead, data, onOpen, surahs, onTest)
            HomeTab.Juz -> JuzList(vm, data, onOpen)
            HomeTab.Bookmarks -> BookmarkList(vm, data, onOpen)
        }
    }
}

@Composable
private fun SearchResults(
    surahHits: List<com.iqra.quran.data.SurahInfo>,
    ayahHits: List<com.iqra.quran.data.AyahSearch.Hit>,
    data: com.iqra.quran.data.QuranData,
    onOpen: (Int, Int) -> Unit,
    onOpenAyah: (Int, Int, Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scale = ReaderPrefs.fontScale(LocalContext.current)
    val lazy = rememberLazyListState()
    LaunchedEffect(surahHits.size, ayahHits.size) { lazy.scrollToItem(0) }
    LazyColumn(
        state = lazy,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (surahHits.isNotEmpty()) {
            item { SectionHeader("Surahs", surahHits.size, false) }
            items(surahHits, key = { "s${it.number}" }) { s ->
                SurahRow(s) { n, p -> onOpen(n, p) }
            }
        }
        if (ayahHits.isNotEmpty()) {
            item { SectionHeader("Verses", ayahHits.size, false) }
            items(ayahHits, key = { "a${it.surah}:${it.ayah}" }) { h ->
                val v = data.getVerse(h.surah, h.ayah)
                val surah = data.surahInfo(h.surah)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val p = surah?.let { if (h.ayah in it.startPage..it.endPage) it.startPage else 1 }
                                ?: 1
                            onOpenAyah(h.surah, p, h.ayah)
                        }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${h.surah}:${h.ayah}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = goldColor,
                        modifier = Modifier.width(52.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            v?.textUthmani?.take(90) ?: "",
                            fontFamily = quranFont,
                            fontSize = (17 * scale).sp,
                            color = cs.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            surah?.nameEn ?: "",
                            fontSize = 10.sp,
                            color = cs.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
                HorizontalDivider(color = cs.onSurface.copy(alpha = 0.07f))
            }
        }
        if (surahHits.isEmpty() && ayahHits.isEmpty()) {
            item { EmptyHint("Nothing matched.") }
        }
    }
}

@Composable
fun HomeTabRow(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val tabs = HomeTab.values()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        tabs.forEach { t ->
            val sel = t == selected
            Box(
                Modifier.weight(1f).padding(horizontal = 2.dp).clip(IqraShape.pill)
                    .background(if (sel) goldColor.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(t) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (t) {
                        HomeTab.Surahs -> "Surahs"
                        HomeTab.Juz -> "Juz"
                        HomeTab.Bookmarks -> "Saved"
                    },
                    fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) goldColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SurahIndex(
    vm: PracticeViewModel,
    lastRead: Pair<Int, Int>?,
    data: com.iqra.quran.data.QuranData,
    onOpen: (Int, Int) -> Unit,
    surahs: List<com.iqra.quran.data.SurahInfo> =
        data.surahList(),
    onTest: (Int, Int) -> Unit = { _, _ -> },
) {
    // `surahs` arrives prebuilt from HomeScreen. The default keeps this
    // composable usable on its own without forcing every caller to remember it.
    var view by rememberSaveable { mutableStateOf(SurahView.List) }
    // Filtering is driven by the single search field on HomeScreen; this
    // composable used to carry a second one and home rendered two boxes.
    val filtered = surahs
    val meccan = remember(filtered) { filtered.filter { it.revelationType == "Meccan" } }
    val madani = remember(filtered) { filtered.filter { it.revelationType == "Madani" } }
    val gridRows = remember(filtered) { filtered.chunked(3) }
    // Where a recitation test should start on this page: the first ayah of this
    // surah that the page actually contains. Derived from the mushaf rather
    // than from `lastRead`, which only remembers a surah and a page - testing
    // from ayah 1 of a surah whose page 50 you were on would be a different
    // exercise from the one the card is offering.
    val mushafPages = vm.mushaf.collectAsStateWithLifecycle().value
    fun anchorOn(surah: Int, page: Int): Int =
        mushafPages?.getOrNull(page - 1)?.lines
            ?.flatMap { it.words ?: emptyList() }
            ?.firstOrNull { it.surah == surah }?.verse ?: 1
    val continueInfo = remember(lastRead, surahs) {
        lastRead?.let { (num, page) -> surahs.firstOrNull { it.number == num }?.let { it to page } }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { view = if (view == SurahView.List) SurahView.Grid else SurahView.List }) {
                Icon(
                    if (view == SurahView.List) Icons.Filled.ViewModule else Icons.Filled.ViewList,
                    "Toggle layout",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            if (view == SurahView.List) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item {
                        val target = continueInfo?.first?.number ?: 1
                        val targetPage = continueInfo?.second ?: 1
                        TestHeroCard(
                            surah = target,
                            onTest = { onTest(target, anchorOn(target, targetPage)) },
                        )
                    }

                    continueInfo?.let { (info, page) ->
                        item {
                            ContinueCard(info = info, page = page) {
                                onOpen(info.number, page)
                            }
                        }
                    }
                    if (meccan.isNotEmpty()) {
                        item { SectionHeader("Meccan", meccan.size, false) }
                        items(meccan, key = { it.number }) { SurahRow(it, onOpen) }
                    }
                    if (madani.isNotEmpty()) {
                        item { SectionHeader("Madani", madani.size, true) }
                        items(madani, key = { it.number }) { SurahRow(it, onOpen) }
                    }
                    if (filtered.isEmpty()) {
                        item { EmptyHint("No surah matches") }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp, top = 4.dp),
                ) {
                    item {
                        val target = continueInfo?.first?.number ?: 1
                        val targetPage = continueInfo?.second ?: 1
                        TestHeroCard(
                            surah = target,
                            onTest = { onTest(target, anchorOn(target, targetPage)) },
                        )
                    }

                    continueInfo?.let { (info, page) ->
                        item {
                            ContinueCard(info = info, page = page) {
                                onOpen(info.number, page)
                            }
                        }
                    }
                    items(gridRows, key = { it.first().number }) { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { s ->
                                Box(Modifier.weight(1f)) { SurahGridCell(s, onOpen) }
                            }
                            repeat(3 - row.size) { Box(Modifier.weight(1f)) {} }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContinueCard(
    info: com.iqra.quran.data.SurahInfo,
    page: Int,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = IqraShape.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Continue", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        "${info.nameEn}  ·  Page $page  ·  ${info.ayahCount} verses",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * The way into a recitation test, with its own door on the home screen.
 *
 * It was buried at the bottom of the Continue card, which is wrong twice over:
 * testing from memory is a different act from carrying on reading, and a control
 * that only exists once you have read something is invisible to exactly the
 * people who most want it. So it is a card of its own, above Continue, and it
 * says what will happen - "we listen" is the promise, and without it the word
 * "test" reads like an exam rather than a practice partner.
 *
 * The glyph is a still frame of the live orb: the same sphere, drawn once, so
 * the door looks like what is behind it without running an animation on the home
 * screen where nobody asked for one.
 */
@Composable
private fun TestHeroCard(surah: Int, onTest: () -> Unit) {
    val c = LocalIqraColors.current
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onTest),
        shape = IqraShape.card,
        colors = CardDefaults.cardColors(containerColor = c.accent.copy(alpha = 0.10f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(48.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to c.goldBright,
                        0.20f to c.accent.copy(alpha = 0.85f),
                        1f to Color.Transparent,
                        center = center,
                        radius = size.minDimension * 0.5f,
                    ),
                    radius = size.minDimension * 0.32f,
                )
                drawCircle(
                    color = c.accent.copy(alpha = 0.5f),
                    radius = size.minDimension * 0.46f,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                drawCircle(
                    color = c.gold.copy(alpha = 0.32f),
                    radius = size.minDimension * 0.37f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Recite from memory",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.ink,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "The verse stays hidden until you say it. We listen and " +
                        "grade each word.",
                    fontSize = 12.sp,
                    color = c.inkMuted,
                )
            }
            Icon(Icons.Filled.Mic, null, tint = c.accent,
                modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun SectionHeader(title: String, count: Int, madani: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = (if (madani) accentColor else goldColor).copy(alpha = 0.2f), modifier = Modifier.size(10.dp)) {}
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text("· $count", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
fun SurahRow(s: com.iqra.quran.data.SurahInfo, onOpen: (Int, Int) -> Unit) {
    val madani = s.revelationType == "Madani"
    val accent = if (madani) accentColor else goldColor
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onOpen(s.number, s.startPage) },
        shape = IqraShape.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = accent.copy(alpha = 0.16f), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("${s.number}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, fontFamily = quranFont, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(2.dp))
                Text(s.nameEn, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                Spacer(Modifier.height(4.dp))
                Text(
                    "${if (madani) "Madani" else "Meccan"} · ${s.ayahCount} verses · pp ${s.startPage}–${s.endPage} · Juz ${s.juz}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            Surface(shape = CircleShape, color = accent, modifier = Modifier.size(10.dp)) {}
        }
    }
}

@Composable
fun SurahGridCell(s: com.iqra.quran.data.SurahInfo, onOpen: (Int, Int) -> Unit) {
    val madani = s.revelationType == "Madani"
    val accent = if (madani) accentColor else goldColor
    Column(
        Modifier.padding(6.dp).fillMaxWidth().clickable { onOpen(s.number, s.startPage) }
            .clip(IqraShape.card).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(34.dp).background(accent.copy(alpha = 0.16f), CircleShape),
        ) { Text("${s.number}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent) }
        Spacer(Modifier.height(6.dp))
        Text(s.name, fontFamily = quranFont, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, maxLines = 1)
        Text(s.nameEn, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun JuzList(
    vm: PracticeViewModel,
    data: com.iqra.quran.data.QuranData,
    onOpen: (Int, Int) -> Unit,
) {
    val juz = remember { data.juzList() }
    val surahs = remember { data.surahList() }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp),
    ) {
        itemsIndexed(juz, key = { _, j -> j.number }) { idx, j ->
            val surah = surahs.firstOrNull { j.startPage in it.startPage..it.endPage }
            val endPage = juz.getOrNull(idx + 1)?.startPage?.minus(1) ?: 604
            Card(
                Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    .clickable { onOpen(surah?.number ?: 1, j.startPage) },
                shape = IqraShape.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = accentColor.copy(alpha = 0.16f), modifier = Modifier.size(42.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${j.number}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accentColor)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Juz ${j.number}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "pp ${j.startPage}–$endPage" + if (j.surahNameEn.isNotEmpty()) " · ${j.surahNameEn}" else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BookmarkList(
    vm: PracticeViewModel,
    data: com.iqra.quran.data.QuranData,
    onOpen: (Int, Int) -> Unit,
) {
    val pages by vm.bookmarks.collectAsStateWithLifecycle()
    val sorted = pages.sorted()
    val surahs = remember { data.surahList() }
    if (sorted.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.BookmarkBorder,
                null,
                tint = goldColor.copy(alpha = 0.7f),
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "No bookmarks yet",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap the bookmark icon while reading to save a page.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp),
    ) {
        items(sorted, key = { it }) { page ->
            val info = surahs.firstOrNull { page in it.startPage..it.endPage }
            Card(
                Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onOpen(info?.number ?: 1, page) },
                shape = IqraShape.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bookmark, null, tint = goldColor)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Page $page", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            info?.let { "${it.nameEn} · Juz ${it.juz}" } ?: "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = { vm.toggleBookmark(page) }) {
                        Icon(
                            Icons.Filled.Delete,
                            "Remove bookmark",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
    }
}
