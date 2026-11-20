package tw.worldbrief.app

import android.content.Context
import androidx.core.text.HtmlCompat
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class LiveEdition(val news: List<News>, val updated: String, val failedSources: Int = 0)

object LiveNewsStore {
    private data class Feed(val source: String, val category: String, val address: String, val language: String = "zh")
    private val feeds = listOf(
        "政治" to "politics", "兩岸" to "mainland", "財經" to "finance",
        "科技" to "technology", "生活" to "lifehealth", "社會" to "social",
        "地方" to "local", "文化" to "culture", "體育" to "sport"
    ).map { Feed("中央通訊社", it.first, "https://feeds.feedburner.com/rsscna/" + it.second) } +
        listOf("政治" to "politics", "財經" to "business", "生活" to "life", "社會" to "society",
            "地方" to "local", "軍事" to "def", "文化" to "art", "體育" to "sports")
            .map { Feed("自由時報", it.first, "https://news.ltn.com.tw/rss/" + it.second + ".xml") } +
        listOf(Feed("民報", "台灣", "https://www.peoplemedia.tw/rss/%E5%8D%B3%E6%99%82"),
            Feed("公視新聞", "台灣", "https://news.pts.org.tw/xml/newsfeed.xml"),
            Feed("報導者", "台灣", "https://www.twreporter.org/a/rss2.xml"))
    private val worldFeeds = listOf(
        Feed("BBC", "國際", "https://feeds.bbci.co.uk/news/world/rss.xml", "en"),
        Feed("BBC", "財經", "https://feeds.bbci.co.uk/news/business/rss.xml", "en"),
        Feed("BBC", "科技", "https://feeds.bbci.co.uk/news/technology/rss.xml", "en"),
        Feed("The Guardian", "國際", "https://www.theguardian.com/world/rss", "en"),
        Feed("The Guardian", "環境", "https://www.theguardian.com/environment/rss", "en"),
        Feed("The Guardian", "科學", "https://www.theguardian.com/science/rss", "en"),
        Feed("中央通訊社", "國際", "https://feeds.feedburner.com/rsscna/intworld"),
        Feed("自由時報", "國際", "https://news.ltn.com.tw/rss/world.xml")
    )
    val sourceNames = (feeds + worldFeeds).map { it.source }.distinct()
    private val major = Regex("政府|總統|行政院|立法院|國會|法案|三讀|政策|選舉|國防|國軍|兩岸|台積電|臺積電|半導體|經濟|央行|股市|台股|臺股|物價|通膨|關稅|能源|電價|勞工|健保|醫療|疫情|疫苗|公共衛生|地震|颱風|豪雨|災害|停班|停課|停電|停水|大規模|交通|高鐵|台鐵|臺鐵|捷運|資安|詐騙集團|食安|教育|環境|氣候|研究|科學|產業|國際賽|世界盃|奧運|亞運|國家隊|文化資產")
    private val minor = Regex("走失|尋狗|尋貓|生肖運勢|星座|網紅|八卦|戀情|開箱|美食推薦|彩券頭獎|撿到錢|單一車禍|鄰居吵架")
    private val domestic = Regex("台灣|臺灣|台北|臺北|新北|桃園|新竹|苗栗|台中|臺中|彰化|南投|雲林|嘉義|台南|臺南|高雄|屏東|宜蘭|花蓮|台東|臺東|澎湖|金門|馬祖|基隆|全台|全臺|國內|行政院|立法院|國健署|衛福部|經濟部|教育部|交通部|台積電|臺積電|台股|臺股|國軍|我國|陸委會|總統府")
    private fun taipei(pattern: String) = SimpleDateFormat(pattern, Locale.TAIWAN).apply { timeZone = TimeZone.getTimeZone("Asia/Taipei") }
    private fun parseDate(value: String): Date? {
        for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm:ss")) {
            val parsed = runCatching { SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Taipei"); isLenient = false }.parse(value.trim()) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
    private fun plain(value: String) = HtmlCompat.fromHtml(value, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    private fun readFeed(feed: Feed, international: Boolean): List<News> {
        val connection = URL(feed.address).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000; connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "WorldBriefAndroid/1.0 (personal RSS reader)")
        connection.setRequestProperty("Accept", "application/rss+xml,application/xml,text/xml")
        try {
            return connection.inputStream.use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                parser.setInput(stream, null)
                val results = mutableListOf<News>()
                var inItem = false
                var title = ""; var link = ""; var desc = ""; var pub = ""; var image = ""
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        when {
                            parser.name == "item" -> { inItem = true; title = ""; link = ""; desc = ""; pub = ""; image = "" }
                            inItem -> when (parser.name) {
                                "title" -> title = plain(parser.nextText())
                                "link" -> link = parser.nextText().trim()
                                "description" -> desc = parser.nextText()
                                "pubDate" -> pub = parser.nextText()
                                "thumbnail", "content", "enclosure" -> {
                                    val type = parser.getAttributeValue(null, "type").orEmpty()
                                    val medium = parser.getAttributeValue(null, "medium").orEmpty()
                                    if (parser.name == "thumbnail" || type.startsWith("image/") || medium == "image") {
                                        val candidate = parser.getAttributeValue(null, "url").orEmpty()
                                        if (candidate.startsWith("https://")) image = candidate
                                    }
                                }
                            }
                        }
                    } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "item" && inItem) {
                        inItem = false
                        val summary = plain(desc).take(300)
                        val combined = title + " " + summary
                        val date = parseDate(pub)
                        val today = taipei("yyyy-MM-dd").format(Date())
                        if (title.isNotBlank() && link.startsWith("https://") && date != null &&
                            taipei("yyyy-MM-dd").format(date) == today && (international ||
                            (domestic.containsMatchIn(combined) && major.containsMatchIn(combined) && !minor.containsMatchIn(title)))) {
                            if (image.isBlank()) image = Regex("""<img[^>]+src\s*=\s*["'](https://[^"']+)["']""", RegexOption.IGNORE_CASE)
                                .find(desc)?.groupValues?.get(1).orEmpty()
                            results.add(News(link, today, taipei("HH:mm").format(date), feed.category,
                                title, summary, feed.source, link, image, if (image.isNotBlank()) "圖片來源：${feed.source} RSS" else "",
                                body = listOf(summary).filter { it.isNotBlank() }, language = feed.language, isLive = true))
                        }
                    }
                    parser.next()
                }
                results
            }
        } finally { connection.disconnect() }
    }
    suspend fun refresh(context: Context, international: Boolean = false): LiveEdition = coroutineScope {
        val prefs = context.getSharedPreferences("news", Context.MODE_PRIVATE)
        val disabled = prefs.getStringSet("disabledSources", emptySet()).orEmpty()
        val custom = prefs.getString("customFeeds", "").orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size == 3 && parts[2].startsWith("https://") && parts[0].isNotBlank() &&
                (parts[1] == if (international) "國際" else "台灣"))
                Feed(parts[0].take(50), parts[1], parts[2], if (international) "en" else "zh") else null
        }.take(12).toList()
        val enabled = ((if (international) worldFeeds else feeds) + custom).filter { it.source !in disabled }
        if (enabled.isEmpty()) return@coroutineScope LiveEdition(emptyList(), taipei("yyyy-MM-dd HH:mm").format(Date()))
        val batches = enabled.map { feed -> async(Dispatchers.IO) { runCatching { readFeed(feed, international) } } }.awaitAll()
        require(batches.any { it.isSuccess }) { "所有即時來源暫時無法連線" }
        val seen = mutableSetOf<String>()
        val news = batches.flatMap { it.getOrDefault(emptyList()) }.sortedByDescending { it.date + it.time }
            .distinctBy { it.url.substringBefore('?') }
            .filter { seen.add(it.title.replace(Regex("[\\p{P}\\s]"), "")) }.take(100)
        val result = LiveEdition(news, taipei("yyyy-MM-dd HH:mm").format(Date()), batches.count { it.isFailure })
        val array = JSONArray()
        news.forEach { n -> array.put(JSONObject().put("id", n.id).put("date", n.date).put("time", n.time)
            .put("category", n.category).put("title", n.title).put("summary", n.summary).put("source", n.source)
            .put("url", n.url).put("image", n.image).put("caption", n.caption).put("language", n.language)) }
        context.getSharedPreferences(if (international) "live-world" else "live-news", Context.MODE_PRIVATE).edit()
            .putString("items", array.toString()).putString("updated", result.updated).apply()
        result
    }
    fun local(context: Context, international: Boolean = false): LiveEdition {
        val prefs = context.getSharedPreferences(if (international) "live-world" else "live-news", Context.MODE_PRIVATE)
        return runCatching {
            val a = JSONArray(prefs.getString("items", "[]"))
            val news = (0 until a.length()).map { i ->
                val n = a.getJSONObject(i)
                News(n.getString("id"), n.getString("date"), n.getString("time"), n.getString("category"),
                    n.getString("title"), n.optString("summary"), n.getString("source"), n.getString("url"),
                    n.optString("image"), n.optString("caption"), body = listOf(n.optString("summary")), language = n.optString("language", "zh"), isLive = true)
            }
            LiveEdition(news, prefs.getString("updated", "").orEmpty())
        }.getOrDefault(LiveEdition(emptyList(), ""))
    }
}
