package tw.worldbrief.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

internal fun taipeiTimestamp(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN).apply {
        timeZone = TimeZone.getTimeZone("Asia/Taipei")
    }.format(Date(time))

object NewsArchive {
    private val changeVersion = MutableStateFlow(0L)
    val changes = changeVersion.asStateFlow()

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
    internal fun identity(n: News) = n.url.substringBefore('#').ifBlank { n.id }
    @Synchronized fun save(c: Context, news: List<News>, keepExisting: Boolean = false) {
        val db = db(c); db.beginTransaction()
        try {
            news.forEach { n ->
                val id = identity(n); if (id.isBlank() || n.title.isBlank()) return@forEach
                val encoded = encode(n).toString()
                var old: String? = null; var read = 0; var previousLive = false
                db.rawQuery("SELECT data,read,live FROM stories WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) { old = it.getString(0); read = it.getInt(1); previousLive = it.getInt(2) == 1 } }
                if (keepExisting && old != null) return@forEach
                if (old != null && !previousLive && n.isLive) return@forEach
                if (old == encoded) return@forEach
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
        if (!db.inTransaction()) changeVersion.update { it + 1 }
    }
    fun search(c: Context, query: String = "", limit: Int = 500, unread: Boolean = false): List<News> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(8)
        val clauses = terms.map { "data LIKE ? ESCAPE '\\'" } + if (unread) listOf("read=0") else emptyList()
        val args = terms.map { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }.toTypedArray()
        return db(c).query("stories", arrayOf("data"), clauses.joinToString(" AND ").ifBlank { null }, args, null, null, "stamp DESC", limit.coerceIn(1, 2000).toString()).use { cursor -> buildList { while (cursor.moveToNext()) runCatching { decode(JSONObject(cursor.getString(0))) }.getOrNull()?.let { add(it) } } }
    }
    fun markRead(c: Context, n: News) { db(c).update("stories", ContentValues().apply { put("read", 1) }, "id=?", arrayOf(identity(n))) }
    fun revisions(c: Context, n: News): Int = db(c).rawQuery("SELECT count(*) FROM revisions WHERE id=?", arrayOf(identity(n))).use { it.moveToFirst(); it.getInt(0) }
    fun revisionHistory(c: Context, n: News): List<Pair<String,News>> = db(c).query("revisions",arrayOf("stamp","data"),"id=?",arrayOf(identity(n)),null,null,"stamp DESC","30").use { cursor -> buildList { while(cursor.moveToNext()) runCatching { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.TAIWAN).apply{timeZone=TimeZone.getTimeZone("Asia/Taipei")}.format(Date(cursor.getString(0).toLong())) to decode(JSONObject(cursor.getString(1))) }.getOrNull()?.let{add(it)} } }
    fun cached(c: Context, id: String): String? = db(c).rawQuery("SELECT answer FROM ai_cache WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun cache(c: Context, id: String, answer: String) { db(c).insertWithOnConflict("ai_cache", null, ContentValues().apply { put("id", id); put("answer", answer) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun export(c: Context): JSONObject {
        val stories = JSONArray()
        val answers = JSONArray()
        val revisions = JSONArray()
        val database = db(c)
        database.beginTransactionNonExclusive()
        try {
            database.rawQuery("SELECT data,read FROM stories ORDER BY stamp DESC", null).use { rows ->
                while (rows.moveToNext()) stories.put(JSONObject(rows.getString(0)).put("read", rows.getInt(1)))
            }
            database.rawQuery("SELECT id,answer FROM ai_cache", null).use { rows ->
                while (rows.moveToNext()) answers.put(JSONObject().put("id", rows.getString(0)).put("answer", rows.getString(1)))
            }
            database.rawQuery("SELECT id,stamp,data FROM revisions", null).use { rows ->
                while (rows.moveToNext()) {
                    revisions.put(JSONObject().put("id", rows.getString(0)).put("stamp", rows.getString(1)).put("data", rows.getString(2)))
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        val translations = JSONObject()
        c.getSharedPreferences("translations", Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (value is String) translations.put(key, value)
        }
        val prefs = c.getSharedPreferences("news", Context.MODE_PRIVATE)
        return JSONObject()
            .put("format", "worldbrief-v2").put("stories", stories).put("ai", answers)
            .put("revisions", revisions).put("translations", translations)
            .put("topics", JSONArray(prefs.getStringSet("topics", emptySet()).orEmpty().toList()))
    }
    @Synchronized fun restore(c: Context, root: JSONObject) {
        require(root.optString("format") == "worldbrief-v2") { "不是世界晨報備份檔" }
        val stories = root.getJSONArray("stories")
        require(stories.length() <= 300000)
        val items = (0 until stories.length()).map { decode(stories.getJSONObject(it)) }

        val translationPrefs = c.getSharedPreferences("translations", Context.MODE_PRIVATE)
        val translationEdits = translationPrefs.edit()
        root.optJSONObject("translations")?.let { translations ->
            translations.keys().forEach { key ->
                val value = translations.getString(key)
                if (!translationPrefs.contains(key)) translationEdits.putString(key, value)
            }
        }
        val topics = root.optJSONArray("topics")?.let { list ->
            (0 until list.length()).map { list.getString(it).trim() }
                .filter { it.isNotBlank() && it.length <= 60 }
        }.orEmpty()

        val database = db(c)
        database.beginTransaction()
        try {
            save(c, items, keepExisting = true)
            for (i in items.indices) {
                if (stories.getJSONObject(i).optInt("read") == 1) markRead(c, items[i])
            }
            root.optJSONArray("ai")?.let { answers ->
                for (i in 0 until answers.length()) {
                    val answer = answers.getJSONObject(i)
                    database.insertWithOnConflict("ai_cache", null, ContentValues().apply {
                        put("id", answer.getString("id"))
                        put("answer", answer.getString("answer"))
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            root.optJSONArray("revisions")?.let { revisions ->
                for (i in 0 until revisions.length()) {
                    val revision = revisions.getJSONObject(i)
                    val id = revision.getString("id")
                    val stamp = revision.getString("stamp")
                    require(stamp.toLongOrNull() != null)
                    val data = revision.getString("data")
                    JSONObject(data)
                    database.execSQL(
                        "INSERT OR IGNORE INTO revisions(id,stamp,data) SELECT ?,?,? WHERE EXISTS (SELECT 1 FROM stories WHERE id=?)",
                        arrayOf(id, stamp, data, id)
                    )
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        translationEdits.apply()
        val prefs = c.getSharedPreferences("news", Context.MODE_PRIVATE)
        val merged = prefs.getStringSet("topics", emptySet()).orEmpty() + topics
        prefs.edit().putStringSet("topics", merged).apply()
        changeVersion.update { it + 1 }
    }
    fun size(c: Context): Long = c.getDatabasePath("worldbrief-archive.db").length()
}

internal class ArchiveLimitException : IllegalStateException()

object ArchiveBackup {
    private const val MAX_BACKUP_BYTES = 50_000_000
    const val LAST_BACKUP = "lastBackup"
    const val STATUS = "archiveBackupStatus"

    private fun prefs(c: Context) = c.getSharedPreferences("news", Context.MODE_PRIVATE)

    fun canWriteAutomatically(c: Context, uri: Uri): Boolean =
        c.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    @Synchronized fun backup(c: Context, uri: Uri) {
        val bytes = NewsArchive.export(c).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BACKUP_BYTES) throw ArchiveLimitException()
        c.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("無法寫入備份")
        prefs(c).edit().putLong(LAST_BACKUP, System.currentTimeMillis()).remove(STATUS).apply()
    }

    @Synchronized fun restore(c: Context, uri: Uri) {
        val bytes = c.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                require(out.size() + length <= MAX_BACKUP_BYTES) { "備份超過 50 MB 還原限制" }
                out.write(buffer, 0, length)
            }
            out.toByteArray()
        } ?: error("無法讀取備份")
        NewsArchive.restore(c, JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    fun schedule(c: Context) {
        val request = PeriodicWorkRequestBuilder<ArchiveBackupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("archive-backup", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class ArchiveBackupWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val prefs = c.getSharedPreferences("news", Context.MODE_PRIVATE)
        val uri = prefs.getString("backupUri", null)?.let(Uri::parse) ?: return@withContext Result.success()
        if (!ArchiveBackup.canWriteAutomatically(c, uri)) {
            prefs.edit().putString(ArchiveBackup.STATUS, "備份檔授權已失效，請重新選擇備份位置").apply()
            return@withContext Result.failure()
        }
        try {
            ArchiveBackup.backup(c, uri)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ArchiveLimitException) {
            prefs.edit().putString(ArchiveBackup.STATUS, "資料超過 50 MB，自動備份未寫入").apply()
            Result.failure()
        } catch (_: Exception) {
            if (runAttemptCount < 2) {
                prefs.edit().putString(ArchiveBackup.STATUS, "背景備份失敗，將重試").apply()
                Result.retry()
            } else {
                prefs.edit().putString(ArchiveBackup.STATUS, "背景備份失敗，請檢查儲存位置或手動備份").apply()
                Result.failure()
            }
        }
    }
}
