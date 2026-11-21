package tw.worldbrief.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable private fun PageTitle(title: String, subtitle: String) {
    Text(title,fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold,fontSize=28.sp)
    Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)
    HorizontalDivider(Modifier.padding(vertical=8.dp))
}

@Composable fun TrackingPage(onOpen: (News)->Unit) {
    val c=LocalContext.current; val prefs=remember { c.getSharedPreferences("news",Context.MODE_PRIVATE) }
    var topics by remember { mutableStateOf(prefs.getStringSet("topics",emptySet()).orEmpty().toSet()) }
    var input by remember { mutableStateOf("") }; var archive by remember { mutableStateOf(emptyList<News>()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(topics) { archive=withContext(Dispatchers.IO){NewsArchive.search(c,limit=2000)} }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { PageTitle("事件追蹤","關注清單 · 最新進展"); Row(verticalAlignment=Alignment.CenterVertically) { OutlinedTextField(input,{input=it},Modifier.weight(1f),label={Text("新增關鍵字")},singleLine=true); TextButton(onClick={val t=input.trim().take(60);if(t.isNotBlank() && topics.size<50){topics=topics+t; prefs.edit().putStringSet("topics",topics).apply(); input=""}}){Text("新增")}} }
        if(topics.isEmpty()) item { Text("加入關心的主題，例如半導體、台積電或日本經濟，便可看到歷史新聞時間軸。",fontSize=14.sp) }
        items(topics.sorted()) { topic ->
            val matches=archive.filter{(it.title+" "+it.summary+" "+it.category).contains(topic,true)}
            OutlinedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(6.dp),colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.background)) {
                Column(Modifier.padding(13.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically){Text(topic,fontFamily=FontFamily.Serif,fontSize=21.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f)); Text("${matches.size} 則",color=MaterialTheme.colorScheme.secondary);TextButton(onClick={topics=topics-topic;prefs.edit().putStringSet("topics",topics).apply()}){Text("移除")}}
                    Text("關鍵字相關報導（非自動確認同一事件）",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    matches.take(if(topic in expanded) 40 else 3).forEach { n -> Row(Modifier.fillMaxWidth().clickable{onOpen(n)}.padding(vertical=9.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) { Box(Modifier.width(3.dp).height(40.dp).background(MaterialTheme.colorScheme.secondary));Column{Text(n.date+" · "+n.source,fontSize=11.sp);Text(n.title,fontWeight=FontWeight.Bold)}};HorizontalDivider() }
                    if(matches.size>3) TextButton(onClick={expanded=if(topic in expanded) expanded-topic else expanded+topic}){Text(if(topic in expanded) "收起" else "更多進展")}
                }
            }
        }
    }
}

@Composable fun KnowledgePage(onOpen:(News)->Unit) {
    val c=LocalContext.current;val scope=rememberCoroutineScope();var query by remember{mutableStateOf("")};var question by remember{mutableStateOf("")};var results by remember{mutableStateOf(emptyList<News>())};var refs by remember{mutableStateOf(emptyList<News>())};var answer by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};var unread by remember{mutableStateOf(false)}
    LaunchedEffect(query,unread){results=withContext(Dispatchers.IO){NewsArchive.search(c,query,500,unread)}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { PageTitle("我的新聞知識庫","歷史新聞 · AI 問答 · 本機保存");OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("搜尋歷史新聞")},singleLine=true);Row(verticalAlignment=Alignment.CenterVertically){FilterChip(unread,{unread=!unread},{Text("僅未讀")});Spacer(Modifier.width(8.dp));Text("${results.size} 則結果（最多 500 則）",fontSize=12.sp)} }
        item { OutlinedTextField(question,{question=it},Modifier.fillMaxWidth(),label={Text("詢問目前搜尋結果中的新聞")},maxLines=3);Button(onClick={busy=true; refs=results.take(12);scope.launch{try{answer=PersonalAi.ask(c,question,refs)}catch(e:Exception){answer=e.message?:"分析失敗"}finally{busy=false}}},enabled=!busy && question.isNotBlank() && results.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text(if(busy) "Gemini 分析中…" else "✦  詢問 Gemini")};if(answer.isNotBlank()){Text("AI 回答・請核對原始來源",color=MaterialTheme.colorScheme.secondary,fontWeight=FontWeight.Bold);Text(answer,lineHeight=23.sp);refs.forEachIndexed{i,n->TextButton(onClick={onOpen(n)}){Text("[${i+1}] ${n.title}")}}};HorizontalDivider();Text("參考新聞",fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold,fontSize=19.sp) }
        items(results,key={it.id}){n->EditorialRow(n,true,true){onOpen(n)}}
    }
}

