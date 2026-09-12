package com.ioscastaway.airpods.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.ioscastaway.airpods.R

/**
 * Draws one widget cell — a battery ring around a picture of the pod or case — as a bitmap.
 *
 * RemoteViews can only show stock views, so anything beyond text and static images is rendered
 * here and handed to an ImageView. Geometry is in a 100×100 design box and scaled to the density.
 * The pictures are the author's own generated art (white pods on transparent); they sit on a white
 * disc so they read on any wallpaper — the widget has no background of its own.
 */
object WidgetArt {

    enum class Kind(val res: Int) {
        LEFT(R.drawable.art_pod_left), RIGHT(R.drawable.art_pod_right), CASE(R.drawable.art_case)
    }

    private const val DISC = 0xF2FFFFFF.toInt()   // near-opaque white: the widget itself is transparent
    private const val TRACK = 0xFFD9DBE0.toInt()
    private const val GREEN = 0xFF34C759.toInt()
    private const val AMBER = 0xFFFF9F0A.toInt()
    private const val RED = 0xFFFF3B30.toInt()

    private val cache = HashMap<Int, Bitmap>()

    fun cell(context: Context, kind: Kind, percent: Int?, charging: Boolean, sizeDp: Int = 40): Bitmap {
        val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = px / 100f
        c.scale(s, s)

        // Pale disc so white pods stay visible on the white card.
        c.drawCircle(50f, 50f, 41f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISC })

        // Battery ring.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
        val box = RectF(6f, 6f, 94f, 94f)
        ring.color = TRACK
        c.drawOval(box, ring)
        if (percent != null) {
            ring.color = when {
                percent <= 10 -> RED
                percent <= 25 -> AMBER
                else -> GREEN
            }
            c.drawArc(box, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, ring)
        }

        // Picture, fitted inside the disc; dimmed when the part is not reporting.
        val art = picture(context, kind)
        val fit = if (kind == Kind.CASE) 56f else 60f
        val dst = RectF(50f - fit / 2, 50f - fit / 2, 50f + fit / 2, 50f + fit / 2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = if (percent == null) 90 else 255 }
        c.drawBitmap(art, Rect(0, 0, art.width, art.height), dst, paint)

        if (charging) bolt(c)
        return bmp
    }

    private fun picture(context: Context, kind: Kind): Bitmap = synchronized(cache) {
        cache.getOrPut(kind.res) {
            BitmapFactory.decodeResource(context.resources, kind.res, BitmapFactory.Options().apply { inScaled = false })
        }
    }

    private fun bolt(c: Canvas) {
        c.drawCircle(79f, 79f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawCircle(79f, 79f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN })
        val bolt = Path().apply {
            moveTo(81f, 71f); lineTo(73.5f, 80.5f); lineTo(78.5f, 80.5f); lineTo(77f, 87f); lineTo(84.5f, 77.5f); lineTo(79.5f, 77.5f); close()
        }
        c.drawPath(bolt, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }
}
