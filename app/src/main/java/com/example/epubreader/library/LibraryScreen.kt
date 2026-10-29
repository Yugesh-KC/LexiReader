package com.example.epubreader.library

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.epubreader.App
import com.example.epubreader.R
import com.example.epubreader.data.AppThemeMode
import com.example.epubreader.data.BookEntity
import com.example.epubreader.data.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val coverCache = LruCache<String, ImageBitmap>(48)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (Long) -> Unit) {
    val lib = App.instance.library
    val books by lib.books.collectAsState()
    val importing by lib.importing.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<BookEntity?>(null) }

    LaunchedEffect(Unit) { lib.refresh() }

    // Imports run in the app scope (results and "open" events are handled by the activity), so opening
    // a book while a batch is still importing doesn't cancel the rest.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        lib.importAll(uris, openWhenSingle = true)
    }

    val filtered = books.filter {
        query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true)
    }
    val recent = books.firstOrNull { it.opened > 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.library_search_hint)) },
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Text(stringResource(R.string.library_title), fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            stringResource(if (searching) R.string.library_close_search else R.string.library_search),
                        )
                    }
                    AppThemeButton()
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.library_add_books)) },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (books.isEmpty()) EmptyLibrary { picker.launch(arrayOf("*/*")) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (recent != null && query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { ContinueCard(recent) { onOpen(recent.id) } }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.library_all_books, books.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                items(filtered, key = { it.id }) { b ->
                    BookTile(b, onOpen = { onOpen(b.id) }, onDelete = { toDelete = b })
                }
            }
            if (importing > 0) {
                val desc = stringResource(R.string.library_importing)
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).semantics { contentDescription = desc })
            }
        }
    }

    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.library_remove_title)) },
            text = { Text(stringResource(R.string.library_remove_message, b.title)) },
            confirmButton = {
                TextButton(onClick = { App.instance.appScope.launch { lib.delete(b) }; toDelete = null }) { Text(stringResource(R.string.library_remove)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AppThemeButton() {
    val store = App.instance.settings
    val mode = store.value.appTheme
    var open by remember { mutableStateOf(false) }
    fun icon(m: AppThemeMode) = when (m) {
        AppThemeMode.System -> Icons.Outlined.BrightnessAuto
        AppThemeMode.Light -> Icons.Outlined.LightMode
        AppThemeMode.Dark -> Icons.Outlined.DarkMode
    }
    @Composable
    fun label(m: AppThemeMode) = stringResource(
        when (m) {
            AppThemeMode.System -> R.string.app_theme_system
            AppThemeMode.Light -> R.string.app_theme_light
            AppThemeMode.Dark -> R.string.app_theme_dark
        }
    )
    Box {
        IconButton(onClick = { open = true }) { Icon(icon(mode), stringResource(R.string.app_theme_label, label(mode))) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (m in AppThemeMode.entries) {
                DropdownMenuItem(
                    text = { Text(label(m)) },
                    leadingIcon = { Icon(icon(m), null) },
                    trailingIcon = { if (m == mode) Icon(Icons.Filled.Check, stringResource(R.string.selected)) },
                    onClick = {
                        // Carry an explicit choice over to the page if it's on the plain opposite theme, so opening a book doesn't flash.
                        store.update {
                            val page = when {
                                m == AppThemeMode.Dark && it.theme == ReaderTheme.Light -> ReaderTheme.Dark
                                m == AppThemeMode.Light && it.theme == ReaderTheme.Dark -> ReaderTheme.Light
                                else -> it.theme
                            }
                            it.copy(appTheme = m, theme = page)
                        }
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.library_empty_message),
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAdd) { Text(stringResource(R.string.library_add_books)) }
    }
}

@Composable
private fun ContinueCard(b: BookEntity, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(b, Modifier.width(64.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(R.string.library_continue), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (b.author.isNotBlank()) {
                    Text(b.author, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { b.percent }, modifier = Modifier.weight(1f))
                    Text("  " + stringResource(R.string.library_percent, (b.percent * 100).roundToInt()), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(b: BookEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Box {
            BookCover(b, Modifier.fillMaxWidth())
            if (b.percent > 0.005f) {
                LinearProgressIndicator(
                    progress = { b.percent },
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).height(3.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.library_open)) }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text(stringResource(R.string.library_remove)) }, onClick = { menu = false; onDelete() })
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(b.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (b.author.isNotBlank()) {
            Text(b.author, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BookCover(b: BookEntity, modifier: Modifier) {
    val img by produceState<ImageBitmap?>(b.cover?.let { coverCache.get(it) }, b.cover) {
        val path = b.cover ?: return@produceState
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }?.also { coverCache.put(path, it) }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.aspectRatio(2f / 3f).clip(shape)) {
        val bmp = img
        if (bmp != null) {
            Image(bmp, b.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val hue = Math.floorMod(b.title.hashCode(), 360).toFloat()
            val c1 = Color.hsv(hue, .45f, .55f)
            val c2 = Color.hsv((hue + 40) % 360, .55f, .35f)
            Column(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, c2))).padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    b.title, color = Color.White, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, lineHeight = 17.sp, maxLines = 5, overflow = TextOverflow.Ellipsis,
                )
                Text(b.format.uppercase(), color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
