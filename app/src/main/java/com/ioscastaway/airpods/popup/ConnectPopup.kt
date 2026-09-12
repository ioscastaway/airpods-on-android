package com.ioscastaway.airpods.popup

import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.ioscastaway.airpods.R
import com.ioscastaway.airpods.platform.PodsService
import com.ioscastaway.airpods.platform.PodsStore
import com.ioscastaway.airpods.pods.PodsStatus
import kotlin.math.roundToInt

/**
 * The card that slides up from the bottom when AirPods connect, the way iOS shows one.
 *
 * iOS can do this because the OS owns both the pods and the screen. Here it is an overlay window,
 * which needs "display over other apps"; without that permission the same content goes out as a
 * heads-up notification, which is the platform-sanctioned way to interrupt.
 */
class ConnectPopup(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val hide = Runnable { dismiss() }

    fun show(connected: Boolean, status: PodsStatus?) {
        val title = status?.let { PodsStore.label(it.model, it.modelId) } ?: "AirPods"
        val line = if (connected) batteryLine(status) else context.getString(R.string.popup_disconnected)
        if (Settings.canDrawOverlays(context)) showOverlay(title, line, connected)
        else showNotification(title, line)
    }

    private fun batteryLine(status: PodsStatus?): String {
        if (status == null) return context.getString(R.string.popup_connected)
        return "L ${PodsService.pct(status.leftBattery)}   R ${PodsService.pct(status.rightBattery)}   Case ${PodsService.pct(status.caseBattery)}"
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()

    private fun showOverlay(title: String, line: String, connected: Boolean) {
        dismiss()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F21C1C1E"))
            }
            elevation = dp(10).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnClickListener { dismiss() }
        }
        card.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_pods)
            alpha = if (connected) 1f else 0.5f
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(TextView(context).apply { text = title; setTextColor(Color.WHITE); textSize = 16f })
            addView(TextView(context).apply { text = line; setTextColor(Color.parseColor("#B9BDC7")); textSize = 13f })
        })

        val width = minOf((context.resources.displayMetrics.widthPixels * 0.9f).roundToInt(), dp(400))
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }
        runCatching {
            wm.addView(card, params)
            view = card
            card.translationY = dp(80).toFloat(); card.alpha = 0f
            card.animate().translationY(0f).alpha(1f).setDuration(260).start()
            handler.postDelayed(hide, if (connected) 4000 else 2500)
        }.onFailure { showNotification(title, line) }
    }

    fun dismiss() {
        handler.removeCallbacks(hide)
        val v = view ?: return
        view = null
        v.animate().translationY(dp(60).toFloat()).alpha(0f).setDuration(200)
            .withEndAction { runCatching { wm.removeView(v) } }.start()
    }

    private fun showNotification(title: String, line: String) {
        val n = NotificationCompat.Builder(context, PodsService.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_pods)
            .setContentTitle(title)
            .setContentText(line)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(5000)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(11, n)
    }
}
