package com.example.epubreader.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.epubreader.App
import com.example.epubreader.R
import com.example.epubreader.data.Annotation
import com.example.epubreader.data.Palette
import com.example.epubreader.data.ReaderFont
import com.example.epubreader.data.ReaderTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PanelHost(model: ReaderModel) {
    when (model.panel) {
        Panel.Toc -> TocSheet(model)
        Panel.Settings -> SettingsSheet(model)
        else -> Unit
    }
    val full = model.panel
    AnimatedVisibility(
        visible = full == Panel.Notes || full is Panel.TagPage || full == Panel.Search,
        enter = slideInHorizontally { it / 3 } + fadeIn(), exit = slideOutHorizontally { it / 3 } + fadeOut(),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            when (full) {
                Panel.Notes -> NotesPanel(model)
                is Panel.TagPage -> TagPanel(model, full.tagId)
                Panel.Search -> SearchPanel(model)
                else -> Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_back)) }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

// ---------------------------------------------------------------------------------- shared rows

@Composable
fun LinkedNoteText(text: String, model: ReaderModel, style: TextStyle = MaterialTheme.typography.bodyMedium, maxLines: Int = Int.MAX_VALUE) {
    val primary = MaterialTheme.colorScheme.primary
    val tags = model.tags.toList()
    val annotated = remember(text, tags, primary) {
        buildAnnotatedString {
            var last = 0
            for (m in ReaderModel.LINK_RE.findAll(text)) {
                append(text.substring(last, m.range.first))
                val name = m.groupValues[1].trim()
                val tag = tags.firstOrNull { it.name.equals(name, true) }
                val c = if (tag != null) Color(tag.color) else primary
                withLink(
                    LinkAnnotation.Clickable(
                        "tag:$name",
                        TextLinkStyles(SpanStyle(color = c, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)),
                    ) { if (tag != null) model.panel = Panel.TagPage(tag.id) }
                ) { append(name) }
                last = m.range.last + 1
            }
            append(text.substring(last))
        }
    }
    Text(annotated, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnotationRow(model: ReaderModel, a: Annotation, onOpen: () -> Unit) {
    val tags = a.tagIds.mapNotNull { model.tag(it) }
    val bar = when {
        a.color != null -> Color(Palette.highlight(a.color))
        tags.isNotEmpty() -> Color(tags.first().color)
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).height(IntrinsicSize.Min).padding(start = 16.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(bar))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                a.quote, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, maxLines = 3,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            )
            if (a.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                LinkedNoteText(a.note, model, maxLines = 6)
            }
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    for (t in tags) TagPill(t.name, t.color) { model.panel = Panel.TagPage(t.id) }
                }
            }
        }
        IconButton(onClick = { model.editAnnotation(a) }) { Icon(Icons.Filled.Edit, stringResource(R.string.reader_edit), Modifier.size(18.dp)) }
    }
}

