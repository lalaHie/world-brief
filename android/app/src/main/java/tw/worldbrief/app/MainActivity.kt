package tw.worldbrief.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.Locale

private val teal = Color(0xFF176B68)
private val paper = Color(0xFFF5F0E7)
private val ink = Color(0xFF172B35)
private val card = Color(0xFFFFFDF8)

data class News(val id: String, val date: String, val time: String, val category: String,
    val title: String, val summary: String, val source: String, val url: String,
    val image: String = "", val caption: String = "", val original: String = "",
    val body: List<String> = emptyList(), val impact: String = "", val watch: String = "",
    val language: String = "zh", val isLive: Boolean = false, val relatedSources: List<SourceLink> = emptyList(),
    val keyPoints: List<String> = emptyList(), val background: String = "",
    val verification: String = "", val verificationEvidence: String = "")
data class SourceLink(val source: String, val url: String)
data class Edition(val news: List<News>, val updated: String)

object NewsStore {
    private const val FEED = "https://lalahie.github.io/world-brief/news.json"
    private const val FILE = "latest-news.json"
    fun parse(text: String): Edition {
        val root = JSONObject(text).getJSONObject("editions")
        val dates = root.keys().asSequence().sortedDescending().toList()
        val news = dates.flatMap { date ->
            val a = root.getJSONObject(date).optJSONArray("stories")
            (0 until (a?.length() ?: 0)).mapNotNull { i ->
                val s = a?.optJSONObject(i) ?: return@mapNotNull null
                fun field(k: String) = s.optString(k).takeUnless { it == "null" }.orEmpty()
                fun paragraphs(k: String): List<String> {
                    val arr = s.optJSONArray(k)
                    return if (arr != null) (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                    else listOf(field(k)).filter { it.isNotBlank() }
                }
                val img = s.optJSONObject("image")
                val url = field("url")
                val links = s.optJSONArray("relatedSources")
                val related = (0 until (links?.length() ?: 0)).mapNotNull { index ->
                    val link = links?.optJSONObject(index) ?: return@mapNotNull null
                    val address = link.optString("url")
                    if (address.startsWith("https://")) SourceLink(link.optString("source", "相關來源"), address) else null
                }.distinctBy { it.url }
                News(url.ifBlank { date + ":" + field("title") }, date, field("published").drop(11).take(5),
                    field("category").ifBlank { "其他" }, field("title"), field("summary"),
                    field("source"), url, img?.optString("url").orEmpty(),
                    listOf(img?.optString("caption"), img?.optString("credit"), img?.optString("license")).filter { !it.isNullOrBlank() }.joinToString(" · "),
                    field("original"), paragraphs("article").ifEmpty { listOf(field("summary")) + paragraphs("detail") + paragraphs("background") },
                    field("impact"), field("watch"), relatedSources = related,
                    keyPoints = paragraphs("keyPoints"), background = field("background"),
                    verification = field("verification"), verificationEvidence = field("verificationEvidence"))
            }
        }.sortedByDescending { it.date + it.time }
        return Edition(news, dates.firstOrNull()?.let { root.getJSONObject(it).optString("updated") }.orEmpty())
    }
    fun local(context: Context): Edition = runCatching {
        parse(context.openFileInput(FILE).bufferedReader().use { it.readText() })
    }.getOrElse { parse(context.assets.open("app/news.json").bufferedReader().use { it.readText() }) }
    @Synchronized fun refresh(context: Context): Edition {
        val connection = (URL(FEED + "?ts=" + System.currentTimeMillis()).openConnection() as HttpURLConnection)
        connection.connectTimeout = 10000
        connection.readTimeout = 12000
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache")
        try {
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val edition = parse(raw)
            require(edition.news.isNotEmpty())
            val file = android.util.AtomicFile(java.io.File(context.filesDir, FILE))
            val out = file.startWrite()
            try { out.write(raw.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
            catch (e: Exception) { file.failWrite(out); throw e }
            return edition
        } finally { connection.disconnect() }
    }
}

class HourlyNewsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val edition = NewsStore.refresh(applicationContext)
            NewsNotifications.notifyNew(applicationContext, edition)
            Result.success()
        }
        catch (_: Exception) { Result.retry() }
    }
}

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_STORY_URL = "tw.worldbrief.app.STORY_URL" }
    private var requested by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requested = intent.getStringExtra(EXTRA_STORY_URL)
        NewsNotifications.subscribe(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("hourly-news", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HourlyNewsWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        setContent { WorldBriefTheme { NewsApp(requested = requested, consumeRequest = { requested = null }) } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requested = intent.getStringExtra(EXTRA_STORY_URL)
    }
}

@Composable
private fun WorldBriefTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(primary = Color(0xFF91C9B9), secondary = Color(0xFFE9B65D), background = Color(0xFF151E20), surface = Color(0xFF202B2C), onSurface = Color(0xFFF2EEE3), onBackground = Color(0xFFF2EEE3))
    else lightColorScheme(primary = teal, secondary = Color(0xFF9B6E24), background = paper, surface = card, onSurface = ink, onBackground = ink, secondaryContainer = Color(0xFFDDECE5), onSecondaryContainer = teal)
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsApp(requested: String? = null, consumeRequest: () -> Unit = {}, preview: Edition? = null) {
    val context = LocalContext.current
    val prefs = remember { if (preview == null) context.getSharedPreferences("news", Context.MODE_PRIVATE) else null }
    var dark by remember { mutableStateOf(prefs?.getBoolean("darkMode", false) ?: false) }
    var fontSize by remember { mutableIntStateOf(prefs?.getInt("fontSize", 1) ?: 1) }
    val density = LocalDensity.current
    WorldBriefTheme(dark) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * listOf(0.9f, 1f, 1.18f).getOrElse(fontSize) { 1f })) {
            NewsAppContent(requested, consumeRequest, preview, dark, { dark = it; prefs?.edit()?.putBoolean("darkMode", it)?.apply() },
                fontSize, { fontSize = it; prefs?.edit()?.putInt("fontSize", it)?.apply() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewsAppContent(requested: String?, consumeRequest: () -> Unit, preview: Edition?, dark: Boolean, onDark: (Boolean) -> Unit, fontSize: Int, onFont: (Int) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { if (preview == null) context.getSharedPreferences("news", Context.MODE_PRIVATE) else null }
    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    var edition by remember { mutableStateOf(preview ?: Edition(emptyList(), "")) }
    var live by remember { mutableStateOf(LiveEdition(emptyList(), "")) }
    var liveRefreshing by remember { mutableStateOf(false) }
    var liveStatus by remember { mutableStateOf("") }
    var liveToken by remember { mutableIntStateOf(0) }
    var international by remember { mutableStateOf(false) }
    var compact by remember { mutableStateOf(prefs?.getBoolean("compact", false) ?: false) }
    var disabledSources by remember { mutableStateOf(prefs?.getStringSet("disabledSources", emptySet())?.toSet().orEmpty()) }
    var customFeeds by remember { mutableStateOf(prefs?.getString("customFeeds", "").orEmpty()) }
    var quiet by remember { mutableStateOf(prefs?.getBoolean("quiet", false) ?: false) }
    var quietStart by remember { mutableStateOf(prefs?.getInt("quietStart", 22 * 60) ?: 22 * 60) }
    var quietEnd by remember { mutableStateOf(prefs?.getInt("quietEnd", 7 * 60) ?: 7 * 60) }
    var dailyDigest by remember { mutableStateOf(false) }
    var notifyCategories by remember { mutableStateOf(prefs?.getStringSet("notifyCategories", emptySet())?.toSet().orEmpty()) }
    var editSources by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("今日") }
    var category by remember { mutableStateOf("全部") }
    var query by remember { mutableStateOf("") }
    var search by remember { mutableStateOf(false) }
    var verifiedOnly by remember { mutableStateOf(false) }
    var ascending by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf(prefs?.getBoolean("images", true) ?: true) }
    var selected by remember { mutableStateOf<News?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var refreshToken by remember { mutableIntStateOf(0) }
    var push by remember { mutableStateOf(prefs?.getBoolean("push", false) ?: false) }
    var urgent by remember { mutableStateOf(prefs?.getBoolean("urgent", true) ?: true) }
    var hourly by remember { mutableStateOf(prefs?.getBoolean("hourly", true) ?: true) }
    var notificationImages by remember { mutableStateOf(prefs?.getBoolean("notificationImages", true) ?: true) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        push = granted
        prefs?.edit()?.putBoolean("push", granted)?.apply()
        NewsNotifications.subscribe(context)
    }
    LaunchedEffect(tab, international) {
        if (preview == null && tab == "即時") {
            live = withContext(Dispatchers.IO) { LiveNewsStore.local(context, international) }
            liveToken++
            while (true) {
                delay(5 * 60 * 1000L)
                if (!liveRefreshing) liveToken++
            }
        }
    }
    LaunchedEffect(liveToken, international, tab) {
        if (preview != null || liveToken == 0 || tab != "即時") return@LaunchedEffect
        liveRefreshing = true
        liveStatus = "正在讀取各媒體最新新聞…"
        try {
            live = LiveNewsStore.refresh(context, international)
            liveStatus = if (live.failedSources > 0) "部分來源暫不可用 · ${live.failedSources} 個分類" else "同步完成"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { liveStatus = "更新失敗 · 保留本機即時新聞" }
        finally { liveRefreshing = false }
    }
    LaunchedEffect(Unit) {
        if (preview == null) {
            runCatching { withContext(Dispatchers.IO) { NewsStore.local(context) } }.onSuccess { edition = it }
            refreshToken++
            while (true) {
                delay(60 * 60 * 1000L)
                if (!refreshing) refreshToken++
            }
        }
    }
    LaunchedEffect(refreshToken) {
        if (preview != null || refreshToken == 0) return@LaunchedEffect
        refreshing = true
        status = "正在同步最新新聞…"
        try {
            edition = withContext(Dispatchers.IO) { NewsStore.refresh(context).also { NewsNotifications.notifyNew(context, it) } }
            status = "同步完成"
        }
        catch (_: Exception) { status = "更新失敗 · 保留本機新聞" }
        finally { refreshing = false }
    }
    LaunchedEffect(requested, edition, live, refreshing, status) {
        if (requested != null) {
            (edition.news + live.news).find { it.url == requested }?.let { selected = it; consumeRequest(); return@LaunchedEffect }
            if (!refreshing && (status == "同步完成" || status.startsWith("更新失敗"))) {
                if (requested.startsWith("https://")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(requested))) }
                consumeRequest()
            }
        }
    }
    BackHandler(selected != null) { selected = null }
    val current = selected
    if (current != null) {
        ArticlePage(current, edition.news + live.news, images, onBack = { selected = null }, onOpen = { selected = it })
        return
    }
    val displayed = (if (tab == "即時") live.news else edition.news).distinctBy { it.id }
    val categories = listOf("全部") + displayed.map { it.category }.distinct()
    val filtered = displayed.filter {
        (category == "全部" || it.category == category) &&
        (!verifiedOnly || (it.verification == "已核對" && it.verificationEvidence.isNotBlank())) &&
        (it.title + it.summary + it.source).contains(query, ignoreCase = true)
    }.let { list ->
        if (tab == "今日" && !verifiedOnly && category == "全部" && query.isBlank()) list.filter { it.id != edition.news.firstOrNull()?.id }
        else list
    }
    val activeRefreshing = if (tab == "即時") liveRefreshing else refreshing
    Scaffold(containerColor = background, topBar = {
        TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { Image(painterResource(R.drawable.brand_logo), "晨", Modifier.size(48.dp)); Spacer(Modifier.width(10.dp)); Text("世界晨報", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = primary) } },
            actions = { TextButton(onClick = { search = !search }) { Text("搜尋") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = background))
    }, bottomBar = {
        NavigationBar(containerColor = surface) {
            listOf("今日" to "⌂", "即時" to "ϟ", "設定" to "⚙").forEach { (name, icon) ->
                NavigationBarItem(selected = tab == name, onClick = { tab = name; category = "全部"; query = ""; search = false; ascending = false; verifiedOnly = false }, icon = { Text(icon, fontSize = 24.sp) }, label = { Text(name) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (activeRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (tab == "設定") {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("閱讀設定", style = MaterialTheme.typography.headlineSmall)
                    SettingSwitch("深色模式", dark, onChange = onDark)
                    Text("字體大小", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("小", "標準", "大").forEachIndexed { i, label -> FilterChip(selected = fontSize == i, onClick = { onFont(i) }, label = { Text(label) }) }
                    }
                    SettingSwitch("精簡閱讀模式", compact) { compact = it; prefs?.edit()?.putBoolean("compact", it)?.apply() }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("顯示新聞圖片"); Switch(checked = images, onCheckedChange = { images = it; prefs?.edit()?.putBoolean("images", it)?.apply() }) }
                    Text("新聞自動同步：每小時（由 Android 排程，可能延後）")
                    Text("繁體中文 · 本機保存新聞")
                    HorizontalDivider()
                    Text("新聞推播通知", style = MaterialTheme.typography.headlineSmall)
                    SettingSwitch("啟用新聞通知", push) { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            push = enabled; prefs?.edit()?.putBoolean("push", enabled)?.apply()
                            NewsNotifications.subscribe(context)
                        }
                    }
                    SettingSwitch("重大新聞即時通知", urgent, push && NewsNotifications.configured(context)) { urgent = it; prefs?.edit()?.putBoolean("urgent", it)?.apply() }
                    SettingSwitch("每小時新增新聞通知", hourly, push) { hourly = it; prefs?.edit()?.putBoolean("hourly", it)?.apply() }
                    SettingSwitch("通知顯示圖片", notificationImages, push) { notificationImages = it; prefs?.edit()?.putBoolean("notificationImages", it)?.apply() }
                    SettingSwitch("勿擾時段", quiet, push) { quiet = it; prefs?.edit()?.putBoolean("quiet", it)?.apply() }
                    if (quiet) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { android.app.TimePickerDialog(context, { _, h, m -> quietStart = h * 60 + m; prefs?.edit()?.putInt("quietStart", quietStart)?.apply() }, quietStart / 60, quietStart % 60, true).show() }) { Text("開始 " + timeLabel(quietStart)) }
                            TextButton(onClick = { android.app.TimePickerDialog(context, { _, h, m -> quietEnd = h * 60 + m; prefs?.edit()?.putInt("quietEnd", quietEnd)?.apply() }, quietEnd / 60, quietEnd % 60, true).show() }) { Text("結束 " + timeLabel(quietEnd)) }
                        }
                    }
                    Text("通知分類（未選取＝全部）", fontWeight = FontWeight.Bold)
                    listOf("國際", "台灣", "政治", "財經", "科技", "軍事", "社會", "環境", "自然災害", "科學", "健康", "文化", "體育").forEach { name ->
                        SettingSwitch(name, name in notifyCategories, push) {
                            notifyCategories = if (it) notifyCategories + name else notifyCategories - name
                            prefs?.edit()?.putStringSet("notifyCategories", notifyCategories)?.apply()
                        }
                    }
                    Text(if (NewsNotifications.configured(context)) "Firebase 推播已設定" else "尚未連接 Firebase：目前使用 Android 定期同步通知。", fontSize = 13.sp, color = Color.Gray)
                    HorizontalDivider()
                    Text("新聞來源", style = MaterialTheme.typography.headlineSmall)
                    LiveNewsStore.sourceNames.forEach { name ->
                        SettingSwitch(name, name !in disabledSources) { enabled ->
                            disabledSources = if (enabled) disabledSources - name else disabledSources + name
                            prefs?.edit()?.putStringSet("disabledSources", disabledSources)?.apply()
                        }
                    }
                    TextButton(onClick = { editSources = !editSources }) { Text(if (editSources) "收起自訂 RSS" else "新增自訂 RSS") }
                    if (editSources) {
                        Text("每行一個公開 HTTPS RSS：媒體名稱 | 台灣或國際 | 網址。國際自訂來源預設英文。", fontSize = 12.sp)
                        OutlinedTextField(value = customFeeds, onValueChange = { customFeeds = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("自訂新聞來源") })
                        Button(onClick = { prefs?.edit()?.putString("customFeeds", customFeeds)?.apply(); editSources = false }) { Text("儲存來源") }
                    }
                    Text("版本 1.0 · 個人非商業閱讀", fontSize = 12.sp, color = Color.Gray)
                    Text(status)
                }
            } else {
                if (search) OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("搜尋新聞") }, singleLine = true)
                PullToRefreshBox(isRefreshing = activeRefreshing, onRefresh = { if (!activeRefreshing) { if (tab == "即時") liveToken++ else refreshToken++ } }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (tab == "今日") {
                        item { Text("WORLD BRIEF · 每小時更新", color = primary, fontWeight = FontWeight.Bold, fontSize = 12.sp); Text(edition.updated.ifBlank { "讀取本機新聞" } + " · " + status, fontSize = 12.sp) }
                        edition.news.firstOrNull()?.takeUnless { verifiedOnly }?.let { lead -> item {
                            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { selected = lead }) {
                                if (images && lead.image.isNotBlank()) NewsPhoto(lead, 220)
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("本日特寫 · " + lead.category, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                    Text(lead.title, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Serif, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                    Text(lead.summary, maxLines = 3)
                                    Text("查看頭條 →", color = primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        } }
                        if (!verifiedOnly) item {
                            OutlinedButton(onClick = { dailyDigest = !dailyDigest }, modifier = Modifier.fillMaxWidth()) { Text(if (dailyDigest) "收起今日五分鐘" else "今日五分鐘 · 五則重點") }
                            if (dailyDigest) {
                                val latestDay = edition.news.firstOrNull()?.date
                                edition.news.filter { it.date == latestDay }.take(5).forEachIndexed { i, n ->
                                    Column(Modifier.fillMaxWidth().clickable { selected = n }.padding(vertical = 8.dp)) {
                                        Text("${i + 1}. ${n.title}", fontWeight = FontWeight.Bold)
                                        Text(n.summary, fontSize = 13.sp, maxLines = 3)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    } else {
                        item {
                            Text("即時新聞", fontFamily = FontFamily.Serif, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(false to "台灣", true to "國際").forEach { (value, label) ->
                                    if (international == value) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(label) }
                                    else OutlinedButton(onClick = { international = value; category = "全部"; live = LiveEdition(emptyList(), ""); liveStatus = "正在更新" }, modifier = Modifier.weight(1f)) { Text(label) }
                                }
                            }
                            Text(if (international) "BBC · The Guardian · 中央社 · 自由時報｜每 5 分鐘檢查" else "中央社 · 自由時報 · 公視 · 報導者 · 民報｜每 5 分鐘檢查", color = primary, fontSize = 12.sp)
                            Text(live.updated.ifBlank { "等待首次更新" } + " · " + liveStatus, fontSize = 12.sp)
                            Text(if (international) "英文標題自動機器翻譯，首次需透過 Wi-Fi 下載模型；內文可按需翻譯。" else "依公共利益與台灣關聯篩選，提供媒體標題、前言及原文連結。", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { FilterChip(selected = verifiedOnly, onClick = { verifiedOnly = !verifiedOnly }, label = { Text("僅已核對") }) }
                            items(categories) { c -> FilterChip(selected = c == category, onClick = { category = c }, label = { Text(c) }) }
                        }
                    }
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(if (category == "全部") "全部新聞" else category + "新聞", fontWeight = FontWeight.Bold, fontSize = 20.sp); TextButton(onClick = { ascending = !ascending }) { Text(if (ascending) "舊 → 新" else "新 → 舊") } } }
                    items(if (ascending) filtered.reversed() else filtered, key = { it.id }) { n ->
                        EditorialRow(n, images, compact || tab == "即時", onClick = { selected = n })
                    }
                    if (filtered.isEmpty()) item { Text(if (activeRefreshing) "正在讀取新聞…" else if (verifiedOnly) "目前沒有附獨立查證依據的新聞。可取消篩選查看初步消息。" else "目前沒有符合條件的新聞，請下拉更新。", modifier = Modifier.padding(16.dp)) }
                }
                }
            }
        }
    }
}

private fun timeLabel(minutes: Int) = String.format(Locale.TAIWAN, "%02d:%02d", minutes / 60, minutes % 60)

@Composable
private fun translatedTitle(news: News): String {
    val context = LocalContext.current
    var title by remember(news.id, news.title) { mutableStateOf(news.title) }
    LaunchedEffect(news.id, news.title) {
        if (news.language != "zh") {
            try { title = LocalTranslation.translate(context, news.title, news.language) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { title = news.title }
        }
    }
    return title
}

private fun verificationLabel(news: News): String = when {
    news.verification == "已核對" && news.verificationEvidence.isNotBlank() -> "來源標記：已核對"
    news.verification == "存在爭議" && news.verificationEvidence.isNotBlank() -> "存在爭議"
    news.relatedSources.isNotEmpty() -> "附相關來源 · 待查證"
    else -> "初步消息 · 尚未獨立核實"
}

@Composable
private fun EditorialRow(news: News, images: Boolean, compact: Boolean, onClick: () -> Unit) {
    val title = translatedTitle(news)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(news.category + " · " + news.date + " " + news.time, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, maxLines = 3)
                if (!compact) Text(news.summary, fontSize = 13.sp, maxLines = 3)
                Text(news.source, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(verificationLabel(news), fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
            }
            if (images && news.image.isNotBlank()) NewsPhoto(news, if (compact) 82 else 108, modifier = Modifier.width(if (compact) 112.dp else 124.dp))
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticlePage(news: News, all: List<News>, images: Boolean, onBack: () -> Unit, onOpen: (News) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = translatedTitle(news)
    var translated by remember(news.id) { mutableStateOf<List<String>?>(null) }
    var showOriginal by remember(news.id) { mutableStateOf(false) }
    var translating by remember(news.id) { mutableStateOf(false) }
    var translationError by remember(news.id) { mutableStateOf("") }
    val body = news.body.filter { it.isNotBlank() }.ifEmpty { listOf(news.summary).filter { it.isNotBlank() } }
    val visibleBody = if (!showOriginal) translated ?: body else body
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var speechStatus by remember { mutableStateOf("") }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { code -> ttsReady = code == TextToSpeech.SUCCESS }
        tts = engine
        onDispose { engine.stop(); engine.shutdown(); tts = null; ttsReady = false }
    }
    LaunchedEffect(news.id) { tts?.stop(); speechStatus = "" }
    val related = remember(news, all) { EventLinks.related(news, all) }
    fun openUrl(url: String) {
        if (url.startsWith("https://") || url.startsWith("http://")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("世界晨報", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            item {
                Text(news.category + " · " + news.date + " " + news.time, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                Text(title, fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (news.original.isNotBlank() || news.language != "zh") Text(news.original.ifBlank { news.title }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(news.source, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = ttsReady, onClick = {
                        tts?.let { engine ->
                            val locale = if (news.language != "zh" && (translated == null || showOriginal)) Locale.ENGLISH else Locale.TAIWAN
                            if (engine.setLanguage(locale) < 0) speechStatus = "請先在 Android 安裝此語言的語音資料"
                            else {
                                engine.stop()
                                val limit = TextToSpeech.getMaxSpeechInputLength()
                                (listOf(if (locale == Locale.ENGLISH) news.title else title) + visibleBody).forEachIndexed { i, part ->
                                    part.chunked(limit.coerceAtMost(3000)).forEachIndexed { j, chunk -> engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, "news-$i-$j") }
                                }
                                speechStatus = "正在朗讀"
                            }
                        }
                    }) { Text("朗讀") }
                    TextButton(onClick = { tts?.stop(); speechStatus = "已停止" }) { Text("停止") }
                }
                if (speechStatus.isNotBlank()) Text(speechStatus, fontSize = 12.sp)
                if (news.language != "zh") {
                    Button(enabled = !translating, onClick = {
                        if (translated != null) showOriginal = !showOriginal
                        else {
                            translating = true; translationError = ""
                            scope.launch {
                                try { translated = body.map { LocalTranslation.translate(context, it, news.language) }; showOriginal = false }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { translationError = "翻譯未完成，請確認 Wi-Fi 或稍後重試。" }
                                finally { translating = false }
                            }
                        }
                    }) { Text(if (translating) "下載模型／翻譯中…" else if (translated != null && !showOriginal) "查看原文" else "翻譯成繁體中文") }
                    if (translationError.isNotBlank()) Text(translationError, color = MaterialTheme.colorScheme.error)
                    Text("機器翻譯可能有誤；以原文為準。", fontSize = 12.sp)
                }
            }
            if (images && news.image.isNotBlank()) item { NewsPhoto(news, 220, showCaption = true) }
            item { SectionLabel(if (news.isLive) "媒體摘要" else "事件摘要") }
            items(visibleBody) { Text(it, fontSize = 17.sp, lineHeight = 29.sp) }
            if (news.keyPoints.isNotEmpty()) item { SectionLabel("重點整理"); news.keyPoints.forEach { Text("• " + it, Modifier.padding(vertical = 3.dp)) } }
            if (news.background.isNotBlank() && news.background !in news.body) item { SectionLabel("事件背景"); Text(news.background) }
            if (news.impact.isNotBlank()) item { SectionLabel("為什麼重要"); Text(news.impact) }
            if (news.watch.isNotBlank()) item { SectionLabel("後續觀察"); Text(news.watch) }
            item {
                SectionLabel("查證狀態")
                Text(verificationLabel(news), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                Text(news.verificationEvidence.ifBlank { "媒體發布或相似報導並不等於事實已獲獨立證實，請查閱來源。" }, fontSize = 13.sp)
                SectionLabel("資料來源")
                OutlinedButton(onClick = { openUrl(news.url) }, enabled = news.url.startsWith("https://") || news.url.startsWith("http://"), modifier = Modifier.fillMaxWidth()) { Text("原始報導 · " + news.source + " ↗") }
                news.relatedSources.forEach { link ->
                    OutlinedButton(onClick = { openUrl(link.url) }, modifier = Modifier.fillMaxWidth()) { Text("相關來源 · " + link.source + " ↗") }
                }
            }
            if (related.isNotEmpty()) {
                item { SectionLabel("事件時間軸 · 可能相關"); Text("依標題相似度提出線索，並非已確認是同一事件。", fontSize = 12.sp) }
                items(related) { n ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(n) }.padding(vertical = 8.dp)) {
                        Text(n.date + " " + n.time + " · " + n.source, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        Text(translatedTitle(n), fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text("│ " + text, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
}

@Composable
private fun NewsPhoto(news: News, height: Int, showCaption: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    val context = LocalContext.current
    val original = news.image.trim()
    val fallback = remember(original) {
        val match = Regex("^https://(?:thumb|upload)\\.wikimedia\\.org/wikipedia/commons/thumb/([^/]+/[^/]+/[^/]+)/[^/]+$").matchEntire(original)
        match?.let { "https://upload.wikimedia.org/wikipedia/commons/" + it.groupValues[1] }.orEmpty()
    }
    var url by remember(original) { mutableStateOf(original) }
    var failed by remember(original) { mutableStateOf(false) }
    var loaded by remember(url) { mutableStateOf(false) }
    if (!url.startsWith("https://") || failed) return
    val request = remember(url) {
        ImageRequest.Builder(context).data(url)
            .addHeader("User-Agent", "WorldBriefAndroid/0.4 (https://github.com/lalaHie/world-brief)")
            .crossfade(true).build()
    }
    Box(modifier.height(height.dp), contentAlignment = Alignment.Center) {
        if (!loaded) Text("圖片載入中…", color = Color.Gray, fontSize = 12.sp)
        AsyncImage(model = request, contentDescription = news.caption.ifBlank { news.title },
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
            onError = {
                android.util.Log.w("WorldBriefImages", "Image load failed: $url", it.result.throwable)
                if (fallback.isNotBlank() && url != fallback) url = fallback else failed = true
            })
    }
    if (showCaption && loaded && news.caption.isNotBlank()) Text(news.caption, fontSize = 12.sp)
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun HomePreview() {
    val sample = News("demo", "2026-11-20", "14:50", "國際", "今日世界焦點", "在這裡閱讀全球重要新聞的繁體中文摘要。", "世界晨報", "")
    WorldBriefTheme { NewsApp(preview = Edition(listOf(sample, sample.copy(id = "2", category = "台灣", title = "台灣今日重要消息")), "2026-11-20 14:50")) }
}