@Composable fun ArticleAi(news:News) {
    val c=LocalContext.current; val scope=rememberCoroutineScope();var output by remember(news.id){mutableStateOf("")};var busy by remember(news.id){mutableStateOf(false)};var question by remember(news.id){mutableStateOf("")}
    fun run(q:String){busy=true;scope.launch{try{output=PersonalAi.ask(c,q,listOf(news))}catch(e:Exception){output=e.message?:"分析失敗"}finally{busy=false}}}
    OutlinedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(6.dp),colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.background)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("✦ Gemini 深度閱讀",fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold,fontSize=20.sp,color=MaterialTheme.colorScheme.secondary)
            Text("僅分析 App 已取得的新聞內容；AI 推論不是查證結果。",fontSize=12.sp)
            OutlinedButton(onClick={run("用台灣繁體中文精準翻譯標題與現有新聞文字，保留原意、數字與專業術語")},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("精準翻譯")}
            OutlinedButton(onClick={run("依重點摘要、事件背景、為什麼重要、對台灣可能影響、後續觀察五個項目分析；不足資料請標明")},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("深度分析")}
            OutlinedTextField(question,{question=it},Modifier.fillMaxWidth(),label={Text("針對這則新聞提問")});TextButton(onClick={run(question)},enabled=!busy && question.isNotBlank()){Text("送出問題")}
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth());if(output.isNotBlank()) Text(output,lineHeight=23.sp)
        }
    }
}

