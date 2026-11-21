package tw.worldbrief.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.HttpURLConnection
import java.util.Calendar
import java.util.TimeZone

object NewsNotifications {
    const val CHANNEL = "world-brief-news"
    private const val TOPIC = "world-brief-news"
    fun configured(context: Context) = FirebaseApp.getApps(context).isNotEmpty()
    fun subscribe(context: Context) {
        if (!configured(context)) return
        val prefs = context.getSharedPreferences("news", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("push", false)
        val task = if (enabled) FirebaseMessaging.getInstance().subscribeToTopic(TOPIC) else FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC)
        task.addOnCompleteListener { prefs.edit().putString("fcmStatus", if(it.isSuccessful) if(enabled) "已訂閱（仍需伺服器測試）" else "已取消訂閱" else "訂閱失敗，請確認網路及 Firebase 設定").apply() }
    }
    @Synchronized fun notifyNew(context: Context, edition: Edition) {
        val prefs = context.getSharedPreferences("news", Context.MODE_PRIVATE)
        val previouslySeen = prefs.getStringSet("notificationSeen", null)
        val ids = edition.news.map { it.id }.toSet()
        prefs.edit().putStringSet("notificationSeen", ids).apply()
        if (previouslySeen == null) return
        val categories = prefs.getStringSet("notifyCategories", emptySet()).orEmpty()
        val topics = prefs.getStringSet("topics",emptySet()).orEmpty()
        val fresh = edition.news.filter { it.id !in previouslySeen && (categories.isEmpty() || it.category in categories) }
        (fresh.firstOrNull { n -> topics.any { (n.title+n.summary).contains(it,true) } } ?: fresh.firstOrNull())?.let {
            show(context, it.title, it.summary, it.url, it.image, it.id, false, it.category)
        }
    }
    @Synchronized fun show(context: Context, title: String, summary: String, url: String, image: String, id: String, urgent: Boolean, category: String = "", remoteTest: Boolean = false, imageUpdate: Boolean = true) {
        val prefs = context.getSharedPreferences("news", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("push", false) || (!remoteTest && !(if (urgent) prefs.getBoolean("urgent", true) else prefs.getBoolean("hourly", true)))) return
        val categories = prefs.getStringSet("notifyCategories", emptySet()).orEmpty()
        if (!remoteTest && categories.isNotEmpty() && category !in categories) return
        if (!remoteTest && prefs.getBoolean("quiet", false)) {
            val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"))
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val start = prefs.getInt("quietStart", 22 * 60)
            val end = prefs.getInt("quietEnd", 7 * 60)
            val muted = if (start == end) true else if (start < end) minute in start until end else minute >= start || minute < end
            if (muted) return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(manager)
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val identifier = if (url.isNotBlank()) url.substringBefore('#') else id
        val hash = identifier.hashCode()
        val recent = prefs.getStringSet("sentNotifications",emptySet()).orEmpty()
        if (!remoteTest && identifier in recent) return
        val channel = if (urgent || remoteTest) "world-brief-breaking-v2" else CHANNEL
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_STORY_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, hash, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_news_notification)
            .setContentTitle(title).setContentText(summary).setAutoCancel(true).setContentIntent(pending)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary)).setPriority(if (urgent || remoteTest) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        manager.notify(hash, builder.build())
        if (!remoteTest) prefs.edit().putStringSet("sentNotifications", (recent.toList().takeLast(499) + identifier).toSet()).apply()
        if (imageUpdate && prefs.getBoolean("notificationImages", true)) {
            picture(image)?.let { builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).setSummaryText(summary)) }
            manager.notify(hash, builder.setOnlyAlertOnce(true).build())
        }
    }
    private fun createChannels(manager: NotificationManager) { if (Build.VERSION.SDK_INT >= 26) { manager.createNotificationChannel(NotificationChannel(CHANNEL,"每小時新聞",NotificationManager.IMPORTANCE_DEFAULT));manager.createNotificationChannel(NotificationChannel("world-brief-breaking-v2","重大新聞與推播測試",NotificationManager.IMPORTANCE_HIGH)) } }
    fun test(context:Context): String {
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return "系統通知未允許，請先啟用。"
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;createChannels(manager)
        manager.notify(202020,NotificationCompat.Builder(context,"world-brief-breaking-v2").setSmallIcon(R.drawable.ic_news_notification).setContentTitle("世界晨報｜本機通知測試").setContentText("若能看見此訊息，手機通知權限正常。這不是遠端 FCM 測試。").setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
        return "已發送本機測試；請檢查通知欄。"
    }
    private fun picture(url: String): Bitmap? {
        return runCatching {
        if (!url.startsWith("https://")) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000; conn.readTimeout = 5000
        try {
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 3_000_000) return null
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            var sample = 1
            while (options.outWidth / sample > 1200 || options.outHeight / sample > 1200) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally { conn.disconnect() }
        }.getOrNull()
    }
}

class NewsMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { NewsNotifications.subscribe(this) }
    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val title = d["title"] ?: return
        val id = d["id"] ?: message.messageId ?: title
        getSharedPreferences("news",Context.MODE_PRIVATE).edit().putString("fcmStatus","已收到遠端訊息 · " + java.util.Date().toString()).apply()
        // Post promptly during the FCM callback; do not wait for another background worker or image download.
        NewsNotifications.show(this,title,d["summary"].orEmpty(),d["url"].orEmpty(),d["image"].orEmpty(),id,d["type"]=="breaking",d["category"].orEmpty(),remoteTest=id=="world-brief-fcm-test",imageUpdate=false)
    }
}

class PushNewsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val title = inputData.getString("title") ?: return@withContext Result.failure()
        NewsNotifications.show(applicationContext, title, inputData.getString("summary").orEmpty(),
            inputData.getString("url").orEmpty(), inputData.getString("image").orEmpty(),
            inputData.getString("id") ?: title, inputData.getBoolean("urgent", false), inputData.getString("category").orEmpty())
        Result.success()
    }
}
