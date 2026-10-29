package com.example.epubreader.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.example.epubreader.data.AppDb
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Sense(val pos: String, val definition: String, val examples: List<String>, val synonyms: List<String>)

data class DictEntry(val headword: String, val phonetic: String?, val senses: List<Sense>, val source: String)

/**
 * Offline lookups use a bundled WordNet 3.1 database (with WordNet's "morphy" lemmatiser so
 * "running", "geese" or "better" resolve). Online lookups try Free Dictionary API, Wiktionary
 * and finally Wikipedia (useful for names and places), and are cached so they work offline later.
 */
class Dictionary(private val ctx: Context, private val appDb: AppDb) {

    @Volatile private var wn: SQLiteDatabase? = null

    @Synchronized
    private fun db(): SQLiteDatabase {
        wn?.let { return it }
        val f = File(ctx.noBackupFilesDir, "wordnet-v1.db")
        if (!f.exists()) {
            val tmp = File(f.path + ".tmp")
            ctx.assets.open("dict/wordnet.db").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            tmp.renameTo(f)
        }
        return SQLiteDatabase.openDatabase(
            f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).also { wn = it }
    }

    fun warmUp() {
        runCatching { db().rawQuery("SELECT entries FROM words WHERE lemma='a'", null).use { it.moveToFirst() } }
    }

    fun normalize(raw: String): String {
        var w = raw.trim().replace('’', '\'').replace('‘', '\'').replace(Regex("\\s+"), " ")
        w = w.trim('\'', '"', '“', '”', '-', '—', '–', '.', ',', ';', ':', '!', '?', '(', ')', '[', ']')
        if (w.endsWith("'s", true)) w = w.dropLast(2)
        if (w.endsWith("s'")) w = w.dropLast(1)
        return w.lowercase()
    }

    // ------------------------------------------------------------------ offline

    private val rules = listOf(
        "s" to "", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh", "men" to "man", "ies" to "y",
        "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to "",
        "er" to "", "est" to "", "er" to "e", "est" to "e", "ier" to "y", "iest" to "y", "ly" to "", "ily" to "y",
    )

    fun candidates(w: String): List<String> {
        val out = LinkedHashSet<String>()
        out += w
        db().rawQuery("SELECT bases FROM exc WHERE form=?", arrayOf(w)).use { c ->
            if (c.moveToFirst()) out += c.getString(0).split(',')
        }
        for ((suf, rep) in rules) {
            if (w.length > suf.length + 1 && w.endsWith(suf)) out += w.dropLast(suf.length) + rep
        }
        for (suf in listOf("ing", "ed", "er", "est")) {
            if (w.endsWith(suf)) {
                val st = w.dropLast(suf.length)
                if (st.length >= 3 && st[st.length - 1] == st[st.length - 2]) out += st.dropLast(1)
            }
        }
        if ('-' in w) out += w.replace("-", "")
        return out.toList()
    }