@Composable fun PersonalSettings() {
    val c=LocalContext.current;val prefs=remember{c.getSharedPreferences("news",Context.MODE_PRIVATE)};val scope=rememberCoroutineScope();var key by remember{mutableStateOf("")};var model by remember{mutableStateOf(PersonalAi.model(c))};var configured by remember{mutableStateOf(PersonalAi.configured(c))};var status by remember{mutableStateOf("")};var backupUri by remember{mutableStateOf(prefs.getString("backupUri",null))};var size by remember{mutableStateOf(0L)}
    LaunchedEffect(status){size=withContext(Dispatchers.IO){NewsArchive.size(c)}}
    fun backup(uri:Uri){scope.launch{status="備份中…";try{withContext(Dispatchers.IO){ArchiveBackup.backup(c,uri)};status="備份成功"}catch(e:Exception){status="備份失敗，原有新聞未刪除"}}}
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null){runCatching{c.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}.onSuccess{backupUri=uri.toString();prefs.edit().putString("backupUri",backupUri).apply();ArchiveBackup.schedule(c)};backup(uri)}}
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{status="還原中…";try{withContext(Dispatchers.IO){ArchiveBackup.restore(c,uri)};status="已合併還原新聞與 AI 資料"}catch(e:Exception){status="還原失敗：${e.message}"}}}
    PageTitle("AI 與資料管理","Gemini · 本機資料庫 · Google Drive")
    Text(if(configured) "Gemini 金鑰已儲存於此裝置" else "尚未設定 Gemini API Key",fontWeight=FontWeight.Bold)
    OutlinedTextField(key,{key=it},Modifier.fillMaxWidth(),label={Text("個人 API Key")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
    OutlinedTextField(model,{model=it},Modifier.fillMaxWidth(),label={Text("Gemini 模型名稱")},singleLine=true)
    Row{Button(onClick={runCatching{PersonalAi.model(c,model);if(key.isNotBlank())PersonalAi.saveKey(c,key);key="";configured=PersonalAi.configured(c);status="已儲存設定"}.onFailure{status="無法儲存設定"}}){Text("儲存")};TextButton(onClick={PersonalAi.saveKey(c,"");configured=false;status="已移除金鑰"}){Text("移除金鑰")}}
    Text("每日最多 15 次新 AI 請求（已用 ${PersonalAi.usage(c)} 次）。只在手動操作時向 Google 傳送選取的新聞及問題；請使用未啟用付費的專案。App 上限不代表免費額度保證。",fontSize=12.sp)
    TextButton(onClick={c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://aistudio.google.com/apikey")))}){Text("前往 Google AI Studio ↗")}
    HorizontalDivider();Text("新聞資料庫：${size/1024} KB",fontWeight=FontWeight.Bold); Text("今日新聞長期保存；即時新聞保留七天。備份不包含 API Key。",fontSize=12.sp)
    OutlinedButton(onClick={create.launch("世界晨報-備份.json")},modifier=Modifier.fillMaxWidth()){Text("選擇 Google Drive／本機備份檔")}
    if(backupUri!=null) OutlinedButton(onClick={backup(Uri.parse(backupUri))},modifier=Modifier.fillMaxWidth()){Text("立即備份（每日背景排程已設定）")}
    OutlinedButton(onClick={restore.launch(arrayOf("application/json","text/plain","application/octet-stream"))},modifier=Modifier.fillMaxWidth()){Text("從 Drive 或本機還原")}
    Text("使用 Android 檔案選擇器授權：選擇 Google Drive 時只存取該備份檔；背景同步視 Drive 提供者、網路與 ColorOS 排程而定。",fontSize=12.sp)
    if(status.isNotBlank())Text(status,color=MaterialTheme.colorScheme.primary)
}

@Composable fun NotificationDiagnostics(){
    val c=LocalContext.current;val prefs=remember{c.getSharedPreferences("news",Context.MODE_PRIVATE)};var status by remember{mutableStateOf("")}
    val firebase=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){status=runCatching { val raw=c.contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:error("無法讀取");require(raw.length<100000);FirebaseSetup.import(c,raw);"已匯入 Firebase 設定；請開啟通知並重新註冊" }.getOrElse{"匯入失敗：${it.message}"}}}
    fun open(intent:Intent){runCatching{c.startActivity(intent)}.onFailure{status="請手動開啟手機設定"}}
    Text("OPPO / ColorOS 通知診斷",fontWeight=FontWeight.Bold)
    val allowed=androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled()
    val unrestricted=(c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)
    Text("系統通知：${if(allowed) "允許" else "未允許"}  ·  電池最佳化：${if(unrestricted) "已排除" else "仍可能限制"}",fontSize=12.sp)
    Text("背景更新：${prefs.getString("workerStatus","尚無紀錄")}\nFCM：${prefs.getString("fcmStatus",if(NewsNotifications.configured(c)) "等待訂閱" else "尚未配置")}",fontSize=12.sp)
    OutlinedButton(onClick={status=NewsNotifications.test(c)},modifier=Modifier.fillMaxWidth()){Text("發送本機通知測試")}
    OutlinedButton(onClick={NewsNotifications.subscribe(c);status="已請求重新訂閱；請稍後查看狀態"},modifier=Modifier.fillMaxWidth()){Text("重新註冊 Firebase 推播")}
    if(!NewsNotifications.configured(c)) OutlinedButton(onClick={firebase.launch(arrayOf("application/json","text/plain","application/octet-stream"))},modifier=Modifier.fillMaxWidth()){Text("匯入 google-services.json")}
    TextButton(onClick={open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName))}){Text("開啟系統通知設定")}
    TextButton(onClick={open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${c.packageName}")))}){Text("開啟 App 電池／自動啟動設定")}
    Text("OPPO 請允許背景活動、自動啟動；不要強制停止 App。背景排程仍不保證準時，遠端 FCM 須完成伺服器憑證。",fontSize=12.sp)
    if(status.isNotBlank())Text(status,fontSize=12.sp)
}
