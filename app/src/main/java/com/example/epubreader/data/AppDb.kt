package com.example.epubreader.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Plain SQLite storage: books, annotations, tags and the online dictionary cache. */
class AppDb(ctx: Context) : SQLiteOpenHelper(ctx, "reader.db", null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
               author TEXT NOT NULL DEFAULT '', format TEXT NOT NULL, path TEXT NOT NULL, cover TEXT,
               added INTEGER NOT NULL, opened INTEGER NOT NULL DEFAULT 0, chapter INTEGER NOT NULL DEFAULT 0,
               progress REAL NOT NULL DEFAULT 0, percent REAL NOT NULL DEFAULT 0,
               source_name TEXT NOT NULL DEFAULT '', size INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE annotations(id INTEGER PRIMARY KEY AUTOINCREMENT,
               book INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE, chapter INTEGER NOT NULL,
               start_off INTEGER NOT NULL, end_off INTEGER NOT NULL, quote TEXT NOT NULL,
               note TEXT NOT NULL DEFAULT '', color TEXT, created INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX ann_book ON annotations(book, chapter)")
        db.execSQL(
            """CREATE TABLE tags(id INTEGER PRIMARY KEY AUTOINCREMENT,
               book INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE, name TEXT NOT NULL,
               color INTEGER NOT NULL, description TEXT NOT NULL DEFAULT '',
               UNIQUE(book, name COLLATE NOCASE))"""
        )
        db.execSQL(
            """CREATE TABLE ann_tags(ann INTEGER NOT NULL REFERENCES annotations(id) ON DELETE CASCADE,
               tag INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE, PRIMARY KEY(ann, tag))"""
        )
        db.execSQL("CREATE TABLE dict_cache(word TEXT PRIMARY KEY, json TEXT NOT NULL, ts INTEGER NOT NULL)")
    }

    /**
     * Step-by-step migrations: each `if (v < N)` block upgrades a version N-1 database to N, so a user
     * several versions behind runs every step in order. When changing the schema, bump [VERSION], add the
     * matching block here, and mirror the change in [onCreate] for fresh installs.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        @Suppress("CanBeVal") // becomes a real var once the first step exists
        var v = oldVersion
        // if (v < 2) { db.execSQL("ALTER TABLE ..."); v = 2 }
        check(v == newVersion) { "No migration from database version $v to $newVersion" }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never wipe the user's notes on a downgrade; newer columns are simply ignored by older code.
    }

    // ---------------------------------------------------------------- books

    private fun Cursor.toBook() = BookEntity(
        id = getLong(0), title = getString(1), author = getString(2), format = getString(3),
        path = getString(4), cover = getString(5), added = getLong(6), opened = getLong(7),
        chapter = getInt(8), progress = getFloat(9), percent = getFloat(10),
        sourceName = getString(11), size = getLong(12),
    )

    private val bookCols = "id,title,author,format,path,cover,added,opened,chapter,progress,percent,source_name,size"

    fun books(): List<BookEntity> = readableDatabase.rawQuery(
        "SELECT $bookCols FROM books ORDER BY MAX(opened, added) DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toBook()) } }

    fun book(id: Long): BookEntity? = readableDatabase.rawQuery(
        "SELECT $bookCols FROM books WHERE id=?", arrayOf(id.toString())
    ).use { if (it.moveToFirst()) it.toBook() else null }

    fun findBook(sourceName: String, size: Long): BookEntity? = readableDatabase.rawQuery(
        "SELECT $bookCols FROM books WHERE source_name=? AND size=?", arrayOf(sourceName, size.toString())
    ).use { if (it.moveToFirst()) it.toBook() else null }

    fun insertBook(
        title: String, author: String, format: String, path: String, cover: String?, sourceName: String, size: Long,
    ): Long = writableDatabase.insertOrThrow("books", null, ContentValues().apply {
        put("title", title); put("author", author); put("format", format); put("path", path)
        put("cover", cover); put("added", System.currentTimeMillis()); put("source_name", sourceName); put("size", size)
    })

    fun updatePosition(id: Long, chapter: Int, progress: Float, percent: Float) {
        writableDatabase.update("books", ContentValues().apply {
            put("chapter", chapter); put("progress", progress); put("percent", percent)
            put("opened", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun touchBook(id: Long) {
        writableDatabase.update(
            "books", ContentValues().apply { put("opened", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString())
        )
    }

    fun updateTitle(id: Long, title: String) {
        writableDatabase.update("books", ContentValues().apply { put("title", title) }, "id=?", arrayOf(id.toString()))
    }

    fun deleteBook(id: Long) {
        writableDatabase.delete("books", "id=?", arrayOf(id.toString()))
    }

    // ---------------------------------------------------------------- annotations & tags

    fun annotations(bookId: Long): List<Annotation> {
        val tagMap = HashMap<Long, MutableList<Long>>()
        readableDatabase.rawQuery(
            "SELECT at.ann, at.tag FROM ann_tags at JOIN annotations a ON a.id=at.ann WHERE a.book=?",
            arrayOf(bookId.toString())
        ).use { c -> while (c.moveToNext()) tagMap.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getLong(1)) }
        return readableDatabase.rawQuery(
            "SELECT id,book,chapter,start_off,end_off,quote,note,color,created FROM annotations " +
                "WHERE book=? ORDER BY chapter, start_off",
            arrayOf(bookId.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    add(
                        Annotation(
                            id, c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getString(5), c.getString(6),
                            if (c.isNull(7)) null else c.getString(7), c.getLong(8), tagMap[id] ?: emptyList()
                        )
                    )
                }
            }
        }
    }

    fun saveAnnotation(a: Annotation): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("book", a.bookId); put("chapter", a.chapter); put("start_off", a.start); put("end_off", a.end)
                put("quote", a.quote); put("note", a.note); put("color", a.color)
                put("created", if (a.created > 0) a.created else System.currentTimeMillis())
            }
            val id = if (a.id > 0) {
                db.update("annotations", cv, "id=?", arrayOf(a.id.toString())); a.id
            } else db.insertOrThrow("annotations", null, cv)
            db.delete("ann_tags", "ann=?", arrayOf(id.toString()))
            for (t in a.tagIds.distinct()) {
                db.insert("ann_tags", null, ContentValues().apply { put("ann", id); put("tag", t) })
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAnnotation(id: Long) {
        writableDatabase.delete("annotations", "id=?", arrayOf(id.toString()))
    }

    fun tags(bookId: Long): List<Tag> = readableDatabase.rawQuery(
        "SELECT id,book,name,color,description FROM tags WHERE book=? ORDER BY name COLLATE NOCASE",
        arrayOf(bookId.toString())
    ).use { c ->
        buildList { while (c.moveToNext()) add(Tag(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getString(4))) }
    }

    private fun tagId(bookId: Long, name: String): Long? = readableDatabase.rawQuery(
        "SELECT id FROM tags WHERE book=? AND name=? COLLATE NOCASE", arrayOf(bookId.toString(), name)
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    /** Returns the id of the tag with this name (case-insensitive), creating it if needed. Never throws on duplicates. */
    fun createTag(bookId: Long, name: String, color: Int): Long {
        tagId(bookId, name)?.let { return it }
        // IGNORE covers a concurrent insert of the same name between the lookup and here.
        val id = writableDatabase.insertWithOnConflict("tags", null, ContentValues().apply {
            put("book", bookId); put("name", name); put("color", color)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return if (id != -1L) id else tagId(bookId, name) ?: error("Could not create tag $name")
    }

    /** Returns false (and changes nothing) when another tag of this book already has the new name. */
    fun updateTag(t: Tag): Boolean = try {
        writableDatabase.update("tags", ContentValues().apply {
            put("name", t.name); put("color", t.color); put("description", t.description)
        }, "id=?", arrayOf(t.id.toString()))
        true
    } catch (_: SQLiteConstraintException) {
        false
    }

    fun deleteTag(id: Long) {
        writableDatabase.delete("tags", "id=?", arrayOf(id.toString()))
    }

    // ---------------------------------------------------------------- dictionary cache

    fun dictCacheGet(word: String): String? = readableDatabase.rawQuery(
        "SELECT json FROM dict_cache WHERE word=?", arrayOf(word)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun dictCachePut(word: String, json: String) {
        writableDatabase.insertWithOnConflict("dict_cache", null, ContentValues().apply {
            put("word", word); put("json", json); put("ts", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        const val VERSION = 1
    }
}
