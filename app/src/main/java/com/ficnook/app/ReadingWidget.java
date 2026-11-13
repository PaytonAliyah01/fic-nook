package com.ficnook.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/** Home screen widget: shows the fic you're reading and opens it when tapped. */
public class ReadingWidget extends AppWidgetProvider {
    static final String ACTION_RESUME = "com.ficnook.app.RESUME";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, build(context));
    }

    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, ReadingWidget.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews views = build(context);
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    private static RemoteViews build(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("ficnook", Context.MODE_PRIVATE);
        String title = prefs.getString("w_title", "");
        String info = prefs.getString("w_info", "");
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget);
        if (title.isEmpty()) {
            v.setTextViewText(R.id.widget_label, "FIC NOOK");
            v.setTextViewText(R.id.widget_title, "Open a fic to see it here");
            v.setTextViewText(R.id.widget_info, "");
        } else {
            v.setTextViewText(R.id.widget_label, "KEEP READING");
            v.setTextViewText(R.id.widget_title, title);
            v.setTextViewText(R.id.widget_info, info);
        }
        Intent open = new Intent(context, MainActivity.class).setAction(ACTION_RESUME);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_root, pi);
        return v;
    }
}
