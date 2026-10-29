package com.example.epubreader.reader

import android.app.Activity
import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.epubreader.App
import com.example.epubreader.R
import com.example.epubreader.data.ReaderTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(bookId: Long, onClose: () -> Unit) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val model = remember(bookId) { ReaderModel(app, bookId, scope) }
    val settings = app.settings.value
    val view = LocalView.current
    val density = LocalDensity.current

    // Declared first so it is disposed last: the effects below check it to tell whether a newer reader
    // (another book opened while this one is still fading out) has taken over the window.
    DisposableEffect(model) {
        activeReader = model
        onDispose { if (activeReader === model) activeReader = null }
    }
    fun stillActive() = activeReader == null || activeReader === model

    LaunchedEffect(model) { model.load() }
    DisposableEffect(model) { onDispose { model.dispose() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { model.flushPosition() }

    LaunchedEffect(settings) {
        if (model.ready) model.js("Reader.applySettings(${settings.toJs()})")
        model.web?.setBackgroundColor(settings.theme.bg)
    }

    // Reading area padding follows the system bars even while they are hidden, so text never jumps.
    val topInset = with(density) { WindowInsets.statusBarsIgnoringVisibility.getTop(this).toDp().value }
    val bottomInset = with(density) { WindowInsets.navigationBarsIgnoringVisibility.getBottom(this).toDp().value }
    LaunchedEffect(topInset, bottomInset) { model.setInsets(topInset.coerceAtLeast(12f), bottomInset.coerceAtLeast(12f)) }

    // Immersive reading: hide system bars unless the menus are showing.
    val immersive = !model.chromeVisible && model.panel == Panel.None && model.draft == null
    LaunchedEffect(immersive, settings.theme) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = !settings.theme.dark
        c.isAppearanceLightNavigationBars = !settings.theme.dark
        if (immersive) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(Unit) {
        onDispose {
            if (!stillActive()) return@onDispose
            val window = (view.context as? Activity)?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { if (stillActive()) view.keepScreenOn = false }
    }
    DisposableEffect(model) {
        val handler: (KeyEvent) -> Boolean = handler@{ ev ->
            if (!app.settings.value.volumeKeys || model.panel != Panel.None || model.draft != null) return@handler false
            val dir = when (ev.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> 1
                KeyEvent.KEYCODE_VOLUME_UP -> -1
                else -> return@handler false
            }
            if (ev.action == KeyEvent.ACTION_DOWN) { model.dismissLookup(); model.page(dir) }
            true
        }
        app.keyHandler = handler
        onDispose { app.clearKeyHandlerIf(handler) }
    }

    BackHandler {
        when {
            model.draft != null -> model.draftCloseRequests++ // the sheet asks before dropping unsaved text
            model.footnote != null -> model.footnote = null
            model.panel is Panel.TagPage -> model.panel = Panel.Notes
            model.panel != Panel.None -> model.panel = Panel.None
            model.lookup != null -> model.dismissLookup()
            model.trail != null -> model.trail = null
            model.chromeVisible -> model.chromeVisible = false
            else -> onClose()
        }
    }

    MaterialTheme(colorScheme = readerScheme(settings.theme)) {
        Box(Modifier.fillMaxSize().background(Color(settings.theme.bg))) {
            // A new generation replaces a WebView whose renderer crashed; it reopens at the saved position.
            key(model.webGeneration) {
                AndroidView(
                    factory = { ctx -> createReaderWebView(ctx, model, settings.theme.bg) },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.destroy() },
                )
            }

            if (!model.ready && model.error == null) LoadingCover(settings.theme)
            model.error?.let { ErrorView(it, onClose) }

            FloatingNav(model, Modifier.align(Alignment.BottomCenter).padding(bottom = (bottomInset + if (model.chromeVisible) 150f else 18f).dp))

            AnimatedVisibility(
                visible = model.chromeVisible,
                enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) { TopBar(model, onClose) }
            AnimatedVisibility(
                visible = model.chromeVisible,
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { BottomBar(model) }

            AnimatedVisibility(
                visible = !model.chromeVisible && model.trail == null,
                enter = fadeIn(), exit = fadeOut(),
            ) { PageFolio(model, settings.theme, settings.paged, topInset, bottomInset) }

            model.lookup?.let { DefinitionPopup(model, it, topInset, bottomInset) }
            model.footnote?.let { FootnotePopup(model, it, topInset, bottomInset) }

            model.undo?.let { u ->
                UndoBar(model, u, Modifier.align(Alignment.BottomCenter).padding(bottom = (bottomInset + if (model.chromeVisible) 160f else 52f).dp))
            }

            PanelHost(model)
            model.draft?.let { AnnotationSheet(model, it) }
        }
    }
}

/** The reader that currently owns the window (keep-screen-on, system bars). Only touched on the main thread. */
private var activeReader: ReaderModel? = null

@Composable
fun readerScheme(t: ReaderTheme): ColorScheme {
    val ctx = LocalContext.current
    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (t.dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (t.dark) darkColorScheme() else lightColorScheme()
    return when (t) {
        ReaderTheme.Sepia -> base.copy(
            primary = Color(0xFF8A4B14), onPrimary = Color.White,
            primaryContainer = Color(0xFFEBD3B4), onPrimaryContainer = Color(0xFF3A2008),
            secondaryContainer = Color(0xFFE7D6BA), onSecondaryContainer = Color(0xFF3E3022),
            background = Color(0xFFF5ECD9), surface = Color(0xFFF5ECD9), onSurface = Color(0xFF3E3022),
            surfaceVariant = Color(0xFFE9DCC3), onSurfaceVariant = Color(0xFF6B5A45),
            surfaceContainerLowest = Color(0xFFF8F1E3), surfaceContainerLow = Color(0xFFF3E9D4),
            surfaceContainer = Color(0xFFEFE3CA), surfaceContainerHigh = Color(0xFFEADCBF),
            surfaceContainerHighest = Color(0xFFE4D4B4), outlineVariant = Color(0xFFD7C4A3),
        )
        ReaderTheme.Black -> base.copy(
            background = Color.Black, surface = Color.Black,
            surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0B0B0B),
            surfaceContainer = Color(0xFF121212), surfaceContainerHigh = Color(0xFF1B1B1B),
            surfaceContainerHighest = Color(0xFF242424),
        )
        else -> base
    }
}

@Composable
private fun LoadingCover(theme: ReaderTheme) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(250); show = true }
    Box(Modifier.fillMaxSize().background(Color(theme.bg)), contentAlignment = Alignment.Center) {
        if (show) CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun ErrorView(msg: String, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.reader_error_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(msg, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onClose) { Text(stringResource(R.string.reader_back_to_library)) }
        }
    }
}

