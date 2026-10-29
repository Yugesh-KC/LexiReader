package com.example.epubreader.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.example.epubreader.R
import com.example.epubreader.data.Palette
import com.example.epubreader.dict.DictEntry

/**
 * Floating dictionary card anchored to the tapped word: below it when there is room, otherwise above.
 * Offline (WordNet) results show instantly; online results replace them as soon as they arrive.
 */
@Composable
fun DefinitionPopup(model: ReaderModel, lk: Lookup, topInset: Float, bottomInset: Float) {
    AnchoredCard(lk, lk.rect, topInset, bottomInset) { DefinitionContent(model, lk) }
}

/** Footnote text shown in place, so following a note reference never loses your page. */
@Composable
fun FootnotePopup(model: ReaderModel, f: Footnote, topInset: Float, bottomInset: Float) {
    AnchoredCard(f, f.rect, topInset, bottomInset) {
        Column {
            Row(Modifier.padding(start = 18.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.footnote_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { model.footnote = null }) { Icon(Icons.Filled.Close, stringResource(R.string.reader_close)) }
            }
            Text(
                f.text, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, bottom = 4.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = { model.copy(f.text) }) { Text(stringResource(R.string.dict_copy)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { model.followFootnote(f) }) { Text(stringResource(R.string.footnote_go)) }
            }
        }
    }
}

/** Card placed just below the anchor rect when there is room, otherwise just above it. */
@Composable
private fun AnchoredCard(key: Any, rect: Box, topInset: Float, bottomInset: Float, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenH = maxHeight
        val cardW = min(maxWidth - 20.dp, 560.dp)
        val maxCardH = screenH * 0.46f
        var cardH by remember(key) { mutableIntStateOf(0) }
        val anim = remember(key) { Animatable(0f) }
        LaunchedEffect(key, cardH > 0) {
            if (cardH > 0) anim.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
        }
        val spaceBelow = screenH.value - rect.b
        val below = spaceBelow >= rect.t
        val hDp = with(density) { cardH.toDp() }.value
        val yDp = if (below) rect.b + 10f else rect.t - 10f - hDp
        val minY = topInset + 8f
        val maxY = screenH.value - bottomInset - 8f - hDp
        val y = yDp.coerceIn(minY, maxOf(minY, maxY))
        val yPx = with(density) { y.dp.roundToPx() }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 12.dp,
            tonalElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, yPx) }
                .width(cardW)
                .heightIn(max = maxCardH)
                .onSizeChanged { cardH = it.height }
                .graphicsLayer {
                    alpha = anim.value
                    val s = 0.94f + 0.06f * anim.value
                    scaleX = s; scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, if (below) 0f else 1f)
                },
        ) {
            content()
        }
    }
}

