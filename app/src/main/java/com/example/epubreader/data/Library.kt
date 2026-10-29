package com.example.epubreader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.epubreader.R
import com.example.epubreader.book.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

sealed interface ImportEvent {
    data class Failed(val message: String) : ImportEvent
    data class Open(val bookId: Long) : ImportEvent
}

/** Imports files into private storage (fast random access, no lost permissions) and tracks the shelf. */
class Library(private val ctx: Context, private val db: AppDb, private val scope: CoroutineScope) {
    private val booksDir = File(ctx.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(ctx.filesDir, "covers").apply { mkdirs() }

    private val _books = MutableStateFlow<List<BookEntity>>(emptyList())
    val books: StateFlow<List<BookEntity>> = _books

    private val _importing = MutableStateFlow(0)
    /** Number of files still waiting to be imported. */
    val importing: StateFlow<Int> = _importing

    // Buffered so results survive while no screen is collecting (e.g. during activity recreation).
    private val _events = Channel<ImportEvent>(Channel.UNLIMITED)
    /** Import results for the UI. Collect from a single place (the activity). */
    val events: Flow<ImportEvent> = _events.receiveAsFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) { _books.value = db.books() }

    /**
     * Imports in the app scope, so leaving the library or recreating the activity doesn't cancel it.
     * When [openWhenSingle] and exactly one file was picked, an [ImportEvent.Open] follows its import.
     */
    fun importAll(uris: List<Uri>, openWhenSingle: Boolean) {
        if (uris.isEmpty()) return
        _importing.update { it + uris.size }
        scope.launch {
            for (u in uris) {
                try {
                    import(u)
                        .onSuccess { if (openWhenSingle && uris.size == 1) _events.trySend(ImportEvent.Open(it)) }
                        .onFailure { _events.trySend(ImportEvent.Failed(it.message ?: ctx.getString(R.string.import_failed))) }
                } finally {
                    _importing.update { it - 1 }
                }
            }
        }
    }

    suspend fun import(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "book"
            var size = -1L
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            if (size > MAX_IMPORT_BYTES) error(ctx.getString(R.string.import_too_large, name))
            val tmp = File(booksDir, "import-" + UUID.randomUUID() + ".tmp")
            val (dest, format) = try {
                (cr.openInputStream(uri) ?: error(ctx.getString(R.string.import_cannot_open))).use { input ->
                    tmp.outputStream().use { out -> copyCapped(input, out, name) }
                }
                if (size < 0) size = tmp.length()
                db.findBook(name, size)?.let { existing -> return@runCatching existing.id }
                val fmt = detectFormat(name, cr.getType(uri), tmp)
                    ?: error(ctx.getString(R.string.import_unsupported, name))
                val target = File(booksDir, UUID.randomUUID().toString() + "." + fmt)
                if (!tmp.renameTo(target)) {
                    try {
                        tmp.copyTo(target)
                    } catch (t: Throwable) {
                        target.delete()
                        throw t
                    }
                }
                target to fmt
            } finally {
                tmp.delete() // no-op after a successful rename
            }
            val baseName = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { ctx.getString(R.string.untitled) }
            var title = baseName
            var author = ""
            val coverFile = File(coversDir, dest.nameWithoutExtension + ".jpg")
            var cover: String? = null
            try {
                if (format == "pdf") {
                    if (renderPdfCover(dest, coverFile)) cover = coverFile.path
                } else {
                    val book = Book.open(dest, format)
                    try {
                        title = book.title.ifBlank { baseName }
                        author = book.author
                        book.cover()?.let { if (saveCover(it, coverFile)) cover = coverFile.path }
                    } finally {
                        book.close()
                    }
                }
            } catch (e: Throwable) {
                dest.delete()
                coverFile.delete()
                if (e is OutOfMemoryError) throw IllegalStateException(ctx.getString(R.string.import_too_large, name), e)
                if (e !is Exception) throw e
                throw IllegalStateException(ctx.getString(R.string.import_unreadable, name, e.message.orEmpty()), e)
            }
            val id = try {
                db.insertBook(title, author, format, dest.path, cover, name, size)
            } catch (e: Throwable) {
                dest.delete()
                coverFile.delete()
                throw e
            }
            _books.value = db.books()
            id
        }
    }

    suspend fun delete(book: BookEntity) = withContext(Dispatchers.IO) {
        db.deleteBook(book.id)
        File(book.path).delete()
        book.cover?.let { File(it).delete() }
        _books.value = db.books()
    }

    private fun copyCapped(input: java.io.InputStream, out: java.io.OutputStream, name: String) {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_IMPORT_BYTES) error(ctx.getString(R.string.import_too_large, name))
            out.write(buf, 0, n)
        }
    }

    private fun detectFormat(name: String, mime: String?, file: File): String? {
        Book.FORMATS[name.substringAfterLast('.', "").lowercase()]?.let { return it }
        when (mime) {
            "application/epub+zip" -> return "epub"
            "application/pdf" -> return "pdf"
            "text/plain" -> return "txt"
            "text/markdown" -> return "md"
            "text/html", "application/xhtml+xml" -> return "html"
            "application/x-fictionbook+xml" -> return "fb2"
        }
        val head = file.inputStream().use { s -> ByteArray(512).let { b -> val n = s.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } }
        val text = String(head, Charsets.ISO_8859_1)
        return when {
            text.startsWith("%PDF") -> "pdf"
            text.startsWith("PK") -> runCatching {
                ZipFile(file).use { z -> if (z.getEntry("META-INF/container.xml") != null) "epub" else null }
            }.getOrNull()
            text.contains("<FictionBook") -> "fb2"
            text.contains("<html", ignoreCase = true) -> "html"
            head.none { it == 0.toByte() } -> "txt"
            else -> null
        }
    }

    private fun saveCover(bytes: ByteArray, out: File): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0) return false
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 360) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return true
    }

    private fun renderPdfCover(file: File, out: File): Boolean = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return false
                r.openPage(0).use { page ->
                    val w = 360
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, 1200)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    bmp.recycle()
                }
            }
        }
        true
    }.getOrDefault(false)

    companion object {
        /** Larger files can't be opened reliably within an app's memory budget. */
        const val MAX_IMPORT_BYTES = 500L * 1024 * 1024
    }
}
