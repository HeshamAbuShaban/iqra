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
import androidx.compose.material.icons.filled.Headphones
import com.iqra.quran.data.AudioPrefs
import com.iqra.quran.data.PracticeLog
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqra.quran.R
import com.iqra.quran.data.AyahBookmarks
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
    onSettings: () -> Unit = {},
    onTest: (Int, Int) -> Unit = { _, _ -> },
    onProgress: () -> Unit = {},
    onResume: () -> Unit = {},
    // The two further resume doors. Read resumes the page; recite resumes the
    // last recorded position with its anchor; listen resumes the last surah
    // whose playback was chosen. All three remember a different act.
    onRecite: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onListen: (Int, Int) -> Unit = { _, _ -> },
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
                stringResource(R.string.app_name),
                fontFamily = quranFont,
                fontSize = 24.sp,
                color = cs.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (lastRead != null) {
                // Resuming is a navigation decision, so it lives here - on
                // home - rather than inside the reading surface.
                IconButton(onClick = onResume) {
                    Icon(Icons.Outlined.History, stringResource(R.string.home_cd_last_read), tint = cs.onSurface.copy(alpha = 0.55f))
                }
            }
            // Three doors, not five. Data files and the engine check are
            // Settings concerns - they each already had a labelled row there -
            // and a header of unlabeled icons was reading as clutter rather
            // than navigation.
            IconButton(onClick = onProgress) {
                Icon(Icons.Outlined.ShowChart, stringResource(R.string.home_cd_practice), tint = cs.onSurface.copy(alpha = 0.55f))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Tune, stringResource(R.string.home_cd_settings), tint = cs.onSurface.copy(alpha = 0.55f))
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
                    stringResource(R.string.home_search_hint),
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
                        Icon(Icons.Outlined.Close, stringResource(R.string.home_cd_clear), tint = cs.onSurface.copy(alpha = 0.5f))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.primary,
                unfocusedBorderColor = cs.onSurface.copy(alpha = 0.18f),
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val trimmed = q.trim()
        // The stored names are fully vocalised, so the Arabic side of the
        // match is folded (marks stripped, alef variants unified) on both
        // sides - see arabicFold. The English side is a plain case-fold.
        val qAr = remember(trimmed) { arabicFold(trimmed) }
        val surahHits = remember(trimmed, qAr, surahs) {
            if (trimmed.isBlank()) emptyList() else {
                val qEn = trimmed.lowercase()
                val qNum = trimmed.toIntOrNull()
                surahs.filter {
                    it.number == qNum ||
                        it.nameEn.lowercase().contains(qEn) ||
                        (qAr.isNotEmpty() && arabicFold(it.name).contains(qAr))
                }
            }
        }
        val ayahHits = remember(trimmed) { if (trimmed.isBlank()) emptyList() else vm.searchAyat(trimmed) }

        if (trimmed.isNotBlank()) {
            SearchResults(
                surahHits = surahHits,
                ayahHits = ayahHits,
                data = data,
                lastRead = lastRead,
                onOpen = onOpen,
                onOpenAyah = onOpenAyah,
            )
            return@Column
        }

        HomeTabRow(tab) { tab = it }
        when (tab) {
            HomeTab.Surahs -> SurahIndex(vm, lastRead, data, onOpen, surahs, onTest, onRecite, onListen)
            HomeTab.Juz -> JuzList(vm, data, onOpen)
            HomeTab.Bookmarks -> BookmarkList(vm, data, onOpen, onOpenAyah)
        }
    }
}

