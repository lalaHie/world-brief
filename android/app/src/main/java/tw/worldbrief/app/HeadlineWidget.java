package tw.worldbrief.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HeadlineWidget extends AppWidgetProvider {
    private static final String ACTION_REFRESH = "tw.worldbrief.app.ACTION_REFRESH";
    private static final String FEED_URL = "https://lalahie.github.io/world-brief/news.json";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        for (int id : widgetIds) showLoading(context, manager, id);
        updateAsync(context, widgetIds);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (ids == null || ids.length == 0) {
                ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
                        new ComponentName(context, HeadlineWidget.class));
            }
            for (int id : ids) showLoading(context, AppWidgetManager.getInstance(context), id);
            updateAsync(context, ids);
        } else {
            super.onReceive(context, intent);
        }
    }

    private void updateAsync(Context context, int[] ids) {
        PendingResult pending = goAsync();
        EXECUTOR.execute(() -> {
            try {
                Story story = fetchLeadStory();
                AppWidgetManager manager = AppWidgetManager.getInstance(context);
                for (int id : ids) render(context, manager, id, story, null);
            } catch (Exception error) {
                AppWidgetManager manager = AppWidgetManager.getInstance(context);
                for (int id : ids) render(context, manager, id, null, "連線更新失敗，點右上角重試");
            } finally {
                pending.finish();
            }
        });
    }

    private void showLoading(Context context, AppWidgetManager manager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_headline);
        views.setTextViewText(R.id.widget_title, "正在更新今日頭條…");
        views.setTextViewText(R.id.widget_summary, "稍等片刻，正在讀取最新新聞。");
        bindActions(context, views, id, null);
        manager.updateAppWidget(id, views);
    }

    private void render(Context context, AppWidgetManager manager, int id, Story story, String error) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_headline);
        if (story == null) {
            views.setTextViewText(R.id.widget_category, "暫時無法更新");
            views.setTextViewText(R.id.widget_title, error == null ? "目前沒有可顯示的頭條" : error);
            views.setTextViewText(R.id.widget_summary, "請確認網路連線後點右上角重新整理。");
            views.setTextViewText(R.id.widget_footer, "世界晨報 · 資料暫時不可用");
            bindActions(context, views, id, null);
        } else {
            views.setTextViewText(R.id.widget_category, story.category);
            views.setTextViewText(R.id.widget_title, story.title);
            views.setTextViewText(R.id.widget_summary, story.summary);
            views.setTextViewText(R.id.widget_footer, story.source + " · " + story.published + " · 點擊閱讀");
            bindActions(context, views, id, story.url);
        }
        manager.updateAppWidget(id, views);
    }

    private void bindActions(Context context, RemoteViews views, int id, String storyUrl) {
        Intent refresh = new Intent(context, HeadlineWidget.class).setAction(ACTION_REFRESH);
        refresh.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{id});
        PendingIntent refreshPending = PendingIntent.getBroadcast(context, 2000 + id, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPending);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (storyUrl != null) open.putExtra(MainActivity.EXTRA_STORY_URL, storyUrl);
        PendingIntent openPending = PendingIntent.getActivity(context, 3000 + id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPending);
    }

    private Story fetchLeadStory() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(FEED_URL).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "WorldBriefAndroid/0.1");
        try {
            InputStream input = new BufferedInputStream(connection.getInputStream());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            input.close();
            JSONObject root = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            JSONObject editions = root.getJSONObject("editions");
            List<String> dates = new ArrayList<>();
            Iterator<String> keys = editions.keys();
            while (keys.hasNext()) dates.add(keys.next());
            Collections.sort(dates, Collections.reverseOrder());
            for (String date : dates) {
                JSONArray stories = editions.getJSONObject(date).optJSONArray("stories");
                if (stories == null || stories.length() == 0) continue;
                JSONObject lead = stories.getJSONObject(0);
                String published = lead.optString("published", date).replace('T', ' ');
                return new Story(
                        lead.optString("category", "焦點新聞"),
                        lead.optString("title", "世界最新消息"),
                        lead.optString("summary", "點擊卡片閱讀完整報導。"),
                        lead.optString("source", "世界晨報"),
                        lead.optString("url", "https://lalahie.github.io/world-brief/app/"),
                        published);
            }
            throw new IllegalStateException("No story in the news archive");
        } finally {
            connection.disconnect();
        }
    }

    private static final class Story {
        final String category, title, summary, source, url, published;
        Story(String category, String title, String summary, String source, String url, String published) {
            this.category = category; this.title = title; this.summary = summary;
            this.source = source; this.url = url; this.published = published;
        }
    }
}
