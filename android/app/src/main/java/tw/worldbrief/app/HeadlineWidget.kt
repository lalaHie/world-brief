package tw.worldbrief.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import androidx.work.*
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WidgetRefresh {
        @JvmStatic fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.getAppWidgetIds(ComponentName(context, HeadlineWidget::class.java)).isEmpty()) return
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork("worldbrief-widget-refresh", ExistingWorkPolicy.REPLACE, request)
        }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val manager = AppWidgetManager.getInstance(c)
        val ids = manager.getAppWidgetIds(ComponentName(c, HeadlineWidget::class.java))
        if (ids.isEmpty()) return@withContext Result.success()
        val pref = c.getSharedPreferences("news", Context.MODE_PRIVATE)
        val mode = pref.getString("widgetMode", "今日") ?: "今日"
        val cached = runCatching {
            if (mode == "今日") NewsStore.local(c).news else LiveNewsStore.local(c, mode == "國際").news
        }.getOrDefault(emptyList())
        ids.forEach { render(c, manager, it, mode, cached, null, "更新中") }
        var stale = false
        val news = try {
            if (mode == "今日") NewsStore.refresh(c).news else LiveNewsStore.refresh(c, mode == "國際").news
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { stale = true; cached }
        val latest = news.firstOrNull()
        val needPhoto = ids.any { manager.getAppWidgetOptions(it).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150) >= 220 }
        val bitmap = if (needPhoto && pref.getBoolean("images", true) && latest != null) image(c, latest.image) else null
        ids.forEach { render(c, manager, it, mode, news, bitmap, if (stale) "本機快取" else "更新完成") }
        Result.success()
    }

    private suspend fun image(c: Context, url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        return runCatching {
            val request = ImageRequest.Builder(c).data(url).size(360, 240).allowHardware(false)
                .addHeader("User-Agent", "WorldBriefAndroid/1.1 (personal reader)").build()
            (Coil.imageLoader(c).execute(request) as? SuccessResult)?.drawable?.toBitmap(360, 240)
        }.getOrNull()
    }

    private fun render(c: Context, manager: AppWidgetManager, id: Int, mode: String, news: List<News>, bitmap: Bitmap?, status: String) {
        val options = manager.getAppWidgetOptions(id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        val wide = width >= 220
        val large = wide && height >= 245
        val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val foreground = Color.parseColor(if (dark) "#F2EEE3" else "#163632")
        val accent = Color.parseColor(if (dark) "#E6BE77" else "#916421")
        val muted = Color.parseColor(if (dark) "#C3CCC4" else "#68746C")
        val rv = RemoteViews(c.packageName, R.layout.widget_headline)
        val lead = news.firstOrNull()
        rv.setTextViewText(R.id.widget_brand, "世界晨報 · " + mode)
        rv.setTextViewText(R.id.widget_category, lead?.category ?: "新聞")
        rv.setTextViewText(R.id.widget_title, lead?.title ?: "暫無新聞")
        rv.setTextViewText(R.id.widget_summary, lead?.summary ?: "點擊更新以讀取最新新聞。")
        rv.setTextViewText(R.id.widget_footer, listOfNotNull(lead?.source, lead?.time, status).joinToString(" · "))
        rv.setViewVisibility(R.id.widget_summary, if (wide && (!large || height >= 290)) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.widget_photo, if (wide && !large && bitmap != null) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.widget_large_photo, if (large && bitmap != null) View.VISIBLE else View.GONE)
        if (bitmap != null) {
            if (large) rv.setImageViewBitmap(R.id.widget_large_photo, bitmap)
            else if (wide) rv.setImageViewBitmap(R.id.widget_photo, bitmap)
        }
        rv.setViewVisibility(R.id.widget_more, if (large && news.size > 1) View.VISIBLE else View.GONE)
        val moreIds = listOf(R.id.widget_more1, R.id.widget_more2, R.id.widget_more3)
        moreIds.forEachIndexed { index, viewId ->
            val story = news.getOrNull(index + 1)
            rv.setViewVisibility(viewId, if (large && story != null) View.VISIBLE else View.GONE)
            rv.setTextViewText(viewId, story?.let { "• " + it.title } ?: "")
            rv.setTextColor(viewId, foreground)
            if (story != null) rv.setOnClickPendingIntent(viewId, open(c, id, index + 1, story.url))
        }
        for (viewId in listOf(R.id.widget_brand, R.id.widget_title, R.id.widget_refresh)) rv.setTextColor(viewId, foreground)
        rv.setTextColor(R.id.widget_category, accent)
        rv.setTextColor(R.id.widget_summary, muted)
        rv.setTextColor(R.id.widget_footer, muted)
        val refresh = Intent(c, HeadlineWidget::class.java).setAction(HeadlineWidget.ACTION_REFRESH)
        rv.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(c, id, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        rv.setOnClickPendingIntent(R.id.widget_root, open(c, id, 0, lead?.url))
        manager.updateAppWidget(id, rv)
    }

    private fun open(c: Context, id: Int, index: Int, url: String?): PendingIntent {
        val intent = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (url != null) intent.putExtra(MainActivity.EXTRA_STORY_URL, url)
        return PendingIntent.getActivity(c, id * 10 + index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
