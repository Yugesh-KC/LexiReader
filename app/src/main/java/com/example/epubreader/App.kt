package com.example.epubreader

import android.app.Application
import android.view.KeyEvent
import com.example.epubreader.data.AppDb
import com.example.epubreader.data.Library
import com.example.epubreader.data.SettingsStore
import com.example.epubreader.dict.Dictionary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var db: AppDb
        private set
    lateinit var dict: Dictionary
        private set
    lateinit var library: Library
        private set
    lateinit var settings: SettingsStore
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Set by the reader so the activity can route volume keys to page turns. */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    /**
     * Clears [keyHandler] only if it is still [h]. During screen transitions the outgoing reader is disposed
     * after the incoming one has installed its handler, so an unconditional clear would drop the new one.
     */
    fun clearKeyHandlerIf(h: (KeyEvent) -> Boolean) {
        if (keyHandler === h) keyHandler = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDb(this)
        dict = Dictionary(this, db)
        library = Library(this, db, appScope)
        settings = SettingsStore(this)
        // Copy/open the offline dictionary up-front so the first tap on a word is instant.
        appScope.launch(Dispatchers.IO) { dict.warmUp() }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