@Composable
private fun TagPill(name: String, color: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Color(color).copy(alpha = .16f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(color)))
            Spacer(Modifier.width(5.dp))
            Text(name, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SearchHitRow(model: ReaderModel, hit: SearchHit, onClick: () -> Unit) {
    val hl = MaterialTheme.colorScheme.primary
    val text = remember(hit) {
        buildAnnotatedString {
            val s = hit.snippet
            val a = hit.at.coerceIn(0, s.length)
            val b = (hit.at + hit.len).coerceIn(a, s.length)
            append(if (hit.at > 0 && a > 0) "…" else "")
            append(s.substring(0, a))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = hl)) { append(s.substring(a, b)) }
            append(s.substring(b)); append("…")
        }
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(model.chapterTitle(hit.chapter), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Spoken names for [Palette.tagColors], in the same order. */
private val TAG_COLOR_NAMES = listOf(
    R.string.color_red, R.string.color_blue, R.string.color_green, R.string.color_orange, R.string.color_purple,
    R.string.color_teal, R.string.color_pink, R.string.color_brown, R.string.color_indigo, R.string.color_light_green,
)

private val THEME_NAMES = mapOf(
    ReaderTheme.Light to R.string.theme_light, ReaderTheme.Sepia to R.string.theme_sepia, ReaderTheme.Dusk to R.string.theme_dusk,
    ReaderTheme.Dark to R.string.theme_dark, ReaderTheme.Black to R.string.theme_black,
)

private val FONT_NAMES = mapOf(
    ReaderFont.Serif to R.string.font_serif, ReaderFont.Sans to R.string.font_sans,
    ReaderFont.Literata to R.string.font_book, ReaderFont.Mono to R.string.font_mono,
)

private fun firstIndexFrom(chapters: List<Int>, current: Int): Int =
    chapters.indexOfFirst { it >= current }.let { if (it < 0) 0 else it }

// ---------------------------------------------------------------------------------- notes panel

@Composable
private fun NotesPanel(model: ReaderModel) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var newTagDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PanelHeader(stringResource(R.string.notes_notebook), onBack = { model.panel = Panel.None }) {
            IconButton(onClick = { model.exportNotes(ctx) }, enabled = model.annotations.isNotEmpty()) {
                Icon(Icons.Outlined.IosShare, stringResource(R.string.reader_export_notes))
            }
        }
        SecondaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.notes_tab_notes, model.annotations.size)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.notes_tab_tags, model.tags.size)) })
        }
        if (tab == 0) {
            val list = model.annotations.filter {
                filter.isBlank() || it.quote.contains(filter, true) || it.note.contains(filter, true) ||
                    it.tagIds.any { id -> model.tag(id)?.name?.contains(filter, true) == true }
            }
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text(stringResource(R.string.notes_filter)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (list.isEmpty()) {
                EmptyHint(
                    if (model.annotations.isEmpty()) stringResource(R.string.notes_empty)
                    else stringResource(R.string.notes_no_match, filter)
                )
            }
            val notesLabel = stringResource(R.string.notes_trail)
            LazyColumn(Modifier.fillMaxSize()) {
                var lastCh = -1
                list.forEachIndexed { i, a ->
                    if (a.chapter != lastCh) {
                        lastCh = a.chapter
                        item(key = "h${a.chapter}") {
                            Text(
                                model.chapterTitle(a.chapter), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
                            )
                        }
                    }
                    item(key = a.id) {
                        AnnotationRow(model, a) {
                            model.startTrail(notesLabel, null, list.map { model.locOf(it) }, i)
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    TextButton(onClick = { newTagDialog = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.note_new_tag))
                    }
                }
                if (model.tags.isEmpty()) item {
                    EmptyHint(stringResource(R.string.tags_empty))
                }
                items(model.tags, key = { it.id }) { t ->
                    val n = model.annotations.count { t.id in it.tagIds }
                    ListItem(
                        headlineContent = { Text(t.name, fontWeight = FontWeight.Medium) },
                        supportingContent = {
                            val count = pluralStringResource(R.plurals.tags_passages, n, n)
                            Text(
                                if (t.description.isNotBlank()) stringResource(R.string.tags_desc_with_count, t.description, count) else count,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(t.color)), contentAlignment = Alignment.Center) {
                                Text(t.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        },
                        modifier = Modifier.clickable { model.panel = Panel.TagPage(t.id) },
                    )
                }
            }
        }
    }
    if (newTagDialog) {
        NameDialog(stringResource(R.string.note_new_tag), "", onDismiss = { newTagDialog = false }) { name ->
            newTagDialog = false
            scope.launch { model.createTag(name)?.let { model.panel = Panel.TagPage(it) } }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onOk(name.trim()) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onOk(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.reader_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_cancel)) } },
    )
}

