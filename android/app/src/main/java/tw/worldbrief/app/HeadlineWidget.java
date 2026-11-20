package tw.worldbrief.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class HeadlineWidget extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "tw.worldbrief.app.ACTION_REFRESH";
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        WidgetRefresh.refreshAll(context);
    }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        WidgetRefresh.refreshAll(context);
    }
    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) WidgetRefresh.refreshAll(context);
        else super.onReceive(context, intent);
    }
}