@Composable
private fun SearchResults(
    surahHits: List<com.iqra.quran.data.SurahInfo>,
    ayahHits: List<com.iqra.quran.data.AyahSearch.Hit>,
    data: com.iqra.quran.data.QuranData,
    lastRead: Pair<Int, Int>?,
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
            item { SectionHeader(stringResource(R.string.home_section_surahs), surahHits.size, false) }
            items(surahHits, key = { "s${it.number}" }) { s ->
                SurahRow(s, lastRead) { n, p -> onOpen(n, p) }
            }
        }
        if (ayahHits.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_section_verses), ayahHits.size, false) }
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
                        color = MaterialTheme.colorScheme.secondary,
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
            item { EmptyHint(stringResource(R.string.home_empty_search)) }
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
                    .background(
                        if (sel) MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
                        else Color.Transparent,
                    )
                    .clickable(role = Role.Tab) { onSelect(t) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (t) {
                        HomeTab.Surahs -> stringResource(R.string.home_tab_surahs)
                        HomeTab.Juz -> stringResource(R.string.home_tab_juz)
                        HomeTab.Bookmarks -> stringResource(R.string.home_tab_saved)
                    },
                    fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
    onRecite: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onListen: (Int, Int) -> Unit = { _, _ -> },
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
                    stringResource(R.string.home_cd_toggle_layout),
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
                    item { ResumeSlots(vm, surahs, onRecite, onListen) }
                    if (meccan.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.home_section_meccan), meccan.size, false) }
                        items(meccan, key = { it.number }) { SurahRow(it, lastRead, onOpen) }
                    }
                    if (madani.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.home_section_madani), madani.size, true) }
                        items(madani, key = { it.number }) { SurahRow(it, lastRead, onOpen) }
                    }
                    if (filtered.isEmpty()) {
                        item { EmptyHint(stringResource(R.string.home_empty_surah_match)) }
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
                    item { ResumeSlots(vm, surahs, onRecite, onListen) }
                    items(gridRows, key = { it.first().number }) { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { s ->
                                Box(Modifier.weight(1f)) { SurahGridCell(s, lastRead, onOpen) }
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
                    Text(stringResource(R.string.home_continue_title), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        stringResource(R.string.home_continue_detail, info.nameEn, page, info.ayahCount),
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
 * The two further resume doors beneath Continue.
 *
 * Reading, reciting and listening are three different acts that end in
 * different places, and one "continue" could only remember one of them. The
 * recite slot resumes the latest recorded session's own position - the lock's
 * spot, not the page top - and the listen slot resumes the surah whose
 * playback was last chosen. A slot with nothing behind it renders nothing, so
 * a fresh install sees only the read door.
 */
@Composable
fun ResumeSlots(
    vm: PracticeViewModel,
    surahs: List<com.iqra.quran.data.SurahInfo>,
    onRecite: (Int, Int, Int) -> Unit,
    onListen: (Int, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val lastRecite = remember {
        PracticeLog.summarise(ctx).recent
            .firstOrNull { it.safePosition != null }?.safePosition
    }
    val lastListen = remember { AudioPrefs.lastPlayedSurah(ctx) }
    if (lastRecite != null) {
        val (surah, ayah) = lastRecite
        val info = surahs.firstOrNull { it.number == surah }
        if (info != null) {
            val page = vm.pageOfVerse(surah, ayah) ?: info.startPage
            SlotCard(
                icon = Icons.Filled.Mic,
                title = stringResource(R.string.home_slot_recite),
                detail = stringResource(R.string.home_slot_recite_detail, info.nameEn, ayah),
            ) { onRecite(surah, page, ayah) }
        }
    }
    if (lastListen in 1..114) {
        val info = surahs.firstOrNull { it.number == lastListen }
        if (info != null) {
            SlotCard(
                icon = Icons.Filled.Headphones,
                title = stringResource(R.string.home_slot_listen),
                detail = stringResource(R.string.home_slot_listen_detail, info.nameEn),
            ) { onListen(info.number, info.startPage) }
        }
    }
}

@Composable
private fun SlotCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = IqraShape.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Text(detail, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
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
                    stringResource(R.string.home_test_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.ink,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.home_test_blurb),
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
        Surface(
            shape = CircleShape,
            color = (if (madani) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.secondary).copy(alpha = 0.2f),
            modifier = Modifier.size(10.dp),
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text("· $count", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
fun SurahRow(
    s: com.iqra.quran.data.SurahInfo,
    lastRead: Pair<Int, Int>? = null,
    onOpen: (Int, Int) -> Unit,
) {
    val madani = s.revelationType == "Madani"
    val accent = if (madani) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    // The row resumes, it does not restart: tapping the surah you were last
    // reading opened its FIRST page, and the page you were actually on was
    // reachable only by hunting for it. The continue card knew better; the
    // rows now agree with it.
    val openPage = if (lastRead?.first == s.number) lastRead.second else s.startPage
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onOpen(s.number, openPage) },
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
                    stringResource(
                        R.string.home_surah_meta,
                        stringResource(if (madani) R.string.home_section_madani else R.string.home_section_meccan),
                        s.ayahCount, s.startPage, s.endPage, s.juz,
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            Surface(shape = CircleShape, color = accent, modifier = Modifier.size(10.dp)) {}
        }
    }
}

@Composable
fun SurahGridCell(
    s: com.iqra.quran.data.SurahInfo,
    lastRead: Pair<Int, Int>? = null,
    onOpen: (Int, Int) -> Unit,
) {
    val madani = s.revelationType == "Madani"
    val accent = if (madani) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val openPage = if (lastRead?.first == s.number) lastRead.second else s.startPage
    Column(
        Modifier.padding(6.dp).fillMaxWidth().clickable { onOpen(s.number, openPage) }
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
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${j.number}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_juz_title, j.number), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            stringResource(R.string.home_juz_pages, j.startPage, endPage) +
                                if (j.surahNameEn.isNotEmpty()) " · ${j.surahNameEn}" else "",
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
    onOpenAyah: (Int, Int, Int) -> Unit = { _, _, _ -> },
) {
    val pages by vm.bookmarks.collectAsStateWithLifecycle()
    val sorted = pages.sorted()
    val surahs = remember { data.surahList() }
    val ctx = LocalContext.current
    var ayahTick by remember { mutableStateOf(0) }
    val ayahKeys = remember(ayahTick) {
        AyahBookmarks.all(ctx)
            .mapNotNull { k ->
                val p = k.split(":")
                val s = p.getOrNull(0)?.toIntOrNull()
                val a = p.getOrNull(1)?.toIntOrNull()
                if (s != null && a != null) s to a else null
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
    }
    if (sorted.isEmpty() && ayahKeys.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.BookmarkBorder,
                null,
                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.home_bookmarks_empty_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.home_bookmarks_empty_hint),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        return
    }
    val bothSections = sorted.isNotEmpty() && ayahKeys.isNotEmpty()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp),
    ) {
        if (ayahKeys.isNotEmpty()) {
            if (bothSections) {
                item(key = "hdr-ayah") { BookmarkSectionHeader(stringResource(R.string.home_bookmark_section_ayahs)) }
            }
            items(ayahKeys, key = { "a:${it.first}:${it.second}" }) { (s, a) ->
                val info = surahs.firstOrNull { it.number == s }
                val text = remember(s, a) { vm.ayahText(s, a) }
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable {
                        onOpenAyah(s, vm.pageOfVerse(s, a) ?: info?.startPage ?: 1, a)
                    },
                    shape = IqraShape.card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(
                                    R.string.home_surah_ayah,
                                    info?.nameEn ?: stringResource(R.string.home_surah_n, s), a,
                                ),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text,
                                fontSize = 12.sp,
                                fontFamily = quranFont,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            AyahBookmarks.toggle(ctx, s, a)
                            ayahTick++
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                stringResource(R.string.home_cd_remove_ayah_bookmark),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }
        }
        if (sorted.isNotEmpty()) {
            if (bothSections) {
                item(key = "hdr-page") { BookmarkSectionHeader(stringResource(R.string.home_bookmark_section_pages)) }
            }
        items(sorted, key = { it }) { page ->
            val info = surahs.firstOrNull { page in it.startPage..it.endPage }
            Card(
                Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onOpen(info?.number ?: 1, page) },
                shape = IqraShape.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_page_n, page), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            info?.let { stringResource(R.string.home_surah_juz, it.nameEn, it.juz) } ?: "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = { vm.toggleBookmark(page) }) {
                        Icon(
                            Icons.Filled.Delete,
                            stringResource(R.string.home_cd_remove_bookmark),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun BookmarkSectionHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
    )
}