// ---------------------------------------------------------------------------------- tag page

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPanel(model: ReaderModel, tagId: Long) {
    val tag = model.tag(tagId)
    if (tag == null) {
        LaunchedEffect(Unit) { model.panel = Panel.Notes }
        return
    }
    var desc by remember(tagId) { mutableStateOf(tag.description) }
    var mentions by remember(tagId, tag.name) { mutableStateOf<List<SearchHit>?>(null) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    val passages = model.annotationsForTag(tagId)

    // Its own search channel: it neither disturbs the search panel nor gets stuck on short (1-letter, CJK) names.
    LaunchedEffect(tagId, tag.name) { mentions = model.findMentions(tag.name) }
    LaunchedEffect(desc) {
        delay(600)
        if (desc != tag.description) model.updateTag(tag.copy(description = desc))
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PanelHeader(tag.name, onBack = { model.panel = Panel.Notes }) {
            IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.tag_rename)) }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.tag_delete)) }
        }
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val changeColor = stringResource(R.string.tag_change_color)
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = changeColor) { colorMenu = !colorMenu }
                            .semantics { contentDescription = changeColor },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(tag.color)), contentAlignment = Alignment.Center) {
                            Text(tag.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = desc, onValueChange = { desc = it },
                        label = { Text(stringResource(R.string.tag_description_label)) }, maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (colorMenu) {
                    // 48dp targets around 28dp swatches; wraps on narrow screens.
                    FlowRow(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).selectableGroup()) {
                        Palette.tagColors.forEachIndexed { i, c ->
                            val name = stringResource(TAG_COLOR_NAMES.getOrElse(i) { R.string.color_unknown })
                            Box(
                                Modifier.size(48.dp)
                                    .selectable(selected = c == tag.color, role = Role.RadioButton) {
                                        model.updateTag(tag.copy(color = c)); colorMenu = false
                                    }
                                    .semantics { contentDescription = name },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                        .border(if (c == tag.color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                )
                            }
                        }
                    }
                }
            }
            item {
                SectionHeader(stringResource(R.string.tag_your_passages, passages.size)) {
                    if (passages.isNotEmpty()) FollowButton {
                        model.startTrail(tag.name, tag.color, passages.map { model.locOf(it) }, firstIndexFrom(passages.map { it.chapter }, model.chapter))
                    }
                }
            }
            if (passages.isEmpty()) item {
                EmptyHint(stringResource(R.string.tag_no_passages, tag.name))
            }
            itemsIndexed(passages, key = { _, a -> "p${a.id}" }) { i, a ->
                AnnotationRow(model, a) { model.startTrail(tag.name, tag.color, passages.map { model.locOf(it) }, i) }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 12.dp))
                val m = mentions
                SectionHeader(if (m == null) stringResource(R.string.tag_mentions) else stringResource(R.string.tag_mentions_count, m.size)) {
                    if (m == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else if (m.isNotEmpty()) FollowButton {
                        model.startTrail(tag.name, tag.color, m.map { Loc(it.chapter, find = tag.name, nth = it.nth, whole = true) }, firstIndexFrom(m.map { it.chapter }, model.chapter))
                    }
                }
                if (m != null && m.isEmpty()) EmptyHint(stringResource(R.string.tag_no_mentions, tag.name))
            }
            val m = mentions
            if (m != null) {
                itemsIndexed(m.take(500), key = { i, _ -> "m$i" }) { i, hit ->
                    SearchHitRow(model, hit) {
                        model.startTrail(tag.name, tag.color, m.map { Loc(it.chapter, find = tag.name, nth = it.nth, whole = true) }, i)
                    }
                }
            }
        }
    }
    if (rename) {
        NameDialog(stringResource(R.string.tag_rename_title), tag.name, onDismiss = { rename = false }) { model.updateTag(tag.copy(name = it)); rename = false }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tag_delete_title, tag.name)) },
            text = { Text(stringResource(R.string.tag_delete_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; model.deleteTag(tag.id) }) { Text(stringResource(R.string.reader_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.reader_cancel)) } },
        )
    }
}

@Composable
private fun SectionHeader(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun FollowButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.tag_follow))
    }
}

// ---------------------------------------------------------------------------------- search