@Composable
private fun TopBar(model: ReaderModel, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.statusBarsPadding().height(60.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_library)) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    model.entity?.title.orEmpty(), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    model.chapterTitle(), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { model.panel = Panel.Search }) { Icon(Icons.Filled.Search, stringResource(R.string.reader_search)) }
            IconButton(onClick = { model.panel = Panel.Toc }) { Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.reader_contents)) }
            IconButton(onClick = { model.panel = Panel.Notes }) { Icon(Icons.Outlined.BookmarkBorder, stringResource(R.string.reader_notes_and_tags)) }
            IconButton(onClick = { model.panel = Panel.Settings }) { Icon(Icons.Filled.TextFields, stringResource(R.string.reader_reading_settings)) }
        }
    }
}

@Composable
private fun BottomBar(model: ReaderModel) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var goToDialog by remember { mutableStateOf(false) }
    val total = model.pageTotal
    val usePages = total > 1
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
            // Slider works in book pages when they are known, otherwise in percent.
            val sliderValue = dragging ?: if (usePages) model.pageNumber.toFloat().coerceIn(1f, total.toFloat()) else model.percent
            val targetPage = if (usePages) sliderValue.roundToInt().coerceIn(1, total) else 0
            val targetChapter = when {
                dragging == null -> model.chapter
                usePages -> model.chapterOfPage(targetPage)
                else -> model.locationOfPercent(sliderValue).chapter
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.chapterTitle(targetChapter), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    fontWeight = if (dragging != null) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    stringResource(R.string.reader_percent, if (usePages) (targetPage * 100f / total).roundToInt() else (sliderValue * 100).roundToInt()),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Slider(
                value = sliderValue,
                valueRange = if (usePages) 1f..total.toFloat() else 0f..1f,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let {
                        if (usePages) model.goToPage(it.roundToInt().coerceIn(1, total))
                        else model.goTo(model.locationOfPercent(it), remember = true, flash = false)
                    }
                    dragging = null
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.goTo(Loc((model.chapter - 1).coerceAtLeast(0), progress = 0f), flash = false) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.reader_prev_chapter))
                }
                Surface(
                    onClick = { goToDialog = true },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                ) {
                    Text(
                        when {
                            dragging != null && usePages -> stringResource(R.string.reader_go_to_page_of, targetPage, total)
                            usePages -> stringResource(
                                if (model.pagesExact || model.isPdf) R.string.reader_page_of else R.string.reader_page_of_est,
                                model.pageNumber, total,
                            )
                            else -> stringResource(R.string.reader_chapter_of, model.chapter + 1, model.chapterCount)
                        },
                        style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                    )
                }
                IconButton(onClick = {
                    model.goTo(Loc((model.chapter + 1).coerceAtMost(model.chapterCount - 1).coerceAtLeast(0), progress = 0f), flash = false)
                }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.reader_next_chapter)) }
            }
        }
    }
    if (goToDialog && usePages) GoToPageDialog(model, total) { goToDialog = false }
}