    private fun query(lemma: String): DictEntry? =
        db().rawQuery("SELECT entries FROM words WHERE lemma=?", arrayOf(lemma)).use { c ->
            if (!c.moveToFirst()) return null
            val senses = c.getString(0).split('\n').mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 2) return@mapNotNull null
                Sense(
                    pos = p[0], definition = p[1],
                    examples = p.getOrNull(2)?.split(" | ")?.filter { it.isNotBlank() } ?: emptyList(),
                    synonyms = p.getOrNull(3)?.split(", ")?.filter { it.isNotBlank() } ?: emptyList(),
                )
            }
            DictEntry(lemma, null, senses, "WordNet (offline)")
        }

    fun existsOffline(lemma: String): Boolean =
        db().rawQuery("SELECT 1 FROM words WHERE lemma=?", arrayOf(lemma)).use { it.moveToFirst() }

    fun offline(raw: String): List<DictEntry> {
        val w = normalize(raw)
        if (w.isEmpty()) return emptyList()
        val out = mutableListOf<DictEntry>()
        for (c in candidates(w)) {
            query(c)?.let { out += it }
            if (out.size >= 3) break
        }
        return out
    }

    // ------------------------------------------------------------------ online

    fun isOnline(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun cached(raw: String): List<DictEntry>? =
        appDb.dictCacheGet(normalize(raw))?.let { runCatching { fromJson(it) }.getOrNull() }
            // older caches may hold only "plural of X" — refetch those so the base word is included
            ?.takeUnless { list -> list.flatMap { it.senses }.all { formOf.matches(it.definition) } }

    /** @throws IOException when the network itself fails (as opposed to "no definition"). */
    fun online(raw: String): List<DictEntry> {
        val w = normalize(raw)
        if (w.isEmpty()) return emptyList()
        val lemma = candidates(w).drop(1).firstOrNull { existsOffline(it) }
        // Query the dictionaries in parallel and take the first useful answer, so one slow or
        // dead service never holds up the popup.
        val sources = listOf(
            Callable { freeDictionary(w).ifEmpty { lemma?.let { freeDictionary(it) } ?: emptyList() } },
            Callable { wiktionaryFollowingForms(w) },
        )
        val done = ExecutorCompletionService<List<DictEntry>>(pool)
        val futures = sources.map { done.submit(it) }
        var res: List<DictEntry> = emptyList()
        var networkErrors = 0
        try {
            repeat(futures.size) {
                if (res.isNotEmpty()) return@repeat
                val f = done.poll(15, TimeUnit.SECONDS)
                if (f == null) { networkErrors++; return@repeat }
                try {
                    res = f.get()
                } catch (e: ExecutionException) {
                    if (e.cause is IOException) networkErrors++
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        if (res.isEmpty()) {
            res = try {
                wikipedia(raw.trim().trim('\'', '’', '"', '.', ','))
            } catch (e: IOException) {
                networkErrors++
                emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (res.isEmpty() && networkErrors >= sources.size + 1) throw IOException("All dictionary services unreachable")
        if (res.isNotEmpty()) appDb.dictCachePut(w, toJson(res))
        return res
    }

    private val pool = Executors.newCachedThreadPool()

    private fun http(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "LexiReader/1.0 (Android e-book reader)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code != 200) return null
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun freeDictionary(w: String): List<DictEntry> {
        val body = http("https://api.dictionaryapi.dev/api/v2/entries/en/" + Uri.encode(w)) ?: return emptyList()
        val arr = JSONArray(body)
        val byWord = LinkedHashMap<String, Pair<String?, MutableList<Sense>>>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val word = e.optString("word", w)
            var phon = e.optString("phonetic").takeIf { it.isNotBlank() }
            if (phon == null) {
                val ps = e.optJSONArray("phonetics")
                if (ps != null) for (k in 0 until ps.length()) {
                    val t = ps.getJSONObject(k).optString("text")
                    if (t.isNotBlank()) { phon = t; break }
                }
            }
            val slot = byWord.getOrPut(word) { phon to mutableListOf() }
            val meanings = e.optJSONArray("meanings") ?: continue
            for (m in 0 until meanings.length()) {
                val mo = meanings.getJSONObject(m)
                val pos = mo.optString("partOfSpeech")
                val msyn = strings(mo.optJSONArray("synonyms"))
                val defs = mo.optJSONArray("definitions") ?: continue
                for (d in 0 until defs.length()) {
                    val dobj = defs.getJSONObject(d)
                    val def = dobj.optString("definition").trim()
                    if (def.isEmpty()) continue
                    slot.second += Sense(
                        pos, def,
                        listOfNotNull(dobj.optString("example").takeIf { it.isNotBlank() }),
                        (strings(dobj.optJSONArray("synonyms")) + msyn).distinct().take(6),
                    )
                }
            }
        }
        return byWord.filter { it.value.second.isNotEmpty() }
            .map { (word, v) -> DictEntry(word, v.first, v.second, "Free Dictionary") }
    }

    private fun wiktionary(w: String): List<DictEntry> {
        val body = http("https://en.wiktionary.org/api/rest_v1/page/definition/" + Uri.encode(w)) ?: return emptyList()
        val en = JSONObject(body).optJSONArray("en") ?: return emptyList()
        val senses = mutableListOf<Sense>()
        for (i in 0 until en.length()) {
            val o = en.getJSONObject(i)
            val pos = o.optString("partOfSpeech").lowercase()
            if (pos == "symbol" || pos == "letter") continue // ISO codes etc. — noise for a reader
            val defs = o.optJSONArray("definitions") ?: continue
            for (d in 0 until defs.length()) {
                val dobj = defs.getJSONObject(d)
                val def = Jsoup.parse(dobj.optString("definition")).text().trim()
                if (def.isEmpty()) continue
                val ex = strings(dobj.optJSONArray("examples")).map { Jsoup.parse(it).text() }.filter { it.isNotBlank() }.take(2)
                senses += Sense(pos, def, ex, emptyList())
            }
        }
        return if (senses.isEmpty()) emptyList() else listOf(DictEntry(w, null, senses, "Wiktionary"))
    }

    private val formOf = Regex(
        "^(?:.*\\b)?(?:plural|singular|past|present|participle|tense|third-person|comparative|superlative|" +
            "alternative|obsolete|archaic|misspelling|inflection|form|spelling)\\b.*\\bof ([\\p{L}' -]{1,40}?)\\.?$",
        RegexOption.IGNORE_CASE,
    )

    /** Wiktionary often answers inflections with just "plural of X"; fetch X too so there is a real meaning. */
    private fun wiktionaryFollowingForms(w: String): List<DictEntry> {
        val res = wiktionary(w)
        val senses = res.flatMap { it.senses }
        val target = senses.firstNotNullOfOrNull { formOf.find(it.definition)?.groupValues?.get(1)?.trim() }
        if (senses.isEmpty() || target == null || !senses.all { formOf.matches(it.definition) }) return res
        if (target.equals(w, true)) return res
        val base = runCatching { wiktionary(target.lowercase()) }.getOrDefault(emptyList())
        return res + base
    }

    private fun wikipedia(term: String): List<DictEntry> {
        if (term.isBlank()) return emptyList()
        val title = term.split(' ').joinToString("_") { it.replaceFirstChar { c -> c.uppercase() } }
        val body = http("https://en.wikipedia.org/api/rest_v1/page/summary/" + Uri.encode(title)) ?: return emptyList()
        val o = JSONObject(body)
        if (o.optString("type") == "disambiguation") return emptyList()
        val extract = o.optString("extract").trim()
        if (extract.isEmpty()) return emptyList()
        return listOf(
            DictEntry(
                o.optString("title", term), null,
                listOf(Sense(o.optString("description").ifBlank { "encyclopedia" }, extract, emptyList(), emptyList())),
                "Wikipedia",
            )
        )
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    // ------------------------------------------------------------------ cache serialisation

    private fun toJson(list: List<DictEntry>): String = JSONArray().apply {
        for (e in list) put(JSONObject().apply {
            put("w", e.headword); put("p", e.phonetic ?: ""); put("src", e.source)
            put("s", JSONArray().apply {
                for (s in e.senses) put(JSONObject().apply {
                    put("pos", s.pos); put("d", s.definition)
                    put("ex", JSONArray(s.examples)); put("syn", JSONArray(s.synonyms))
                })
            })
        })
    }.toString()

    private fun fromJson(json: String): List<DictEntry> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val s = o.getJSONArray("s")
            DictEntry(
                o.getString("w"), o.optString("p").takeIf { it.isNotBlank() },
                (0 until s.length()).map { k ->
                    val so = s.getJSONObject(k)
                    Sense(so.getString("pos"), so.getString("d"), strings(so.optJSONArray("ex")), strings(so.optJSONArray("syn")))
                },
                o.optString("src", "Online") + " · saved",
            )
        }
    }
}