@Composable
private fun DefinitionContent(model: ReaderModel, lk: Lookup) {
    val onlineList = lk.online.orEmpty()
    val showOnline = lk.preferOnline ?: onlineList.isNotEmpty()
    val entries: List<DictEntry>? = if (showOnline) lk.online else lk.offline
    val phonetic = onlineList.firstNotNullOfOrNull { it.phonetic }
    val ann = model.annotations.firstOrNull { it.id == lk.annotationId } ?: model.annotationAt(lk.chapter, lk.start, lk.end)

    Column {
        // Header
        Row(Modifier.padding(start = 18.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    lk.word, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (phonetic != null) {
                    Text(phonetic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { model.speak(lk.word) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.dict_pronounce)) }
            IconButton(onClick = { model.dismissLookup() }) { Icon(Icons.Filled.Close, stringResource(R.string.reader_close)) }
        }

        if (ann != null) {
            val tagNames = ann.tagIds.mapNotNull { model.tag(it) }
            Surface(
                onClick = { model.editAnnotation(ann) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth(),
            ) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier.width(4.dp).fillMaxHeight()
                            .background(Color(if (ann.color != null) Palette.highlight(ann.color) else (tagNames.firstOrNull()?.color?.toLong() ?: 0xFF888888)))
                    )
                    Column(Modifier.padding(10.dp).weight(1f)) {
                        if (tagNames.isNotEmpty()) {
                            Text(
                                tagNames.joinToString("  ·  ") { "#" + it.name }, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            ann.note.ifBlank { stringResource(R.string.dict_highlighted_passage) }, style = MaterialTheme.typography.bodySmall,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Filled.Edit, stringResource(R.string.dict_edit_note), Modifier.padding(10.dp).size(18.dp))
                }
            }
        }

        // Source switcher
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !showOnline,
                onClick = { lk.preferOnline = false },
                label = { Text(stringResource(R.string.dict_offline)) },
            )
            FilterChip(
                selected = showOnline,
                onClick = {
                    when (lk.onlineState) {
                        OnlineState.Failed, OnlineState.NoNetwork, OnlineState.Disabled, OnlineState.Idle -> model.retryOnline()
                        else -> lk.preferOnline = true
                    }
                },
                label = {
                    Text(
                        when (lk.onlineState) {
                            OnlineState.Loading -> stringResource(R.string.dict_online_loading)
                            OnlineState.Done -> onlineList.firstOrNull()?.source ?: stringResource(R.string.dict_online)
                            OnlineState.NotFound -> stringResource(R.string.dict_online_no_match)
                            OnlineState.NoNetwork -> stringResource(R.string.dict_no_internet)
                            OnlineState.Failed -> stringResource(R.string.dict_online_failed)
                            OnlineState.Disabled -> stringResource(R.string.dict_online_off)
                            OnlineState.Idle -> stringResource(R.string.dict_online)
                        },
                        maxLines = 1,
                    )
                },
                leadingIcon = when (lk.onlineState) {
                    OnlineState.Loading -> ({ CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) })
                    OnlineState.NoNetwork, OnlineState.Failed -> ({ Icon(Icons.Outlined.WifiOff, null, Modifier.size(16.dp)) })
                    else -> null
                },
            )
        }

        // Definitions
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(remember(entries) { androidx.compose.foundation.ScrollState(0) }).padding(horizontal = 18.dp, vertical = 6.dp)
        ) {
            when {
                entries == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                entries.isEmpty() -> EmptyResult(model, lk, showOnline)
                else -> Entries(entries, lk.word)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
            Action(Icons.Outlined.BorderColor, stringResource(R.string.dict_highlight), Modifier.weight(1f)) {
                model.dismissLookup(); model.quickHighlight(lk.chapter, lk.start, lk.end, lk.word)
            }
            Action(Icons.Outlined.EditNote, stringResource(R.string.dict_note), Modifier.weight(1f)) {
                model.newDraft(lk.chapter, lk.start, lk.end, lk.word, Draft.Focus.Note)
            }
            Action(Icons.Outlined.Sell, stringResource(R.string.dict_tag), Modifier.weight(1f)) { model.tagWord(lk) }
            Action(Icons.Filled.ContentCopy, stringResource(R.string.dict_copy), Modifier.weight(1f)) { model.copy(lk.word) }
            Action(Icons.Outlined.Language, stringResource(R.string.dict_web), Modifier.weight(1f)) { model.webSearch(lk.word) }
        }
    }
}

@Composable
private fun EmptyResult(model: ReaderModel, lk: Lookup, showOnline: Boolean) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            if (showOnline) stringResource(R.string.dict_no_online_def, lk.word) else stringResource(R.string.dict_not_offline),
            style = MaterialTheme.typography.bodyMedium,
        )
        val hint = when (lk.onlineState) {
            OnlineState.Loading -> stringResource(R.string.dict_hint_checking)
            OnlineState.NoNetwork -> stringResource(R.string.dict_hint_connect)
            OnlineState.Disabled -> stringResource(R.string.dict_hint_disabled)
            else -> if (lk.word.firstOrNull()?.isUpperCase() == true) stringResource(R.string.dict_hint_name) else null
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (lk.word.firstOrNull()?.isUpperCase() == true) {
            TextButton(onClick = { model.tagWord(lk) }, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Outlined.Sell, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.dict_tag_word, lk.word))
            }
        }
    }
}

@Composable
private fun Entries(entries: List<DictEntry>, word: String) {
    var expanded by remember(entries) { mutableStateOf(false) }
    val limit = if (expanded) Int.MAX_VALUE else 5
    var shown = 0
    var total = 0
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    for ((ei, e) in entries.withIndex()) {
        total += e.senses.size
        if (shown >= limit) continue
        if (entries.size > 1 || !e.headword.equals(word, ignoreCase = true)) {
            Text(
                e.headword, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = if (ei == 0) 2.dp else 10.dp),
            )
        }
        var lastPos: String? = null
        var n = 0
        for (s in e.senses) {
            if (shown >= limit) break
            if (s.pos != lastPos) {
                Text(
                    s.pos, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                lastPos = s.pos
            }
            n++
            shown++
            Row(Modifier.padding(vertical = 2.dp)) {
                Text("$n.", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.width(22.dp))
                Column {
                    Text(s.definition, style = MaterialTheme.typography.bodyMedium)
                    s.examples.firstOrNull()?.let {
                        Text("“$it”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = muted)
                    }
                    if (s.synonyms.isNotEmpty()) {
                        Text(
                            "≈ " + s.synonyms.take(5).joinToString(", "), style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                }
            }
        }
    }
    if (total > shown || expanded) {
        Text(
            if (expanded) stringResource(R.string.dict_show_less) else stringResource(R.string.dict_show_all, total),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 8.dp),
        )
    }
    if (entries.isNotEmpty()) {
        Text(
            entries.first().source, style = MaterialTheme.typography.labelSmall, color = muted.copy(alpha = .7f),
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
