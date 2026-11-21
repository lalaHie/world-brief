package tw.worldbrief.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object NewsArchive {
    private class Helper(context: Context) : SQLiteOpenHelper(context, "worldbrief-archive.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE stories(id TEXT PRIMARY KEY, stamp TEXT NOT NULL, live INTEGER NOT NULL, data TEXT NOT NULL, read INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX story_stamp ON stories(stamp)")
            db.execSQL("CREATE TABLE ai_cache(id TEXT PRIMARY KEY, answer TEXT NOT NULL)")
            db.execSQL("CREATE TABLE revisions(id TEXT NOT NULL, stamp TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(id, stamp))")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    }
    @Volatile private var instance: Helper? = null
    private fun db(c: Context): SQLiteDatabase = (instance ?: synchronized(this) { instance ?: Helper(c.applicationContext).also { instance = it } }).writableDatabase
    fun encode(n: News): JSONObject = JSONObject().apply {
        put("id", n.id); put("date", n.date); put("time", n.time); put("category", n.category); put("title", n.title); put("summary", n.summary); put("source", n.source); put("url", n.url); put("image", n.image); put("caption", n.caption); put("original", n.original); put("body", JSONArray(n.body)); put("impact", n.impact); put("watch", n.watch); put("language", n.language); put("live", n.isLive); put("links", JSONArray(n.relatedSources.map { JSONObject().put("source", it.source).put("url", it.url) })); put("keyPoints", JSONArray(n.keyPoints)); put("background", n.background); put("verification", n.verification); put("verificationEvidence", n.verificationEvidence)
    }
    fun decode(o: JSONObject): News {
        fun s(k: String) = o.optString(k)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val links = o.optJSONArray("links")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { l -> SourceLink(l.optString("source"), l.optString("url")) } } }.orEmpty()
        return News(s("id"), s("date"), s("time"), s("category"), s("title"), s("summary"), s("source"), s("url"), s("image"), s("caption"), s("original"), list("body"), s("impact"), s("watch"), s("language").ifBlank { "zh" }, o.optBoolean("live"), links, list("keyPoints"), s("background"), s("verification"), s("verificationEvidence"))
    }
    private fun identity(n: News) = n.url.substringBefore('#').ifBlank { n.id }
    @Synchronized fun save(c: Context, news: List<News>) {
        val db = db(c); db.beginTransaction()
        try {
            news.forEach { n ->
                val id = identity(n); if (id.isBlank() || n.title.isBlank()) return@forEach
                val encoded = encode(n).toString()
                var old: String? = null; var read = 0; var previousLive = false
                db.rawQuery("SELECT data,read,live FROM stories WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) { old = it.getString(0); read = it.getInt(1); previousLive = it.getInt(2) == 1 } }
                if (old != null && !previousLive && n.isLive) return@forEach
                if (old != null && old != encoded) {
                    db.insertWithOnConflict("revisions", null, ContentValues().apply { put("id", id); put("stamp", System.currentTimeMillis().toString()); put("data", old) }, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.insertWithOnConflict("stories", null, ContentValues().apply { put("id", id); put("stamp", n.date + " " + n.time); put("live", if (n.isLive) 1 else 0); put("data", encoded); put("read", read) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Taipei") }.format(Date(System.currentTimeMillis() - 7L * 86400000))
            db.delete("revisions", "id IN (SELECT id FROM stories WHERE live=1 AND stamp<?)", arrayOf(date))
            db.delete("stories", "live=1 AND stamp<?", arrayOf(date))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun search(c: Context, query: String = "", limit: Int = 500, unread: Boolean = false): List<News> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(8)
        val clauses = terms.map { "data LIKE ? ESCAPE '\\'" } + if (unread) listOf("read=0") else emptyList()
        val args = terms.map { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }.toTypedArray()
        return db(c).query("stories", arrayOf("data"), clauses.joinToString(" AND ").ifBlank { null }, args, null, null, "stamp DESC", limit.coerceIn(1, 2000).toString()).use { cursor -> buildList { while (cursor.moveToNext()) runCatching { decode(JSONObject(cursor.getString(0))) }.getOrNull()?.let { add(it) } } }
    }
    fun markRead(c: Context, n: News) { db(c).update("stories", ContentValues().apply { put("read", 1) }, "id=?", arrayOf(identity(n))) }
    fun revisions(c: Context, n: News): Int = db(c).rawQuery("SELECT count(*) FROM revisions WHERE id=?", arrayOf(identity(n))).use { it.moveToFirst(); it.getInt(0) }
    fun cached(c: Context, id: String): String? = db(c).rawQuery("SELECT answer FROM ai_cache WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun cache(c: Context, id: String, answer: String) { db(c).insertWithOnConflict("ai_cache", null, ContentValues().apply { put("id", id); put("answer", answer) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun export(c: Context): JSONObject {
        val data = JSONArray(); val ai = JSONArray(); val revisions = JSONArray(); val translations = JSONObject()
        db(c).rawQuery("SELECT data,read FROM stories ORDER BY stamp DESC", null).use { while (it.moveToNext()) data.put(JSONObject(it.getString(0)).put("read", it.getInt(1))) }
        db(c).rawQuery("SELECT id,answer FROM ai_cache", null).use { while (it.moveToNext()) ai.put(JSONObject().put("id", it.getString(0)).put("answer", it.getString(1))) }
        db(c).rawQuery("SELECT id,stamp,data FROM revisions", null).use { while (it.moveToNext()) revisions.put(JSONObject().put("id", it.getString(0)).put("stamp", it.getString(1)).put("data", it.getString(2))) }
        c.getSharedPreferences("translations", Context.MODE_PRIVATE).all.forEach { (k,v) -> if (v is String) translations.put(k,v) }
        val prefs = c.getSharedPreferences("news", Context.MODE_PRIVATE)
        return JSONObject().put("format", "worldbrief-v2").put("stories", data).put("ai", ai).put("revisions", revisions).put("translations", translations).put("topics", JSONArray(prefs.getStringSet("topics", emptySet()).orEmpty().toList()))
    }
    fun restore(c: Context, root: JSONObject) {
        require(root.optString("format") == "worldbrief-v2") { "不是世界晨報備份檔" }
        val a = root.getJSONArray("stories"); require(a.length() <= 300000)
        val items = (0 until a.length()).map { decode(a.getJSONObject(it)) }
        save(c, items)
        for (i in items.indices) if (a.getJSONObject(i).optInt("read") == 1) markRead(c, items[i])
        root.optJSONArray("ai")?.let { for (i in 0 until it.length()) { val o = it.getJSONObject(i); cache(c,o.getString("id"),o.getString("answer")) } }
        root.optJSONArray("revisions")?.let { for (i in 0 until it.length()) { val o = it.getJSONObject(i); db(c).insertWithOnConflict("revisions",null,ContentValues().apply { put("id",o.getString("id"));put("stamp",o.getString("stamp"));put("data",o.getString("data")) }, SQLiteDatabase.CONFLICT_IGNORE) } }
        root.optJSONObject("translations")?.let { o -> val edit = c.getSharedPreferences("translations", Context.MODE_PRIVATE).edit(); o.keys().forEach { edit.putString(it,o.getString(it)) }; edit.apply() }
        root.optJSONArray("topics")?.let { a -> val prefs = c.getSharedPreferences("news", Context.MODE_PRIVATE); prefs.edit().putStringSet("topics", prefs.getStringSet("topics",emptySet()).orEmpty() + (0 until a.length()).map { a.getString(it) }).apply() }
    }
    fun size(c: Context): Long = c.getDatabasePath("worldbrief-archive.db").length()
}

object ArchiveBackup {
    fun backup(c: Context, uri: Uri) {
        val bytes = NewsArchive.export(c).toString().toByteArray(Charsets.UTF_8)
        c.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("無法寫入備份")
        c.getSharedPreferences("news",Context.MODE_PRIVATE).edit().putLong("lastBackup", System.currentTimeMillis()).apply()
    }
    fun restore(c: Context, uri: Uri) { val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("無法讀取備份"); require(bytes.size <= 150_000_000); NewsArchive.restore(c,JSONObject(bytes.toString(Charsets.UTF_8))) }
    fun schedule(c: Context) { WorkManager.getInstance(c).enqueueUniquePeriodicWork("archive-backup", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<ArchiveBackupWorker>(1, TimeUnit.DAYS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()) }
}
class ArchiveBackupWorker(c: Context, p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = applicationContext.getSharedPreferences("news",Context.MODE_PRIVATE).getString("backupUri",null) ?: return@withContext Result.success()
        try { ArchiveBackup.backup(applicationContext,Uri.parse(uri)); Result.success() } catch (_: Exception) { Result.retry() }
    }
}
