package com.example.epubreader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.epubreader.R
import com.example.epubreader.data.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Spoken names for the highlight colours, in [Palette.highlightKeys] order. */
private fun highlightName(key: String) = when (key) {
    "yellow" -> R.string.color_yellow
    "green" -> R.string.color_green
    "blue" -> R.string.color_blue
    "pink" -> R.string.color_pink
    "purple" -> R.string.color_purple
    else -> R.string.color_unknown
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnnotationSheet(model: ReaderModel, d: Draft) {
    val scope = rememberCoroutineScope()
    var note by remember(d) { mutableStateOf(TextFieldValue(d.note, TextRange(d.note.length))) }
    var color by remember(d) { mutableStateOf(d.color) }
    var tagIds by remember(d) { mutableStateOf(d.tagIds) }
    var newTag by remember(d) {
        mutableStateOf(if (d.focus == Draft.Focus.Tag && d.tagIds.isEmpty()) d.suggestedTag.orEmpty() else "")
    }
    var linkMenu by remember { mutableStateOf(false) }
    var saving by remember(d) { mutableStateOf(false) }
    // Not keyed on the draft: the sheet state's confirmValueChange below keeps the first instance it saw.
    var confirmDiscard by remember { mutableStateOf(false) }
    val closeRequestsAtOpen = remember(d) { model.draftCloseRequests }
    val noteFocus = remember { FocusRequester() }
    val tagFocus = remember { FocusRequester() }

    // Typed note text that hasn't been saved; closing the sheet then asks before throwing it away.
    val dirty = note.text.isNotBlank() && note.text.trim() != d.note.trim()
    val dirtyNow by rememberUpdatedState(dirty && !saving && !model.saving)
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && dirtyNow) { confirmDiscard = true; false } else true
        },
    )

    fun requestClose() {
        if (dirtyNow) confirmDiscard = true else model.draft = null
    }

    LaunchedEffect(model.draftCloseRequests) {
        if (model.draftCloseRequests > closeRequestsAtOpen) requestClose()
    }

    LaunchedEffect(d) {
        delay(250)
        when (d.focus) {
            Draft.Focus.Note -> runCatching { noteFocus.requestFocus() }
            Draft.Focus.Tag -> if (d.tagIds.isEmpty()) runCatching { tagFocus.requestFocus() }
            Draft.Focus.None -> Unit
        }
    }

    fun addNewTag(then: (() -> Unit)? = null) {
        val name = newTag.trim()
        if (name.isEmpty()) { then?.invoke(); return }
        scope.launch {
            val id = model.createTag(name)
            if (id != null) {
                tagIds = tagIds + id
                newTag = ""
            }
            then?.invoke()
        }
    }

    fun save() {
        if (saving || model.saving) return
        saving = true
        addNewTag {
            model.saveDraft(d.copy(note = note.text, color = color, tagIds = tagIds))
            saving = false
        }
    }

    ModalBottomSheet(onDismissRequest = { model.draft = null }, sheetState = sheet) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding().verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(if (d.id == 0L) R.string.note_new else R.string.note_edit), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (color != null) Color(Palette.highlight(color)).copy(alpha = .22f) else MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    d.quote, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, maxLines = 5,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp).fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(14.dp))
            // 48dp touch targets around 34dp swatches, announced as a group of radio buttons.
            Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                for (key in Palette.highlightKeys) {
                    val sel = color == key
                    val name = stringResource(highlightName(key))
                    Box(
                        Modifier.size(48.dp)
                            .selectable(selected = sel, role = Role.RadioButton, onClick = { color = key })
                            .semantics { contentDescription = name },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(Palette.highlight(key)))
                                .border(if (sel) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (sel) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = Color.Black) }
                    }
                }
                val none = color == null
                val noneLabel = stringResource(R.string.note_no_highlight)
                Box(
                    Modifier.size(48.dp)
                        .selectable(selected = none, role = Role.RadioButton, onClick = { color = null })
                        .semantics { contentDescription = noneLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(if (none) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (none) 1f else .3f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.FormatUnderlined, null, Modifier.size(18.dp)) }
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.note_label)) },
                placeholder = { Text(stringResource(R.string.note_placeholder)) },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(noteFocus),
            )
            Box {
                TextButton(onClick = { linkMenu = true }) {
                    Icon(Icons.Filled.Link, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.note_link_tag))
                }
                DropdownMenu(expanded = linkMenu, onDismissRequest = { linkMenu = false }) {
                    if (model.tags.isEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.note_no_tags_yet)) }, onClick = { linkMenu = false })
                    }
                    for (t in model.tags) {
                        DropdownMenuItem(
                            text = { Text(t.name) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.color))) },
                            onClick = {
                                linkMenu = false
                                val ins = "[[${t.name}]]"
                                val sel = note.selection
                                val txt = note.text.replaceRange(sel.min, sel.max, ins)
                                note = TextFieldValue(txt, TextRange(sel.min + ins.length))
                                tagIds = tagIds + t.id
                            },
                        )
                    }
                }
            }

            Text(stringResource(R.string.reader_tags), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in model.tags) {
                    val sel = t.id in tagIds
                    FilterChip(
                        selected = sel,
                        onClick = { tagIds = if (sel) tagIds - t.id else tagIds + t.id },
                        label = { Text(t.name) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.color))) },
                    )
                }
            }
            OutlinedTextField(
                value = newTag,
                onValueChange = { newTag = it },
                singleLine = true,
                label = { Text(stringResource(R.string.note_new_tag)) },
                placeholder = { Text(stringResource(R.string.note_new_tag_placeholder)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addNewTag() }),
                trailingIcon = {
                    IconButton(onClick = { addNewTag() }, enabled = newTag.isNotBlank()) {
                        Icon(Icons.Filled.Add, stringResource(R.string.note_add_tag))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).focusRequester(tagFocus),
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (d.id > 0) {
                    TextButton(onClick = { model.deleteAnnotation(d.id) }) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(if (d.note.isNotBlank()) R.string.note_remove_note else R.string.note_remove_highlight),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { requestClose() }) { Text(stringResource(R.string.reader_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { save() }, enabled = !saving && !model.saving) { Text(stringResource(R.string.note_save)) }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.note_discard_title)) },
            text = { Text(stringResource(R.string.note_discard_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; model.draft = null }) { Text(stringResource(R.string.note_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.note_keep_editing)) }
            },
        )
    }
}
