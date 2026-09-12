package com.ioscastaway.airpods.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ioscastaway.airpods.R
import com.ioscastaway.airpods.platform.PodsStore
import com.ioscastaway.airpods.pods.PodsStatus
import com.ioscastaway.airpods.ui.MainActivity

/**
 * Home-screen battery widget.
 *
 * Android widgets are RemoteViews: a description of a layout that the *launcher* inflates in its
 * own process. That is the same architecture as WidgetKit's timeline entries, just older and
 * without the timeline — we push a new snapshot whenever a beacon changes the numbers.
 */
class BatteryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val status = PodsStore.get(context).status.value
        ids.forEach { manager.updateAppWidget(it, render(context, status)) }
    }

    companion object {
        fun push(context: Context, status: PodsStatus?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BatteryWidgetProvider::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, render(context, status))
        }

        private fun render(context: Context, status: PodsStatus?): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_battery)
            if (status == null) {
                v.setTextViewText(R.id.widget_title, context.getString(R.string.widget_no_data))
                v.setTextViewText(R.id.widget_left, "")
                v.setTextViewText(R.id.widget_right, "")
                v.setTextViewText(R.id.widget_case, "")
            } else {
                v.setTextViewText(R.id.widget_title, PodsStore.label(status.model, status.modelId))
                v.setTextViewText(R.id.widget_left, cell(context, R.string.widget_left, status.leftBattery, status.leftCharging))
                v.setTextViewText(R.id.widget_right, cell(context, R.string.widget_right, status.rightBattery, status.rightCharging))
                v.setTextViewText(R.id.widget_case, cell(context, R.string.widget_case, status.caseBattery, status.caseCharging))
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }

        private fun cell(context: Context, label: Int, value: Int?, charging: Boolean): String {
            val pct = value?.let { "$it%" } ?: context.getString(R.string.widget_unknown)
            return context.getString(label) + " " + pct + if (charging) " ⚡" else ""
        }
    }
}
