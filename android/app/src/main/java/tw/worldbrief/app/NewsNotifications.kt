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
        val enabled = context.getSharedPreferences("news", Context.MODE_PRIVATE).getBoolean("push", false)
        if (enabled) FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
        else FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC)
    }
    @Synchronized fun notifyNew(context: Context, edition: Edition) {
        if (configured(context)) return
        val prefs = context.getSharedPreferences("news", Context.MODE_PRIVATE)
        val previouslySeen = prefs.getStringSet("notificationSeen", null)
        val ids = edition.news.map { it.id }.toSet()
        prefs.edit().putStringSet("notificationSeen", ids).apply()
        if (previouslySeen == null) return
        val categories = prefs.getStringSet("notifyCategories", emptySet()).orEmpty()
        edition.news.firstOrNull { it.id !in previouslySeen && (categories.isEmpty() || it.category in categories) }?.let {
            show(context, it.title, it.summary, it.url, it.image, it.id, false, it.category)
        }
    }
    fun show(context: Context, title: String, summary: String, url: String, image: String, id: String, urgent: Boolean, category: String = "") {
        val prefs = context.getSharedPreferences("news", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("push", false) || !(if (urgent) prefs.getBoolean("urgent", true) else prefs.getBoolean("hourly", true))) return
        val categories = prefs.getStringSet("notifyCategories", emptySet()).orEmpty()
        if (categories.isNotEmpty() && category !in categories) return
        if (prefs.getBoolean("quiet", false)) {
            val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"))
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val start = prefs.getInt("quietStart", 22 * 60)
            val end = prefs.getInt("quietEnd", 7 * 60)
            val muted = if (start == end) true else if (start < end) minute in start until end else minute >= start || minute < end
            if (muted) return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "新聞通知", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_STORY_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_news_notification)
            .setContentTitle(title).setContentText(summary).setAutoCancel(true).setContentIntent(pending)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
        if (prefs.getBoolean("notificationImages", true)) {
            picture(image)?.let { builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).setSummaryText(summary)) }
        }
        manager.notify(id.hashCode(), builder.build())
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
        val work = OneTimeWorkRequestBuilder<PushNewsWorker>()
            .setInputData(workDataOf("title" to title.take(200), "summary" to d["summary"].orEmpty().take(500),
                "url" to d["url"].orEmpty().take(1500), "image" to d["image"].orEmpty().take(1500),
                "id" to id.take(200), "category" to d["category"].orEmpty().take(50), "urgent" to (d["type"] == "breaking"))).build()
        WorkManager.getInstance(this).enqueueUniqueWork("news-push-" + id.hashCode(), ExistingWorkPolicy.KEEP, work)
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