@Composable
private fun SearchPanel(model: ReaderModel) {
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    LaunchedEffect(model.searchQuery) {
        delay(350)
        model.search(model.searchQuery)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.panel = Panel.None }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_back)) }
            TextField(
                value = model.searchQuery, onValueChange = { model.searchQuery = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { model.search(model.searchQuery) }),
                trailingIcon = {
                    if (model.searchQuery.isNotEmpty()) IconButton(onClick = { model.searchQuery = ""; model.clearSearch() }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.search_clear))
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        if (model.searching) LinearProgressIndicator(progress = { model.searchProgress }, modifier = Modifier.fillMaxWidth())
        val results = model.searchResults
        if (results != null) {
            Text(
                when {
                    results.isEmpty() -> stringResource(R.string.search_no_results)
                    results.size >= 1000 -> stringResource(R.string.search_results_capped, results.size)
                    else -> pluralStringResource(R.plurals.search_results, results.size, results.size)
                },
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(results, key = { i, _ -> i }) { i, hit ->
                    SearchHitRow(model, hit) {
                        val q = model.searchQuery.trim()
                        model.startTrail(ctx.getString(R.string.search_trail, q), null, results.map { Loc(it.chapter, find = q, nth = it.nth) }, i)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- contents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocSheet(model: ReaderModel) {
    val sheet = rememberModalBottomSheetState()
    val entries = model.toc.ifEmpty {
        if (model.isPdf) emptyList()
        else (0 until model.chapterCount).map { com.example.epubreader.book.TocEntry(model.chapterTitle(it), it, "", 0) }
    }
    val current = entries.indexOfLast { it.chapter <= model.chapter }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    var page by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { model.panel = Panel.None }, sheetState = sheet) {
        Text(stringResource(R.string.reader_contents), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        if (model.isPdf) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = page, onValueChange = { page = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    label = { Text(stringResource(R.string.toc_go_to_page, model.chapterCount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        val n = model.chapterCount
                        // n is 0 until the PDF reports its page count; coerceIn(0, -1) would throw.
                        if (n > 0) page.toIntOrNull()?.let { model.goTo(Loc((it - 1).coerceIn(0, n - 1), progress = 0f), flash = false) }
                        model.panel = Panel.None
                    }),
                    modifier = Modifier.weight(1f),
                )
            }
            if (entries.isEmpty()) EmptyHint(stringResource(R.string.toc_no_outline))
        }
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(entries) { i, e ->
                val sel = i == current
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable {
                            model.panel = Panel.None
                            if (model.isPdf) model.goTo(Loc(e.chapter, progress = 0f), flash = false)
                            else model.goToAnchor(e.chapter, e.anchor)
                        }
                        .padding(start = (24 + 16 * e.depth).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        e.title.ifBlank { stringResource(R.string.toc_untitled) }, modifier = Modifier.weight(1f),
                        fontWeight = if (sel) FontWeight.SemiBold else if (e.depth == 0) FontWeight.Medium else FontWeight.Normal,
                        style = if (e.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    )
                    if (model.isPdf) Text("${e.chapter + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(model: ReaderModel) {
    val store = App.instance.settings
    val s = store.value
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = { model.panel = Panel.None }, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().selectableGroup()) {
                for (t in ReaderTheme.entries) {
                    val sel = s.theme == t
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.selectable(selected = sel, role = Role.RadioButton) { store.update { it.copy(theme = t) } },
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Color(t.bg))
                                .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(R.string.settings_theme_sample), color = Color(t.fg), fontFamily = FontFamily.Serif) }
                        Text(THEME_NAMES[t]?.let { stringResource(it) } ?: t.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (!model.isPdf) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_page_turning), modifier = Modifier.weight(1f))
                    FilterChip(
                        selected = s.paged, onClick = { store.update { it.copy(paged = true) } },
                        label = { Text(stringResource(R.string.settings_pages)) },
                        leadingIcon = if (s.paged) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null,
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = !s.paged, onClick = { store.update { it.copy(paged = false) } },
                        label = { Text(stringResource(R.string.settings_scroll)) },
                        leadingIcon = if (!s.paged) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_text_size), modifier = Modifier.weight(1f))
                    IconButton(onClick = { store.update { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(12)) } }) { Icon(Icons.Filled.Remove, stringResource(R.string.settings_smaller)) }
                    Text("${s.fontSize}", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { store.update { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(40)) } }) { Icon(Icons.Filled.Add, stringResource(R.string.settings_larger)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in ReaderFont.entries) {
                        FilterChip(
                            selected = s.font == f, onClick = { store.update { it.copy(font = f) } },
                            label = { Text(FONT_NAMES[f]?.let { stringResource(it) } ?: f.label) },
                            leadingIcon = if (s.font == f) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null,
                        )
                    }
                }
                LabeledSlider(stringResource(R.string.settings_line_spacing), "%.1f".format(s.lineHeight), s.lineHeight, 1.2f..2.2f, 9) { v ->
                    store.update { it.copy(lineHeight = (v * 10).roundToInt() / 10f) }
                }
                LabeledSlider(stringResource(R.string.settings_margins), "${s.margin}", s.margin.toFloat(), 4f..56f, 12) { v ->
                    store.update { it.copy(margin = v.roundToInt()) }
                }
                SwitchRow(stringResource(R.string.settings_justify), null, s.justify) { v -> store.update { it.copy(justify = v) } }
            } else {
                LabeledSlider(stringResource(R.string.settings_zoom), stringResource(R.string.reader_percent, (s.pdfZoom * 100).roundToInt()), s.pdfZoom, 1f..3f, 7) { v ->
                    store.update { it.copy(pdfZoom = (v * 4).roundToInt() / 4f) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_meaning_on))
                    Text(
                        stringResource(if (s.doubleTapDefine) R.string.settings_meaning_double_hint else R.string.settings_meaning_tap_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilterChip(
                    selected = s.doubleTapDefine, onClick = { store.update { it.copy(doubleTapDefine = true) } },
                    label = { Text(stringResource(R.string.settings_double_tap)) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = !s.doubleTapDefine, onClick = { store.update { it.copy(doubleTapDefine = false) } },
                    label = { Text(stringResource(R.string.settings_tap)) },
                )
            }
            SwitchRow(stringResource(R.string.settings_online_dict), stringResource(R.string.settings_online_dict_hint), s.onlineDictionary) { v ->
                store.update { it.copy(onlineDictionary = v) }
            }
            SwitchRow(stringResource(R.string.settings_tap_edges), stringResource(R.string.settings_tap_edges_hint), s.tapZones) { v -> store.update { it.copy(tapZones = v) } }
            SwitchRow(stringResource(R.string.settings_volume_keys), null, s.volumeKeys) { v -> store.update { it.copy(volumeKeys = v) } }
            SwitchRow(stringResource(R.string.settings_keep_screen_on), null, s.keepScreenOn) { v -> store.update { it.copy(keepScreenOn = v) } }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    var local by remember(v) { mutableFloatStateOf(v) }
    Column(Modifier.padding(top = 10.dp)) {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range, steps = steps)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
