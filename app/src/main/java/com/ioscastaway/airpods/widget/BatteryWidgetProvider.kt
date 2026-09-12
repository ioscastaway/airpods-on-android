package com.ioscastaway.airpods.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
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
 * without the timeline — we push a new snapshot whenever the pods report a change. Rings and
 * silhouettes are bitmaps from [WidgetArt], because RemoteViews cannot draw.
 */
class BatteryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val status = PodsStore.get(context).status.value
        Log.d(TAG, "onUpdate ids=${ids.toList()} status=${status?.let { "L${it.leftBattery} R${it.rightBattery} C${it.caseBattery}" }}")
        ids.forEach { manager.updateAppWidget(it, render(context, status)) }
    }

    companion object {
        private const val TAG = "BatteryWidget"

        fun push(context: Context, status: PodsStatus?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BatteryWidgetProvider::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, render(context, status))
        }

        private fun render(context: Context, status: PodsStatus?): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_battery)
            fun cell(art: Int, text: Int, kind: WidgetArt.Kind, value: Int?, charging: Boolean) {
                v.setImageViewBitmap(art, WidgetArt.cell(context, kind, value, charging))
                v.setTextViewText(text, value?.let { "$it%" } ?: context.getString(R.string.widget_unknown))
            }
            cell(R.id.widget_left_art, R.id.widget_left, WidgetArt.Kind.LEFT, status?.leftBattery, status?.leftCharging == true)
            cell(R.id.widget_right_art, R.id.widget_right, WidgetArt.Kind.RIGHT, status?.rightBattery, status?.rightCharging == true)
            cell(R.id.widget_case_art, R.id.widget_case, WidgetArt.Kind.CASE, status?.caseBattery, status?.caseCharging == true)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }
    }
}