@Composable
private fun GoToPageDialog(model: ReaderModel, total: Int, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val n = text.toIntOrNull()
    val valid = n != null && n in 1..total
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { focus.requestFocus() } }
    fun go() {
        if (valid) { model.goToPage(n!!); onDismiss() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_go_to_page)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.filter(Char::isDigit).take(6) }, singleLine = true,
                label = { Text(stringResource(R.string.reader_page_range, total)) },
                supportingText = { if (n != null && valid) Text(model.chapterTitle(model.chapterOfPage(n)), maxLines = 1) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { go() }, enabled = valid) { Text(stringResource(R.string.reader_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_cancel)) } },
    )
}

/** Always-on page counter (and running chapter title in page mode), like a printed book's folio. */
@Composable
private fun PageFolio(model: ReaderModel, theme: ReaderTheme, paged: Boolean, topInset: Float, bottomInset: Float) {
    if (!model.ready || model.pageTotal <= 0) return
    val muted = Color(theme.muted)
    val noRipple = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize()) {
        // In page mode the header and footer strips are text-free margins, so they open the menus. In scroll mode
        // text runs underneath them; they stay out of the way there and a tap on the page toggles the menus.
        if (paged || model.isPdf) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height((topInset + 28).dp)
                    .clickable(interactionSource = noRipple, indication = null) { model.dismissLookup(); model.chromeVisible = true }
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((bottomInset + 36).dp)
                    .clickable(interactionSource = noRipple, indication = null) { model.dismissLookup(); model.chromeVisible = true }
            )
        }
        if (paged && !model.isPdf) {
            Text(
                model.chapterTitle(), color = muted, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(top = (topInset + 6).dp, start = 48.dp, end = 48.dp),
            )
        }
        val left = model.chapterPages - model.chapterPage
        val pageOf = stringResource(R.string.reader_folio_page_of, model.pageNumber, model.pageTotal)
        val label = when {
            model.isPdf || left <= 0 -> pageOf
            left == 1 -> stringResource(R.string.reader_folio_with, pageOf, stringResource(R.string.reader_last_page_in_chapter))
            else -> stringResource(R.string.reader_folio_with, pageOf, pluralStringResource(R.plurals.reader_pages_left_in_chapter, left, left))
        }
        Surface(
            color = if (paged || model.isPdf) Color.Transparent else Color(theme.bg).copy(alpha = .88f),
            shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (bottomInset + 6).dp),
        ) {
            Text(
                label, color = muted, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

/** Snackbar-style "Highlighted · Undo" bar; disappears by itself after a few seconds. */
@Composable
private fun UndoBar(model: ReaderModel, u: UndoAction, modifier: Modifier) {
    LaunchedEffect(u) {
        delay(8000)
        if (model.undo === u) model.undo = null
    }
    Surface(
        shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier.padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(u.message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { model.runUndo() }) {
                Text(stringResource(R.string.reader_undo), color = MaterialTheme.colorScheme.inversePrimary, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { model.undo = null }) { Icon(Icons.Filled.Close, stringResource(R.string.reader_dismiss), Modifier.size(18.dp)) }
        }
    }
}

/** Trail stepper ("Gandalf 3/12 ‹ ›") and the "back to where I was" chip. */
@Composable
private fun FloatingNav(model: ReaderModel, modifier: Modifier) {
    var showBack by remember { mutableStateOf(false) }
    LaunchedEffect(model.history.size) {
        if (model.history.isNotEmpty()) { showBack = true; delay(7000); showBack = false }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val t = model.trail
        if (t != null) {
            Surface(shape = RoundedCornerShape(50), shadowElevation = 6.dp, tonalElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = { model.trailStep(-1) }, enabled = t.index > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.reader_previous))
                    }
                    if (t.color != null) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.color)))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        t.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp).clickable {
                            model.panel = Panel.Notes
                        },
                    )
                    Text("  ${t.index + 1}/${t.items.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { model.trailStep(1) }, enabled = t.index < t.items.lastIndex) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.reader_next))
                    }
                    IconButton(onClick = { model.trail = null }) { Icon(Icons.Filled.Close, stringResource(R.string.reader_close), Modifier.size(18.dp)) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        AnimatedVisibility(
            visible = model.history.isNotEmpty() && (showBack || model.chromeVisible),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Surface(
                onClick = { model.goBack() }, shape = RoundedCornerShape(50), shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.reader_back_to_where), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
