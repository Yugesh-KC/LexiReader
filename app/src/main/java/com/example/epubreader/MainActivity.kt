package com.example.epubreader

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.example.epubreader.data.ImportEvent
import com.example.epubreader.library.LibraryScreen
import com.example.epubreader.reader.ReaderScreen
import com.example.epubreader.ui.theme.EpubReaderTheme
import com.example.epubreader.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val dark = App.instance.settings.value.appTheme.isDark()
            EpubReaderTheme(darkTheme = dark) {
                var openBook by rememberSaveable { mutableStateOf<Long?>(null) }
                // System bar icons follow the app theme in the library; the reader styles them from its page theme.
                // Keyed on openBook too so the library's style is restored after leaving a differently-themed book.
                val windowBg = MaterialTheme.colorScheme.background.toArgb()
                LaunchedEffect(dark, openBook, windowBg) {
                    if (openBook == null) {
                        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    }
                    window.decorView.setBackgroundColor(windowBg)
                }
                // Imports run in the app scope; their results are buffered until this collector picks them up,
                // so they survive leaving the library and activity recreation.
                LaunchedEffect(Unit) {
                    App.instance.library.events.collect { e ->
                        when (e) {
                            is ImportEvent.Open -> openBook = e.bookId
                            is ImportEvent.Failed -> Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                AnimatedContent(openBook, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { id ->
                    if (id == null) LibraryScreen(onOpen = { openBook = it })
                    else ReaderScreen(id, onClose = { openBook = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        // Re-launches from recents replay the original intent without its URI grant — ignore those.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        intent.data?.let { App.instance.library.importAll(listOf(it), openWhenSingle = true) }
    }

    // Volume keys reach these only when no view consumed them, and before the window's default volume handling.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.instance.keyHandler?.invoke(event) == true || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        App.instance.keyHandler?.invoke(event) == true || super.onKeyUp(keyCode, event)
}
